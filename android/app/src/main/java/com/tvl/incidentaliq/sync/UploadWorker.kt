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
 * Data safety: we snapshot the buffer, upload it, and only prune AFTER a 2xx response — so a failed
 * or partial upload never drops messages. Duplicates are harmless: the backend dedups by content
 * hash, so re-uploading the same line after a flaky retry is a no-op there.
 */
class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val base = Config.backendUrl(ctx)
        if (base.isBlank()) {
            AppLog.write(TAG, "upload skipped — backend URL not configured")
            return@withContext Result.success()
        }

        val lines = MessageStore.snapshotForUpload(ctx)
        if (lines.isEmpty()) {
            AppLog.write(TAG, "upload skipped — nothing buffered")
            return@withContext Result.success()
        }

        val body = """{"messages":[${lines.joinToString(",")}]}"""
        AppLog.write(TAG, "uploading ${lines.size} message(s) → $base/messages")

        try {
            val conn = (URL("$base/messages").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 20_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }

            val code = conn.responseCode
            val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()

            if (code in 200..299) {
                MessageStore.pruneFirst(ctx, lines.size)
                AppLog.write(TAG, "upload OK ($code): ${resp.take(160)}")
                Result.success()
            } else {
                AppLog.write(TAG, "upload FAILED HTTP $code: ${resp.take(160)} — will retry")
                Result.retry()
            }
        } catch (e: Exception) {
            AppLog.write(TAG, "upload error: ${e.message} — will retry")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "UPLOAD"
    }
}
