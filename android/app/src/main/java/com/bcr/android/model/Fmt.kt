package com.bcr.android.model

/** Định dạng số kiểu Việt Nam: 1.500.000 (bỏ phần thập phân nếu là số nguyên). */
object Fmt {
    fun money(value: Double?): String {
        val v = value ?: 0.0
        if (!v.isFinite()) return "—"
        return if (v == Math.floor(v) && Math.abs(v) < 1e15) {
            group(Math.round(v))
        } else {
            String.format(java.util.Locale.US, "%,.2f", v)
                .replace(',', '\u0000').replace('.', ',').replace('\u0000', '.')
        }
    }

    fun signed(value: Double): String = (if (value > 0) "+" else "") + money(value)

    fun units(value: Double, digits: Int = 0): String =
        String.format(java.util.Locale.US, "%,.${digits}f", value)
            .replace(',', '\u0000').replace('.', ',').replace('\u0000', '.')

    private fun group(value: Long): String {
        val raw = value.toString()
        val negative = raw.startsWith("-")
        val digits = if (negative) raw.substring(1) else raw
        val out = StringBuilder()
        for ((index, ch) in digits.withIndex()) {
            if (index > 0 && (digits.length - index) % 3 == 0) out.append('.')
            out.append(ch)
        }
        return if (negative) "-$out" else out.toString()
    }

    /** Đọc số tiền người dùng nhập: chấp nhận 1.500.000, 1500000, 5.100. */
    fun parseMoney(text: String?, fallback: Double = 0.0): Double {
        val raw = (text ?: "").trim().replace("\u00a0", " ").replace("VN2", "", ignoreCase = true)
        if (raw.isEmpty()) return fallback
        val digits = raw.filter { it.isDigit() }
        if (digits.isEmpty()) return fallback
        // Dấu phân cách cuối là dấu thập phân khi chỉ có 1-2 chữ số phía sau.
        val lastSep = raw.indexOfLast { it == '.' || it == ',' }
        val value = if (lastSep >= 0 && raw.length - lastSep - 1 in 1..2 &&
            raw.substring(lastSep + 1).all { it.isDigit() }
        ) {
            val whole = raw.substring(0, lastSep).filter { it.isDigit() }
            val fraction = raw.substring(lastSep + 1)
            (whole.ifEmpty { "0" } + "." + fraction).toDoubleOrNull() ?: fallback
        } else {
            digits.toDoubleOrNull() ?: fallback
        }
        return if (value.isFinite()) value else fallback
    }
}
