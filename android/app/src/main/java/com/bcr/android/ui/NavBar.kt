package com.bcr.android.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Thanh điều hướng nổi cho WebView.
 *
 * WebView chạy toàn màn hình nên không có thanh địa chỉ của trình duyệt; nếu người dùng
 * bấm vào một liên kết sâu (đăng nhập, trang nạp tiền, sảnh con) thì không có cách quay lại.
 * Thanh này bổ sung đúng những nút đó:
 *
 *   ‹  lùi trang      ›  tiến trang      ⟳  tải lại      ⌂  về trang chủ đã lưu
 *
 * Đặt dọc ở mép trái, giữa chiều cao (vùng bàn thường trống). Giữ và kéo để dời đi chỗ khác;
 * kéo được bắt đầu từ bất kỳ điểm nào trên thanh, chạm ngắn vẫn là bấm nút.
 */
class NavBar(context: Context) : FrameLayout(context) {

    var onBack: () -> Unit = {}
    var onForward: () -> Unit = {}
    var onReload: () -> Unit = {}
    var onHome: () -> Unit = {}

    private val backButton: TextView
    private val forwardButton: TextView

    // ---- trạng thái kéo
    private var downRawX = 0f
    private var downRawY = 0f
    private var startTop = 0
    private var startLeft = 0
    private var dragging = false
    private var userMoved = false
    private val slop = 10 * resources.displayMetrics.density

    init {
        setBackgroundColor(Color.TRANSPARENT)
        // Phải clickable để nhận được onTouchEvent khi đã intercept.
        isClickable = true

        val rail = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(2))
            background = roundFill(RAIL, dp(16), STROKE)
            elevation = dp(5).toFloat()
        }

        backButton = key("‹", "Lùi trang") { onBack() }
        forwardButton = key("›", "Tiến trang") { onForward() }
        val reloadButton = key("⟳", "Tải lại trang") { onReload() }
        val homeButton = key("⌂", "Về trang chủ") { onHome() }

        rail.addView(backButton)
        rail.addView(forwardButton)
        rail.addView(reloadButton)
        rail.addView(homeButton)

        addView(rail, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })

        setNavState(canBack = false, canForward = false)
    }

    /** Bật/tắt nút lùi/tiến theo lịch sử thật của WebView. */
    fun setNavState(canBack: Boolean, canForward: Boolean) {
        backButton.alpha = if (canBack) 1f else 0.3f
        forwardButton.alpha = if (canForward) 1f else 0.3f
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Căn giữa dọc lần đầu, sau khi cha đã có kích thước.
        post { if (!userMoved) centerVertical() }
    }

    private fun centerVertical() {
        val parentView = parent as? View ?: return
        if (parentView.height <= 0 || height <= 0) return
        val lp = layoutParams as? LayoutParams ?: return
        lp.topMargin = ((parentView.height - height) / 2).coerceAtLeast(0)
        layoutParams = lp
    }

    /**
     * Chỉ "bắt" sự kiện khi ngón tay đã di chuyển quá ngưỡng. Nhờ vậy chạm ngắn vẫn tới
     * được các nút con (nút vẫn bấm bình thường), còn kéo thì thanh đi theo tay.
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = ev.rawX
                downRawY = ev.rawY
                val lp = layoutParams as LayoutParams
                startTop = lp.topMargin
                startLeft = lp.leftMargin
                dragging = false
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(ev.rawX - downRawX) > slop || abs(ev.rawY - downRawY) > slop) {
                    dragging = true
                    userMoved = true
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                return false
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!dragging) return super.onTouchEvent(ev)
        val lp = layoutParams as LayoutParams
        val parentView = parent as? View ?: return true
        val dx = ev.rawX - downRawX
        val dy = ev.rawY - downRawY
        lp.topMargin = (startTop + dy.toInt())
            .coerceIn(0, (parentView.height - height).coerceAtLeast(0))
        lp.leftMargin = (startLeft + dx.toInt())
            .coerceIn(0, (parentView.width - width).coerceAtLeast(0))
        layoutParams = lp
        if (ev.actionMasked == MotionEvent.ACTION_UP ||
            ev.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            dragging = false
        }
        return true
    }

    private fun key(label: String, description: String, action: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(TEXT)
            typeface = Typeface.DEFAULT_BOLD
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            contentDescription = description
            isClickable = true
            isFocusable = true
            setPadding(0, dp(2), 0, dp(2))
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
            background = roundFill(BTN, dp(9), BTN_STROKE)
            setOnClickListener { action() }
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun roundFill(fill: Int, radius: Int, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(fill)
            setStroke(1, stroke)
        }

    companion object {
        private val RAIL = Color.parseColor("#E60B1220")
        private val BTN = Color.parseColor("#2A3A55")
        private val BTN_STROKE = Color.parseColor("#33FFFFFF")
        private val STROKE = Color.parseColor("#44FFFFFF")
        private val TEXT = Color.parseColor("#EAF1FF")
    }
}
