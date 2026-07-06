package com.tvl.incidentaliq.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tvl.incidentaliq.core.AppLog
import com.tvl.incidentaliq.core.Monitoring
import com.tvl.incidentaliq.sync.Uploader

/**
 * Auto-restarts monitoring after the phone reboots (or the app is updated). The dedicated Trip Ops
 * phone runs unattended 24/7, so without this a power blip would silently kill monitoring until
 * someone opened the app by hand.
 *
 * What actually needs restarting here is ONLY the foreground service — the NotificationListener and
 * AccessibilityService are system-bound and the OS rebinds them on boot on its own, and WorkManager
 * restores the periodic upload job itself. We re-schedule the uploader anyway as a cheap belt-and-
 * suspenders (schedulePeriodic uses KEEP, so it's a no-op if already queued).
 *
 * Registered exported (a system broadcast must reach it). Guarded to only act on the boot/replace
 * actions so another app can't use it to spin up our service arbitrarily.
 */
class BootReceiver : BroadcastReceiver() {

    companion object { private const val TAG = "BOOT" }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val handled = action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||       // some OEMs (HTC, older Samsung)
            action == "com.htc.intent.action.QUICKBOOT_POWERON"
        if (!handled) {
            AppLog.write(TAG, "ignored broadcast: $action")
            return
        }

        AppLog.write(TAG, "boot/replace received ($action)")

        // Belt-and-suspenders: make sure the periodic uploader is queued (no-op if it already is).
        Uploader.schedulePeriodic(context)

        if (!Monitoring.isEnabled(context)) {
            AppLog.write(TAG, "monitoring is OFF — not starting foreground service")
            return
        }

        try {
            context.startForegroundService(Intent(context, MonitorForegroundService::class.java))
            AppLog.write(TAG, "foreground service start requested")
        } catch (e: Exception) {
            AppLog.write(TAG, "failed to start service: ${e.message}")
        }
    }
}
