package com.tvl.incidentaliq

import android.app.KeyguardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

object WakeLockHelper {
    private const val TAG = "WAKE"
    private const val WAKE_DURATION = 5000L

    @Suppress("DEPRECATION")
    fun wake(context: Context) {
        AppLog.write(TAG, "WakeLock requested")

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or
            PowerManager.ACQUIRE_CAUSES_WAKEUP or
            PowerManager.ON_AFTER_RELEASE,
            "IncidentalIQ::Wake"
        )
        wl.acquire(WAKE_DURATION)
        AppLog.write(TAG, "WakeLock acquired — screen forced on for ${WAKE_DURATION}ms")

        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (km.isKeyguardLocked) {
            AppLog.write(TAG, "Keyguard LOCKED — screen on but lock screen visible. Ensure no PIN is set.")
        } else {
            AppLog.write(TAG, "Keyguard clear — screen on and unlocked")
        }

        Handler(Looper.getMainLooper()).postDelayed({
            AppLog.write(TAG, "WakeLock auto-released after ${WAKE_DURATION}ms")
        }, WAKE_DURATION + 100)
    }
}
