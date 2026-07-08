package com.tvl.incidentaliq.data

import android.content.Context
import com.tvl.incidentaliq.core.AppLog
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

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

/** Appends captured messages to captured_messages.jsonl, with dedup that survives app restarts. */
object MessageStore {
    private const val TAG = "STORE"
    private const val DEDUP_CAP = 500              // remember the last N message keys
    private const val DEDUP_WINDOW_MS = 10 * 60 * 1000L  // collapse repeats seen within 10 min
    private const val MAX_CONTENT = 4000           // cap a pathological message so one line/upload can't explode
    // The whole system runs on Philippine time. Emit PH wall-clock WITH the "+08:00" offset
    // (e.g. "2026-07-08T20:56:01+08:00") — the offset is REQUIRED so the backend parses the instant
    // correctly (a bare no-zone string was misread as UTC → looked 8h in the future → the backend's
    // "wild timestamp" clamp overwrote every message's time, collapsing batches. Learned 2026-07-08).
    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("Asia/Manila")
    }
    private fun file(ctx: Context) = File(ctx.filesDir, "captured_messages.jsonl")
    private fun dedupFile(ctx: Context) = File(ctx.filesDir, "dedup_seen.tsv")

    // Bounded LRU of recently-stored keys → last-seen timestamp, so re-reading an open chat
    // (or re-scraping a still-visible message) doesn't duplicate. Key is a HASH of source+chat+
    // content only (sender excluded — the two capture paths report it differently). The map is
    // persisted to dedupFile so a restart doesn't forget recent messages and re-store them; the
    // backend content-hash dedup in D1 is still the durable guard of last resort.
    private val seen = object : LinkedHashMap<String, Long>(DEDUP_CAP, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > DEDUP_CAP
    }
    private var loaded = false

    // JSON string escaping. MUST escape ALL C0 control chars (U+0000–U+001F), not just \n — a raw
    // TAB (from a pasted manifest) or other control char inside "content" produces invalid JSON that
    // the backend's request.json() rejects with 400, which (since a failed upload never prunes) jams
    // the whole upload queue behind one poison message. Learned the hard way on 2026-07-07.
    private fun esc(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when {
                c == '\\' -> sb.append("\\\\")
                c == '"' -> sb.append("\\\"")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u").append("%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun sha256(s: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }

    private fun keyOf(source: String, chat: String, content: String) =
        sha256("$source|$chat|$content")

    /** Load the persisted dedup keys once (survives app restart). */
    private fun ensureLoaded(ctx: Context) {
        if (loaded) return
        loaded = true
        val f = dedupFile(ctx)
        if (!f.exists()) return
        try {
            f.readLines().takeLast(DEDUP_CAP).forEach { line ->
                val tab = line.indexOf('\t')
                if (tab > 0) {
                    val k = line.substring(0, tab)
                    val t = line.substring(tab + 1).toLongOrNull() ?: return@forEach
                    seen[k] = t
                }
            }
            AppLog.write(TAG, "loaded ${seen.size} dedup key(s) from disk")
        } catch (e: Exception) {
            AppLog.write(TAG, "dedup load failed: ${e.message}")
        }
    }

    /** Rewrite the dedup file from the current in-memory map (≤500 short lines — cheap). */
    private fun persistDedup(ctx: Context) {
        try {
            dedupFile(ctx).writeText(seen.entries.joinToString("\n") { "${it.key}\t${it.value}" })
        } catch (e: Exception) {
            AppLog.write(TAG, "dedup persist failed: ${e.message}")
        }
    }

    /** Returns true if the message was new and stored, false if it was a recent duplicate. */
    @Synchronized
    fun save(ctx: Context, m: CapturedMessage): Boolean {
        ensureLoaded(ctx)
        val content = if (m.content.length > MAX_CONTENT) m.content.take(MAX_CONTENT) else m.content
        val k = keyOf(m.source, m.chat, content)
        val now = m.capturedAt
        val last = seen[k]
        if (last != null && now - last < DEDUP_WINDOW_MS) return false
        seen[k] = now
        val json = """{"ts":"${iso.format(Date(m.capturedAt))}","source":"${m.source}",""" +
            """"chat":"${esc(m.chat)}","sender":"${esc(m.sender)}",""" +
            """"content":"${esc(content)}","is_image":${m.isImage},""" +
            """"via_accessibility":${m.viaAccessibility}}"""
        return try {
            file(ctx).appendText("$json\n")
            persistDedup(ctx)
            AppLog.write(TAG, "saved → ${m.source} | ${m.sender}: ${content.take(80)}")
            true
        } catch (e: Exception) {
            AppLog.write(TAG, "save FAILED: ${e.message}")
            false
        }
    }

    fun path(ctx: Context) = file(ctx).absolutePath

    /** How many messages are currently buffered on disk (not yet uploaded). */
    @Synchronized
    fun pendingCount(ctx: Context): Int {
        val f = file(ctx)
        if (!f.exists()) return 0
        return f.readLines().count { it.isNotBlank() }
    }

    /**
     * Snapshot up to [limit] buffered messages for upload (oldest first). Returns the current
     * non-blank JSONL lines. We DON'T delete here — only after the POST is confirmed do we call
     * pruneFirst(count) to remove exactly these, so a failed upload never loses data.
     */
    @Synchronized
    fun snapshotForUpload(ctx: Context, limit: Int = Int.MAX_VALUE): List<String> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        val lines = f.readLines().filter { it.isNotBlank() }
        return if (lines.size > limit) lines.take(limit) else lines
    }

    /**
     * Remove the first [count] buffered lines — the ones we just uploaded successfully — while
     * KEEPING anything appended during the upload (those are the lines after the first count).
     * This is the pruning that stops the on-disk buffer from growing forever.
     */
    @Synchronized
    fun pruneFirst(ctx: Context, count: Int) {
        if (count <= 0) return
        val f = file(ctx)
        if (!f.exists()) return
        val remaining = f.readLines().filter { it.isNotBlank() }.drop(count)
        if (remaining.isEmpty()) f.writeText("") else f.writeText(remaining.joinToString("\n") + "\n")
        AppLog.write(TAG, "pruned $count uploaded message(s); ${remaining.size} still buffered")
    }

    /**
     * Move the first [count] buffered lines to a dead-letter file and remove them from the buffer.
     * Used when the backend permanently REJECTS a batch (HTTP 400): retrying can never succeed, and
     * leaving it at the head of the queue would block every message behind it forever (the poison-
     * message deadlock). Quarantining unblocks the queue while preserving the rejected lines for
     * inspection instead of silently dropping them.
     */
    @Synchronized
    fun quarantineFirst(ctx: Context, count: Int) {
        if (count <= 0) return
        val f = file(ctx)
        if (!f.exists()) return
        val all = f.readLines().filter { it.isNotBlank() }
        val bad = all.take(count)
        if (bad.isEmpty()) return
        try {
            File(ctx.filesDir, "rejected_messages.jsonl").appendText(bad.joinToString("\n") + "\n")
        } catch (e: Exception) {
            AppLog.write(TAG, "dead-letter write failed: ${e.message}")
        }
        val remaining = all.drop(count)
        if (remaining.isEmpty()) f.writeText("") else f.writeText(remaining.joinToString("\n") + "\n")
        AppLog.write(TAG, "QUARANTINED $count rejected message(s) → rejected_messages.jsonl; ${remaining.size} still buffered")
    }
}
