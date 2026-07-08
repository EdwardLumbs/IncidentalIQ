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

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(1, buildNotification())
        AppLog.write("SERVICE", "=== MONITOR STARTED ===")
        AppLog.write("SERVICE", "Heartbeat interval: ${HEARTBEAT_INTERVAL / 1000}s")
        AppLog.write("SERVICE", "Log file: ${filesDir}/monitor.log")
        handler.post(heartbeat)
    }

    // START_STICKY: if the OS kills the service under memory pressure, recreate it (with a null
    // intent) as soon as resources free up — so the 24/7 monitor self-heals without waiting for a
    // reboot. onCreate() already (re)asserts startForeground on each recreation.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacks(heartbeat)
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
