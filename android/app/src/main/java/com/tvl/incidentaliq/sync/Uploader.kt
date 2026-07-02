package com.tvl.incidentaliq.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.tvl.incidentaliq.core.AppLog
import java.util.concurrent.TimeUnit

/**
 * Schedules the message upload. Periodic every ~30 min (network-aware, exponential backoff), plus a
 * manual "sync now" for the Test/Sync button. Upload cadence (30 min) is intentionally decoupled
 * from the backend's classification cadence (every 6 h).
 */
object Uploader {
    private const val PERIODIC_NAME = "upload-sync"
    private const val ONESHOT_NAME = "upload-now"

    private val netConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** Call once on app start / boot. Idempotent — KEEP means an existing schedule isn't disturbed. */
    fun schedulePeriodic(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<UploadWorker>(30, TimeUnit.MINUTES)
            .setConstraints(netConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx)
            .enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        AppLog.write("UPLOAD", "periodic upload scheduled (every 30 min)")
    }

    /** Fire an immediate one-off upload (Sync Now button / test). */
    fun syncNow(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(netConstraint)
            .build()
        WorkManager.getInstance(ctx)
            .enqueueUniqueWork(ONESHOT_NAME, ExistingWorkPolicy.REPLACE, req)
        AppLog.write("UPLOAD", "manual sync enqueued")
    }
}
