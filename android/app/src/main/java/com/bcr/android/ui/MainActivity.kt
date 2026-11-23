package com.bcr.android.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bcr.android.core.Runner
import com.bcr.android.core.SitePrefs
import com.bcr.android.model.Method
import com.bcr.android.web.Bridge

/**
 * Cửa sổ duy nhất: WebView chạy sảnh thật + bảng điều khiển nổi.
 *
 * Người dùng tự đăng nhập và tự vào bàn trên web; app chỉ đọc DOM và bấm chip khi được
 * bật. Mọi thứ chạy on-device, không qua server trung gian.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var bridge: Bridge
    private lateinit var runner: Runner
    private lateinit var panel: ControlPanel
    private lateinit var prefs: SitePrefs

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = SitePrefs(this)
        val root = FrameLayout(this)
        webView = WebView(this)
        configure(webView)
        root.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        bridge = Bridge(webView)
        // Cài listener TRƯỚC khi tải trang để không mất sự kiện của khung đầu tiên.
        if (!bridge.attach()) bridge.attachLegacy()
        val injected = bridge.inject(readAsset("bcr_core.js"))
        if (!injected) {
            toast(
                "WebView trên máy quá cũ: không tiêm được script đọc bàn. " +
                    "Cập nhật Android System WebView trong CH Play rồi mở lại app."
            )
        }

        runner = Runner(bridge) { status -> runOnUiThread { panel.render(status) } }
        runner.onTapRequested = { x, y -> tap(x, y) }

        panel = ControlPanel(this).apply {
            onStart = {
                // Đọc cấu hình ngay trên giao diện và áp dụng trước khi chạy: bấm BẮT ĐẦU
                // là đánh đúng "bậc 1 × số tay" người dùng đã nhập, không cần bấm ÁP DỤNG.
                val (base, hands) = configValues()
                if (base > 0) {
                    val (ok, note) = runner.applyConfig(base, hands)
                    if (!ok) toast(note)
                }
                runner.start()
                toast("Đang dò bàn… tool sẽ tự đánh khi đọc được cầu.")
                postDelayed({ render(runner.status()) }, 2500)
            }
            onStop = {
                runner.stop("Đã dừng tool theo yêu cầu.")
                toast("Đã dừng tool.")
                render(runner.status())
            }
            onRead = {
                Thread {
                    val scan = runner.scanTable()
                    runOnUiThread {
                        toast(
                            when {
                                scan == null ->
                                    "Chưa đọc được bàn. Hãy đăng nhập, vào sảnh và mở một bàn Baccarat."
                                scan.letters.isBlank() ->
                                    "${scan.name}: thấy bàn nhưng chưa đọc được cầu."
                                else ->
                                    "${scan.name}: ${scan.letters.length} tay · " +
                                        scan.letters.takeLast(18)
                            }
                        )
                        render(runner.status())
                    }
                }.start()
            }
            onToggleAutoPlace = { value -> runner.autoPlace = value }
            onResetSession = {
                runner.resetSession(Runner.CAPITAL)
                toast("Đã tạo phiên mới · vốn ${"%,.0f".format(Runner.CAPITAL)}.")
                render(runner.status())
            }
            onOpenLobby = {
                val target = prefs.resumeUrl()
                if (target.isBlank()) askSiteUrl()
                else webView.loadUrl(target)
            }
            onEditSite = { askSiteUrl() }
            onOpenSiteUrl = { raw -> openSiteUrl(raw) }
            onApplyConfig = { capital, hands ->
                val (ok, note) = runner.applyConfig(capital, hands)
                toast(note)
                render(runner.status())
            }
        }
        runner.autoPlace = true
        panel.setConfigDefaults(Runner.BASE_UNIT, Method.DEFAULT_LADDER.size)

        root.addView(
            panel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(root)
        render(runner.status())
        val start = prefs.resumeUrl()
        panel.setSiteUrl(if (start.isBlank()) prefs.siteUrl else start)
        if (start.isBlank()) askSiteUrl() else webView.loadUrl(start)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(view: WebView) {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        // Bỏ cờ "; wv" để site không nhận ra WebView và chặn đăng nhập/iframe.
        view.settings.userAgentString = view.settings.userAgentString.replace("; wv", "")
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
        }
        view.webChromeClient = WebChromeClient()
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                // Giữ mọi điều hướng http(s) trong app để phiên đăng nhập không mất.
                val scheme = request.url.scheme ?: ""
                return scheme != "http" && scheme != "https"
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // Ghi nhớ trang thật đang mở để "MỞ SẢNH" quay lại đúng site của người dùng.
                url?.let { prefs.lastUrl = it }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                super.onReceivedError(view, request, error)
                // Chỉ báo lỗi của chính TRANG (không báo lỗi ảnh/quảng cáo trong trang).
                if (!request.isForMainFrame) return
                // Lỗi SSL/thanh chứng chỉ thì để nguyên nhân thật, không khuyên đổi link.
                val text = runCatching { error.description.toString() }.getOrNull().orEmpty()
                panel.setSiteUrl(prefs.siteUrl)
                toast(
                    "Không mở được trang ($text).\n" +
                        "Dán link khác vào ô \"Link site\" trong panel rồi bấm MỞ LINK."
                )
            }
        }
        view.setBackgroundColor(Color.parseColor("#0B1220"))
    }

    private fun render(status: Runner.Status) {
        panel.render(status)
    }

    /**
     * Mở link người dùng dán trong panel. Tự thêm https://, lưu lại làm site đang dùng,
     * và báo rõ khi link không hợp lệ.
     */
    private fun openSiteUrl(raw: String) {
        val url = normalizeUrl(raw)
        if (url == null) {
            toast("Link không hợp lệ. Ví dụ: https://ten-mien.com")
            return
        }
        prefs.siteUrl = url
        prefs.lastUrl = url
        panel.setSiteUrl(url)
        webView.loadUrl(url)
        toast("Đang mở: $url")
    }

    /** Hỏi link site khi chưa cấu hình (hoặc khi người dùng muốn đổi site).
     * Chấp nhận link đầy đủ hoặc tên miền trần; tự thêm https:// và tự sửa lỗi thiếu dấu chấm.
     */
    private fun askSiteUrl() {
        val input = android.widget.EditText(this).apply {
            setText(prefs.siteUrl)
            hint = "https://ten-mien-cua-ban.com"
            setSingleLine()
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Link vào site")
            .setMessage(
                "Dán link trang chủ (hoặc link sảnh) của site bạn chơi. " +
                    "App sẽ mở đúng link này mỗi lần khởi động.\n\n" +
                    "Muốn đổi site sau này: giữ lâu nút \"MỞ SẢNH\"."
            )
            .setView(box)
            .setPositiveButton("MỞ") { _, _ ->
                val url = normalizeUrl(input.text.toString())
                if (url == null) {
                    toast("Link không hợp lệ. Ví dụ: https://ten-mien.com")
                } else {
                    prefs.siteUrl = url
                    prefs.lastUrl = url
                    webView.loadUrl(url)
                }
            }
            .setNegativeButton("Để sau", null)
            .show()
    }

    /** Chuẩn hoá link người dùng dán: thêm https:// khi thiếu, bỏ khoảng trắng, kiểm tra host. */
    private fun normalizeUrl(raw: String): String? {
        var text = raw.trim().replace(" ", "")
        if (text.isEmpty()) return null
        if (!text.startsWith("http://") && !text.startsWith("https://")) {
            text = "https://" + text.removePrefix("//")
        }
        val parsed = runCatching { java.net.URI(text) }.getOrNull() ?: return null
        val host = parsed.host ?: return null
        if (host.isBlank() || !host.contains(".")) return null
        return text
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun toast(text: String) {
        if (text.isBlank()) return
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun readAsset(name: String): String =
        assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }

    /**
     * Bấm cảm ứng thật tại toạ độ CSS px của khung gốc. Dùng khi site chặn sự kiện chuột
     * tổng hợp phát ra từ script. Phải chạy trên UI thread.
     */
    private fun tap(x: Float, y: Float): Boolean {
        val density = resources.displayMetrics.density
        val px = x * density
        val py = y * density
        var result = false
        val run = Runnable {
            try {
                val now = SystemClock.uptimeMillis()
                val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, px, py, 0)
                val up = MotionEvent.obtain(now, now + 70, MotionEvent.ACTION_UP, px, py, 0)
                result = webView.dispatchTouchEvent(down) or webView.dispatchTouchEvent(up)
                down.recycle()
                up.recycle()
            } catch (_: Throwable) {
                result = false
            }
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            run.run()
        } else {
            runOnUiThread(run)
        }
        return result
    }

    @Deprecated("Cần cho API < 33")
    override fun onBackPressed() {
        if (this::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        if (this::runner.isInitialized) runner.stop("Activity đóng.")
        if (this::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }
}
