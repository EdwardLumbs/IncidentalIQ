package com.tvl.incidentaliq.data

import android.content.Context
import android.os.Environment
import com.tvl.incidentaliq.core.AppLog
import org.json.JSONObject
import java.io.File

/**
 * The photos that have been saved to the phone's gallery but not yet uploaded.
 *
 * Deliberately NOT a copy of the bytes. Viber and Messenger write the file into their own public
 * gallery folder; we record where it is, upload it from there, and delete it once the server has
 * confirmed. Copying first would double both the write and the peak storage for no benefit — the
 * whole point of deleting afterwards is that a phone with a 17-photo job-sheet dump every day does
 * not slowly fill up.
 *
 * The queue is a JSONL file, one line per photo, same pattern (and same durability rule) as
 * MessageStore: nothing is removed until the upload is confirmed, so a crash or a flat battery
 * costs a retry, never a photo.
 */
object ImageQueue {
    private const val TAG = "IMGQ"
    private const val MAX_PENDING = 500   // a stuck backend must not grow this without bound

    // Where each app writes what it saves. Both are public gallery folders, not app-private
    // storage, which is why reading them needs the media permission and deleting them needs
    // "All files access" — see ImagePermissions.
    private val PICTURES: File get() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
    fun folderFor(source: String): File =
        File(PICTURES, if (source.equals("VIBER", true)) "Viber" else "Messenger")

    private fun file(ctx: Context) = File(ctx.filesDir, "pending_images.jsonl")

    /** One queued photo: where it is on disk, and which bubble it belongs to. */
    data class Pending(
        val albumKey: String,
        val source: String,
        val chat: String,
        val sender: String,
        val ts: String,
        val seq: Int,
        val count: Int,
        val storeOnly: Boolean,
        val path: String,
    )

    /** Image files currently in an app's gallery folder — the before/after snapshot the saver diffs. */
    fun snapshot(source: String): Set<String> {
        val dir = folderFor(source)
        val files = dir.listFiles() ?: return emptySet()
        return files.filter { it.isFile && looksLikeImage(it.name) }.map { it.absolutePath }.toSet()
    }

    private fun looksLikeImage(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")
    }

    /**
     * Files that appeared in the folder since [before], oldest first.
     *
     * Ordered by mtime rather than by name because neither app names files in send order — Messenger
     * uses its own message ids (received_1078531791481215.jpeg) and Viber a content hash.
     */
    fun newSince(source: String, before: Set<String>): List<File> {
        val dir = folderFor(source)
        val files = dir.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && looksLikeImage(it.name) && it.absolutePath !in before && it.length() > 0 }
            .sortedBy { it.lastModified() }
    }

    @Synchronized
    fun enqueue(ctx: Context, items: List<Pending>) {
        if (items.isEmpty()) return
        val f = file(ctx)
        val existing = pendingCount(ctx)
        if (existing >= MAX_PENDING) {
            AppLog.write(TAG, "queue at cap ($MAX_PENDING) — not queueing ${items.size} more photo(s)")
            return
        }
        val sb = StringBuilder()
        for (p in items) {
            sb.append(
                JSONObject()
                    .put("album_key", p.albumKey).put("source", p.source).put("chat", p.chat)
                    .put("sender", p.sender).put("ts", p.ts).put("seq", p.seq).put("count", p.count)
                    .put("store_only", p.storeOnly).put("path", p.path)
                    .toString()
            ).append('\n')
        }
        try {
            f.appendText(sb.toString())
            AppLog.write(TAG, "queued ${items.size} photo(s) for upload (pending ${existing + items.size})")
        } catch (e: Exception) {
            AppLog.write(TAG, "queue write FAILED: ${e.message}")
        }
    }

    @Synchronized
    fun pending(ctx: Context): List<Pending> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return f.readLines().filter { it.isNotBlank() }.mapNotNull { line ->
            try {
                val o = JSONObject(line)
                Pending(
                    albumKey = o.getString("album_key"), source = o.getString("source"),
                    chat = o.getString("chat"), sender = o.optString("sender"),
                    ts = o.getString("ts"), seq = o.optInt("seq", 1), count = o.optInt("count", 1),
                    storeOnly = o.optBoolean("store_only", false), path = o.getString("path"),
                )
            } catch (_: Exception) {
                null   // a corrupt line is dropped on the next rewrite rather than jamming the queue
            }
        }
    }

    @Synchronized
    fun pendingCount(ctx: Context): Int {
        val f = file(ctx)
        if (!f.exists()) return 0
        return f.readLines().count { it.isNotBlank() }
    }

    /**
     * Drop the named paths from the queue — called only AFTER the server confirmed each one, so an
     * upload that failed halfway leaves the rest of the queue intact.
     */
    @Synchronized
    fun remove(ctx: Context, paths: Set<String>) {
        if (paths.isEmpty()) return
        val f = file(ctx)
        if (!f.exists()) return
        val keep = f.readLines().filter { line ->
            if (line.isBlank()) return@filter false
            val p = try { JSONObject(line).getString("path") } catch (_: Exception) { return@filter false }
            p !in paths
        }
        if (keep.isEmpty()) f.writeText("") else f.writeText(keep.joinToString("\n") + "\n")
    }

    // ── Albums already handled ────────────────────────────────────────────────────────────────
    // A chat gets re-opened constantly (every truncated message triggers a read), and the same
    // photo bubbles are still sitting there. Without this, every read would re-open every visible
    // photo and save it again — minutes of tapping, and a gallery full of duplicates. The backend
    // would dedup them by content hash, but the phone would have wasted the whole cycle getting
    // there. Keyed by album key, capped, and persisted so an app restart doesn't forget.

    private const val SEEN_CAP = 400
    private const val NL = "\n"
    private fun seenFile(ctx: Context) = File(ctx.filesDir, "seen_albums.txt")

    @Synchronized
    fun wasSeen(ctx: Context, albumKey: String): Boolean {
        val f = seenFile(ctx)
        if (!f.exists()) return false
        return try { f.readLines().any { it.trim() == albumKey } } catch (_: Exception) { false }
    }

    @Synchronized
    fun markSeen(ctx: Context, albumKey: String) {
        val f = seenFile(ctx)
        try {
            val keys = (if (f.exists()) f.readLines() else emptyList())
                .map { it.trim() }.filter { it.isNotEmpty() && it != albumKey }
            val kept = (keys + albumKey).takeLast(SEEN_CAP)
            f.writeText(kept.joinToString(NL) + NL)
        } catch (e: Exception) {
            AppLog.write(TAG, "could not record album as seen: ${e.message}")
        }
    }

    /**
     * Delete the phone's copy of a photo the server now holds. This is the whole reason the app
     * needs "All files access": the file belongs to Viber/Messenger, and without that grant Android
     * 11+ refuses another app's delete (or, worse, pops a confirmation dialog on a phone nobody is
     * looking at). A failure here is logged, never fatal — the photo is safely on the server, and
     * the only consequence is storage this run didn't reclaim.
     */
    fun deleteLocal(path: String): Boolean {
        return try {
            val ok = File(path).delete()
            if (!ok) AppLog.write(TAG, "could not delete $path (All files access granted?)")
            ok
        } catch (e: Exception) {
            AppLog.write(TAG, "delete failed for $path: ${e.message}")
            false
        }
    }
}
