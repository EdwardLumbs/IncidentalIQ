package com.tvl.incidentaliq.core

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager

/**
 * Wakes the screen for an accessibility read cycle. The coordinator holds the
 * lock for the whole cycle, then releases it (screen times out naturally).
 * Requires the phone to have NO PIN (dedicated device) so the lock screen
 * doesn't block the launched app.
 */
object WakeLockHelper {
    private const val TAG = "WAKE"
    private var wl: PowerManager.WakeLock? = null

    @Suppress("DEPRECATION")
    fun acquire(ctx: Context, timeoutMs: Long = 60_000L) {
        if (wl?.isHeld == true) return
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        wl = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            "IncidentalIQ::Read"
        )
        wl?.acquire(timeoutMs)
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        AppLog.write(TAG, "WakeLock acquired (screen on). keyguardLocked=${km.isKeyguardLocked}")
        if (km.isKeyguardLocked) AppLog.write(TAG, "⚠ keyguard locked — ensure NO PIN is set on this phone")
    }

    fun release() {
        try { if (wl?.isHeld == true) wl?.release() } catch (_: Exception) {}
        wl = null
        AppLog.write(TAG, "WakeLock released")
    }

    /** One-shot wake used by the manual test button. */
    fun wake(ctx: Context) {
        acquire(ctx, 5_000L)
        AppLog.write(TAG, "manual wake — screen forced on ~5s")
    }
}
