package com.tvl.incidentaliq.capture

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import com.tvl.incidentaliq.core.AppLog
import com.tvl.incidentaliq.data.CapturedMessage

class UITreeAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "TREE"
        const val ACTION_DUMP = "com.tvl.incidentaliq.DUMP"
        var instance: UITreeAccessibilityService? = null
        private const val MAX_SCROLLS = 20        // hard cap so a huge unread backlog can't hang the read
        private const val SCROLL_SETTLE_MS = 350L // let the list settle after each scroll before re-reading
    }

    // Internal dump trigger. Registered NOT_EXPORTED so no other app on the device can fire it
    // (an exported receiver would let any app dump on-screen chat content into our log). Use the
    // in-app "Dump Tree" button for dev, which calls dumpTree() directly.
    private val dumpReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            AppLog.write(TAG, "DUMP broadcast received")
            dumpTree()
        }
    }

    override fun onServiceConnected() {
        instance = this
        ContextCompat.registerReceiver(
            this, dumpReceiver, IntentFilter(ACTION_DUMP), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        AppLog.write(TAG, "AccessibilityService connected — watching Viber + Messenger")
        AppLog.write(TAG, "Dump via the in-app 'Dump Tree' button while a chat is on-screen")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {}

    /** Package of whatever is currently on screen (null if nothing readable). */
    fun foregroundPackage(): String? = rootInActiveWindow?.packageName?.toString()

    /** Parse the chat currently on screen. Returns (package, messages) or null. */
    fun readActiveChat(): Pair<String, List<CapturedMessage>>? {
        val root = rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString() ?: return null
        val msgs = when (pkg) {
            "com.viber.voip" -> ViberParser.parse(root)
            "com.facebook.orca" -> MessengerParser.parse(root)
            else -> return null
        }
        return pkg to msgs
    }

    /**
     * Drive the conversation to the very BOTTOM (newest message) before we read it.
     *
     * Why this exists: the Trip Ops account is a silent member, so unread messages pile up. When the
     * chat is opened via the notification's contentIntent, Viber lands at the UNREAD DIVIDER — which
     * can be many screens ABOVE the newest message — not at the bottom. Without this, the read
     * captures whatever old screenful it landed on (re-storing days-old messages with today's capture
     * time) and MISSES the new message that actually triggered the read (e.g. the job sheet at the
     * very bottom). Messenger usually opens at the bottom already, so for it this is a cheap no-op.
     *
     * Scrolls forward (toward newest) until a scroll can't move OR the visible message set stops
     * changing, whichever comes first, with a hard iteration cap so a huge backlog can't hang the
     * cycle. Side benefit: scrolling marks the backlog read, so future opens land at the bottom
     * naturally and this gets cheaper over time.
     */
    fun scrollToBottom() {
        for (i in 0 until MAX_SCROLLS) {
            val root = rootInActiveWindow ?: return
            val scroller = conversationScroller(root) ?: run {
                AppLog.write(TAG, "scrollToBottom: no scrollable list found — reading as-is")
                return
            }
            val sig = visibleSignature(root)
            val moved = scroller.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            Thread.sleep(SCROLL_SETTLE_MS)
            // At the bottom when the list can't scroll further, OR the visible messages didn't change
            // after a scroll (the return value lies on some ROMs, so we check content too).
            if (!moved || visibleSignature(rootInActiveWindow) == sig) {
                AppLog.write(TAG, "scrollToBottom: reached bottom after $i scroll(s)")
                return
            }
        }
        AppLog.write(TAG, "scrollToBottom: hit MAX_SCROLLS ($MAX_SCROLLS) cap — read may miss oldest backlog")
    }

    /** Fingerprint of the currently-visible messages, so we can tell when a scroll stopped moving. */
    private fun visibleSignature(root: AccessibilityNodeInfo?): String {
        root ?: return ""
        val pkg = root.packageName?.toString() ?: return ""
        val msgs = when (pkg) {
            "com.viber.voip" -> ViberParser.parse(root)
            "com.facebook.orca" -> MessengerParser.parse(root)
            else -> return ""
        }
        return msgs.joinToString("|") { it.content }
    }

    /**
     * The scrollable message list. Viber exposes it by id; Messenger obfuscates ids, so fall back to
     * the TALLEST scrollable region on screen (the conversation list dwarfs any other scroller).
     */
    private fun conversationScroller(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        root.findAccessibilityNodeInfosByViewId("com.viber.voip:id/conversation_recycler_view")
            ?.firstOrNull { it.isScrollable }?.let { return it }
        val scrollers = ArrayList<AccessibilityNodeInfo>()
        collectScrollables(root, scrollers)
        return scrollers.maxByOrNull { n ->
            val r = Rect(); n.getBoundsInScreen(r); r.height()
        }
    }

    private fun collectScrollables(node: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
        node ?: return
        if (node.isScrollable) out.add(node)
        for (i in 0 until node.childCount) collectScrollables(node.getChild(i), out)
    }

    override fun onInterrupt() {
        AppLog.write(TAG, "AccessibilityService interrupted")
    }

    override fun onDestroy() {
        try { unregisterReceiver(dumpReceiver) } catch (_: Exception) {}
        instance = null
        AppLog.write(TAG, "AccessibilityService destroyed")
        super.onDestroy()
    }

    fun dumpTree() {
        val root = rootInActiveWindow ?: run {
            AppLog.write(TAG, "dumpTree: no active window — open Viber or Messenger first")
            return
        }

        val pkg = root.packageName?.toString() ?: "unknown"
        AppLog.write(TAG, "═══════════ TREE DUMP START ═══════════ pkg=$pkg")

        var nodeCount = 0
        dumpNode(root, 0) { nodeCount++ }

        AppLog.write(TAG, "═══════════ TREE DUMP END ═══════════ nodes=$nodeCount pkg=$pkg")
    }

    private fun dumpNode(node: AccessibilityNodeInfo?, depth: Int, counter: () -> Unit) {
        node ?: return
        counter()

        val indent = "·".repeat(depth)
        val cls = (node.className ?: "").toString().substringAfterLast(".")
        val id = node.viewIdResourceName?.substringAfterLast("/") ?: "-"
        val text = node.text?.toString()?.replace("\n", "⏎") ?: ""
        val cd = node.contentDescription?.toString()?.replace("\n", "⏎") ?: ""
        val r = Rect().also { node.getBoundsInScreen(it) }
        val flags = buildString {
            if (node.isClickable) append("C")
            if (node.isScrollable) append("S")
            if (node.isEditable) append("E")
        }

        // Log every node so we get the full structure for reverse-engineering the parsers.
        AppLog.write(
            TAG,
            "$indent[$depth] $cls id=$id [$flags] text=\"$text\" cd=\"$cd\" b=${r.left},${r.top},${r.right},${r.bottom}"
        )

        for (i in 0 until node.childCount) dumpNode(node.getChild(i), depth + 1, counter)
    }
}
