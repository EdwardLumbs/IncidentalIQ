package com.tvl.incidentaliq.capture

import android.accessibilityservice.AccessibilityService
import android.content.Context
import com.tvl.incidentaliq.core.AppLog
import com.tvl.incidentaliq.data.CapturedMessage
import com.tvl.incidentaliq.data.ImageQueue
import com.tvl.incidentaliq.data.MessageStore
import java.io.File

/**
 * Gets the PHOTOS out of a chat and into the upload queue.
 *
 * Neither Viber nor Messenger exposes the image file to another app — the bytes live in their
 * private sandbox, and no API on either side hands them over (the same wall that made this whole
 * project a phone in the first place). What they DO offer is a save path a human can drive, so this
 * drives it: open the photo, let the app write it to the public gallery folder, pick the file up
 * from there.
 *
 *   MESSENGER  tap tile → viewer → tap "Save" → (swipe → Save …) → Close
 *   VIBER      tap photo → viewer writes the file by itself (Save-to-gallery); tap the in-viewer
 *              download button first when the full-size copy hasn't been fetched yet → Back
 *
 * Verified end-to-end on the dedicated phone 2026-09-22: 5/5 photos saved, full-resolution
 * originals (1536×2048 from Messenger), the app left back on the chat afterwards.
 *
 * ⚠️ NOTHING here copies or uploads. It records the saved files in ImageQueue and returns; the
 * upload (and the delete that frees the phone's storage) is ImageUploader's job, off the wake-lock
 * and on the network's schedule rather than the read cycle's.
 */
object ImageSaver {
    private const val TAG = "IMG"

    private const val VIEWER_TRIES = 20        // × POLL_MS ≈ 5s for the viewer to appear
    private const val POLL_MS = 250L
    private const val FILE_WAIT_MS = 6000L     // how long one photo gets to land in the gallery
    private const val FILE_POLL_MS = 250L
    private const val SETTLE_AFTER_SWIPE_MS = 900L
    private const val MAX_PHOTOS_PER_BUBBLE = 40  // a pathological album can't hold the phone hostage

    /**
     * Save every photo in [bubble]. Returns how many made it into the queue.
     *
     * Failure is always partial, never fatal: whatever was saved before a step went wrong stays
     * saved and queued, the app is driven back to the chat, and the read cycle carries on with the
     * text. A bubble that yielded nothing at all leaves a placeholder message behind so the photo's
     * existence is still recorded.
     */
    fun saveBubble(ctx: Context, svc: UITreeAccessibilityService, pkg: String, bubble: ImageBubble, storeOnly: Boolean): Int {
        val count = bubble.count.coerceAtMost(MAX_PHOTOS_PER_BUBBLE)
        AppLog.write(TAG, "bubble: ${bubble.source} \"${bubble.chat}\" ${bubble.sender} ${bubble.timeText} — $count photo(s)")

        val before = ImageQueue.snapshot(bubble.source)
        // Two attempts: a tap can land while the list is still settling from the previous bubble,
        // and re-finding the node between tries picks up wherever the row has moved to.
        var opened = false
        for (attempt in 1..2) {
            val target = if (attempt == 1) bubble.open else refind(svc, bubble) ?: bubble.open
            if (!svc.tap(target)) {
                AppLog.write(TAG, "tap $attempt/2 on the photo did not register")
                Thread.sleep(600)
                continue
            }
            if (awaitViewer(svc, pkg)) { opened = true; break }
            AppLog.write(TAG, "viewer did not open on attempt $attempt/2")
            Thread.sleep(600)
        }
        if (!opened) {
            AppLog.write(TAG, "viewer never opened — skipping bubble")
            returnToChat(svc, pkg)
            savePlaceholder(ctx, bubble, storeOnly, count)
            return 0
        }

        // ⚠️ THE TILE COUNT IS A FLOOR, NOT THE TOTAL. Messenger's grid shows at most 9 tiles and
        // hides the rest behind a "+6" badge on the last one, so a 15-photo album parses as 9 —
        // and stopping at the parsed count silently drops the remainder (Christian Manabat's 15
        // photos came back as 9 on 2026-09-23). The viewer, however, pages through ALL of them.
        //
        // So the loop keeps going until the album itself ends: the swipe stops working, the viewer
        // closes, or two pages in a row yield no new file (the last photo re-saving over itself).
        // MAX_PHOTOS_PER_BUBBLE is the only hard stop.
        var saved = 0
        var barren = 0
        for (seq in 1..MAX_PHOTOS_PER_BUBBLE) {
            val landed = saveCurrent(svc, pkg, bubble.source, before, saved)
            if (landed) { saved++; barren = 0 } else {
                barren++
                AppLog.write(TAG, "photo $seq did not land in ${ImageQueue.folderFor(bubble.source).name}")
                // Past the album's own count, an empty page means the end rather than a failure.
                if (barren >= 2 && saved >= count) break
                if (barren >= 3) { AppLog.write(TAG, "three pages with nothing saved — stopping"); break }
            }
            if (!svc.swipeNext()) { AppLog.write(TAG, "swipe to next photo failed — stopping at $seq"); break }
            Thread.sleep(SETTLE_AFTER_SWIPE_MS)
            // A swipe that runs off the end of the album leaves the viewer, and carrying on
            // would start saving unrelated media from further up the chat.
            if (!viewerOpen(svc, pkg)) { AppLog.write(TAG, "viewer closed after swipe — stopping at $seq"); break }
        }

        returnToChat(svc, pkg)

        // One diff at the END, not per photo: Viber preloads the neighbouring image while you look
        // at one, so files do not appear strictly one-per-tap. Everything new in the folder since we
        // opened this bubble belongs to it, in write order.
        val files = ImageQueue.newSince(bubble.source, before)
        if (files.isEmpty()) {
            AppLog.write(TAG, "no files appeared — nothing to queue for this bubble")
            savePlaceholder(ctx, bubble, storeOnly, count)
            return 0
        }
        val ts = bubble.timestamp()
        val key = bubble.albumKey()
        val items = files.mapIndexed { i, f ->
            ImageQueue.Pending(
                albumKey = key, source = bubble.source, chat = bubble.chat, sender = bubble.sender,
                ts = ts, seq = i + 1, count = maxOf(count, files.size), storeOnly = storeOnly,
                path = f.absolutePath,
            )
        }
        ImageQueue.enqueue(ctx, items)
        AppLog.write(TAG, "saved ${files.size} photo(s) from this bubble (asked for $count)")
        return files.size
    }

    /** The same bubble in a freshly-read tree — its node, after the screen may have moved. */
    private fun refind(svc: UITreeAccessibilityService, bubble: ImageBubble): android.view.accessibility.AccessibilityNodeInfo? =
        try { svc.readImageBubbles().firstOrNull { it.albumKey() == bubble.albumKey() }?.open }
        catch (_: Exception) { null }

    /** Save whatever the viewer is currently showing, and wait for the file to hit the folder. */
    private fun saveCurrent(
        svc: UITreeAccessibilityService, pkg: String, source: String, before: Set<String>, alreadySeen: Int,
    ): Boolean {
        when (pkg) {
            "com.facebook.orca" -> {
                val save = svc.findByDesc("Save").firstOrNull()
                if (save == null) { AppLog.write(TAG, "no Save button in the viewer"); return false }
                if (!svc.tap(save)) { AppLog.write(TAG, "Save tap failed"); return false }
            }
            "com.viber.voip" -> {
                // Viber writes the file itself once the full-size image is on screen. The download
                // button only appears when that copy hasn't been fetched yet — tap it and the same
                // auto-save follows.
                svc.findByViewId("downloadButton")?.let {
                    AppLog.write(TAG, "photo not downloaded yet — tapping download")
                    svc.tap(it)
                }
            }
        }
        return awaitNewFile(source, before, alreadySeen)
    }

    /** Wait until the folder holds more new files than we've already accounted for. */
    private fun awaitNewFile(source: String, before: Set<String>, alreadySeen: Int): Boolean {
        val deadline = System.currentTimeMillis() + FILE_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            val fresh = ImageQueue.newSince(source, before)
            // Settled = the file is fully written. A half-written JPEG would upload as a broken one.
            if (fresh.size > alreadySeen && isSettled(fresh.last())) return true
            Thread.sleep(FILE_POLL_MS)
        }
        return false
    }

    /** A file whose size stopped growing between two looks is done being written. */
    private fun isSettled(f: File): Boolean {
        val a = f.length()
        Thread.sleep(150)
        return a > 0 && f.length() == a
    }

    private fun viewerOpen(svc: UITreeAccessibilityService, pkg: String): Boolean = !svc.inChat(pkg)

    private fun awaitViewer(svc: UITreeAccessibilityService, pkg: String): Boolean {
        for (i in 0 until VIEWER_TRIES) {
            Thread.sleep(POLL_MS)
            if (viewerOpen(svc, pkg)) return true
        }
        return false
    }

    /**
     * Get back to the conversation. Uses each app's own close control first (Messenger's "Close",
     * Viber's "Navigate up") and falls back to the global Back, then verifies we actually landed —
     * leaving the phone sitting in a media viewer would break the next read cycle, which expects a
     * chat on screen.
     */
    private fun returnToChat(svc: UITreeAccessibilityService, pkg: String) {
        for (attempt in 0 until 3) {
            if (svc.inChat(pkg)) return
            val closer = when (pkg) {
                "com.facebook.orca" -> svc.findByDesc("Close").firstOrNull()
                "com.viber.voip" -> svc.findByDesc("Navigate up").firstOrNull()
                else -> null
            }
            if (closer == null || !svc.tap(closer)) {
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            }
            Thread.sleep(700)
        }
        if (!svc.inChat(pkg)) AppLog.write(TAG, "WARNING: still not back on the chat after 3 attempts")
    }

    /**
     * Record that photos were here even though we couldn't save them. Without this a failed save is
     * invisible — the chat shows a job sheet nobody can find, and no row explains why. The text is
     * what the message list will display.
     */
    private fun savePlaceholder(ctx: Context, bubble: ImageBubble, storeOnly: Boolean, count: Int) {
        val label = if (count > 1) "[$count photos — not saved]" else "[photo — not saved]"
        MessageStore.save(
            ctx,
            CapturedMessage(
                bubble.source, bubble.chat, bubble.sender, label,
                isImage = true, viaAccessibility = true, storeOnly = storeOnly,
            ),
        )
    }
}
