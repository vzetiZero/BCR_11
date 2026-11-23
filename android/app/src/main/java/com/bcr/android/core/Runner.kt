package com.bcr.android.core

import com.bcr.android.model.Fmt
import com.bcr.android.model.HandRecord
import com.bcr.android.model.Method
import com.bcr.android.model.MoneyBook
import com.bcr.android.model.Placement
import com.bcr.android.model.Plan
import com.bcr.android.model.Session
import com.bcr.android.model.Step
import com.bcr.android.web.Bridge
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * "Tool" của bản Android: vòng lặp đọc bàn → chốt kế hoạch → đặt chip thật → tính tay.
 *
 * Người dùng tự đăng nhập và tự vào bàn. Khi bấm BẮT ĐẦU, runner đọc cầu bàn đang mở,
 * kiểm tra đủ số tay, rồi đánh 1-1 xen kẽ B/P với gấp thếp 8 bậc — không xét cầu.
 *
 * Mọi lệnh gọi vào WebView đều CHẶN (chờ khung trả lời), nên runner chạy trên thread
 * riêng; UI chỉ đọc [Status] đã chụp sẵn.
 */
class Runner(
    private val bridge: Bridge,
    private val onUpdate: (Status) -> Unit
) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    @Volatile var autoPlace: Boolean = true
    @Volatile var clickDelayMs: Int = 110

    val session = Session(money = MoneyBook(capital = CAPITAL, unit = 20.0))

    @Volatile var liveTableName: String = ""
    @Volatile var liveLetters: String = ""
    @Volatile var liveTableId: String = ""
    @Volatile var liveInTable: Boolean = false
    @Volatile var liveWallet: String = ""
    @Volatile var lastAction: String = ""
    @Volatile var frames: Int = 0
    @Volatile var readNote: String = ""
    @Volatile var busy: Boolean = false
    /** Ghi chú của lượt đặt chip gần nhất (để báo đúng lý do khi phải thử lại). */
    @Volatile private var lastPlaceNote: String = ""
    @Volatile private var lastError: String = ""

    private var stableLetters: String = ""
    private var stableCount = 0
    /** Chặn hai luồng cùng bấm chip cho một kế hoạch. */
    private val planClaim = AtomicBoolean(false)
    private var entered = false
    private val pollMs: Long = 300

    /** Callback do Activity cài để bấm cảm ứng thật tại toạ độ CSS px của khung gốc. */
    var onTapRequested: (Float, Float) -> Boolean = { _, _ -> false }

    // ------------------------------------------------------------------ điều khiển

    /** Bắt đầu: dò bàn trên thread nền rồi vào vòng lặp. UI không bị chặn. */
    fun start(): Boolean {
        if (running.getAndSet(true)) return true
        entered = false
        stableLetters = ""
        stableCount = 0
        lastError = ""
        thread = Thread({ loop() }, "bcr-runner").apply {
            isDaemon = true
            start()
        }
        publish()
        return true
    }

    fun stop(reason: String = "Đã dừng tool.") {
        running.set(false)
        session.pause(reason)
        thread?.interrupt()
        thread = null
        publish()
    }

    fun isRunning(): Boolean = running.get()

    fun addCapital(value: Double): Pair<Boolean, String> {
        val (ok, note) = session.money.syncCapital(value)
        publish()
        return ok to note
    }

    fun resetSession(capital: Double) {
        stop("Đã tạo phiên mới.")
        session.money.reset(capital)
        session.configure()
        publish()
    }

    /**
     * Áp dụng cấu hình người dùng nhập trên giao diện.
     *
     * @param baseStake tiền cược **bậc 1**, theo đơn vị VN2 (1 VN2 = 1.000đ).
     * @param hands số tay của thang = số bậc gấp thếp.
     *
     * Ví dụ "vốn 10 · số tay 10" → 10 tay gấp thếp của 10:
     *   10 · 20 · 40 · 80 · 160 · 320 · 640 · 1.280 · 2.560 · 5.120 (tổng 10.230).
     * Vốn của phiên được đặt bằng TỔNG thang, vì đó là số tiền cần có để chạy hết thang.
     */
    fun applyConfig(baseStake: Double, hands: Int): Pair<Boolean, String> {
        if (running.get()) {
            return false to "Đang chạy — bấm DỪNG rồi mới đổi cấu hình."
        }
        val base = if (baseStake.isFinite() && baseStake > 0) baseStake else BASE_UNIT
        val steps = if (hands <= 0) Method.DEFAULT_LADDER.size
        else hands.coerceIn(Method.LADDER_LOWER, Method.LADDER_UPPER)
        val ladder = (0 until steps).map { base * (1 shl it) }
        val needed = ladder.sum()
        return try {
            session.money.reset(needed)
            session.configure(ladder = ladder, minHands = 0, maxHands = Method.MAX_HANDS)
            publish()
            true to "Đã áp dụng: $steps tay gấp thếp của ${Fmt.money(base)} " +
                "(${Fmt.money(ladder.first())} → ${Fmt.money(ladder.last())}) · " +
                "vốn cần ${Fmt.money(needed)}."
        } catch (t: Throwable) {
            false to "Cấu hình không hợp lệ: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    // ------------------------------------------------------------------ đọc bàn

    data class TableScan(val tableId: String, val name: String, val letters: String, val wallet: String)

    /**
     * URL khung gốc có `table_id` = người dùng đang đứng trong một bàn (bàn nằm ở iframe,
     * iframe chưa dựng cầu xong thì DOM chưa có gì để đọc).
     */
    private fun inTableByUrl(): Boolean = runCatching {
        val replies = bridge.call(
            "page_url", JSONObject(), timeoutMs = 1200, quietMs = 80,
            until = { it.ok && it.result?.optString("url").orEmpty().contains("table_id=") }
        )
        replies.any { it.result?.optString("url").orEmpty().contains("table_id=") }
    }.getOrDefault(false)

    data class Tile(val id: String, val title: String, val gameType: String, val lightning: Boolean, val special: Boolean)

    /** Đọc bàn đang mở ở bất kỳ khung nào (CHẶN — gọi ngoài UI thread). */
    fun scanTable(): TableScan? {
        // Ưu tiên khung đọc được ÍT NHẤT 1 dãy cầu (bàn thật nằm trong iframe). Nếu hết giờ
        // mà chưa khung nào có cầu thì vẫn nhận bàn theo `table_id` trong URL: phương pháp
        // 1-1 không cần cầu trước đó, chỉ cần biết mình đang ở bàn nào.
        val replies = bridge.call(
            "scan", JSONObject(), timeoutMs = 3200, quietMs = 180,
            until = { reply ->
                reply.ok &&
                    reply.result?.optBoolean("in_table") == true &&
                    (reply.result?.optJSONArray("tables")?.length() ?: 0) > 0
            }
        )
        frames = bridge.knownFrameCount()

        // Trường hợp 1: có khung đọc được cầu.
        val withRoad = bridge.tableReply(replies)
            ?.takeIf { (it.result?.optJSONArray("tables")?.length() ?: 0) > 0 }
        if (withRoad != null) {
            return fromReply(withRoad)
        }

        // Trường hợp 2: đang ở trong bàn nhưng cầu chưa render → trả bàn với cầu rỗng.
        val inTable = replies.firstOrNull {
            it.ok && it.result?.optBoolean("in_table") == true
        }
        if (inTable != null) {
            return fromReply(inTable, allowEmptyRoad = true)
        }
        return null
    }

    /** Dựng [TableScan] từ một trả lời quét trang. */
    private fun fromReply(reply: Bridge.Reply, allowEmptyRoad: Boolean = false): TableScan? {
        val result = reply.result ?: return null
        val page = result.optJSONObject("page")
        val tables = result.optJSONArray("tables")
        val count = tables?.length() ?: 0
        if (count == 0 && !allowEmptyRoad) return null

        val row = if (count > 0) tables?.optJSONObject(count - 1) else null
        val tableId = row?.optString("table_id").orEmpty()
            .ifBlank { page?.optString("table_id") ?: "" }
        if (tableId.isBlank()) return null
        val name = row?.optString("label").orEmpty()
            .ifBlank { liveTableName.ifBlank { tableId } }
        liveWallet = page?.optString("wallet") ?: ""
        readNote = row?.optString("read_error").orEmpty()
        return TableScan(tableId, name, row?.optString("letters").orEmpty(), liveWallet)
    }

    /** Danh sách ô bàn đang thấy ở sảnh (CHẶN). */
    fun lobbyTiles(): List<Tile> {
        val replies = bridge.call(
            "scan", JSONObject(), timeoutMs = 2600, quietMs = 150,
            until = { bridge.lobbyReply(listOf(it)) != null }
        )
        val result = bridge.lobbyReply(replies)?.result ?: return emptyList()
        val tiles = result.optJSONObject("lobby")?.optJSONArray("tiles") ?: return emptyList()
        val out = mutableListOf<Tile>()
        for (i in 0 until tiles.length()) {
            val tile = tiles.optJSONObject(i) ?: continue
            val id = tile.optString("id")
            if (id.isBlank()) continue
            out.add(
                Tile(
                    id = id,
                    title = tile.optString("title").ifBlank { id },
                    gameType = tile.optString("game_type"),
                    lightning = tile.optBoolean("lightning"),
                    special = tile.optBoolean("special")
                )
            )
        }
        return out
    }

    /**
     * Chọn bàn đánh được bằng 1-1: bàn baccarat chuẩn, không có luật trả thưởng đặc biệt
     * (Lightning/XXXtreme…) vì bảng trả thưởng khác sẽ làm sai phép cân tài 0,95/1,0.
     */
    fun pickStandardTable(tiles: List<Tile>): Tile? = tiles
        .filter { tile ->
            if (tile.lightning || tile.special) return@filter false
            val type = tile.gameType.lowercase()
            type.isEmpty() || type.contains("baccarat")
        }
        .firstOrNull()

    /** Đọc chip đang đặt trên bàn (CHẶN). */
    fun readChips(): JSONObject? {
        val replies = bridge.call(
            "chips", JSONObject(), timeoutMs = 1800, quietMs = 100,
            until = { it.ok && it.result?.optJSONObject("chips")?.optBoolean("has_table") == true }
        )
        return bridge.chipReply(replies)?.result?.optJSONObject("chips")
    }

    // ------------------------------------------------------------------ vòng lặp

    private fun loop() {
        try {
            if (!enter()) return
            while (running.get()) {
                try {
                    tick()
                } catch (t: Throwable) {
                    lastError = "Lỗi vòng lặp: ${t.javaClass.simpleName}"
                }
                publish()
                if (!running.get()) break
                try {
                    Thread.sleep(pollMs)
                } catch (_: InterruptedException) {
                    break
                }
            }
        } finally {
            running.set(false)
            publish()
        }
    }

    /**
     * Vào bàn: chờ bàn/cầu sẵn sàng, kiểm tra đủ tay, nạp phiên.
     *
     * Người dùng chỉ cần bấm BẮT ĐẦU một lần: nếu bàn đang tải hoặc cầu chưa hiện, app tự
     * chờ (tối đa ~45 giây) thay vì bắt bấm lại.
     */
    private fun enter(): Boolean {
        var scan = waitForTable()
        if (scan == null) {
            fail(
                "Chưa đọc được bàn sau 45 giây. Kiểm tra: đã đăng nhập chưa, đã vào bàn " +
                    "Baccarat chưa, và bàn có hiện cầu chưa."
            )
            return false
        }
        liveInTable = true
        liveTableId = scan.tableId
        liveTableName = scan.name
        liveLetters = scan.letters
        // Phương pháp 1-1 không xét cầu trước đó, nên vào bàn là chốt kế hoạch Banker
        // ngay — không chờ cầu render.
        val check = Method.checkTable(scan.letters.length, session.minHands, session.maxHands)
        if (!check.eligible) {
            fail(
                "Bàn mới đi ${scan.letters.length} tay — cần tối thiểu ${session.minHands} tay " +
                    "để đánh 1-1. Chờ thêm vài ván rồi bấm lại."
            )
            return false
        }
        session.bind(scan.tableId, scan.name, scan.letters)
        if (!session.start()) {
            fail(session.note)
            return false
        }
        // Chốt kế hoạch Banker ngay: đây là tay đầu của chuỗi B-P-B-P.
        session.beginFirstHand()
        stableLetters = scan.letters
        stableCount = 0
        entered = true
        lastError = ""
        publish()
        // Đặt luôn nếu còn kịp cửa sổ cược của ván hiện tại.
        placeIfNeeded()
        return true
    }

    /**
     * Chờ tới khi đọc được bàn đang mở. Tự vào bàn từ sảnh nếu đang đứng ở sảnh.
     * Trả null khi hết thời gian chờ.
     */
    private fun waitForTable(): TableScan? {
        val deadline = System.currentTimeMillis() + 45_000
        var triedAutoEnter = false
        var waited = 0
        while (running.get() && System.currentTimeMillis() < deadline) {
            val scan = scanTable()
            if (scan != null) {
                // Có bàn là đủ để bắt đầu: phương pháp 1-1 không xét cầu trước đó nên
                // không cần chờ cầu render.
                return scan
            } else if (inTableByUrl()) {
                lastError = "Bàn đang tải…"
                publish()
            } else if (!triedAutoEnter) {
                // Đang ở sảnh → tự vào một bàn chuẩn một lần, rồi chờ bàn tải.
                triedAutoEnter = true
                lastError = "Đang tìm và mở một bàn Baccarat…"
                publish()
                autoEnterTable()
            }
            if (!running.get()) return null
            Thread.sleep(900)
            waited += 900
            if (waited % 2700 == 0) publish()
        }
        return null
    }

    /**
     * Tự vào bàn từ sảnh: quét ô bàn, chọn bàn baccarat chuẩn, bấm vào ô rồi chờ bàn mở.
     * Trả true khi đã bấm được (chưa chắc bàn đã tải xong — nơi gọi sẽ đọc lại).
     */
    private fun autoEnterTable(): Boolean {
        repeat(3) { attempt ->
            val tiles = lobbyTiles()
            val pick = pickStandardTable(tiles)
            if (pick == null) {
                if (tiles.isEmpty()) return false
                // Sảnh chỉ có bàn đặc biệt/không phải baccarat: không đánh.
                lastError = "Sảnh chỉ có bàn đặc biệt hoặc không phải Baccarat chuẩn; không tự vào."
                return false
            }
            val args = JSONObject().put("id", pick.id)
            val replies = bridge.call(
                "open_tile", args, timeoutMs = 6000, quietMs = 150,
                until = { it.ok && it.result?.optBoolean("ok") == true }
            )
            val result = replies.firstOrNull { it.result?.optBoolean("ok") == true }?.result
            lastError = "Đang mở bàn ${pick.title}…"
            publish()
            if (result != null && result.optBoolean("clicked")) {
                // Chờ bàn mở xong (iframe mới cần thời gian tải).
                repeat(12) {
                    Thread.sleep(700)
                    if (!running.get()) return false
                    if (scanTable() != null) return true
                }
            } else if (result != null) {
                // Script chỉ đo được toạ độ: bấm cảm ứng thật.
                val abs = result.optJSONObject("abs")
                if (abs != null && abs.optBoolean("ok")) {
                    onTapRequested(abs.optDouble("x").toFloat(), abs.optDouble("y").toFloat())
                    repeat(12) {
                        Thread.sleep(700)
                        if (!running.get()) return false
                        if (scanTable() != null) return true
                    }
                }
            }
            Thread.sleep(600L * (attempt + 1))
        }
        return false
    }

    private fun fail(message: String) {
        lastError = message
        session.pause(message)
        running.set(false)
    }

    private fun tick() {
        // Kế hoạch PENDING quá lâu: đọc chip THẬT trên bàn để quyết định, không đoán.
        resolveStalePending()

        // Kế hoạch đã thất bại: tiêu thụ ngay, không chờ ký tự cầu mới (bàn tạm dừng thì
        // sẽ treo phiên). Giữ nguyên cửa + bậc, đánh lại ván kế tiếp.
        when (session.absorbFailedPlan()) {
            Step.ARMED -> placeIfNeeded()
            Step.PAUSED -> {
                running.set(false)
                return
            }
            Step.IDLE -> Unit
        }

        // Đọc cầu bằng lệnh NHẸ (`road`): chỉ lấy chuỗi ký tự bảng cầu, không quét sảnh
        // và không đọc khay chip. Trước đây mỗi nhịp gọi `scan` tốn tới 3,2 giây nên
        // vòng lặp trễ và bỏ lỡ cửa sổ cược của ván kế tiếp.
        val road = readRoad()
        if (road == null) {
            // Chưa đọc được bàn: thử quét đầy đủ để tự vào bàn từ sảnh.
            val scan = scanTable()
            if (scan == null) {
                if (entered) {
                    liveInTable = false
                    fail(
                        "Không còn đọc được bàn đang mở. Hãy mở lại bàn rồi bấm BẮT ĐẦU."
                    )
                }
                return
            }
            liveInTable = true
            liveTableId = scan.tableId
            liveTableName = scan.name
            liveLetters = scan.letters
            entered = true
            if (session.pendingPlan() == null) {
                val step = session.process(scan.letters)
                if (step == Step.ARMED) placeIfNeeded()
                if (step == Step.PAUSED) running.set(false)
            }
            return
        }

        liveInTable = true
        if (road.tableId.isNotBlank()) liveTableId = road.tableId
        liveLetters = road.letters

        // Cầu chưa render xong thì chưa có mốc để so — nhưng kế hoạch vẫn được giữ và
        // đặt như bình thường (phương pháp 1-1 không cần cầu trước đó).
        if (road.letters.isBlank()) {
            if (session.pendingPlan() != null) placeIfNeeded()
            return
        }

        // Chỉ tin cầu khi hai lần đọc liên tiếp giống nhau (tránh đọc lúc React đang render).
        if (road.letters != stableLetters) {
            stableLetters = road.letters
            stableCount = 0
        } else {
            stableCount += 1
        }

        val step = session.process(road.letters)
        if (readNote.isNotBlank()) lastError = readNote
        when (step) {
            Step.ARMED -> placeIfNeeded()
            Step.PAUSED -> {
                // Phiên đã dừng (hết thang, chặn vốn, cầu nhảy…): dừng cả vòng lặp để
                // người dùng đọc lời nhắn rồi bấm BẮT ĐẦU lại.
                running.set(false)
            }
            Step.IDLE -> {
                // Không có kết quả mới: vẫn phải thử đặt nếu kế hoạch còn chờ (đây là
                // nhịp đặt chip chính, không phải chờ kết quả).
                if (session.pendingPlan() != null) placeIfNeeded()
            }
        }
    }

    /**
     * Giải quyết kế hoạch PENDING bị treo: đọc chip THẬT trên bàn rồi mới kết luận.
     *
     * Trước đây nếu vòng lặp trượt cửa sổ cược mà chip vẫn chưa lên, kế hoạch kẹt ở
     * PENDING vĩnh viễn và cả phiên đứng im. Ở đây chỉ hành động khi đã quá hạn chờ và
     * bàn đã SẠCH chip (hoặc chip đúng bằng mức cần đặt) — tức là đã có bằng chứng rõ.
     */
    private fun resolveStalePending() {
        val plan = session.pendingPlan() ?: return
        if (plan.placed != Placement.PENDING) return
        val waited = System.currentTimeMillis() - plan.armedAt
        if (waited < PENDING_GRACE_MS) return
        if (plan.placingAt == 0L) return   // chưa từng thử bấm → nhịp sau sẽ bấm

        val chips = readChips() ?: return
        if (!chips.optBoolean("has_table")) return
        val amount = chips.optJSONObject("spots")?.optJSONObject(plan.side)
            ?.optDouble("amount") ?: 0.0

        if (amount + 0.5 >= plan.stake) {
            // Chip ĐÃ lên bàn thật → tính là đã đặt; kết quả tay này sẽ được cân bình thường.
            session.markPlaced(
                true,
                "Đã xác nhận chip trên bàn (${Fmt.money(amount)} VN2) sau khi chờ.",
                plan
            )
            return
        }
        // Bàn sạch, không có chip của ta → chưa đặt được đồng nào. Trả về như lượt trượt:
        // giữ nguyên cửa + bậc, đánh lại ván kế tiếp.
        session.markPlaced(
            false,
            "Hết thời gian chờ mà chip không lên bàn (ô ${Method.sideName(plan.side)} = " +
                "${Fmt.money(amount)}).",
            plan
        )
    }

    /** Cầu đọc bằng lệnh nhẹ `road`. Null khi chưa vào bàn / chưa có bảng cầu. */
    private data class Road(val tableId: String, val letters: String)

    private fun readRoad(): Road? {
        val replies = bridge.call(
            "road", JSONObject(), timeoutMs = 900, quietMs = 60,
            until = { it.ok && it.result?.optString("letters").orEmpty().isNotBlank() }
        )
        val best = replies
            .filter { it.ok && it.result != null }
            .maxByOrNull { it.result?.optString("letters").orEmpty().length }
            ?: return null
        val result = best.result ?: return null
        val letters = result.optString("letters")
        val tableId = result.optString("table_id")
        // Chưa vào bàn và cũng chưa thấy cầu: coi như không đọc được bàn.
        if (tableId.isBlank() && letters.isBlank()) return null
        return Road(tableId, letters)
    }


    // ------------------------------------------------------------------ đặt chip

    private fun placeIfNeeded() {
        val plan = session.pendingPlan() ?: return
        if (plan.placed != Placement.PENDING) return
        if (!autoPlace) {
            session.markPlaced(false, "Đang tắt tự đặt chip.", plan)
            return
        }
        // Chặn hai luồng cùng bấm chip cho một kế hoạch.
        if (!planClaim.compareAndSet(false, true)) return
        busy = true
        session.markPlacing()
        val reason: String
        try {
            reason = tryPlace(plan)
        } finally {
            busy = false
            planClaim.set(false)
        }
        if (reason == PLACE_OK) return

        // Lỗi "chắc chắn không đặt được" (vượt hạn mức bàn, không ghép được mệnh giá):
        // đánh dấu thất bại để session xử lý như một lượt trượt.
        if (reason in FATAL_PLACE_ERRORS) {
            session.markPlaced(false, lastPlaceNote, plan)
            return
        }
        // Lỗi TẠM THỜI (bàn còn chip ván trước, cửa sổ cược chưa mở, xác nhận chưa kịp):
        // GIỮ nguyên kế hoạch ở trạng thái chờ và thử lại ngay ở nhịp sau — vẫn trong
        // cùng cửa sổ cược. Trước đây mỗi lần trượt là bỏ luôn tay, nên các tay bị nhảy
        // cách quãng 2, 4, 15, 17, 18.
        session.pendingNote(
            "Chưa đặt được (${lastPlaceNote.ifBlank { reason }}) — đang thử lại trong cửa sổ cược."
        )
    }

    /** Kết quả đặt chip: xong, lỗi tạm thời (thử lại), hay lỗi chắc chắn không đặt được. */
    private fun tryPlace(plan: Plan): String {
        val args = JSONObject()
            .put("side", plan.side)
            .put("amount", plan.stake)
            .put("clickDelayMs", clickDelayMs)
            // Giữ ngắn: chờ quá lâu sẽ lấn sang ván sau và đặt nhầm ván đã có kết quả.
            .put("waitMs", 5000)
            .put("waitClearMs", 7000)
        val replies = bridge.call(
            "place", args, timeoutMs = 20000, quietMs = 120,
            // Khung chứa bàn trả lời ok=true khi đã đặt xong; các khung khác trả lời
            // nhanh "không có bàn" nên phải chờ đúng khung có bàn thay vì thoát sớm.
            until = { it.result?.optBoolean("ok") == true }
        )
        val ok = replies.firstOrNull { it.result?.optBoolean("ok") == true }
        if (ok != null) {
            session.markPlaced(true, ok.result?.optString("note").orEmpty(), plan)
            return PLACE_OK
        }

        // Không xác nhận được qua trả lời: đọc lại ô cược xem chip có lên THẬT không.
        // Đây là chốt chặn đặt đôi — thà nhận là đã đặt còn hơn đặt thêm lần nữa.
        val amount = readChips()?.optJSONObject("spots")?.optJSONObject(plan.side)
            ?.optDouble("amount") ?: 0.0
        if (amount + 0.5 >= plan.stake) {
            session.markPlaced(
                true,
                "Đã xác nhận chip trên bàn (${Fmt.money(amount)} VN2).",
                plan
            )
            return PLACE_OK
        }

        val reason = replies.asSequence()
            .mapNotNull { it.result?.optString("reason")?.takeIf(String::isNotBlank) }
            .firstOrNull()
            ?: "no_reply"
        lastPlaceNote = replies.asSequence()
            .mapNotNull { it.result?.optString("note")?.takeIf(String::isNotBlank) }
            .firstOrNull()
            ?: replies.firstOrNull()?.error?.takeIf(String::isNotBlank)
            ?: "không phản hồi từ trang bàn"

        // Site có thể chặn sự kiện chuột tổng hợp: thử bấm cảm ứng thật NGAY trong lượt
        // này thay vì để mất cả tay.
        if (reason !in FATAL_PLACE_ERRORS && retryWithTouch(plan)) return PLACE_OK
        return reason
    }

    /**
     * Site có thể chặn sự kiện chuột tổng hợp. Khi đó script trả về các bước kèm toạ độ
     * tuyệt đối để native bấm cảm ứng thật trên WebView.
     */
    private fun retryWithTouch(plan: Plan): Boolean {
        val args = JSONObject()
            .put("side", plan.side)
            .put("amount", plan.stake)
            .put("dryRun", true)
            .put("waitMs", 6000)
        val replies = bridge.call(
            "place", args, timeoutMs = 14000, quietMs = 100,
            until = { it.result?.optBoolean("dry_run") == true }
        )
        val result = replies.firstOrNull { it.result?.optBoolean("dry_run") == true }?.result
            ?: return false
        val steps = result.optJSONArray("steps") ?: return false
        val points = mutableListOf<Pair<Float, Float>>()
        for (i in 0 until steps.length()) {
            val abs = steps.optJSONObject(i)?.optJSONObject("abs") ?: return false
            if (!abs.optBoolean("ok")) return false
            points.add(abs.optDouble("x").toFloat() to abs.optDouble("y").toFloat())
        }
        if (points.isEmpty()) return false
        for ((x, y) in points) {
            if (!running.get()) return false
            if (!onTapRequested(x, y)) return false
            Thread.sleep(maxOf(70L, clickDelayMs.toLong()))
        }
        Thread.sleep(900)
        val amount = readChips()?.optJSONObject("spots")?.optJSONObject(plan.side)
            ?.optDouble("amount") ?: 0.0
        val ok = amount + 0.5 >= plan.stake
        session.markPlaced(
            ok,
            if (ok) "Đã đặt ${Fmt.money(plan.stake)} bằng cảm ứng thật."
            else "Cảm ứng thật chưa lên chip (ô ${Method.sideName(plan.side)} = ${Fmt.money(amount)}).",
            plan
        )
        return ok
    }

    // ------------------------------------------------------------------ trạng thái

    fun status(): Status {
        val plan = session.plan()
        val money = session.money
        return Status(
            running = running.get(),
            inTable = liveInTable,
            tableName = session.tableName.ifBlank { liveTableName },
            tableId = liveTableId,
            letters = liveLetters,
            hands = liveLetters.length,
            note = session.note,
            planAction = plan.action,
            planSide = plan.side,
            planStake = plan.stake,
            planReason = plan.reason,
            ladderStep = if (session.fixedStake > 0) 0 else session.method.ladderStep,
            ladder = session.ladder,
            balance = money.balance,
            capital = money.startCapital,
            profit = money.profit,
            wins = money.wins,
            losses = money.losses,
            pushes = money.pushes,
            handsPlayed = money.handsPlayed,
            blocked = money.blocked?.second,
            wallet = liveWallet,
            error = lastError,
            frames = frames,
            chipNote = readNote,
            busy = busy,
            autoPlace = autoPlace,
            log = session.log.toList().takeLast(40).reversed()
        )
    }

    private fun publish() {
        try {
            onUpdate(status())
        } catch (_: Throwable) {
            // UI đã đóng.
        }
    }

    data class Status(
        val running: Boolean,
        val inTable: Boolean,
        val tableName: String,
        val tableId: String,
        val letters: String,
        val hands: Int,
        val note: String,
        val planAction: String,
        val planSide: String?,
        val planStake: Double,
        val planReason: String,
        val ladderStep: Int,
        val ladder: List<Double>,
        val balance: Double,
        val capital: Double,
        val profit: Double,
        val wins: Int,
        val losses: Int,
        val pushes: Int,
        val handsPlayed: Int,
        val blocked: String?,
        val wallet: String,
        val error: String,
        val frames: Int,
        val chipNote: String,
        val busy: Boolean,
        val autoPlace: Boolean,
        val log: List<HandRecord>
    )

    companion object {
        const val CAPITAL = 6000.0
        /**
         * Bậc 1 của thang gấp thếp, tính theo ĐƠN VỊ của bàn (VN2).
         * 1 VN2 = 1.000đ → 20 VN2 = 20.000đ (mệnh giá chip nhỏ nhất của bàn Evolution).
         */
        const val BASE_UNIT = 20.0

        /** Lượt đặt đã xong — không cần thử lại. */
        private const val PLACE_OK = "ok"

        /**
         * Thời gian ân hạn cho một kế hoạch PENDING trước khi đọc chip thật để kết luận.
         * Đủ dài để không cắt ngang một lượt bấm đang chạy, đủ ngắn để không treo phiên.
         */
        private const val PENDING_GRACE_MS = 12_000L

        /**
         * Lỗi mà thử lại cũng vô ích: bậc vượt hạn mức bàn, hoặc số tiền không ghép
         * được bằng mệnh giá chip. Gặp mấy lỗi này thì trả tay về session như một lượt
         * trượt (giữ nguyên cửa/bậc, không đụng vốn).
         */
        private val FATAL_PLACE_ERRORS = setOf(
            "over_table_limit",
            "ungroupable",
            "bad_side",
            "bad_amount"
        )
    }
}
