package com.tvl.incidentaliq.capture

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import com.tvl.incidentaliq.core.AppLog
import com.tvl.incidentaliq.core.ImagePermissions
import com.tvl.incidentaliq.core.WakeLockHelper
import com.tvl.incidentaliq.data.CapturedMessage
import com.tvl.incidentaliq.data.ImageQueue
import com.tvl.incidentaliq.data.MessageStore
import com.tvl.incidentaliq.sync.Uploader

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
    private const val PHOTO_LOOKBACK_SCREENS = 2      // only ever "just above the newest message"
    private const val RECENT_WINDOW_MS = 60 * 60 * 1000L  // the bubble must be from the last hour
    private const val RECENT_SKEW_MS = 10 * 60 * 1000L    // a slightly-future time is clock rounding

    data class Task(
        val source: String,         // VIBER | MESSENGER
        val pkg: String,            // com.viber.voip | com.facebook.orca
        val chatHint: String,       // group name from the notification (also used for storage)
        val intent: PendingIntent?, // notification contentIntent → opens the chat directly
        val sender: String = "",        // sender from the notification, for the fallback save
        val fallbackText: String = "",  // truncated notif content, saved if the read fails (text only)
        val isImage: Boolean = false,   // image notifs have no useful text → no fallback save
        val storeOnly: Boolean = false, // archive-only group → tag every message so backend skips Groq
        val immediate: Boolean = false, // immediate-upload group → sync as soon as something is saved
    )

    /**
     * When the accessibility read can't run (app won't open / chat never becomes readable), save the
     * truncated notification text so the message isn't lost entirely — partial content beats none.
     * Skipped for image notifications (their "text" is just "Photo"). Dedup in MessageStore means if
     * the full read later succeeds via another path, this doesn't create a lasting duplicate.
     */
    private fun saveFallback(ctx: Context, task: Task) {
        if (task.isImage || task.fallbackText.isBlank()) return
        val saved = MessageStore.save(
            ctx, CapturedMessage(task.source, task.chatHint, task.sender, task.fallbackText, false, viaAccessibility = false, storeOnly = task.storeOnly)
        )
        if (saved) {
            AppLog.write(TAG, "saved truncated notification text as fallback (read failed)")
            if (task.immediate) {
                AppLog.write(TAG, "immediate group — syncing now")
                Uploader.syncNow(ctx)
            }
        }
    }

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
            saveFallback(ctx, task)
            return
        }

        WakeLockHelper.acquire(ctx)
        Thread.sleep(500) // let the screen come on

        if (!openChat(ctx, task)) {
            AppLog.write(TAG, "ABORT — could not open chat")
            saveFallback(ctx, task)
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
            saveFallback(ctx, task)
            WakeLockHelper.release()
            return
        }

        // PHASE 1.5 — drive to the bottom BEFORE reading. Viber opens at the unread divider, not the
        // newest message, so without this we'd re-capture whatever old screenful it landed on and miss
        // the message that triggered the read. Cheap no-op when already at the bottom (Messenger).
        svc.scrollToBottom()

        // PHASE 2 — settle: read ALL visible messages, store the new ones (dedup), and
        // linger to catch stragglers that arrive while we're looking (no notif fires for
        // the open chat). Close once it's been quiet for QUIET_MS, or at the hard cap.
        val started = System.currentTimeMillis()
        var lastNewAt = started
        var totalNew = 0
        while (true) {
            svc.readActiveChat()?.second?.forEach { raw ->
                // The parser doesn't know the group's list membership — stamp the task's store-only
                // flag onto every message read in this cycle so the backend skips Groq for them.
                val m = if (task.storeOnly) raw.copy(storeOnly = true) else raw
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

        // PHASE 2.5 — PHOTOS. Deliberately AFTER the text settle and BEFORE closing: text is cheap
        // and must never be held up behind a 17-photo album, and the final scan below re-reads the
        // chat once the tapping is done, so anything that arrived while we were in the photo viewer
        // is still picked up on the way out.
        //
        // Every photo costs a couple of seconds of tapping, so the work is skipped entirely for
        // bubbles this phone has already saved (ImageQueue.wasSeen) — a chat gets re-opened on
        // every truncated message, and its photos are still sitting there each time.
        //
        // ⚠️ ONLY WHAT IS ON SCREEN. It does not scroll looking for older albums. A version that did
        // was tried and removed (2026-09-23): walking back through history re-captured months of old
        // bubbles, 17 of them with no sender at all because the attribution had scrolled out of view,
        // and an album key built from a blank sender is not even stable enough to stop it happening
        // again. Anything already scrolled away stays in the chat, where it can still be read.
        val photos = capturePhotos(ctx, svc, task.pkg, task.storeOnly, expectPhotos = task.isImage)

        // Re-read after the photo phase: no notification fires for the chat that is on screen, so
        // whatever landed during those taps is only visible by looking again.
        if (photos > 0) {
            svc.readActiveChat()?.second?.forEach { raw ->
                val m = if (task.storeOnly) raw.copy(storeOnly = true) else raw
                if (MessageStore.save(ctx, m)) {
                    totalNew++
                    AppLog.write(TAG, "✅ (post-photos) [${m.source}] ${m.sender}: ${m.content.take(140)}")
                }
            }
        }
        if ((totalNew > 0 || photos > 0) && task.immediate) {
            AppLog.write(TAG, "immediate group — syncing now")
            Uploader.syncNow(ctx)
        }

        // PHASE 3 — close: back to home, release the wake lock.
        Thread.sleep(200)
        svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        WakeLockHelper.release()
        AppLog.write(TAG, "────── READ END ──────")
    }

    /**
     * Save every photo bubble on screen that this phone hasn't already handled. Returns how many
     * photos were queued for upload.
     *
     * A bubble is marked seen even when it yields nothing, so a photo the apps refuse to save
     * (deleted by the sender, expired, a video we can't handle) is attempted ONCE rather than
     * re-attempted on every future read of that chat — which would burn the cycle forever on a
     * bubble that will never work.
     */
    /**
     * Run the photo phase against whatever chat is on screen RIGHT NOW, outside a read cycle.
     * The in-app trigger (long-press Dump Tree) for checking the save path on a real chat without
     * waiting for a notification to arrive — the photo equivalent of dumpTree().
     */
    fun capturePhotosOnScreen(ctx: Context): Int {
        val svc = UITreeAccessibilityService.instance ?: run {
            AppLog.write(TAG, "photo test: accessibility service not running")
            return 0
        }
        val pkg = svc.foregroundPackage() ?: run {
            AppLog.write(TAG, "photo test: nothing readable on screen")
            return 0
        }
        if (pkg != "com.viber.voip" && pkg != "com.facebook.orca") {
            AppLog.write(TAG, "photo test: open a Viber or Messenger chat first (on screen: $pkg)")
            return 0
        }
        AppLog.write(TAG, "────── PHOTO TEST on $pkg ──────")
        WakeLockHelper.acquire(ctx)
        return try {
            capturePhotos(ctx, svc, pkg, storeOnly = false, expectPhotos = false)
        } finally {
            WakeLockHelper.release()
            AppLog.write(TAG, "────── PHOTO TEST END ──────")
        }
    }

    private fun capturePhotos(
        ctx: Context, svc: UITreeAccessibilityService, pkg: String, storeOnly: Boolean,
        expectPhotos: Boolean,
    ): Int {
        if (!ImagePermissions.canRead(ctx)) return 0   // status is reported in the app, not per-read
        val bubbles = try {
            svc.readImageBubbles()
        } catch (e: Exception) {
            AppLog.write(TAG, "photo scan failed: ${e.message}")
            return 0
        }
        var saved = saveVisible(ctx, svc, pkg, storeOnly, bubbles)
        if (bubbles.isEmpty() && expectPhotos) saved += lookBackOneScreen(ctx, svc, pkg, storeOnly)
        return saved
    }

    /**
     * The notification said photos, but the chat opened on a screen with none. Look back a screen or
     * two, save what is found, and return to the newest message.
     *
     * ⚠️ DELIBERATELY TINY, and it fires on one condition only. Photos can only be opened while
     * visible, and a later message can push an album off the top before the read gets there — a job
     * sheet followed straight by a long booking post is exactly that shape. This closes that, and
     * nothing else.
     *
     * An earlier version walked back 25 screens on every read and was removed the same day: it
     * re-captured months of old bubbles, 17 of them with no sender at all because their attribution
     * had scrolled out of view, and an album key built from a blank sender is not stable enough to
     * stop it happening again. Hence the guards here — only when photos were announced, only when
     * none are on screen, two screens at most, and only bubbles recent enough to be the ones just
     * announced. A PARTLY visible album needs none of this: opening any tile pages the viewer
     * through the whole bubble regardless of how many the grid shows.
     */
    private fun lookBackOneScreen(
        ctx: Context, svc: UITreeAccessibilityService, pkg: String, storeOnly: Boolean,
    ): Int {
        var saved = 0
        var screens = 0
        while (screens < PHOTO_LOOKBACK_SCREENS) {
            if (!svc.scrollUpOnce()) break
            screens++
            val found = try { svc.readImageBubbles() } catch (_: Exception) { emptyList() }
            val recent = found.filter { it.isRecent() }
            if (recent.isNotEmpty()) {
                AppLog.write(TAG, "photos were announced but were off screen — found ${recent.size} bubble(s) $screens screen(s) back")
                saved += saveVisible(ctx, svc, pkg, storeOnly, recent)
                break
            }
        }
        if (screens > 0) svc.scrollToBottom()   // leave the chat where the rest of the cycle expects it
        return saved
    }

    /**
     * Recent enough to be the bubble the notification was about. Anything older is history the app
     * was never asked to collect, and a bubble with no time on it cannot be keyed stably at all.
     */
    private fun ImageBubble.isRecent(): Boolean {
        if (timeText.isBlank()) return false
        val age = System.currentTimeMillis() - millis()
        return age in -RECENT_SKEW_MS..RECENT_WINDOW_MS
    }

    /**
     * Save every unsaved photo bubble in the chat, LOOKING BACK through recent history as well as at
     * what is currently on screen.
     *
     * The scroll-back is the difference between a photo being captured and being lost forever. A
     * bubble can only be saved while it is visible, and a read cycle lands at the NEWEST message —
     * so an album posted even a few messages ago is already out of reach. That is not theoretical:
     * Christian Manabat's 15 photos (2026-09-23 10:32) went unsaved because the notification that
     * would have fetched them was misread, and by the time it was fixed the album had scrolled away
     * with no way for the app to reach it.
     *
     * Bounded on purpose. Each screen costs a scroll and a tree read, this runs on EVERY read cycle,
     * and bubbles already saved are recognised for free by their album key — so the usual pass is a
     * few hundred milliseconds of finding nothing new. The chat is driven back to the bottom at the
     * end, because everything else in the cycle assumes it is there.
     */
    /** Save the unsaved bubbles visible right now. */
    private fun saveVisible(
        ctx: Context, svc: UITreeAccessibilityService, pkg: String, storeOnly: Boolean,
        bubbles: List<ImageBubble>,
    ): Int {
        val todo = bubbles.filter { !ImageQueue.wasSeen(ctx, it.albumKey()) }
        if (todo.isEmpty()) return 0
        AppLog.write(TAG, "${todo.size} new photo bubble(s) to save")

        // ⚠️ ONE BUBBLE PER TREE READ. The node inside an ImageBubble is a live handle into the
        // window that produced it, and saving a bubble leaves the chat for a media viewer and comes
        // back — which invalidates every node captured before that trip. Reusing them taps at
        // coordinates the list has since scrolled away from, and the viewer simply never opens
        // (seen on the device 2026-09-22: photo 1 fine, the 8-photo album right after it dead).
        //
        // So the loop works off album KEYS, which survive anything, and re-finds each bubble in a
        // freshly-read tree right before touching it.
        var saved = 0
        for (key in todo.map { it.albumKey() }) {
            val current = try { svc.readImageBubbles() } catch (_: Exception) { emptyList() }
            val b = current.firstOrNull { it.albumKey() == key }
            if (b == null) {
                // Scrolled out of view while we worked on an earlier bubble. Leave it UNSEEN so the
                // next read of this chat picks it up, rather than marking it done for good.
                AppLog.write(TAG, "photo bubble no longer on screen — leaving it for the next read")
                continue
            }
            try {
                saved += ImageSaver.saveBubble(ctx, svc, pkg, b, storeOnly)
            } catch (e: Exception) {
                AppLog.write(TAG, "photo save error: ${e.message}")
            }
            ImageQueue.markSeen(ctx, key)
        }
        return saved
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
