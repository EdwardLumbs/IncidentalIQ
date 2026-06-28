package com.tvl.incidentaliq

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "NLS"
        private val WATCHED = setOf("com.viber.voip", "com.facebook.orca")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in WATCHED) return

        val extras = sbn.notification.extras
        val title = extras.getString("android.title") ?: "(no title)"
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        val bigText = extras.getCharSequence("android.bigText")?.toString() ?: ""

        val content = bigText.ifEmpty { text }
        val truncated = content.length >= 95
        val app = if (sbn.packageName == "com.viber.voip") "VIBER" else "MESSENGER"

        AppLog.write(TAG, "─── NEW MESSAGE ───────────────────────")
        AppLog.write(TAG, "source   : $app")
        AppLog.write(TAG, "sender   : $title")
        AppLog.write(TAG, "content  : $content")
        AppLog.write(TAG, "truncated: $truncated")
        AppLog.write(TAG, "notif_id : ${sbn.id}")

        if (truncated) {
            AppLog.write(TAG, "ACTION   : TRUNCATED — AccessibilityService will read full content")
        } else {
            AppLog.write(TAG, "ACTION   : full content captured from notification")
        }
    }

    override fun onListenerConnected() {
        AppLog.write(TAG, "NotificationListenerService connected — watching Viber + Messenger")
    }

    override fun onListenerDisconnected() {
        AppLog.write(TAG, "NotificationListenerService DISCONNECTED")
    }
}
