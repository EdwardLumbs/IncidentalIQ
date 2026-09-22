package com.tvl.incidentaliq.capture

import android.view.accessibility.AccessibilityNodeInfo
import com.tvl.incidentaliq.data.CapturedMessage

/** Depth-first pre-order walk = roughly top-to-bottom visual order. */
private fun walk(node: AccessibilityNodeInfo?, action: (AccessibilityNodeInfo) -> Unit) {
    node ?: return
    action(node)
    for (i in 0 until node.childCount) walk(node.getChild(i), action)
}

private fun AccessibilityNodeInfo.idName(): String =
    viewIdResourceName?.substringAfterLast('/') ?: ""

/**
 * VIBER — resource IDs are readable. See docs/reverse-engineering/ui-structure.md.
 *   toolbar/title = chat name, conversation_recycler_view = messages,
 *   nameView = sender (group; inherit for consecutive), textMessageView = content.
 */
object ViberParser {
    fun chatTitle(root: AccessibilityNodeInfo): String? {
        var title: String? = null
        walk(root) { if (it.idName() == "title" && title == null) title = it.text?.toString() }
        return title
    }

    fun parse(root: AccessibilityNodeInfo): List<CapturedMessage> {
        val chat = chatTitle(root) ?: "?"
        val out = ArrayList<CapturedMessage>()
        var currentSender = ""   // carried forward for consecutive same-sender messages
        walk(root) { n ->
            when (n.idName()) {
                "nameView" -> n.text?.toString()?.let { if (it.isNotBlank()) currentSender = it }
                "textMessageView" -> {
                    val t = n.text?.toString()
                    if (!t.isNullOrBlank()) {
                        // 1-on-1 has no nameView → attribute to the chat (the other party).
                        val sender = currentSender.ifBlank { chat }
                        out.add(CapturedMessage("VIBER", chat, sender, t, false, viaAccessibility = true))
                    }
                }
                // Photos are NOT emitted as "[image]" text any more. ImageSaver captures the
                // actual file and the backend creates the bubble row from the upload, so a
                // placeholder here would produce a SECOND, empty row for the same photo. When a
                // save genuinely fails, ImageSaver writes its own "[photo — not saved]" message.
            }
        }
        return out
    }
}

/**
 * MESSENGER — resource IDs obfuscated. Parse by content-desc.
 *   Message bubble cd = "<sender>, <content>, double tap to see sent/receive date and time, ..."
 *   Toolbar Button cd = "<chat name>, Thread details".
 */
object MessengerParser {
    private const val MSG_MARKER = ", double tap to see sent/receive date and time"

    fun chatTitle(root: AccessibilityNodeInfo): String? {
        var title: String? = null
        walk(root) { n ->
            val cd = n.contentDescription?.toString() ?: return@walk
            if (title == null && cd.endsWith(", Thread details")) {
                title = cd.removeSuffix(", Thread details")
            }
        }
        return title
    }

    fun parse(root: AccessibilityNodeInfo): List<CapturedMessage> {
        val chat = chatTitle(root) ?: "?"
        val out = ArrayList<CapturedMessage>()
        walk(root) { n ->
            val cd = n.contentDescription?.toString() ?: n.text?.toString() ?: return@walk
            val idx = cd.indexOf(MSG_MARKER)
            if (idx > 0) {
                val core = cd.substring(0, idx)           // "sender, content"
                val sep = core.indexOf(", ")
                if (sep > 0) {
                    val sender = core.substring(0, sep)
                    val content = core.substring(sep + 2)
                    if (content.isNotBlank())
                        out.add(CapturedMessage("MESSENGER", chat, sender, content, false, viaAccessibility = true))
                }
            }
            // The "Forward photo sent by <name> on <time>" node is no longer turned into an
            // "[image]" message here — it is the ATTRIBUTION MessengerImageParser reads to identify
            // a photo bubble, and the photo itself is captured as a file. See ImageBubbles.kt.
        }
        // The same bubble appears multiple times in the obfuscated tree → dedupe.
        return out.distinctBy { it.sender + "|" + it.content }
    }
}
