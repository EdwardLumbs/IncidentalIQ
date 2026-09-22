package com.tvl.incidentaliq.capture

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.tvl.incidentaliq.core.AppLog
import com.tvl.incidentaliq.core.Config
import com.tvl.incidentaliq.core.Monitoring
import com.tvl.incidentaliq.data.CapturedMessage
import com.tvl.incidentaliq.data.MessageStore
import com.tvl.incidentaliq.sync.Uploader

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
        //
        // Matched through mediaPreview() below rather than by equality, because the apps are not
        // consistent about the trailing full stop: Messenger posts "Sent a photo." in some chats and
        // "Sent a photo" in others. An exact-match miss is silent and expensive — the notification is
        // stored as if its text WERE the message, so the chat keeps a useless "Sent a photo." row and
        // the actual photo is never fetched (seen live 2026-09-22).
        private val MEDIA_PREVIEWS = setOf(
            "", "sent a photo", "sent a video", "photo", "video", "sent a sticker", "sent an attachment",
            "sent an image", "sent a file", "sent a gif"
        )

        /** True when a notification's text is one of the media placeholders, punctuation aside. */
        fun mediaPreview(content: String): Boolean =
            content.trim().trimEnd('.', '!', '…').lowercase() in MEDIA_PREVIEWS
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

        // Messenger/Viber group notifications put "GroupName: PersonName" in android.title. Strip the
        // group prefix so we store the actual person (the sender→driver/helper trip hint depends on it,
        // and it must match the accessibility path, which reports the bare name). Falls back to the raw
        // title when there's no group prefix (1-on-1, or title already just the sender).
        val sender = run {
            val prefix = "$groupName: "
            if (groupName.isNotEmpty() && title.startsWith(prefix)) title.removePrefix(prefix).trim().ifEmpty { title }
            else title
        }

        val app = if (sbn.packageName == "com.viber.voip") "VIBER" else "MESSENGER"
        val truncated = content.length >= 95
        val imageLike = mediaPreview(content)

        // Noise filters (only to decide whether to act — everything is still logged).
        val isOngoing = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0
        val isSummary = (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0 || sbn.id == Int.MAX_VALUE
        val isSystem = title in SYSTEM_SENDERS || title.contains("call", ignoreCase = true)
        val isNoise = isOngoing || isSummary || isSystem
        // Group gates. A group can be in the classify list, the store-only (archive) list, the
        // immediate-upload list, or neither. Capture if it's in ANY list. Immediate groups are ALSO
        // store-only (§Config.isImmediate) — a chat urgent enough to skip the 30-min batch is never
        // one you'd want silently routed to Groq too, so being in that one box is enough on its own.
        val classify = Config.isGroupTracked(this, app, groupCandidates)
        val storeOnlyListed = Config.isStoreOnly(this, app, groupCandidates)
        val immediate = Config.isImmediate(this, app, groupCandidates)
        val storeOnly = storeOnlyListed || immediate
        val capture = classify || storeOnly

        AppLog.write(TAG, "─── NEW NOTIF ─── $app  id=${sbn.id}")
        AppLog.write(TAG, "  sender=\"$sender\"  group=\"$groupName\"  truncated=$truncated  image=$imageLike  noise=$isNoise  classify=$classify  storeOnly=$storeOnly  immediate=$immediate")
        AppLog.write(TAG, "  content=\"${content.take(110)}\"")

        when {
            isNoise -> AppLog.write(TAG, "  ACTION: skipped (noise: ongoing=$isOngoing summary=$isSummary system=$isSystem)")

            !capture -> AppLog.write(TAG, "  ACTION: skipped (group \"$groupName\" not in $app classify, store-only, or immediate list)")

            truncated || imageLike -> {
                AppLog.write(TAG, "  ACTION: enqueue accessibility READ (${if (truncated) "truncated" else "image"}${if (storeOnly) ", store-only" else ""}${if (immediate) ", immediate" else ""})")
                ReadCoordinator.enqueue(
                    this,
                    ReadCoordinator.Task(
                        app, sbn.packageName, groupName, n.contentIntent,
                        sender = sender, fallbackText = content, isImage = imageLike, storeOnly = storeOnly,
                        immediate = immediate,
                    )
                )
            }

            else -> {
                // Short, full text already in the notification — store it directly.
                AppLog.write(TAG, "  ACTION: full content from notification — stored directly${if (storeOnly) " (store-only)" else ""}${if (immediate) " (immediate)" else ""}")
                val saved = MessageStore.save(this, CapturedMessage(app, groupName, sender, content, false, viaAccessibility = false, storeOnly = storeOnly))
                if (saved && immediate) {
                    AppLog.write(TAG, "  immediate group — syncing now")
                    Uploader.syncNow(this)
                }
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
