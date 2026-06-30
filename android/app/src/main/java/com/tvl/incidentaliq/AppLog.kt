package com.tvl.incidentaliq

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val file get() = File(App.instance.filesDir, "monitor.log")

    // Keep the log file bounded so it can't grow forever (it was ~0.5 MB/day).
    // When it crosses MAX_BYTES we drop the oldest half, keeping the most recent KEEP_BYTES.
    private const val MAX_BYTES = 1_000_000L   // ~1 MB cap
    private const val KEEP_BYTES = 500_000     // keep the newest ~500 KB after trimming

    @Synchronized
    fun write(tag: String, msg: String) {
        val line = "${sdf.format(Date())} [$tag] $msg"
        Log.d(tag, msg)
        LogBus.post("[$tag] $msg")
        try {
            if (file.length() >= MAX_BYTES) trim()
            file.appendText("$line\n")
        } catch (e: Exception) {
            Log.e(tag, "Failed to write to monitor.log: ${e.message}")
        }
    }

    /** Drop the oldest lines, keeping the most recent KEEP_BYTES (rounded to a line boundary). */
    private fun trim() {
        try {
            val text = file.readText()
            if (text.length <= KEEP_BYTES) return
            var tail = text.substring(text.length - KEEP_BYTES)
            val nl = tail.indexOf('\n')               // avoid starting mid-line
            if (nl >= 0) tail = tail.substring(nl + 1)
            file.writeText("--- log trimmed at ${sdf.format(Date())} (older entries removed) ---\n$tail")
        } catch (e: Exception) {
            Log.e("AppLog", "trim failed: ${e.message}")
        }
    }
}
