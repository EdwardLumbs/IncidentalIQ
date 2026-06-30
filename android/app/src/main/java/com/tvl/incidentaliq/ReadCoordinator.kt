package com.tvl.incidentaliq

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread

/**
 * Drives the read cycle for a truncated/image notification, ONE at a time:
 *   wake screen → open the exact chat (fire the notification's contentIntent)
 *   → poll until the chat is on-screen and readable → parse full messages
 *   → store the latest → go home → release wake lock.
 *
 * Sequential by design so concurrent notifications never collide.
 */
object ReadCoordinator {
    private const val TAG = "READ"
    private const val POLL_TRIES = 24      // × 250ms ≈ 6s to wait for the app to open
    private const val POLL_MS = 250L
    private const val QUIET_MS = 2000L     // close after this long with no NEW message
    private const val RESCAN_MS = 600L     // how often to re-read while settling
    private const val MAX_SETTLE_MS = 8000L // hard cap on lingering in a chatty group

    data class Task(
        val source: String,         // VIBER | MESSENGER
        val pkg: String,            // com.viber.voip | com.facebook.orca
        val chatHint: String,       // sender/group from the notification
        val intent: PendingIntent?, // notification contentIntent → opens the chat directly
    )

    private val queue = ArrayDeque<Task>()
    private var handler: Handler? = null
    private var busy = false

    @Synchronized
    fun enqueue(ctx: Context, task: Task) {
        if (handler == null) {
            val t = HandlerThread("read-coordinator").apply { start() }
            handler = Handler(t.looper)
        }
        queue.addLast(task)
        AppLog.write(TAG, "ENQUEUE ${task.source} chat=\"${task.chatHint}\" (queue=${queue.size})")
        if (!busy) {
            busy = true
            val app = ctx.applicationContext
            handler!!.post { drain(app) }
        }
    }

    private fun drain(ctx: Context) {
        while (true) {
            val task = synchronized(this) { queue.removeFirstOrNull() } ?: break
            try { runTask(ctx, task) } catch (e: Exception) { AppLog.write(TAG, "ERROR: ${e.message}") }
        }
        synchronized(this) { busy = false }
    }

    private fun runTask(ctx: Context, task: Task) {
        AppLog.write(TAG, "────── READ START [${task.source}] \"${task.chatHint}\" ──────")

        val svc = UITreeAccessibilityService.instance
        if (svc == null) {
            AppLog.write(TAG, "ABORT — AccessibilityService not running")
            return
        }

        WakeLockHelper.acquire(ctx)
        Thread.sleep(500) // let the screen come on

        if (!openChat(ctx, task)) {
            AppLog.write(TAG, "ABORT — could not open chat")
            WakeLockHelper.release()
            return
        }

        // PHASE 1 — readiness: wait until the target app is foreground and readable.
        var ready = false
        var tries = 0
        while (tries < POLL_TRIES) {
            Thread.sleep(POLL_MS); tries++
            if (svc.foregroundPackage() == task.pkg) {
                val res = svc.readActiveChat()
                if (res != null && res.second.isNotEmpty()) { ready = true; break }
            }
        }
        if (!ready) {
            AppLog.write(TAG, "TIMEOUT — chat not readable after ${tries}×${POLL_MS}ms (fg=${svc.foregroundPackage()})")
            WakeLockHelper.release()
            return
        }

        // PHASE 2 — settle: read ALL visible messages, store the new ones (dedup), and
        // linger to catch stragglers that arrive while we're looking (no notif fires for
        // the open chat). Close once it's been quiet for QUIET_MS, or at the hard cap.
        val started = System.currentTimeMillis()
        var lastNewAt = started
        var totalNew = 0
        while (true) {
            svc.readActiveChat()?.second?.forEach { m ->
                if (MessageStore.save(ctx, m)) {
                    totalNew++
                    lastNewAt = System.currentTimeMillis()
                    AppLog.write(TAG, "✅ [${m.source}] ${m.sender}: ${m.content.take(140)}")
                }
            }
            val now = System.currentTimeMillis()
            if (now - lastNewAt >= QUIET_MS) break
            if (now - started >= MAX_SETTLE_MS) { AppLog.write(TAG, "settle cap ${MAX_SETTLE_MS}ms hit"); break }
            Thread.sleep(RESCAN_MS)
        }
        AppLog.write(TAG, "captured $totalNew new message(s) from \"${task.chatHint}\"")

        // PHASE 3 — close: back to home, release the wake lock.
        Thread.sleep(200)
        svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        WakeLockHelper.release()
        AppLog.write(TAG, "────── READ END ──────")
    }

    /** Open the exact chat. Prefer the notification's contentIntent (lands directly in it). */
    private fun openChat(ctx: Context, task: Task): Boolean {
        if (task.intent != null) {
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    val opts = ActivityOptions.makeBasic()
                        .setPendingIntentBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                        )
                    task.intent.send(ctx, 0, null, null, null, null, opts.toBundle())
                } else {
                    task.intent.send()
                }
                AppLog.write(TAG, "fired notification contentIntent → chat")
                return true
            } catch (e: PendingIntent.CanceledException) {
                AppLog.write(TAG, "contentIntent canceled (${e.message}) — falling back to app launch")
            }
        }
        // Fallback: just open the app (lands wherever it was; better than nothing).
        val li = ctx.packageManager.getLaunchIntentForPackage(task.pkg)
        if (li != null) {
            li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(li)
            AppLog.write(TAG, "launched ${task.pkg} (fallback, no contentIntent)")
            return true
        }
        return false
    }
}
