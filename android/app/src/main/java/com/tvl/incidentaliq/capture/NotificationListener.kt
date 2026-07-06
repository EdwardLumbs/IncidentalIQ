package com.tvl.incidentaliq.capture

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.tvl.incidentaliq.core.AppLog
import com.tvl.incidentaliq.core.Config
import com.tvl.incidentaliq.core.Monitoring
import com.tvl.incidentaliq.data.CapturedMessage
import com.tvl.incidentaliq.data.MessageStore

class NotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "NLS"
        private val WATCHED = setOf("com.viber.voip", "com.facebook.orca")

        // Notifications that are NOT chat messages (so we never auto-open for them).
        private val SYSTEM_SENDERS = setOf(
            "Viber", "Messenger", "Downloading media", "Restore your chat history",
            "(no title)", "(name removed)", "Chat backup"
        )
        // Notification previews that mean "image/media" → must read via accessibility.
        private val MEDIA_PREVIEWS = setOf(
            "", "Sent a photo", "Sent a video", "Photo", "Video", "Sent a sticker", "Sent an attachment"
        )
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in WATCHED) return
        if (!Monitoring.isEnabled(this)) return   // master switch OFF → capture nothing

        val n = sbn.notification
        val extras = n.extras
        val title = extras.getString("android.title") ?: "(no title)"
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        val bigText = extras.getCharSequence("android.bigText")?.toString() ?: ""
        val content = bigText.ifEmpty { text }

        // Group name is not reliably in one field. For a MessagingStyle group notification the
        // group is usually in conversationTitle; subText sometimes carries it too; title is the
        // last resort (often the SENDER for a group). We match against all three candidates.
        val convTitle = extras.getCharSequence("android.conversationTitle")?.toString() ?: ""
        val subText = extras.getCharSequence("android.subText")?.toString() ?: ""
        val groupCandidates = listOf(convTitle, subText, title)
        val groupName = convTitle.ifEmpty { subText }.ifEmpty { title }

        val app = if (sbn.packageName == "com.viber.voip") "VIBER" else "MESSENGER"
        val truncated = content.length >= 95
        val imageLike = content in MEDIA_PREVIEWS

        // Noise filters (only to decide whether to act — everything is still logged).
        val isOngoing = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0
        val isSummary = (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0 || sbn.id == Int.MAX_VALUE
        val isSystem = title in SYSTEM_SENDERS || title.contains("call", ignoreCase = true)
        val isNoise = isOngoing || isSummary || isSystem
        // Tracked-group gate: empty list for this app = capture ALL (default until configured).
        val tracked = Config.isGroupTracked(this, app, groupCandidates)

        AppLog.write(TAG, "─── NEW NOTIF ─── $app  id=${sbn.id}")
        AppLog.write(TAG, "  sender=\"$title\"  group=\"$groupName\"  truncated=$truncated  image=$imageLike  noise=$isNoise  tracked=$tracked")
        AppLog.write(TAG, "  content=\"${content.take(110)}\"")

        when {
            isNoise -> AppLog.write(TAG, "  ACTION: skipped (noise: ongoing=$isOngoing summary=$isSummary system=$isSystem)")

            !tracked -> AppLog.write(TAG, "  ACTION: skipped (untracked group \"$groupName\" not in $app list)")

            truncated || imageLike -> {
                AppLog.write(TAG, "  ACTION: enqueue accessibility READ (${if (truncated) "truncated" else "image"})")
                ReadCoordinator.enqueue(
                    this,
                    ReadCoordinator.Task(app, sbn.packageName, groupName, n.contentIntent)
                )
            }

            else -> {
                // Short, full text already in the notification — store it directly.
                AppLog.write(TAG, "  ACTION: full content from notification — stored directly")
                MessageStore.save(this, CapturedMessage(app, groupName, title, content, false, viaAccessibility = false))
            }
        }
    }

    override fun onListenerConnected() {
        AppLog.write(TAG, "NotificationListener connected — watching Viber + Messenger")
    }

    override fun onListenerDisconnected() {
        AppLog.write(TAG, "NotificationListener DISCONNECTED")
    }
}
