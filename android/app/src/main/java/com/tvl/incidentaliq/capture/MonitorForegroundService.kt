package com.tvl.incidentaliq.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.tvl.incidentaliq.core.AppLog

class MonitorForegroundService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val HEARTBEAT_INTERVAL = 5 * 60 * 1000L

    private val heartbeat = object : Runnable {
        override fun run() {
            AppLog.write("SERVICE", "HEARTBEAT — service alive")
            handler.postDelayed(this, HEARTBEAT_INTERVAL)
        }
    }

    private var heartbeatRunning = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(1, buildNotification())
        AppLog.write("SERVICE", "=== MONITOR STARTED ===")
        AppLog.write("SERVICE", "Heartbeat interval: ${HEARTBEAT_INTERVAL / 1000}s")
        AppLog.write("SERVICE", "Log file: ${filesDir}/monitor.log")
    }

    // Called on every startForegroundService() — including the keep-alive re-starts from UploadWorker
    // and the master switch. Re-assert startForeground each time to satisfy Android's "call
    // startForeground within 5s of startForegroundService" rule, but only arm the heartbeat ONCE so
    // repeated starts don't stack duplicate runnables.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, buildNotification())
        if (!heartbeatRunning) {
            heartbeatRunning = true
            handler.post(heartbeat)
        }
        return START_STICKY  // OS recreates the service after a memory kill (self-heal, no reboot needed)
    }

    override fun onDestroy() {
        handler.removeCallbacks(heartbeat)
        heartbeatRunning = false
        AppLog.write("SERVICE", "=== MONITOR DESTROYED — service was killed ===")
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        AppLog.write("SERVICE", "WARNING — app removed from recents. Service may be killed soon.")
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val ch = NotificationChannel("monitor", "Monitor", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification() = Notification.Builder(this, "monitor")
        .setContentTitle("IncidentalIQ Monitor Active")
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .build()
}
