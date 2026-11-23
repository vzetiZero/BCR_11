package com.bcr.android.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.bcr.android.core.Runner
import com.bcr.android.model.Fmt
import com.bcr.android.model.Method
import kotlin.math.abs

/**
 * Bảng điều khiển native nổi trên WebView.
 *
 * Hai trạng thái để không che bàn:
 *  - Thu gọn: một viên nhỏ ở góc trên trái (trạng thái + số dư). Chạm để mở.
 *  - Mở rộng: thẻ đầy đủ neo ở ĐÁY, gồm kế hoạch tay kế tiếp, thang gấp thếp, nút điều
 *    khiển, thống kê và log.
 *
 * Dựng bằng code (không XML) để giao diện nằm gọn trong một file và không phụ thuộc theme.
 */
class ControlPanel(context: Context) : FrameLayout(context) {

    var onStart: () -> Unit = {}
    var onStop: () -> Unit = {}
    var onRead: () -> Unit = {}
    var onToggleAutoPlace: (Boolean) -> Unit = {}
    var onResetSession: () -> Unit = {}
    var onOpenLobby: () -> Unit = {}
    var onEditSite: () -> Unit = {}
    /** Áp dụng cấu hình: (vốn ban đầu theo đơn vị bàn, số tay gấp thếp). */
    var onApplyConfig: (Double, Int) -> Unit = { _, _ -> }

    /** Mở link do người dùng dán vào ô "Link site" trong panel. */
    var onOpenSiteUrl: (String) -> Unit = {}

    private val capitalInput = android.widget.EditText(context)
    private val handsInput = android.widget.EditText(context)
    private val siteInput = android.widget.EditText(context)

    /** Hiện link site đang dùng trong ô nhập của panel. */
    fun setSiteUrl(url: String) {
        if (siteInput.text.toString() != url) siteInput.setText(url)
    }
    private var defaultCapital = 6000.0
    private var defaultHands = 8

    /** Đặt giá trị ban đầu cho ô cấu hình (gọi một lần từ Activity). */
    fun setConfigDefaults(capital: Double, hands: Int) {
        defaultCapital = capital
        defaultHands = hands
        capitalInput.setText(Fmt.units(capital, 0).replace(".", ""))
        handsInput.setText(hands.toString())
    }

    /** Bậc 1 và số tay hiện tại trên giao diện. */
    fun configValues(): Pair<Double, Int> {
        val base = Fmt.parseMoney(capitalInput.text.toString(), 0.0)
        val hands = handsInput.text.toString().trim().toIntOrNull() ?: 0
        return base to hands
    }

    private val fab: TextView

    private val dot = TextView(context)
    private val stateLabel = TextView(context)
    private val balanceLabel = TextView(context)
    private val planLabel = TextView(context)
    private val tableLine = TextView(context)
    private val planDetail = TextView(context)
    private val statsLine = TextView(context)
    private val noteLine = TextView(context)
    private val logView = TextView(context)
    private val ladderRow = LinearLayout(context)
    private val card: LinearLayout
    private val cardScroll: ScrollView
    private val startButton: Button
    private val stopButton: Button
    private val autoButton: Button

    private var expanded = true
    private var autoPlace = true

    init {
        setBackgroundColor(Color.TRANSPARENT)

        // ------------------------------------------------------------------ thẻ đầy đủ
        card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(10))
            background = roundFill(BG, dp(16), STROKE)
        }

        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        dot.apply {
            text = "●"
            setTextColor(MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, 0, dp(6), 0)
        }
        stateLabel.apply {
            text = "CHƯA VÀO BÀN"
            setTextColor(MUTED)
            typeface = Typeface.DEFAULT_BOLD
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        balanceLabel.apply {
            text = "—"
            setTextColor(ACCENT)
            typeface = Typeface.DEFAULT_BOLD
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        val closeButton = mini("✕")
        closeButton.setOnClickListener { setExpanded(false) }

        head.addView(dot)
        head.addView(stateLabel)
        head.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        head.addView(balanceLabel)
        head.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(dp(6), 1) })
        head.addView(closeButton)
        card.addView(head)

        planLabel.apply {
            text = "Chưa có kế hoạch"
            setTextColor(MUTED)
            typeface = Typeface.DEFAULT_BOLD
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, dp(7), 0, 0)
        }
        card.addView(planLabel)

        planDetail.apply {
            setTextColor(MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(3), 0, 0)
            text = "Tự đăng nhập → vào sảnh → mở một bàn Baccarat → bấm BẮT ĐẦU."
        }
        card.addView(planDetail)

        ladderRow.apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        card.addView(ladderRow)

        val row1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(9), 0, 0)
        }
        startButton = pill("BẮT ĐẦU", ACCENT)
        stopButton = pill("DỪNG", STOP)
        val readButton = pill("ĐỌC BÀN", SLATE)
        startButton.setOnClickListener { onStart() }
        stopButton.setOnClickListener { onStop() }
        readButton.setOnClickListener { onRead() }
        row1.addView(startButton, weight())
        row1.addView(space())
        row1.addView(stopButton, weight())
        row1.addView(space())
        row1.addView(readButton, weight())
        card.addView(row1)

        val row2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        autoButton = pill("TỰ ĐẶT: BẬT", ACCENT)
        val resetButton = pill("PHIÊN MỚI", SLATE)
        val lobbyButton = pill("MỞ SẢNH", SLATE)
        autoButton.setOnClickListener {
            autoPlace = !autoPlace
            paintAuto()
            onToggleAutoPlace(autoPlace)
        }
        resetButton.setOnClickListener { onResetSession() }
        lobbyButton.setOnClickListener { onOpenLobby() }
        // Giữ lâu = đổi site (dán link mới), bấm thường = mở lại sảnh đang dùng.
        lobbyButton.setOnLongClickListener {
            onEditSite()
            true
        }
        row2.addView(autoButton, weight())
        row2.addView(space())
        row2.addView(resetButton, weight())
        row2.addView(space())
        row2.addView(lobbyButton, weight())
        card.addView(row2)

        // ------------------------------------------------- cấu hình vốn + số tay
        val cfgRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        cfgRow.addView(fieldLabel("Bậc 1"))
        capitalInput.apply {
            setText(Fmt.units(defaultCapital, 0).replace(".", ""))
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setBackground(roundFill(FIELD, dp(8), STROKE))
            setPadding(dp(7), dp(5), dp(7), dp(5))
            setSingleLine()
            hint = "vd 10"
        }
        cfgRow.addView(
            capitalInput,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        cfgRow.addView(fieldLabel("Số tay"))
        handsInput.apply {
            setText(defaultHands.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setBackground(roundFill(FIELD, dp(8), STROKE))
            setPadding(dp(7), dp(5), dp(7), dp(5))
            setSingleLine()
            hint = "vd 10"
        }
        cfgRow.addView(
            handsInput,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        val applyButton = pill("ÁP DỤNG", SLATE)
        applyButton.setOnClickListener {
            val capital = Fmt.parseMoney(capitalInput.text.toString(), 0.0)
            val hands = handsInput.text.toString().trim().toIntOrNull() ?: 0
            onApplyConfig(capital, hands)
        }
        cfgRow.addView(space())
        cfgRow.addView(applyButton, LinearLayout.LayoutParams(dp(78), dp(34)))
        card.addView(cfgRow)

        // ------------------------------------------------- link site (máy khác bị đổi tên miền)
        // Ô nhập + nút MỞ: dán link trang đang dùng được rồi mở ngay, không cần vào menu khác.
        val siteRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        siteInput.apply {
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setBackground(roundFill(FIELD, dp(8), STROKE))
            setPadding(dp(7), dp(6), dp(7), dp(6))
            setSingleLine()
            hint = "https://ten-mien-moi.com"
            setHintTextColor(MUTED)
        }
        siteRow.addView(
            siteInput,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        val openSiteButton = pill("MỞ LINK", SLATE)
        openSiteButton.setOnClickListener {
            onOpenSiteUrl(siteInput.text.toString())
        }
        siteRow.addView(space())
        siteRow.addView(openSiteButton, LinearLayout.LayoutParams(dp(84), dp(34)))
        card.addView(siteRow)

        tableLine.apply {
            setTextColor(MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(8), 0, 0)
            text = "Chưa đọc được bàn nào."
        }
        card.addView(tableLine)

        statsLine.apply {
            setTextColor(MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(4), 0, 0)
        }
        card.addView(statsLine)

        noteLine.apply {
            setTextColor(WARN)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(6), 0, 0)
            visibility = View.GONE
        }
        card.addView(noteLine)

        logView.apply {
            setTextColor(MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(6), 0, 0)
        }
        val logScroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(92))
        }
        logScroll.addView(logView)
        card.addView(logScroll)

        // Bọc thẻ trong ScrollView để không bao giờ cao quá 62% màn hình (không che bàn).
        // Neo ở TRÊN: khi bàn phím bật lên, ô nhập vẫn nằm trong vùng nhìn thấy.
        cardScroll = ScrollView(context).apply {
            isFillViewport = false
            visibility = View.GONE
            addView(card)
        }
        addView(cardScroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP
            setMargins(dp(6), dp(6), dp(6), 0)
        })

        // ------------------------------------------- bong bóng nổi kiểu nút trợ năng
        // Hình tròn, luôn nằm trên cùng, KÉO ĐƯỢC tới chỗ trống, CHẠM để mở/đóng panel.
        fab = TextView(context).apply {
            text = "BCR"
            gravity = Gravity.CENTER
            setTextColor(INK)
            typeface = Typeface.DEFAULT_BOLD
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            background = roundFill(ACCENT, dp(28), ACCENT)
            elevation = dp(6).toFloat()
            isClickable = true
            isFocusable = true
            contentDescription = "Mở hoặc đóng bảng cấu hình"
        }
        val size = dp(56)
        addView(fab, LayoutParams(size, size).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            setMargins(0, 0, dp(10), dp(150))
        })
        makeDraggable(fab)

        paintAuto()
        // Mặc định ĐÓNG panel: bàn phải nhìn thấy ngay, chỉ còn bong bóng nhỏ.
        setExpanded(false)

        // Khi bàn phím bật, khung gốc thấp lại: co panel theo và cuộn ô đang nhập vào vùng nhìn.
        addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top == oldBottom - oldTop) return@addOnLayoutChangeListener
            if (!expanded) return@addOnLayoutChangeListener
            applyMaxHeight()
            val focused = findFocus()
            if (focused is android.widget.EditText) {
                cardScroll.post { cardScroll.smoothScrollTo(0, focused.top) }
            }
        }
    }

    /** Giới hạn chiều cao thẻ theo màn hình HIỆN TẠI (đã trừ bàn phím), tối đa 62%. */
    private fun applyMaxHeight() {
        val maxH = (height * 0.62f).toInt().coerceAtLeast(dp(160))
        val lp = cardScroll.layoutParams
        val target = minOf(card.height, maxH)
        if (lp.height != target && target > 0) {
            lp.height = target
            cardScroll.layoutParams = lp
        }
    }

    /**
     * Cho bong bóng kéo được như nút trợ năng: kéo thì dời, chạm (không dời) thì bật/tắt panel.
     */
    private fun makeDraggable(view: View) {
        var downRawX = 0f
        var downRawY = 0f
        var startMarginVertical = 0
        var startMarginEnd = 0
        var atBottom = true
        var dragged = false
        val touchSlop = 8 * resources.displayMetrics.density
        view.setOnTouchListener { v, event ->
            val lp = v.layoutParams as LayoutParams
            atBottom = (lp.gravity and Gravity.BOTTOM) == Gravity.BOTTOM
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startMarginVertical = if (atBottom) lp.bottomMargin else lp.topMargin
                    startMarginEnd = lp.rightMargin
                    dragged = false
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) dragged = true
                    if (dragged) {
                        // Kéo bằng margin: đúng với gravity, không bị đẩy ra ngoài màn hình.
                        val maxV = (height - v.height).coerceAtLeast(0)
                        val nextV = (startMarginVertical + if (atBottom) -dy.toInt() else dy.toInt())
                            .coerceIn(0, maxV)
                        if (atBottom) lp.bottomMargin = nextV else lp.topMargin = nextV
                        lp.rightMargin = (startMarginEnd - dx.toInt()).coerceIn(0, (width - v.width).coerceAtLeast(0))
                        v.layoutParams = lp
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (!dragged) toggle()
                    true
                }
                else -> false
            }
        }
    }

    /** Bật/tắt panel cấu hình (dùng cho bong bóng và nút ✕). */
    fun toggle() = setExpanded(!expanded)

    fun setExpanded(value: Boolean) {
        expanded = value
        cardScroll.visibility = if (value) View.VISIBLE else View.GONE
        // Luôn hiện bong bóng; đổi nhãn để biết đang mở hay đóng.
        fab.text = if (value) "✕" else "BCR"
        fab.background = roundFill(if (value) STOP else ACCENT, dp(28), if (value) STOP else ACCENT)
        fab.setTextColor(if (value) TEXT else INK)
        if (value) {
            // Giới hạn chiều cao để panel không che quá nửa bàn (và không bị bàn phím che).
            post { applyMaxHeight() }
        }
    }

    fun setAutoPlace(value: Boolean) {
        autoPlace = value
        paintAuto()
        onToggleAutoPlace(value)
    }

    private fun paintAuto() {
        autoButton.text = if (autoPlace) "TỰ ĐẶT: BẬT" else "TỰ ĐẶT: TẮT"
        autoButton.background = roundFill(
            if (autoPlace) ACCENT else SLATE, dp(9), if (autoPlace) ACCENT else STROKE
        )
        autoButton.setTextColor(if (autoPlace) INK else TEXT)
    }

    /** Vẽ lại toàn bộ bảng từ trạng thái runner. */
    fun render(status: Runner.Status) {
        val running = status.running
        val stateText = when {
            running -> "ĐANG CHẠY"
            status.inTable -> "ĐÃ VÀO BÀN"
            else -> "CHƯA VÀO BÀN"
        }
        val stateColor = when {
            running -> ACCENT
            status.inTable -> TEXT
            else -> MUTED
        }
        dot.setTextColor(if (running) ACCENT else if (status.inTable) WARN else MUTED)
        stateLabel.text = stateText
        stateLabel.setTextColor(stateColor)
        balanceLabel.text = "${Fmt.money(status.balance)} VN2"

        val planText = when (status.planAction) {
            Method.ACTION_START, Method.ACTION_BET ->
                "${Method.sideName(status.planSide)} · ${Fmt.money(status.planStake)}"
            Method.ACTION_WAIT -> "CHỜ ĐỦ TAY"
            Method.ACTION_HALT -> "HẾT THANG"
            else -> "CHƯA CÓ KẾ HOẠCH"
        }
        // Bong bóng đổi màu theo trạng thái để biết tool đang chạy mà không cần mở panel.
        if (!expanded) {
            fab.background = roundFill(
                if (running) ACCENT else if (status.inTable) WARN else SLATE,
                dp(28),
                if (running) ACCENT else if (status.inTable) WARN else STROKE
            )
            fab.setTextColor(if (running || status.inTable) INK else TEXT)
        }

        renderPlan(status)
        renderLadder(status)

        val wallet = if (status.wallet.isNotBlank()) " · ví ${status.wallet}" else ""
        tableLine.text = if (status.inTable) {
            "${status.tableName.ifBlank { "Bàn đang mở" }} · ${status.hands} tay · " +
                "${status.frames} khung$wallet"
        } else {
            "Chưa thấy bàn Baccarat trong WebView$wallet"
        }

        statsLine.text = buildString {
            append("Vốn ${Fmt.money(status.capital)}")
            append(" · Lãi/Lỗ ${Fmt.signed(status.profit)}")
            append("\nThắng ${status.wins} · Thua ${status.losses} · Hoà ${status.pushes}")
            append(" · đã tính ${status.handsPlayed} tay · bậc ${status.ladderStep + 1}/${status.ladder.size}")
        }

        val notes = mutableListOf<String>()
        status.blocked?.let { notes.add(it) }
        if (status.error.isNotBlank()) notes.add(status.error)
        if (status.note.isNotBlank()) notes.add(status.note)
        noteLine.text = notes.joinToString("\n")
        noteLine.visibility = if (notes.isEmpty()) View.GONE else View.VISIBLE

        logView.text = if (status.log.isEmpty()) {
            "Chưa có tay nào được tính."
        } else {
            status.log.joinToString("\n") { row ->
                val mark = when {
                    !row.placed -> "✗"
                    row.outcome == "win" -> "✓"
                    row.outcome == "push" -> "="
                    else -> "✗"
                }
                val sign = if (row.delta > 0) "+" else ""
                "#${row.hand} $mark ${row.bet}→${row.result} ${Fmt.money(row.stake)} " +
                    "($sign${Fmt.money(row.delta)})"
            }
        }

        startButton.isEnabled = !running
        stopButton.isEnabled = running
        startButton.alpha = if (running) 0.4f else 1f
        stopButton.alpha = if (running) 1f else 0.4f
    }

    private fun renderPlan(status: Runner.Status) {
        val title: String
        val color: Int
        val detail: String
        when (status.planAction) {
            Method.ACTION_START, Method.ACTION_BET -> {
                title = "${Method.sideName(status.planSide)} · ${Fmt.money(status.planStake)} VN2"
                color = if (status.planSide == Method.BANKER) BANKER else PLAYER
                detail = "Tay kế tiếp · bậc ${status.ladderStep + 1}/${status.ladder.size} · " +
                    status.planReason
            }
            Method.ACTION_WAIT -> {
                title = "CHỜ ĐỦ TAY"
                color = WARN
                detail = status.planReason
            }
            Method.ACTION_HALT -> {
                title = "DỪNG — HẾT THANG"
                color = STOP
                detail = status.planReason
            }
            else -> {
                title = "CHƯA CÓ KẾ HOẠCH"
                color = MUTED
                detail = status.planReason
            }
        }
        planLabel.text = title
        planLabel.setTextColor(color)
        planDetail.text = detail
    }

    private fun renderLadder(status: Runner.Status) {
        if (ladderRow.childCount != status.ladder.size) {
            ladderRow.removeAllViews()
            status.ladder.forEachIndexed { index, _ ->
                val cell = TextView(context).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                    gravity = Gravity.CENTER
                    setPadding(dp(2), dp(4), dp(2), dp(4))
                }
                ladderRow.addView(
                    cell,
                    LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (index > 0) marginStart = dp(2)
                    }
                )
            }
        }
        status.ladder.forEachIndexed { index, stake ->
            val cell = ladderRow.getChildAt(index) as TextView
            val active = index == status.ladderStep && status.planAction != Method.ACTION_HALT
            cell.text = Fmt.money(stake)
            cell.setTextColor(if (active) INK else TEXT)
            cell.background = roundFill(
                if (active) ACCENT else CARD, dp(5), if (active) ACCENT else STROKE
            )
        }
    }

    // ------------------------------------------------------------------ vẽ

    private fun pill(label: String, fill: Int): Button = Button(context).apply {
        text = label
        setTextColor(if (fill == ACCENT) INK else TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        typeface = Typeface.DEFAULT_BOLD
        isAllCaps = false
        stateListAnimator = null
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(6), dp(8), dp(6), dp(8))
        background = roundFill(fill, dp(9), if (fill == SLATE) STROKE else fill)
    }

    private fun mini(label: String): Button = Button(context).apply {
        text = label
        setTextColor(MUTED)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        isAllCaps = false
        stateListAnimator = null
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(8), dp(2), dp(8), dp(2))
        background = roundFill(CARD, dp(7), STROKE)
    }

    private fun weight(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)

    private fun space(): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(dp(5), 1)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun fieldLabel(label: String): TextView = TextView(context).apply {
        text = label
        setTextColor(MUTED)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setPadding(0, 0, dp(5), 0)
    }

    private fun roundFill(fill: Int, radius: Int, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(fill)
            if (stroke != fill) setStroke(maxOf(1, dp(1)), stroke)
        }

    companion object {
        val INK = Color.parseColor("#07120B")
        val BG = Color.parseColor("#F20B1220")
        val CARD = Color.parseColor("#22FFFFFF")
        val STROKE = Color.parseColor("#33FFFFFF")
        val TEXT = Color.parseColor("#EAF1FF")
        val MUTED = Color.parseColor("#9FB0CC")
        val ACCENT = Color.parseColor("#3DDC97")
        val WARN = Color.parseColor("#F5C24B")
        val STOP = Color.parseColor("#E05B5B")
        val SLATE = Color.parseColor("#22304A")
        val FIELD = Color.parseColor("#33101828")
        val BANKER = Color.parseColor("#FF7B7B")
        val PLAYER = Color.parseColor("#6BA6FF")
    }
}
