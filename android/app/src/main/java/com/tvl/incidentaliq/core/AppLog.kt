package com.tvl.incidentaliq.core

import android.util.Log
import com.tvl.incidentaliq.App
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

object AppLog {
    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val file get() = File(App.instance.filesDir, "monitor.log")

    // All disk I/O runs on this ONE background thread. NotificationListener.onNotificationPosted (the
    // hottest caller) runs on the MAIN thread and the phone sits in many busy chats, so doing the
    // appendText (and the periodic 1 MB rewrite in trim()) inline would block the UI thread on every
    // notification → jank / dropped callbacks / ANR. Offloading keeps the callback instant. The single
    // thread also serializes writes and makes the non-thread-safe SimpleDateFormat safe (touched here only).
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "applog-io").apply { isDaemon = true } }

    // Keep the log file bounded so it can't grow forever (it was ~0.5 MB/day).
    // When it crosses MAX_BYTES we drop the oldest half, keeping the most recent KEEP_BYTES.
    private const val MAX_BYTES = 1_000_000L   // ~1 MB cap
    private const val KEEP_BYTES = 500_000     // keep the newest ~500 KB after trimming

    fun write(tag: String, msg: String) {
        val at = Date()                  // capture the event time NOW (accurate even if the writer is behind)
        Log.d(tag, msg)                  // logcat: cheap + thread-safe, keep inline
        LogBus.post("[$tag] $msg")       // UI mirror: posts to the UI thread internally
        io.execute {
            try {
                val line = "${sdf.format(at)} [$tag] $msg"
                if (file.length() >= MAX_BYTES) trim()
                file.appendText("$line\n")
            } catch (e: Exception) {
                Log.e(tag, "Failed to write to monitor.log: ${e.message}")
            }
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
