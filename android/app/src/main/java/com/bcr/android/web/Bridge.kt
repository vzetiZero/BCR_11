package com.bcr.android.web

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cầu nối WebView ↔ logic Kotlin.
 *
 * `bcr_core.js` được tiêm vào MỌI khung (kể cả iframe khác origin) ngay từ
 * document-start. Mỗi khung tự đăng ký với native qua `BcrNative.postMessage(json)`:
 *  - `hello` : khung vừa sẵn sàng (kèm url + cờ khung gốc).
 *  - `res`   : trả lời một lệnh, có `id` để ghép.
 *
 * Native gửi lệnh vào khung GỐC; khung gốc chuyển tiếp xuống các khung con, nên khung
 * khác origin vẫn nhận được lệnh mà không cần CDP như bản desktop.
 *
 * Chờ trả lời: mỗi khung đã từng nói chuyện được ghi nhớ, và một lệnh chỉ được coi là
 * "xong" khi đã gom đủ trả lời của các khung đó (hoặc hết giờ). Nhờ vậy khung gốc trả
 * lời "không có bàn" trước không làm mất kết quả thật từ khung iframe chứa bàn.
 */
class Bridge(private val webView: WebView) {

    private val main = Handler(Looper.getMainLooper())
    private val seq = AtomicInteger(1)
    private val waiting = ConcurrentHashMap<String, Pending>()
    private val knownFrames: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    @Volatile private var topFrameId: String = ""
    @Volatile private var mainFrameUrl: String = ""

    @Volatile
    var listenerSupported: Boolean = false
        private set

    @Volatile
    var lastReplyCount: Int = 0
        private set

    /** Số khung đã từng trả lời native — dùng để biết khi nào đã gom đủ. */
    fun knownFrameCount(): Int = knownFrames.size

    private class Pending {
        val replies = Collections.synchronizedList(mutableListOf<Reply>())
    }

    data class Reply(
        val frameId: String,
        val url: String,
        val ok: Boolean,
        val error: String,
        val result: JSONObject?
    )

    // --------------------------------------------------------------- cài đặt cầu nối

    /** Gắn WebMessageListener (WebView ≥ 85). Trả false nếu máy quá cũ. */
    fun attach(): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            listenerSupported = false
            return false
        }
        return try {
            WebViewCompat.addWebMessageListener(webView, JS_OBJECT, setOf("*")) {
                    _, message, _, isMainFrame, _ ->
                if (message.type == WebMessageCompat.TYPE_STRING) {
                    message.data?.let { deliver(it, isMainFrame) }
                }
            }
            listenerSupported = true
            true
        } catch (_: Throwable) {
            listenerSupported = false
            false
        }
    }

    /** Đường dự phòng cho WebView cũ: JS interface có mặt ở mọi khung. */
    fun attachLegacy() {
        try {
            webView.addJavascriptInterface(Legacy(), JS_OBJECT)
        } catch (_: Throwable) {
            // Không còn cách nào khác; app vẫn chạy nhưng tool không đọc được bàn.
        }
    }

    private inner class Legacy {
        @JavascriptInterface
        fun postMessage(text: String) = deliver(text, false)
    }

    /** Tiêm script vào mọi khung trước khi trang chạy. */
    fun inject(script: String): Boolean = try {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))
            true
        } else {
            false
        }
    } catch (_: Throwable) {
        false
    }

    // --------------------------------------------------------------- nhận dữ liệu

    private fun deliver(text: String, isMainFrame: Boolean) {
        val obj = try {
            JSONObject(text)
        } catch (_: Throwable) {
            return
        }
        val frameId = obj.optString("frameId")
        if (frameId.isNotBlank()) knownFrames.add(frameId)

        when (obj.optString("__bcr")) {
            "hello" -> if (isMainFrame) {
                topFrameId = frameId
                mainFrameUrl = obj.optString("href")
            }
            "res" -> {
                val reply = Reply(
                    frameId = frameId,
                    url = obj.optString("url"),
                    ok = obj.optBoolean("ok"),
                    error = obj.optString("error"),
                    result = obj.optJSONObject("result")
                )
                waiting[obj.optString("id")]?.replies?.add(reply)
            }
        }
    }

    // --------------------------------------------------------------- gửi lệnh

    /**
     * Gửi một lệnh tới mọi khung, gom trả lời cho tới khi:
     *  - [until] hài lòng với một trả lời (dùng cho lệnh dài như đặt chip), hoặc
     *  - đã gom đủ trả lời của mọi khung từng thấy, hoặc
     *  - hết [timeoutMs].
     *
     * Sau điều kiện dừng còn chờ thêm [quietMs] để vét các khung tới muộn.
     */
    fun call(
        cmd: String,
        args: JSONObject = JSONObject(),
        timeoutMs: Long = 1800,
        quietMs: Long = 140,
        until: ((Reply) -> Boolean)? = null
    ): List<Reply> {
        val id = "c" + seq.getAndIncrement()
        val pending = Pending()
        waiting[id] = pending
        val expected = maxOf(1, knownFrames.size)

        send(
            JSONObject()
                .put("__bcr", "cmd")
                .put("id", id)
                .put("cmd", cmd)
                .put("args", args)
                .put("depth", 0)
                .toString()
        )

        val deadline = System.currentTimeMillis() + timeoutMs
        var satisfied = false
        while (!satisfied) {
            val snap = pending.replies.toList()
            if (until != null && snap.any(until)) {
                satisfied = true
                break
            }
            if (snap.size >= expected && snap.isNotEmpty()) break
            if (System.currentTimeMillis() >= deadline) break
            try {
                Thread.sleep(35)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        if (quietMs > 0) {
            try {
                Thread.sleep(quietMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        waiting.remove(id)
        val replies = pending.replies.toList()
        lastReplyCount = replies.size
        return replies
    }

    private fun send(payload: String) {
        // Luôn gửi qua khung gốc: script trong khung gốc chuyển tiếp xuống các iframe
        // bằng postMessage. Đường này chạy được trên mọi WebView có hỗ trợ JavaScript,
        // không phụ thuộc chi tiết API của JavaScriptReplyProxy.
        evalTop(payload)
    }

    private fun evalTop(payload: String) {
        val script = "(function(){try{return window.__BCR__&&window.__BCR__.dispatch(" +
            JSONObject.quote(payload) + ");}catch(e){return false;}})()"
        main.post {
            try {
                webView.evaluateJavascript(script, null)
            } catch (_: Throwable) {
                // WebView đã bị huỷ.
            }
        }
    }

    fun mainUrl(): String = mainFrameUrl
    fun topId(): String = topFrameId

    // --------------------------------------------------------------- chọn trả lời

    /** Trả lời của khung có bàn đang mở (đã vào bàn và đọc được cầu). */
    fun tableReply(replies: List<Reply>): Reply? = replies
        .filter { it.ok && it.result?.optBoolean("in_table") == true }
        .maxByOrNull { tableCount(it.result) }

    /** Trả lời của khung sảnh (có ô bàn). */
    fun lobbyReply(replies: List<Reply>): Reply? = replies
        .filter { it.ok && tileCount(it.result) > 0 }
        .maxByOrNull { tileCount(it.result) }

    /** Trả lời của khung có khay chip (để đọc/đặt tiền). */
    fun chipReply(replies: List<Reply>): Reply? = replies
        .firstOrNull { it.ok && it.result?.optJSONObject("chips")?.optBoolean("has_table") == true }

    private fun tableCount(result: JSONObject?): Int =
        result?.optJSONArray("tables")?.length() ?: 0

    private fun tileCount(result: JSONObject?): Int =
        result?.optJSONObject("lobby")?.optJSONArray("tiles")?.length() ?: 0

    companion object {
        const val JS_OBJECT = "BcrNative"
    }
}
