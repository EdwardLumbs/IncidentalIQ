package com.tvl.incidentaliq

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One captured message, from either a notification or an accessibility read. */
data class CapturedMessage(
    val source: String,        // "VIBER" | "MESSENGER"
    val chat: String,          // group / chat name (best effort)
    val sender: String,
    val content: String,
    val isImage: Boolean = false,
    val viaAccessibility: Boolean = false,   // false = straight from notification
    val capturedAt: Long = System.currentTimeMillis(),
)

/** Appends captured messages to captured_messages.jsonl, with in-memory dedup. */
object MessageStore {
    private const val TAG = "STORE"
    private const val DEDUP_CAP = 500              // remember the last N message keys
    private const val DEDUP_WINDOW_MS = 10 * 60 * 1000L  // collapse repeats seen within 10 min
    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    private fun file(ctx: Context) = File(ctx.filesDir, "captured_messages.jsonl")

    // Bounded LRU of recently-stored keys → last-seen timestamp, so re-reading an open chat
    // (or re-scraping a still-visible message during another chat's read) doesn't duplicate.
    // Key is chat+content only: sender is NOT in the key because the two capture paths report
    // it differently (notification = "group: Name", accessibility = bare "Name"), which would
    // otherwise let the same message slip through as a "new" sender. A time window guards the
    // edge case of the same short text legitimately recurring much later.
    // NOTE: still in-memory (resets on app restart) — the durable guard is dedup in the backend
    // (content-hash in D1) before any Groq call. TODO: move to the Room/SQLite buffer.
    private val seen = object : LinkedHashMap<String, Long>(DEDUP_CAP, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > DEDUP_CAP
    }

    private fun esc(s: String) =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")

    private fun key(m: CapturedMessage) = "${m.source}|${m.chat}|${m.content}"

    /** Returns true if the message was new and stored, false if it was a recent duplicate. */
    @Synchronized
    fun save(ctx: Context, m: CapturedMessage): Boolean {
        val k = key(m)
        val now = m.capturedAt
        val last = seen[k]
        if (last != null && now - last < DEDUP_WINDOW_MS) return false
        seen[k] = now
        val json = """{"ts":"${iso.format(Date(m.capturedAt))}","source":"${m.source}",""" +
            """"chat":"${esc(m.chat)}","sender":"${esc(m.sender)}",""" +
            """"content":"${esc(m.content)}","is_image":${m.isImage},""" +
            """"via_accessibility":${m.viaAccessibility}}"""
        return try {
            file(ctx).appendText("$json\n")
            AppLog.write(TAG, "saved → ${m.source} | ${m.sender}: ${m.content.take(80)}")
            true
        } catch (e: Exception) {
            AppLog.write(TAG, "save FAILED: ${e.message}")
            false
        }
    }

    fun path(ctx: Context) = file(ctx).absolutePath
}
