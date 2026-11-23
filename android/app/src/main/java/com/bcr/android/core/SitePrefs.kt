package com.bcr.android.core

import android.content.Context

/**
 * Nhớ link site người dùng đang chơi.
 *
 * Không khoá cứng vào một nhà cái nào: app hỏi link ở lần mở đầu tiên, sau đó tự dùng lại
 * đúng link đó mỗi lần bấm "MỞ SẢNH" (và khi mở lại app). Đổi site bằng cách giữ lâu nút
 * "MỞ SẢNH" rồi dán link mới.
 */
class SitePrefs(context: Context) {

    private val store = context.applicationContext
        .getSharedPreferences("bcr_site", Context.MODE_PRIVATE)

    /** Link gốc người dùng đã nhập (trang chủ / sảnh của site). Rỗng = chưa cấu hình. */
    var siteUrl: String
        get() = store.getString(KEY_SITE, "").orEmpty()
        set(value) {
            store.edit().putString(KEY_SITE, value.trim()).apply()
        }

    /**
     * Trang cuối cùng đã mở ở khung gốc. Dùng để quay lại đúng chỗ (sảnh hoặc bàn) thay vì
     * luôn nhảy về trang chủ.
     */
    var lastUrl: String
        get() = store.getString(KEY_LAST, "").orEmpty()
        set(value) {
            if (value.startsWith("http")) store.edit().putString(KEY_LAST, value).apply()
        }

    /** Link để mở khi bấm "MỞ SẢNH": ưu tiên trang cuối, không có thì dùng link gốc. */
    fun resumeUrl(): String = lastUrl.ifBlank { siteUrl.ifBlank { DEFAULT_SITE } }

    companion object {
        /** Trang sảnh Live mặc định (mở ngay lần đầu nếu người dùng chưa nhập link). */
        const val DEFAULT_SITE = "https://onbet.com/en/live"
        private const val KEY_SITE = "site_url"
        private const val KEY_LAST = "last_url"
    }
}
