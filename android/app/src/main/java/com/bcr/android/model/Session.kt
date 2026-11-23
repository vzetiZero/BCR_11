package com.bcr.android.model

/**
 * Một phiên đánh 1-1 tại một bàn: chốt kế hoạch → đặt chip thật → chờ kết quả → cân
 * tài → chốt kế hoạch tay kế tiếp. Thuần logic, không phụ thuộc Android/WebView.
 *
 * Bất biến quan trọng: một tay chỉ được tính vào vốn khi đã có xác nhận đặt chip thật
 * (`markPlaced(true)`). Tay không đặt được thì chỉ ăn chữ vào cầu, không đụng vốn và
 * không đổi bậc/cửa.
 */
class Session(
    val money: MoneyBook = MoneyBook(),
    ladder: List<Double> = Method.DEFAULT_LADDER,
    fixedStake: Double = 0.0,
    minHands: Int = Method.MIN_HANDS,
    maxHands: Int = Method.MAX_HANDS,
    exitStreak: Int = Method.DEFAULT_EXIT_STREAK
) {
    var ladder: List<Double> = Method.ladderStakes(ladder)
        private set
    var fixedStake: Double = fixedStake.coerceAtLeast(0.0)
        private set
    var minHands: Int = minHands
        private set
    var maxHands: Int = maxHands
        private set
    var exitStreak: Int = Method.normalizeStreak(exitStreak)
        private set

    var tableKey: String? = null; private set
    var tableName: String = "Chưa chọn bàn"; private set
    var letters: String = ""; private set
    var results: List<String> = emptyList(); private set

    var running: Boolean = false; private set
    var entryWaiting: Boolean = false; private set
    var pending: Plan? = null; private set
    var note: String = "Chọn bàn để theo dõi."; private set
    var placeSkips: Int = 0; private set
    var takeProfitDone: Boolean = false; private set
    var exitDone: Boolean = false; private set

    var method: MethodState = MethodState(); private set
    val log: MutableList<HandRecord> = mutableListOf()

    /** Số thứ tự ván WebSocket đã xử lý — chặn tính trùng một ván hai lần. */
    private var lastRoundSeq: Long = 0

    // ------------------------------------------------------------------ cấu hình

    fun configure(
        ladder: List<Double>? = null,
        fixedStake: Double? = null,
        minHands: Int? = null,
        maxHands: Int? = null,
        exitStreak: Int? = null
    ) {
        if (ladder != null) this.ladder = Method.ladderStakes(ladder)
        if (fixedStake != null) this.fixedStake = fixedStake.coerceAtLeast(0.0)
        if (minHands != null || maxHands != null) {
            val (low, high) = Method.normalizeHands(minHands ?: this.minHands, maxHands ?: this.maxHands)
            this.minHands = low
            this.maxHands = high
        }
        if (exitStreak != null) this.exitStreak = Method.normalizeStreak(exitStreak)
        this.money.setUnit(this.ladder.firstOrNull() ?: 20.0)
    }

    // ------------------------------------------------------------------ kế hoạch

    fun plan(): Plan = Method.plan(
        hands = results.size,
        stakes = ladder,
        state = method,
        fixedStake = fixedStake,
        minHands = minHands,
        maxHands = maxHands
    )

    // ------------------------------------------------------------------ vòng đời

    /** Gắn bàn mới và nạp cầu hiện có; đặt lại toàn bộ trạng thái phương pháp. */
    fun bind(key: String, name: String, currentLetters: String) {
        pause("Đã đổi bàn — chưa đặt cược.")
        tableKey = key
        tableName = name
        letters = currentLetters
        results = currentLetters.map { it.toString() }
        method = MethodState()
        placeSkips = 0
        takeProfitDone = false
        exitDone = false
        entryWaiting = false
        pending = null
        note = "Đã chọn bàn."
    }

    /**
     * Bắt đầu theo dõi sau khi xác nhận bàn hợp lệ: chờ kết quả tay đang chạy rồi mới
     * chốt kế hoạch đầu tiên (không bấm vào cửa sổ cược đã đóng).
     */
    fun start(): Boolean {
        val problem = money.blocked
        if (problem != null) {
            note = problem.second
            return false
        }
        val ready = plan()
        if (ready.action != Method.ACTION_START && ready.action != Method.ACTION_BET) {
            note = ready.reason
            return false
        }
        running = true
        entryWaiting = true
        pending = null
        note = "Vừa vào bàn · chờ kết quả tay hiện tại rồi mới đặt cược."
        return true
    }

    fun pause(reason: String = "Đã tạm dừng. Không ghi thêm kết quả vào vốn.") {
        running = false
        entryWaiting = false
        pending = null
        note = reason
    }

    /**
     * Chốt ngay kế hoạch tay đầu tiên (luôn Banker, bậc 1) mà không chờ cầu.
     *
     * Gọi ngay sau [start] khi đã xác định được bàn. Nhờ vậy bấm BẮT ĐẦU là đặt được ở
     * cửa sổ cược gần nhất, không bị lỡ ván chỉ vì cầu chưa render.
     */
    fun beginFirstHand(): Step {
        if (!running || !entryWaiting) return Step.IDLE
        entryWaiting = false
        placeSkips = 0
        return if (arm()) Step.ARMED else Step.PAUSED
    }

    fun resume(): Boolean {
        money.checkLimits()
        val blocked = money.blocked
        if (blocked != null) {
            note = blocked.second
            return false
        }
        if (tableKey == null) {
            note = "Chưa có bàn nào. Hãy vào bàn hợp lệ trước."
            return false
        }
        running = true
        entryWaiting = false
        takeProfitDone = false
        exitDone = false
        placeSkips = 0
        note = "Tiếp tục phiên."
        return arm()
    }

    // ------------------------------------------------------------------ kết quả tay

    /**
     * Nạp cầu mới đọc từ bàn. Trả về việc cần làm tiếp theo.
     */
    fun process(currentLetters: String): Step {
        if (!running) return Step.IDLE
        if (currentLetters == letters) return Step.IDLE

        if (entryWaiting) {
            // Phương pháp 1-1 KHÔNG xét cầu trước đó: vào bàn là chốt kế hoạch Banker
            // ngay, không chờ cầu render. Cầu chỉ dùng làm MỐC để biết tay nào là tay
            // mình vừa đặt; kết quả thật lấy từ WebSocket (xem [applyRound]).
            entryWaiting = false
            letters = currentLetters
            results = currentLetters.map { it.toString() }
            if (exitIfStreakTooLong(currentLetters)) return Step.PAUSED
            note = "Đã vào bàn; chốt kế hoạch tay đầu tiên."
            return if (arm()) Step.ARMED else Step.PAUSED
        }

        // Cầu không nối tiếp mốc cũ (đổi shoe / đổi bàn / trang đọc lại từ đầu).
        // Không suy đoán kết quả. Nếu CHƯA đặt tiền thì chỉ đồng bộ lại mốc rồi đánh
        // tiếp — không dừng phiên, vì phương pháp 1-1 không cần cầu trước đó.
        if (!currentLetters.startsWith(letters)) {
            if (pending?.placed != Placement.OK) {
                letters = currentLetters
                results = currentLetters.map { it.toString() }
                pending = null
                note = "Cầu đổi gốc (đổi shoe/bàn). Đã đồng bộ mốc mới, đánh tiếp."
                return if (arm()) Step.ARMED else Step.PAUSED
            }
            // Đã đặt tiền mà mất dấu kết quả thì không được đoán bừa — thà dừng để
            // người dùng kiểm tra bàn còn hơn ghi sai sổ vốn.
            pause(
                "Cầu đổi gốc ngay sau khi đã đặt ${Fmt.money(pending?.stake ?: 0.0)} VN2 " +
                    "nên không đọc được kết quả. Không tính bù — kiểm tra bàn rồi bấm BẮT ĐẦU lại."
            )
            return Step.PAUSED
        }

        // Lấy KÝ TỰ ĐẦU của phần cầu mới: đó chính là kết quả tay mình vừa đặt.
        // Ký tự thừa (bàn chạy nhanh, lỡ vài tay) chỉ hấp thụ vào mốc, không tính bù.
        val fresh = currentLetters.substring(letters.length)
        val result = fresh.first().toString()

        val plan = pending
        if (plan == null || plan.baseline != letters) {
            pause("Không có kế hoạch được chốt trước kết quả. Hãy xác nhận lại bàn.")
            return Step.PAUSED
        }

        if (plan.placed == Placement.PENDING) {
            note = "Chờ xác nhận đã đặt chip thật xong rồi mới tính tay này."
            plan.firstResultSeenAt = plan.firstResultSeenAt ?: now()
            return Step.IDLE
        }
        if (plan.placed == Placement.FAILED) return skipUnplacedHand(currentLetters, plan)

        // Giữ cầu làm mốc để lần đọc sau còn đối chiếu, rồi cân tay bằng cùng một đường
        // với kết quả WebSocket (một chỗ tính tiền duy nhất, không lệch logic).
        letters = currentLetters
        results = currentLetters.map { it.toString() }
        return settleHand(result, plan)
    }

    /**
     * Kết quả một ván lấy TRỰC TIẾP từ WebSocket của bàn (không đọc cầu).
     *
     * Đây là đường chính khi bàn có WebSocket: server báo cửa thắng ngay khi ván kết
     * thúc, chính xác tuyệt đối — không lệch khi cầu loạn, không cần chờ cầu render,
     * không phụ thuộc selector DOM của bảng cầu.
     *
     * @param winner "B" | "P" | "T"
     * @param seq    số thứ tự ván server báo, để không xử lý trùng
     */
    fun applyRound(winner: String, seq: Long): Step {
        if (!running) return Step.IDLE
        if (seq > 0 && seq <= lastRoundSeq) return Step.IDLE
        if (seq > 0) lastRoundSeq = seq

        val plan = pending
        // Chưa chốt kế hoạch (đang đặt dở) thì bỏ qua ván này, không tính tiền.
        if (plan == null) return Step.IDLE
        if (plan.placed == Placement.PENDING) {
            note = "Chờ xác nhận đã đặt chip thật xong rồi mới tính tay này."
            plan.firstResultSeenAt = plan.firstResultSeenAt ?: now()
            return Step.IDLE
        }
        if (plan.placed == Placement.FAILED) return skipUnplacedHand(letters, plan)
        return settleHand(winner, plan)
    }

    /** Ghi nhận một tay đã đặt: cân vốn, đổi bậc/cửa, rồi chốt kế hoạch kế tiếp. */
    private fun settleHand(result: String, plan: Plan): Step {
        placeSkips = 0
        val settlement = money.settle(plan.side, result, plan.stake)
        val (nextState, _) = Method.advance(method, result, plan.side, plan.stake)
        method = if (fixedStake > 0) nextState.copy(ladderStep = 0) else nextState
        results = results + result
        letters = letters + result
        log.add(
            HandRecord(
                at = now(),
                table = tableName,
                tableKey = tableKey ?: "",
                hand = results.size,
                result = result,
                bet = plan.side,
                stake = plan.stake,
                delta = settlement.delta,
                outcome = settlement.outcome,
                balanceAfter = money.balance,
                ladderStep = plan.ladderStep,
                placed = true
            )
        )
        pending = null

        val blocked = money.blocked
        if (blocked != null) {
            pause(blocked.second)
            if (blocked.first == "TAKE_PROFIT") takeProfitDone = true
            return Step.PAUSED
        }
        if (exitIfStreakTooLong(letters)) return Step.PAUSED
        return if (arm()) Step.ARMED else Step.PAUSED
    }

    private fun skipUnplacedHand(currentLetters: String, plan: Plan): Step {
        val reason = plan.placeNote.ifBlank { "không xác nhận được lượt đặt chip." }
        // KHÔNG đổi bậc, KHÔNG đổi cửa, KHÔNG đụng vốn: kế hoạch này chưa vào tiền thì
        // coi như chưa xảy ra. Chốt lại đúng cửa + đúng bậc đó cho ván kế tiếp để chuỗi
        // đánh không bị đứt (trước đây mỗi lần trượt chip là mất một tay, khiến các tay
        // nhảy cách quãng 2, 4, 15, 17, 18…).
        pending = null
        placeSkips += 1
        // Ghi lại lượt trượt để người dùng thấy rõ app không đặt được đồng nào — KHÔNG
        // ghi vào vốn, không đổi bậc/cửa.
        log.add(
            HandRecord(
                at = now(),
                table = tableName,
                tableKey = tableKey ?: "",
                hand = currentLetters.length,
                result = currentLetters.lastOrNull()?.toString() ?: "",
                bet = plan.side,
                stake = 0.0,
                delta = 0.0,
                outcome = "not_placed",
                balanceAfter = money.balance,
                ladderStep = plan.ladderStep,
                placed = false
            )
        )
        if (placeSkips >= PLACE_SKIP_LIMIT) {
            pause(
                "Đã trượt $placeSkips lượt liên tiếp, chưa đặt được đồng nào ($reason). " +
                    "Dừng để kiểm tra khay chip và ô cược — vốn chưa bị đụng."
            )
            return Step.PAUSED
        }
        // Đồng bộ mốc cầu sang tay vừa qua rồi chốt LẠI đúng cửa + đúng bậc đó cho ván
        // kế tiếp. Không đổi cửa, không đổi bậc, không đụng vốn.
        letters = currentLetters
        results = currentLetters.map { it.toString() }
        pending = plan.copy(
            armedAt = now(),
            baseline = letters,
            placed = Placement.PENDING,
            placeNote = ""
        )
        note = "Trượt lượt đặt ($reason) — giữ nguyên ${Method.sideName(plan.side)} " +
            "${Fmt.money(plan.stake)} VN2, đánh lại ván kế tiếp."
        return Step.ARMED
    }

    /**
     * Rời bàn khi vệt đang chạy chạm ngưỡng thoát. Ngưỡng 0 = TẮT: bệt dài không được làm
     * dừng phiên, vì gấp thếp cần đánh đủ thang mới hồi vốn.
     */
    private fun exitIfStreakTooLong(currentLetters: String): Boolean {
        if (exitStreak <= 0) return false
        val (side, length) = Method.currentStreak(currentLetters)
        if (length < exitStreak) return false
        exitDone = true
        pause(
            "Bệt ${Method.sideName(side)} $length tay đã chạm ngưỡng thoát $exitStreak tay · " +
                "dừng phiên, không tính bù."
        )
        return true
    }

    // ------------------------------------------------------------------ đặt chip

    /** Chốt kế hoạch cho tay kế tiếp. Trả true nếu có kế hoạch để đặt. */
    private fun arm(): Boolean {
        val plan = plan()
        if (plan.action != Method.ACTION_START && plan.action != Method.ACTION_BET) {
            pause(plan.reason)
            return false
        }
        if (plan.stake > money.balance) {
            pause("Vốn không đủ cho bậc tiếp theo. Không tự giảm tiền cược.")
            return false
        }
        pending = plan.copy(armedAt = now(), baseline = letters, placed = Placement.PENDING)
        note = "Chờ ${Method.sideName(plan.side)} · ${Fmt.money(plan.stake)} VN2 · ván kế tiếp."
        return true
    }

    /** Kế hoạch đang chờ — runner cầm object này để báo kết quả đặt chip. */
    fun pendingPlan(): Plan? = pending

    fun markPlacing() {
        pending?.let {
            it.placingAt = now()
            it.placed = Placement.PENDING
        }
    }

    /**
     * Ghi kết quả một lượt bấm chip vào đúng kế hoạch đã sinh ra nó. Kết quả của lượt
     * cũ về muộn (sau khi đã chốt kế hoạch mới) bị bỏ, không gán nhầm sang tay sau.
     */
    fun markPlaced(ok: Boolean, noteText: String = "", plan: Plan? = null): Boolean {
        val target = plan ?: pending ?: return false
        if (target !== pending || target.placed != Placement.PENDING) return false
        target.placed = if (ok) Placement.OK else Placement.FAILED
        target.placeNote = noteText
        target.confirmedAt = now()
        return true
    }

    /** Ghi kết quả đặt chip theo toạ độ (native bấm cảm ứng thật) — cùng ngữ nghĩa. */
    fun markPlacedByTouch(ok: Boolean, noteText: String, plan: Plan? = null): Boolean =
        markPlaced(ok, noteText, plan)

    fun snapshot(): Map<String, Any?> {
        val plan = plan()
        return mapOf(
            "table" to tableKey,
            "table_name" to tableName,
            "letters" to letters,
            "hands" to results.size,
            "running" to running,
            "entry_waiting" to entryWaiting,
            "note" to note,
            "method" to Method.NAME,
            "method_label" to Method.LABEL,
            "next_side" to plan.nextSide,
            "ladder_step" to if (fixedStake > 0) 0 else method.ladderStep,
            "action" to plan.action,
            "plan_side" to plan.side,
            "plan_stake" to plan.stake,
            "plan_reason" to plan.reason,
            "ladder" to ladder,
            "fixed_stake" to fixedStake,
            "min_hands" to minHands,
            "max_hands" to maxHands,
            "exit_streak" to exitStreak,
            "place_skips" to placeSkips,
            "take_profit_done" to takeProfitDone,
            "exit_done" to exitDone,
            "money" to money.summary(),
            "log" to log.takeLast(60).map { it.toMap() }
        )
    }

    private fun now(): Long = System.currentTimeMillis()

    companion object {
        const val PLACE_SKIP_LIMIT = 3
    }
}

enum class Placement { PENDING, OK, FAILED }

enum class Step { IDLE, ARMED, PAUSED }

data class Plan(
    val action: String,
    val side: String,
    val stake: Double,
    val reason: String,
    val ladderStep: Int,
    val nextSide: String,
    val baseline: String = "",
    val armedAt: Long = 0,
    var placed: Placement = Placement.PENDING,
    var placeNote: String = "",
    var placingAt: Long = 0,
    var confirmedAt: Long = 0,
    var firstResultSeenAt: Long? = null
)

data class HandRecord(
    val at: Long,
    val table: String,
    val tableKey: String,
    val hand: Int,
    val result: String,
    val bet: String,
    val stake: Double,
    val delta: Double,
    val outcome: String,
    val balanceAfter: Double,
    val ladderStep: Int,
    val placed: Boolean
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "at" to at,
        "table" to table,
        "hand" to hand,
        "result" to result,
        "bet" to bet,
        "stake" to stake,
        "delta" to delta,
        "outcome" to outcome,
        "balance_after" to balanceAfter,
        "ladder_step" to ladderStep,
        "placed" to placed
    )
}
