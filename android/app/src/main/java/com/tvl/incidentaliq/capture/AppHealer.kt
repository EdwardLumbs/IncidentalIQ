package com.tvl.incidentaliq.capture

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import com.tvl.incidentaliq.core.AppLog

/**
 * Restarts a chat app that has stopped fetching photos, by driving Android's own App info screen.
 *
 * WHY THIS EXISTS
 * Messenger's process rots on the capture phone. Notifications keep arriving, text messages keep
 * rendering, but attachments stop being fetched and the bubble becomes the literal text
 * "Sent 3 photos." — see UITreeAccessibilityService.photosRenderedAsText(). Force-stopping Messenger
 * cures it instantly AND retroactively: on 2026-09-23 the same two bubbles that read "Sent 2 photos."
 * came back as real image tiles the moment the app was restarted. Nothing else tried worked — ninety
 * seconds in the foreground, scrolling, and reopening the thread all left it rotten.
 *
 * WHY THE SETTINGS SCREEN
 * A normal app cannot stop another app: android.permission.FORCE_STOP_PACKAGES is signature-level.
 * The two routes actually available were tested on the phone in use (Vivo V2126 / Android 14):
 *
 *   recents + swipe away   pid 21327 before, pid 21327 after — Funtouch keeps the process. Useless.
 *   App info → Force stop  works. Any app may open this screen; we can press the button.
 *
 * So this fires ACTION_APPLICATION_DETAILS_SETTINGS and taps through it, which is ugly but is the
 * only thing on this device that actually kills the process.
 *
 * ⚠️ ALWAYS RELAUNCHES. A force-stopped Messenger stays dead until something starts it, and a dead
 * Messenger posts no notifications at all — the monitor would go quiet and look healthy while seeing
 * nothing. Relaunching is not optional tidying; it is the difference between a repair and an outage.
 */
object AppHealer {
    private const val TAG = "HEAL"
    private const val SETTINGS_PKG = "com.android.settings"
    private const val POLL_MS = 250L
    private const val POLL_TRIES = 24          // ≈6s for the Settings screen to appear
    private const val SETTLE_MS = 700L

    // Button labels, lowercased, IN PRIORITY ORDER — findButton tries each in turn and takes the
    // first that exists anywhere on screen. Several spellings because OEM skins reword them; the
    // phone in use says "Force stop" on the button and offers "Cancel / OK" in the dialog.
    private val FORCE_STOP_LABELS = listOf("force stop", "force close", "stop")

    // ⚠️ "force stop" is LAST here on purpose. While the confirmation dialog is open, the App info
    // button behind it is still in the tree — so searching for "force stop" first would find that
    // button instead of the dialog's confirm and re-open the same dialog forever. "ok" wins.
    // "cancel" is deliberately absent: tapping it would silently abandon the repair.
    private val CONFIRM_LABELS = listOf("ok", "confirm", "yes", "force stop")

    /**
     * Force-stop [pkg], then start it again. Returns true when the Force stop button was actually
     * pressed — false means we never found it, and the caller should carry on regardless rather than
     * treat a failed repair as a failed read.
     */
    fun restart(ctx: Context, svc: UITreeAccessibilityService, pkg: String): Boolean {
        AppLog.write(TAG, "restarting $pkg — it is showing photos as text")
        val stopped = try {
            forceStop(ctx, svc, pkg)
        } catch (e: Exception) {
            AppLog.write(TAG, "force stop failed: ${e.message}")
            false
        }
        // Unconditional: even a half-finished attempt may have killed it, and a dead Messenger is
        // worse than a rotten one.
        relaunch(ctx, pkg)
        AppLog.write(TAG, if (stopped) "restarted $pkg" else "could not press Force stop — relaunched $pkg anyway")
        return stopped
    }

    private fun forceStop(ctx: Context, svc: UITreeAccessibilityService, pkg: String): Boolean {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:$pkg"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ctx.startActivity(intent)

        if (!waitForSettings(svc)) {
            AppLog.write(TAG, "App info screen never appeared")
            return false
        }
        Thread.sleep(SETTLE_MS)

        val button = findButton(svc, FORCE_STOP_LABELS)
        if (button == null) {
            AppLog.write(TAG, "no Force stop button on the App info screen")
            leaveSettings(svc)
            return false
        }
        // A disabled button means the app is already stopped — nothing to do, and tapping it would
        // sit there waiting for a dialog that never opens.
        if (!button.isEnabled) {
            AppLog.write(TAG, "$pkg is already stopped")
            leaveSettings(svc)
            return true
        }
        svc.tap(button)
        Thread.sleep(SETTLE_MS)

        // Confirmation dialog. Some builds skip it entirely, so a miss here is not a failure —
        // check the button afterwards instead of assuming either way.
        findButton(svc, CONFIRM_LABELS)?.let {
            svc.tap(it)
            Thread.sleep(SETTLE_MS)
        }

        val nowDisabled = findButton(svc, FORCE_STOP_LABELS)?.isEnabled == false
        leaveSettings(svc)
        return nowDisabled
    }

    private fun waitForSettings(svc: UITreeAccessibilityService): Boolean {
        repeat(POLL_TRIES) {
            if (svc.foregroundPackage() == SETTINGS_PKG) return true
            Thread.sleep(POLL_MS)
        }
        return false
    }

    /**
     * A button whose label is one of [labels], tried IN THE ORDER GIVEN — every node is checked
     * against the first label before the second is considered, so priority beats tree position.
     * That is what keeps the dialog's "OK" ahead of the "Force stop" button sitting behind it.
     *
     * Matching is on the node's own text or description and against the WHOLE label, so "stop"
     * cannot match "Stop using this app" or a settings row that merely mentions the word. The
     * dialog's own title, "Force stop?", does not match either — the question mark makes it a
     * different string, which is lucky rather than clever, so the equality is kept strict.
     */
    private fun findButton(
        svc: UITreeAccessibilityService, labels: List<String>,
    ): AccessibilityNodeInfo? {
        val onScreen = ArrayList<Pair<String, AccessibilityNodeInfo>>()
        svc.walkVisible { n ->
            val label = (n.text ?: n.contentDescription)?.toString()?.trim()?.lowercase()
            if (!label.isNullOrEmpty()) onScreen.add(label to n)
        }
        for (want in labels) {
            onScreen.firstOrNull { it.first == want }?.let { return it.second }
        }
        return null
    }

    /** Back out of Settings so the next read doesn't open a chat on top of it. */
    private fun leaveSettings(svc: UITreeAccessibilityService) {
        svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        Thread.sleep(300)
    }

    private fun relaunch(ctx: Context, pkg: String) {
        val li = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return
        li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(li)
            Thread.sleep(SETTLE_MS)
        } catch (e: Exception) {
            AppLog.write(TAG, "could not relaunch $pkg: ${e.message}")
        }
    }
}
