package com.vega.sting.overlay

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TimestampFormatter(pattern: String) {
    private val formatter = try {
        SimpleDateFormat(pattern, Locale.getDefault())
    } catch (_: IllegalArgumentException) {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    }

    fun format(timeMillis: Long): String {
        return formatter.format(Date(timeMillis))
    }
}
