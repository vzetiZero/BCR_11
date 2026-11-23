package com.bcr.android.model

/**
 * Phương pháp vào tiền: đánh xen kẽ Banker/Player mỗi tay (B-P-B-P…) + gấp thếp 8 bậc.
 *
 * Quy tắc (giữ nguyên bản desktop Python `bcr/models/method.py`):
 * 1. Không cần đọc cầu để quyết định: vào bàn là đánh được ngay (số tay tối thiểu = 0).
 *    KHÔNG xét bên mạnh, KHÔNG xét cầu 3-3/3-2, KHÔNG xét bệt.
 * 2. Tay 1 đánh Banker, tay 2 đánh Player, tay 3 đánh Banker… cứ thế xen kẽ.
 * 3. Thắng thì quay về bậc 1. Thua thì sang bậc kế tiếp trong bảng vốn.
 * 4. Hoà là hoàn tiền: không đổi bậc, không đổi cửa.
 */
object Method {
    const val NAME = "1-1"
    const val LABEL = "1-1 · xen kẽ B/P · gấp thếp 8 bậc"

    /** 20 · 40 · 80 · 160 · 320 · 640 · 1.280 · 2.560 = tổng 5.100 (VN2). */
    val DEFAULT_LADDER = listOf(20.0, 40.0, 80.0, 160.0, 320.0, 640.0, 1280.0, 2560.0)

    const val LADDER_LOWER = 1
    const val LADDER_UPPER = 20
    // Phương pháp 1-1 không cần lịch sử cầu: tay đầu luôn đánh Banker. Vì người dùng tự
    // mở bàn nên không đặt ngưỡng tay tối thiểu (0 = vào bàn là đánh được ngay).
    const val MIN_HANDS = 0
    const val MAX_HANDS = 50
    const val HANDS_LOWER = 0
    const val HANDS_UPPER = 50
    // 0 = tắt thoát theo vệt. Bệt dài KHÔNG được làm dừng phiên, vì gấp thếp cần đánh đủ
    // 8 tay liên tiếp mới hồi được vốn; thoát giữa thang là bỏ dở thang.
    const val DEFAULT_EXIT_STREAK = 0
    const val STREAK_LOWER = 0
    const val STREAK_UPPER = 12

    const val ACTION_WAIT = "wait"
    const val ACTION_START = "start"
    const val ACTION_BET = "bet"
    const val ACTION_HALT = "halt"

    const val BANKER = "B"
    const val PLAYER = "P"
    const val TIE = "T"

    fun sideName(side: String?): String = when (side) {
        BANKER -> "Banker"
        PLAYER -> "Player"
        TIE -> "Hoà"
        else -> "—"
    }

    fun opposite(side: String): String = if (side == BANKER) PLAYER else BANKER

    /** Bảng vốn gấp đôi từ bậc 1, đúng 8 bậc. */
    fun multiplyLadder(base: Double): List<Double> =
        (0 until DEFAULT_LADDER.size).map { base * (1 shl it) }

    fun normalizeHands(minHands: Int, maxHands: Int): Pair<Int, Int> {
        val low = minHands.coerceIn(HANDS_LOWER, HANDS_UPPER)
        val high = maxHands.coerceIn(HANDS_LOWER, HANDS_UPPER)
        return if (low <= high) low to high else high to low
    }

    fun normalizeStreak(value: Int): Int = value.coerceIn(STREAK_LOWER, STREAK_UPPER)
    /** Kiểm tra bảng vốn: mỗi bậc dương, số bậc trong biên. Ném lỗi nếu sai. */
    fun ladderStakes(ladder: List<Double>?): List<Double> {
        val stakes = ladder?.takeIf { it.isNotEmpty() } ?: DEFAULT_LADDER
        require(stakes.size in LADDER_LOWER..LADDER_UPPER) {
            "Bảng vốn cần từ $LADDER_LOWER đến $LADDER_UPPER bậc, đang có ${stakes.size} bậc."
        }
        require(stakes.all { it.isFinite() && it > 0 }) { "Mỗi bậc cược phải là một số lớn hơn 0." }
        return stakes.map { it.toDouble() }
    }

    /** Cầu dạng chữ: B=Banker, P=Player, T=Hoà. Ký tự khác bị bỏ qua. */
    fun parseRoad(text: String): List<String> =
        text.uppercase().filter { it == 'B' || it == 'P' || it == 'T' }.map { it.toString() }

    fun decisionsOnly(road: List<String>): List<String> = road.filter { it != TIE }

    /** Độ dài các vệt liên tiếp của cùng một cửa (bỏ qua Hoà). */
    fun runLengths(letters: String): List<Pair<String, Int>> {
        val runs = mutableListOf<Pair<String, Int>>()
        for (ch in letters) {
            val side = ch.toString()
            if (side == TIE) continue
            val last = runs.lastOrNull()
            if (last != null && last.first == side) {
                runs[runs.size - 1] = side to last.second + 1
            } else {
                runs.add(side to 1)
            }
        }
        return runs
    }

    /** Vệt đang chạy ở tay mới nhất: (cửa, độ dài). */
    fun currentStreak(letters: String): Pair<String, Int> {
        val runs = runLengths(letters)
        return if (runs.isEmpty()) Pair("", 0) else runs[runs.size - 1]
    }

    /**
     * Bàn đã đủ dữ liệu để bắt đầu chưa: chỉ xét số tay đã đi.
     *
     * Ngưỡng dưới là điều kiện cứng (đủ dữ liệu mới vào tiền). Ngưỡng trên chỉ là mốc
     * tham khảo của bản desktop khi nó tự chọn bàn; ở app này người dùng tự mở bàn nên
     * cầu đã dài hơn mốc đó vẫn phải đánh được, nếu chặn sẽ không vào được bàn nào chạy lâu.
     */
    fun checkTable(hands: Int, minHands: Int, maxHands: Int): TableCheck {
        val (low, high) = normalizeHands(minHands, maxHands)
        return TableCheck(
            eligible = hands >= low,
            hands = hands,
            detail = "ván này đã đi $hands tay",
            condition = "Số tay đã đi tối thiểu $low (mốc tham khảo $low-$high)"
        )
    }

    /**
     * Kế hoạch cho tay tiếp theo.
     *
     * @param fixedStake > 0 thì bỏ gấp thếp, mỗi tay đánh đúng mức đó.
     */
    fun plan(
        hands: Int,
        stakes: List<Double>,
        state: MethodState,
        fixedStake: Double = 0.0,
        minHands: Int = MIN_HANDS,
        maxHands: Int = MAX_HANDS
    ): Plan {
        val ladder = ladderStakes(stakes)
        val check = checkTable(hands, minHands, maxHands)
        val side = if (state.side == BANKER || state.side == PLAYER) state.side else BANKER

        if (!state.started) {
            if (!check.eligible) {
                return Plan(ACTION_WAIT, side, 0.0, check.detail, 0, side)
            }
            val stake = if (fixedStake > 0) fixedStake else ladder[0]
            return Plan(
                ACTION_START, BANKER, stake,
                "Bàn đủ $hands tay. Bắt đầu 1-1: tay 1 đánh Banker, bậc 1/${ladder.size} (${Fmt.money(stake)}).",
                0, BANKER
            )
        }

        if (state.ladderStep >= ladder.size) {
            return Plan(
                ACTION_HALT, side, 0.0,
                "Đã thua hết ${ladder.size} bậc gấp thếp (mất ${Fmt.money(ladder.sum())}). " +
                    "Dừng, quay về bậc 1 ${Fmt.money(ladder[0])}.",
                state.ladderStep, side
            )
        }

        if (fixedStake > 0) {
            return Plan(
                ACTION_BET, side, fixedStake,
                "Mức cược cố định ${Fmt.money(fixedStake)} cho mỗi tay · đánh ${sideName(side)}.",
                0, side
            )
        }

        val stake = ladder[state.ladderStep]
        return Plan(
            ACTION_BET, side, stake,
            "1-1 xen kẽ: tay kế tiếp đánh ${sideName(side)} · bậc " +
                "${state.ladderStep + 1}/${ladder.size} (${Fmt.money(stake)}).",
            state.ladderStep, side
        )
    }

    /** Kết quả một tay: (tiền thay đổi, win|loss|push, reset|hold|up). */
    fun settle(betSide: String, result: String, stake: Double, commission: Double = 0.95): Settlement {
        if (result == TIE) return Settlement(0.0, "push", "hold")
        if (betSide == result) {
            val rate = if (betSide == BANKER) commission else 1.0
            return Settlement(stake * rate, "win", "reset")
        }
        return Settlement(-stake, "loss", "up")
    }

    /** Cập nhật trạng thái sau một tay. Hoà không đổi bậc và không đổi cửa. */
    fun advance(state: MethodState, result: String, betSide: String, stake: Double): Pair<MethodState, Settlement> {
        val settlement = settle(betSide, result, stake)
        var next = state.copy(started = true)
        if (result != TIE) next = next.copy(side = opposite(betSide))
        when (settlement.step) {
            "up" -> next = next.copy(ladderStep = state.ladderStep + 1)
            "reset" -> next = next.copy(ladderStep = 0)
        }
        return next to settlement
    }
}

data class MethodState(
    val started: Boolean = false,
    val ladderStep: Int = 0,
    val side: String = Method.BANKER
)

data class Settlement(val delta: Double, val outcome: String, val step: String)

data class TableCheck(val eligible: Boolean, val hands: Int, val detail: String, val condition: String)
