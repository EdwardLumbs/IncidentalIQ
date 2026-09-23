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

    /**
     * How long to wait before looking again when a photo notification produced NO photos.
     *
     * The message and its attachments do not arrive together. Facebook delivers the message the
     * instant the sender hits send, and the photos follow whenever the sender's phone manages to
     * finish uploading them — so the chat renders a plain text placeholder ("Sent 16 photos.") with
     * no image tiles in it at all, and a read that fires three seconds after the notification finds
     * an empty screen. That is not an edge case here: the senders are drivers standing in ports and
     * container yards on whatever signal they have. It happened to Manuelito Meridor's 16 photos on
     * 2026-09-23 at 14:44, and a minute later he typed the reason himself — "hndi po agad makapag
     * send ng picture wala po signal d2".
     *
     * So: look again. Twice, then stop.
     *
     * ⚠️ TWO, not a long ladder, because the common case turns out to be UNRECOVERABLE and no amount
     * of looking fixes it. When a sender's upload never completes, Facebook delivers a plain TEXT
     * message whose body is literally "Sent 16 photos." — in the accessibility tree it is shaped
     * exactly like any other text message, with no image tiles behind it and nothing to tap. That is
     * not a placeholder that fills in later: Manuelito's was still text 36 minutes on, and it read
     * the same on a second device, so the photos are not on Facebook's servers either. Verified on
     * 2026-09-23. And if his phone ever does push them through, that arrives as a NEW message with
     * its own notification, which the normal path already handles — the retry is not what saves it.
     *
     * So the retry only covers the genuinely narrow case it can: media that exists but hadn't
     * finished rendering in the three seconds between the notification and the scan. Two looks catch
     * that. Anything beyond is screen-on time spent re-opening chats for photos that do not exist,
     * which is also exactly the restlessness the phone was complained about for.
     *
     * Re-reading is safe to do blindly. A bubble this phone has already saved is skipped by
     * ImageQueue.wasSeen, the server drops a photo whose content hash it already holds, and
     * UNIQUE(message_id, sha256) is the backstop under both.
     */
    private val PHOTO_RETRY_DELAYS_MS = longArrayOf(
        60_000L,      // +1m  — media that existed but hadn't rendered yet
        300_000L,     // +6m  — last look; past here it was never delivered, see recordUndelivered()
    )

    private const val HEAL_RETRY_MS = 30_000L   // let the app finish its cold start

    /**
     * Restarting is driven by the SYMPTOM, never by a clock: whenever a bubble reads "Sent N photos."
     * the app is broken and gets restarted, however recently it last was. A timed cooldown was tried
     * and thrown out — if Messenger rots again five minutes after a repair, sitting on our hands for
     * the rest of the cooldown just loses photos for no reason.
     *
     * The only two guards are about not flogging a dead horse:
     *   MIN_HEAL_GAP_MS   stops a tight loop, and is shorter than one repair cycle takes anyway, so
     *                     in practice it never fires.
     *   MAX_FAILED_HEALS  if restarting twice running fixed nothing, the cause is something else and
     *                     a third restart will not find it either. Stop, and say so in the log.
     *                     Reset the moment any photo is captured, because that proves it works again.
     */
    private const val MIN_HEAL_GAP_MS = 60_000L
    private const val MAX_FAILED_HEALS = 2
    private var lastHealAt = 0L
    private var failedHeals = 0

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
        val attempt: Int = 0,           // 0 = the notification itself; >0 = a photo retry (see below)
        val healed: Boolean = false,    // the chat app was already restarted once for this album
        val sawChat: Boolean = false,   // some attempt actually got the chat on screen and looked
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

    /**
     * Photos were announced, none are on screen. Work out WHY, because the two causes need opposite
     * handling:
     *
     *   the bubble reads "Sent 3 photos."   Messenger has stopped fetching attachments. Waiting is
     *                                       useless — it stayed rotten for 96 minutes on 2026-09-23.
     *                                       Restart Messenger and look again straight away.
     *   nothing photo-shaped at all         media that exists but hasn't rendered yet, or a bubble
     *                                       already scrolled past. Give it time; ordinary retry.
     *
     * Healing happens at most ONCE per album (`healed`) and at most once every HEAL_COOLDOWN_MS
     * across the whole app. Both guards matter: restarting Messenger costs every other chat its
     * warm state, and a loop that force-stopped it every few seconds would take the monitor off the
     * air entirely while looking busy.
     */
    private fun healOrRetry(ctx: Context, svc: UITreeAccessibilityService, task: Task) {
        val rotten = try { svc.photosRenderedAsText() } catch (_: Exception) { false }

        // A restarted read that STILL finds text means the restart didn't cure it. Count that, so a
        // permanently broken Messenger doesn't get force-stopped once per photo message forever.
        if (task.healed) {
            if (rotten) failedHeals++
            schedulePhotoRetry(ctx, task)
            return
        }
        if (!rotten) { schedulePhotoRetry(ctx, task); return }

        if (failedHeals >= MAX_FAILED_HEALS) {
            AppLog.write(TAG, "${task.pkg} still shows photos as text after $failedHeals restarts — " +
                              "something else is wrong, not restarting again until a photo saves")
            schedulePhotoRetry(ctx, task)
            return
        }
        val since = System.currentTimeMillis() - lastHealAt
        if (since < MIN_HEAL_GAP_MS) {
            AppLog.write(TAG, "${task.pkg} was restarted ${since / 1000}s ago — letting that one finish first")
            schedulePhotoRetry(ctx, task)
            return
        }
        lastHealAt = System.currentTimeMillis()
        AppLog.write(TAG, "photos are showing as TEXT — ${task.pkg} has stopped fetching them; restarting it")
        AppHealer.restart(ctx, svc, task.pkg)
        // Straight back in, not down the normal ladder: the repair is immediate and the bubble is
        // right there. The delay is only to let the app finish its cold start.
        scheduleRetry(ctx, task.copy(healed = true), HEAL_RETRY_MS, "re-reading after restart")
    }

    /**
     * Put this task back in the queue after the next retry delay, because a notification that
     * announced photos produced none. Does nothing once the ladder is exhausted.
     *
     * Silent on the first schedule of a chat that simply hasn't finished uploading — the log line
     * says which attempt this is, so a chat retrying four times is visible without reading the whole
     * file.
     */
    private fun schedulePhotoRetry(ctx: Context, task: Task) {
        if (task.attempt >= PHOTO_RETRY_DELAYS_MS.size) {
            AppLog.write(TAG, "photos never appeared for \"${task.chatHint}\" after ${task.attempt} retries — giving up")
            // Only claim the photos weren't there if we actually got in and looked. A read that
            // timed out before the chat opened knows nothing about them, and writing "not saved"
            // into the timeline off the back of that is just a lie in the record.
            if (task.sawChat) recordUndelivered(ctx, task)
            else AppLog.write(TAG, "…but the chat never opened, so nothing is claimed about the photos")
            return
        }
        val delay = PHOTO_RETRY_DELAYS_MS[task.attempt]
        val next = task.attempt + 1
        scheduleRetry(ctx, task.copy(attempt = next), delay,
                      "retry $next of ${PHOTO_RETRY_DELAYS_MS.size}")
    }

    /** Re-queue [task] after [delay]. The one place a retry is booked, so every one of them logs. */
    private fun scheduleRetry(ctx: Context, task: Task, delay: Long, why: String) {
        AppLog.write(TAG, "no photos on screen — $why in ${delay / 1000}s")
        val app = ctx.applicationContext
        // handler is non-null here: this only runs from inside a task, which the handler thread drove.
        handler?.postDelayed({ enqueue(app, task) }, delay)
    }

    /**
     * Photos were announced and never turned up. Write that down.
     *
     * The point is that the loss stops being SILENT. Someone reading the panel a week later should
     * see "Manuelito Meridor — 16 photos announced, never delivered" sitting in the timeline at 2:44
     * PM, not an unexplained gap between two text messages that leaves them wondering whether the
     * capture phone was broken. It usually means the sender's upload died on bad signal, which is a
     * thing to chase the sender about — and the dispatchers are already doing that by hand ("@Francis
     * Larin pasend ng pictures"), so the record is what they actually want.
     *
     * Deliberately NOT a failure of this app, and worded so nobody reads it as one.
     */
    private fun recordUndelivered(ctx: Context, task: Task) {
        val what = task.fallbackText.ifBlank { "photos" }.trim()
        val saved = MessageStore.save(
            ctx,
            CapturedMessage(
                task.source, task.chatHint, task.sender,
                "[$what — photos never appeared on the capture phone, not saved]",
                false, viaAccessibility = false, storeOnly = task.storeOnly,
            ),
        )
        if (saved && task.immediate) Uploader.syncNow(ctx)
    }

    /**
     * Same conversation? The notification's group name and the title drawn at the top of the chat are
     * produced by different parts of Messenger and don't always agree on whitespace or case, so this
     * is deliberately loose — it is a "did we end up somewhere completely different" check, not an
     * identity test.
     */
    private fun sameChat(onScreen: String?, hint: String): Boolean {
        val a = onScreen?.trim()?.lowercase() ?: return false
        val b = hint.trim().lowercase()
        return a == b || a.startsWith(b) || b.startsWith(a)
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
        val retry = if (task.attempt > 0) " (photo retry ${task.attempt}/${PHOTO_RETRY_DELAYS_MS.size})" else ""
        AppLog.write(TAG, "────── READ START [${task.source}] \"${task.chatHint}\"$retry ──────")

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
            if (task.isImage) schedulePhotoRetry(ctx, task)
            WakeLockHelper.release()
            return
        }

        // PHASE 1 — readiness: wait until the target app is foreground and readable.
        var ready = false
        var tries = 0
        // A just-restarted app is starting COLD — Messenger takes well over the usual six seconds to
        // get a thread on screen from a force stop, and timing out here would throw away the very
        // read the restart was performed for.
        val pollTries = if (task.healed) POLL_TRIES * 4 else POLL_TRIES
        while (tries < pollTries) {
            Thread.sleep(POLL_MS); tries++
            if (svc.foregroundPackage() == task.pkg) {
                val res = svc.readActiveChat()
                if (res != null && res.second.isNotEmpty()) { ready = true; break }
                // A SCREENFUL OF PHOTOS HAS NO TEXT IN IT. Waiting for a parsed message declares a
                // perfectly good chat unreadable, times out, and — worse — then records the photos
                // as "never appeared" when they were on screen the whole time (2026-09-23 21:53).
                // Being in the chat is the real readiness signal; messages are just the usual proof.
                if (svc.inChat(task.pkg) && svc.readImageBubbles().isNotEmpty()) { ready = true; break }
            }
        }
        if (!ready) {
            AppLog.write(TAG, "TIMEOUT — chat not readable after ${tries}×${POLL_MS}ms (fg=${svc.foregroundPackage()})")
            saveFallback(ctx, task)
            if (task.isImage) schedulePhotoRetry(ctx, task)
            WakeLockHelper.release()
            return
        }

        // From here on the chat is genuinely on screen, so anything this read concludes about its
        // photos is worth acting on. Retries booked below carry that fact forward.
        val seen = task.copy(sawChat = true)

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
        // A retry may have landed in the wrong conversation (see activeChatTitle) — in which case the
        // photos on screen are somebody else's and none of this applies. Try again rather than scrape
        // them. The first attempt is trusted: it came straight off the notification's own intent.
        val strayed = task.attempt > 0 && !sameChat(svc.activeChatTitle(), task.chatHint)
        if (strayed) {
            AppLog.write(TAG, "retry landed on \"${svc.activeChatTitle()}\", not \"${task.chatHint}\" — skipping photo pass")
        }
        val photos =
            if (strayed) 0
            else capturePhotos(ctx, svc, task.pkg, task.storeOnly, expectPhotos = task.isImage)

        // Photos came through, so whatever was wrong isn't any more — let restarting be possible
        // again the next time it's needed.
        if (photos > 0) failedHeals = 0

        // The notification said photos and there are none on screen. Two different things look like
        // this, and they need opposite responses — so ask which one it is before reacting.
        if (task.isImage && photos == 0 && !strayed) healOrRetry(ctx, svc, seen)
        else if (task.isImage && photos == 0) schedulePhotoRetry(ctx, seen)

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
