package com.tvl.incidentaliq.sync

import android.content.Context
import com.tvl.incidentaliq.core.AppLog
import com.tvl.incidentaliq.core.Config
import com.tvl.incidentaliq.data.ImageQueue
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Uploads queued photos to the backend's POST /images, then deletes the phone's copy.
 *
 * One request per photo, raw bytes as the body, everything about WHERE it came from in a header.
 * One-at-a-time rather than a multipart album because each photo is then independently retryable:
 * a dropped connection halfway through a 17-photo job-sheet dump costs one retry, not seventeen,
 * and the ones already confirmed are already off the phone.
 *
 * Order of operations is the whole safety story, and it is deliberately this way round:
 *   upload → server confirms → remove from queue → delete the local file
 * Never the reverse. A crash between any two steps costs a duplicate upload, which the server
 * discards for free (it stores by content hash), whereas deleting first would cost the photo.
 */
object ImageUploader {
    private const val TAG = "IMGUP"
    private const val MAX_PER_RUN = 40         // pace one run; the rest goes with the next
    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 30_000    // photos are ~200KB-2MB, and the link can be slow

    /**
     * Send everything queued. Returns how many the server now holds. Safe to call from any thread
     * that isn't the main one (UploadWorker's IO dispatcher).
     */
    fun uploadPending(ctx: Context): Int {
        val base = Config.backendUrl(ctx)
        val token = Config.apiToken(ctx)
        if (base.isBlank() || token.isBlank()) return 0

        val queue = ImageQueue.pending(ctx)
        if (queue.isEmpty()) return 0
        AppLog.write(TAG, "uploading ${queue.size} queued photo(s) → $base/images")

        val done = HashSet<String>()   // paths the server confirmed → drop from the queue
        var sent = 0
        for (item in queue.take(MAX_PER_RUN)) {
            val f = File(item.path)
            if (!f.exists()) {
                // Someone (a gallery cleanup, the user) removed it before we got there. Nothing to
                // upload and nothing to retry — drop it rather than carry it forever.
                AppLog.write(TAG, "gone before upload: ${f.name}")
                done.add(item.path)
                continue
            }
            val bytes = try {
                f.readBytes()
            } catch (e: Exception) {
                AppLog.write(TAG, "could not read ${f.name}: ${e.message} — will retry")
                continue
            }

            when (post(base, token, item, bytes)) {
                Result.OK -> {
                    done.add(item.path)
                    sent++
                    // Only now is the phone's copy expendable.
                    ImageQueue.deleteLocal(item.path)
                }
                // A photo the server will never accept (bad metadata, too large). Retrying is
                // pointless, so it leaves the queue — but the FILE stays, because dropping bytes on
                // a bug we haven't diagnosed is not a trade worth making. It shows up as gallery
                // storage, which is visible, instead of as silence.
                Result.REJECTED -> {
                    AppLog.write(TAG, "server rejected ${f.name} — dequeued, file kept on the phone")
                    done.add(item.path)
                }
                // Network, auth, or server trouble: keep everything and stop. Whatever is wrong
                // affects the next photo too, so hammering the rest of the queue helps nobody.
                Result.RETRY -> {
                    AppLog.write(TAG, "upload paused at ${f.name} — will retry next run")
                    break
                }
            }
        }

        ImageQueue.remove(ctx, done)
        val left = ImageQueue.pendingCount(ctx)
        AppLog.write(TAG, "photo upload done — $sent sent, $left still queued")
        return sent
    }

    private enum class Result { OK, RETRY, REJECTED }

    private fun post(base: String, token: String, item: ImageQueue.Pending, bytes: ByteArray): Result {
        // Everything the backend needs to file the photo under the right bubble. It is SELF-
        // CONTAINED on purpose: the photo must not depend on the text batch arriving, or arriving
        // first — that batch goes through a dedup window that can legitimately drop a re-read.
        val meta = JSONObject()
            .put("source", item.source)
            .put("chat", item.chat)
            .put("sender", item.sender)
            .put("ts", item.ts)
            .put("album_key", item.albumKey)
            .put("seq", item.seq)
            .put("count", item.count)
            .put("store_only", item.storeOnly)
            .toString()

        return try {
            val conn = (URL("$base/images").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                doOutput = true
                setFixedLengthStreamingMode(bytes.size)   // stream it; don't buffer a 2MB body twice
                setRequestProperty("Content-Type", "image/jpeg")
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("X-Image-Meta", meta)
            }
            conn.outputStream.use { it.write(bytes) }

            val code = conn.responseCode
            val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()

            when {
                code in 200..299 -> {
                    AppLog.write(TAG, "✅ ${File(item.path).name} → ${resp.take(140)}")
                    Result.OK
                }
                code == 400 -> { AppLog.write(TAG, "400 for ${File(item.path).name}: ${resp.take(140)}"); Result.REJECTED }
                code == 413 -> { AppLog.write(TAG, "413 too large: ${File(item.path).name}"); Result.REJECTED }
                // 401 usually means the token isn't set yet rather than that it's wrong — recoverable.
                code == 401 -> { AppLog.write(TAG, "401 unauthorized — check the API token"); Result.RETRY }
                else -> { AppLog.write(TAG, "$code: ${resp.take(140)}"); Result.RETRY }
            }
        } catch (e: Exception) {
            AppLog.write(TAG, "upload error: ${e.message}")
            Result.RETRY
        }
    }
}
