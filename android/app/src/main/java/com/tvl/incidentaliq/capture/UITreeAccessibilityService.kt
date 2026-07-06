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
