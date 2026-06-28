package com.tvl.incidentaliq

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class UITreeAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "TREE"
        var instance: UITreeAccessibilityService? = null
    }

    override fun onServiceConnected() {
        instance = this
        AppLog.write(TAG, "AccessibilityService connected — watching com.viber.voip + com.facebook.orca")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {}

    override fun onInterrupt() {
        AppLog.write(TAG, "AccessibilityService interrupted")
    }

    override fun onDestroy() {
        instance = null
        AppLog.write(TAG, "AccessibilityService destroyed")
        super.onDestroy()
    }

    fun dumpTree() {
        val root = rootInActiveWindow ?: run {
            AppLog.write(TAG, "dumpTree called but no active window — open Viber or Messenger first")
            return
        }

        val pkg = root.packageName?.toString() ?: "unknown"
        AppLog.write(TAG, "=== TREE DUMP START === pkg=$pkg")

        var nodeCount = 0
        dumpNode(root, 0) { nodeCount++ }

        AppLog.write(TAG, "=== TREE DUMP END === total nodes: $nodeCount")
        root.recycle()
    }

    private fun dumpNode(node: AccessibilityNodeInfo?, depth: Int, counter: () -> Unit) {
        node ?: return
        counter()
        val indent = "  ".repeat(depth)
        val id = node.viewIdResourceName ?: "(none)"
        val cls = (node.className ?: "").toString().substringAfterLast(".")
        val text = node.text?.toString() ?: ""
        val cd = node.contentDescription?.toString() ?: ""

        if (text.isNotEmpty() || cd.isNotEmpty() || depth <= 2) {
            AppLog.write(TAG, "$indent[$depth] $cls | id=$id | text=\"$text\" | cd=\"$cd\"")
        }

        for (i in 0 until node.childCount) dumpNode(node.getChild(i), depth + 1, counter)
    }
}
