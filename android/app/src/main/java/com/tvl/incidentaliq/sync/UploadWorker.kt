package com.tvl.incidentaliq.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tvl.incidentaliq.core.AppLog
import com.tvl.incidentaliq.core.Config
import com.tvl.incidentaliq.data.MessageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Periodic upload: POST the buffered messages to the backend's /messages, then prune the ones that
 * were confirmed stored. Runs via WorkManager (guaranteed, battery/network-aware, survives reboot).
 *
 * Self-limits to MAX_PER_POST messages per request (the backend rejects anything over its own hard
 * cap), and loops up to MAX_POSTS times so a backlog after downtime drains in one run instead of one
 * chunk every 30 min. Data safety: snapshot → upload → prune only AFTER a 2xx, so a failed or
 * partial upload never drops messages. Duplicates are harmless — the backend dedups by content hash.
 */
class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val base = Config.backendUrl(ctx)
        val token = Config.apiToken(ctx)
        if (base.isBlank() || token.isBlank()) {
            AppLog.write(TAG, "upload skipped — backend URL or API token not configured")
            return@withContext Result.success()
        }

        var totalSent = 0
        // Drain in bounded passes; stop when the buffer is empty or we hit the per-run pass cap.
        repeat(MAX_POSTS) {
            val lines = MessageStore.snapshotForUpload(ctx, MAX_PER_POST)
            if (lines.isEmpty()) {
                if (totalSent == 0) AppLog.write(TAG, "upload skipped — nothing buffered")
                return@withContext Result.success()
            }

            when (val r = postBatch(base, token, lines)) {
                UploadResult.OK -> {
                    MessageStore.pruneFirst(ctx, lines.size)
                    totalSent += lines.size
                    // If we sent a full page there may be more — loop again; otherwise we're done.
                    if (lines.size < MAX_PER_POST) return@withContext Result.success()
                }
                UploadResult.RETRY -> {
                    AppLog.write(TAG, "upload will retry (sent $totalSent so far)")
                    return@withContext Result.retry()
                }
                UploadResult.FATAL -> {
                    // 4xx other than auth/size we can't fix by retrying — drop the pass, log loudly.
                    AppLog.write(TAG, "upload FATAL (${r}) — giving up this run (sent $totalSent)")
                    return@withContext Result.failure()
                }
            }
        }
        AppLog.write(TAG, "upload run complete — sent $totalSent message(s)")
        Result.success()
    }

    private enum class UploadResult { OK, RETRY, FATAL }

    private fun postBatch(base: String, token: String, lines: List<String>): UploadResult {
        val body = """{"messages":[${lines.joinToString(",")}]}"""
        AppLog.write(TAG, "uploading ${lines.size} message(s) → $base/messages")
        return try {
            val conn = (URL("$base/messages").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 20_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }

            val code = conn.responseCode
            val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()

            when {
                code in 200..299 -> {
                    AppLog.write(TAG, "upload OK ($code): ${resp.take(160)}")
                    UploadResult.OK
                }
                // 401 (bad/missing token) and 413 (too big) won't fix themselves on blind retry,
                // but 401 often means "token not set yet" → retry so it recovers once configured.
                code == 401 -> {
                    AppLog.write(TAG, "upload 401 UNAUTHORIZED — check API token; will retry")
                    UploadResult.RETRY
                }
                code == 413 -> {
                    AppLog.write(TAG, "upload 413 too large — reduce MAX_PER_POST")
                    UploadResult.FATAL
                }
                code in 500..599 -> {
                    AppLog.write(TAG, "upload $code server error: ${resp.take(160)} — will retry")
                    UploadResult.RETRY
                }
                else -> {
                    AppLog.write(TAG, "upload $code: ${resp.take(160)} — will retry")
                    UploadResult.RETRY
                }
            }
        } catch (e: Exception) {
            AppLog.write(TAG, "upload error: ${e.message} — will retry")
            UploadResult.RETRY
        }
    }

    companion object {
        private const val TAG = "UPLOAD"
        private const val MAX_PER_POST = 500   // phone self-limit; backend hard cap is 1000
        private const val MAX_POSTS = 10       // up to 5000 msgs drained per run; rest waits for next
    }
}
