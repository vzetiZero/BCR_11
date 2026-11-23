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
    private lateinit var navBar: NavBar
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
            onProbe = { runProbe() }
            onApplyConfig = { capital, hands ->
                val (ok, note) = runner.applyConfig(capital, hands)
                toast(note)
                render(runner.status())
            }
        }
        runner.autoPlace = true
        panel.setConfigDefaults(Runner.BASE_UNIT, Method.DEFAULT_LADDER.size)

        // Thanh điều hướng nổi: WebView chạy full màn hình nên cần nút lùi/tiến/tải lại/về nhà.
        navBar = NavBar(this).apply {
            onBack = { if (webView.canGoBack()) webView.goBack(); updateNavState() }
            onForward = { if (webView.canGoForward()) webView.goForward(); updateNavState() }
            onReload = { webView.reload() }
            onHome = {
                val home = prefs.siteUrl.ifBlank { SitePrefs.DEFAULT_SITE }
                webView.loadUrl(home)
            }
        }
        root.addView(
            panel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        // Thêm SAU panel để thanh điều hướng luôn nằm trên cùng, không bị panel che.
        root.addView(
            navBar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.START or android.view.Gravity.TOP
            ).apply { setMargins(dp(4), 0, 0, 0) }
        )
        setContentView(root)
        render(runner.status())
        val start = prefs.resumeUrl()
        panel.setSiteUrl(if (start.isBlank()) prefs.siteUrl else start)
        if (start.isBlank()) askSiteUrl() else webView.loadUrl(start)
        updateNavState()
    }

    /** Bật/tắt nút lùi/tiến của thanh điều hướng theo lịch sử WebView. */
    private fun updateNavState() {
        if (!::navBar.isInitialized) return
        navBar.setNavState(webView.canGoBack(), webView.canGoForward())
    }

    /** Ghi nhớ cặp URL đã đổi scheme, tránh vòng lặp đổi qua đổi lại vô hạn. */
    private var lastFallbackKey: String? = null

    /**
     * Lỗi mạng nghĩa là "không nối được tới host/cổng", không phải "sai đường dẫn".
     * Với các mã này ta thử lại bằng scheme còn lại (https ⇄ http).
     */
    private fun isConnectionError(code: Int): Boolean = when (code) {
        android.webkit.WebViewClient.ERROR_HOST_LOOKUP,
        android.webkit.WebViewClient.ERROR_CONNECT,
        android.webkit.WebViewClient.ERROR_TIMEOUT,
        android.webkit.WebViewClient.ERROR_IO,
        android.webkit.WebViewClient.ERROR_UNKNOWN -> true
        else -> false
    }

    /** Đổi scheme https ⇄ http, giữ nguyên host/cổng/đường dẫn. Trả null nếu không đổi được. */
    private fun swapScheme(url: String): String? = when {
        url.startsWith("https://") -> "http://" + url.removePrefix("https://")
        url.startsWith("http://") -> "https://" + url.removePrefix("http://")
        else -> null
    }

    /**
     * Chuẩn hoá link người dùng dán: bỏ khoảng trắng, thêm https:// khi thiếu,
     * kiểm tra host. Giữ nguyên scheme người dùng đã gõ (http hay https).
     */
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
                updateNavState()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                // Lịch sử chỉ đổi sau khi trang xong → cập nhật nút lùi/tiến ở đây.
                updateNavState()
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                super.onReceivedError(view, request, error)
                // Chỉ xử lý lỗi của chính TRANG (không phải ảnh/quảng cáo trong trang).
                if (!request.isForMainFrame) return
                val failed = request.url?.toString().orEmpty()
                val swapped = swapScheme(failed)
                val code = runCatching { error.errorCode }.getOrDefault(0)

                // "Connection refused" thường do SAI SCHEME: mirror của nhà cái hay chỉ chạy
                // HTTP (cổng 80) trong khi app mặc định thêm https (cổng 443) → bị từ chối.
                // Tự thử một lần bằng scheme còn lại trước khi báo lỗi.
                if (swapped != null && isConnectionError(code) && lastFallbackKey != failed) {
                    lastFallbackKey = failed
                    view.loadUrl(swapped)
                    return
                }

                val text = runCatching { error.description.toString() }.getOrNull().orEmpty()
                panel.setSiteUrl(prefs.siteUrl)
                val hint = if (isConnectionError(code)) {
                    "Máy không nối được tới tên miền này.\n" +
                        "• Link có thể đã chết — lấy link mới của site\n" +
                        "• Nếu site cần VPN/proxy: bật lên rồi thử lại\n" +
                        "• Dán link đầy đủ (có http:// hoặc https://) vào ô \"Link site\""
                } else {
                    "Dán link khác vào ô \"Link site\" trong panel rồi bấm MỞ LINK."
                }
                toast("Không mở được trang ($text).\n$hint")
            }

            override fun onReceivedSslError(
                view: WebView,
                handler: android.webkit.SslErrorHandler,
                error: android.net.http.SslError
            ) {
                // Mirror của nhà cái rất hay dùng chứng chỉ tự ký hoặc sai tên miền.
                // App chỉ mở site do chính người dùng nhập nên chấp nhận và đi tiếp,
                // nếu không sẽ luôn ra "trang web không khả dụng".
                handler.proceed()
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

    /**
     * Chẩn đoán DOM bàn trên thread nền rồi lưu ra file + mở hộp chia sẻ.
     *
     * Mục đích: bàn Evolution trên PC và trên điện thoại dùng chung link nhưng render
     * khác nhau, nên cần đọc cấu trúc THẬT của máy đang chạy để sửa selector.
     */
    private fun runProbe() {
        toast("Đang đọc cấu trúc bàn…")
        Thread {
            val replies = try {
                bridge.call("probe", org.json.JSONObject(), timeoutMs = 6000, quietMs = 500)
            } catch (t: Throwable) {
                emptyList()
            }
            // Chọn khung đọc được nhiều dữ liệu nhất (khung bàn thật, không phải khung rỗng).
            val best = replies
                .filter { it.ok && it.result != null }
                .maxByOrNull { r ->
                    val obj = r.result ?: return@maxByOrNull 0
                    (obj.optJSONArray("chips")?.length() ?: 0) +
                        (obj.optJSONArray("spots")?.length() ?: 0) * 10 +
                        (if (obj.optJSONObject("diag")?.optBoolean("tray") == true) 8 else 0)
                }

            val text = buildString {
                appendLine("BCR — CHẨN ĐOÁN DOM BÀN")
                appendLine("Lúc: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
                appendLine("Thiết bị: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}")
                appendLine("WebView khung: ${replies.size} trả lời")
                appendLine()
                if (best == null) {
                    appendLine("KHÔNG có khung nào trả lời.")
                    appendLine("→ Chưa đăng nhập, chưa vào bàn, hoặc script chưa tiêm được.")
                } else {
                    appendLine("=== URL khung ===")
                    appendLine("url: ${best.url}")
                    appendLine("result_url: ${best.result?.optString("url")}")
                    appendLine()
                    appendLine("=== DIAG (cách đọc hiện tại) ===")
                    appendLine(best.result?.optJSONObject("diag")?.toString(2) ?: "null")
                    appendLine()
                    appendLine("=== DATA-ROLE có trên trang ===")
                    val roles = best.result?.optJSONArray("roles")
                    if (roles == null || roles.length() == 0) {
                        appendLine("(không có data-role nào)")
                    } else {
                        for (i in 0 until roles.length()) {
                            val o = roles.optJSONObject(i) ?: continue
                            appendLine("  ${o.optString("role")} × ${o.optInt("count")}")
                        }
                    }
                    appendLine()
                    appendLine("=== CHIP (data-value) ===")
                    val chips = best.result?.optJSONArray("chips")
                    if (chips == null || chips.length() == 0) {
                        appendLine("(không thấy chip nào)")
                    } else {
                        for (i in 0 until chips.length()) {
                            val o = chips.optJSONObject(i) ?: continue
                            appendLine(
                                "  value=${o.optString("value")} role=${o.optString("role")} " +
                                    "tray=${o.optBoolean("inTray")} ${o.optInt("w")}x${o.optInt("h")} " +
                                    "tại (${o.optInt("x")},${o.optInt("y")}) text='${o.optString("text")}'"
                            )
                        }
                    }
                    appendLine()
                    appendLine("=== Ô CƯỢC (bet-spot-*) ===")
                    val spots = best.result?.optJSONArray("spots")
                    if (spots == null || spots.length() == 0) {
                        appendLine("(không thấy ô cược bet-spot-*)")
                    } else {
                        for (i in 0 until spots.length()) {
                            val o = spots.optJSONObject(i) ?: continue
                            appendLine(
                                "  ${o.optString("role")} ${o.optInt("w")}x${o.optInt("h")} " +
                                    "tại (${o.optInt("x")},${o.optInt("y")}) text='${o.optString("text")}'"
                            )
                        }
                    }
                    appendLine()
                    appendLine("=== NÚT ĐẶC BIỆT (×2 / gấp đôi / hoàn tác) ===")
                    val buttons = best.result?.optJSONArray("buttons")
                    if (buttons == null || buttons.length() == 0) {
                        appendLine("(không thấy)")
                    } else {
                        for (i in 0 until buttons.length()) {
                            val o = buttons.optJSONObject(i) ?: continue
                            appendLine(
                                "  '${o.optString("text")}' role=${o.optString("role")} " +
                                    "${o.optInt("w")}x${o.optInt("h")} tại (${o.optInt("x")},${o.optInt("y")})"
                            )
                        }
                    }
                    appendLine()
                    appendLine("=== HTML KHAY CHIP ===")
                    appendLine(best.result?.optString("tray_html").orEmpty().ifBlank { "(rỗng)" })
                    appendLine()
                    appendLine("=== HTML Ô CƯỢC ===")
                    val html = best.result?.optJSONObject("spot_html")
                    if (html != null) {
                        for (key in listOf("B", "P", "T")) {
                            appendLine("--- $key ---")
                            appendLine(html.optString(key).ifBlank { "(không có)" })
                        }
                    }
                }
            }

            // Lưu ra bộ nhớ ngoài riêng của app rồi chia sẻ.
            val file = java.io.File(getExternalFilesDir(null), "bcr_dom_probe.txt")
            runCatching { file.writeText(text, Charsets.UTF_8) }

            runOnUiThread {
                toast(if (best == null) "Không đọc được bàn. Đã lưu chẩn đoán." else "Đã lưu chẩn đoán DOM.")
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_SUBJECT, "BCR DOM probe")
                    putExtra(android.content.Intent.EXTRA_TEXT, text)
                }
                runCatching {
                    startActivity(android.content.Intent.createChooser(intent, "Gửi chẩn đoán DOM"))
                }
            }
        }.start()
    }

    /** Hỏi link site khi chưa cấu hình (hoặc khi người dùng muốn đổi site).
     * Chấp nhận link đầy đủ hoặc tên miền trần; tự thêm https:// và tự sửa lỗi thiếu dấu chấm.
     */
    private fun askSiteUrl() {
        val input = android.widget.EditText(this).apply {
            // Điền sẵn link mặc định để người dùng chỉ cần bấm MỞ nếu đúng site.
            setText(prefs.siteUrl.ifBlank { prefs.resumeUrl() })
            hint = SitePrefs.DEFAULT_SITE
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
            updateNavState()
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
