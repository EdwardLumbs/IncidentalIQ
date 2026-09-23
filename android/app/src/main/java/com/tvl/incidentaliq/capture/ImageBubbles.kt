package com.tvl.incidentaliq.capture

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Finds PHOTO bubbles in an open chat — the counterpart to MessageParsers, which reads text.
 *
 * A "bubble" is what the sender posted in one go: one photo, or the album Messenger labels
 * "Ike sent 4 photos". It is the unit the backend stores (one captured_messages row + N
 * message_images rows), so the parser's job is to report the bubble's identity — chat, sender,
 * time, how many photos — plus the node to tap to open the viewer.
 *
 * Both parsers were derived from real tree dumps of the dedicated phone (Vivo V2126 / Android 14)
 * on 2026-09-22; see docs/reverse-engineering/ui-structure.md. Neither app exposes the image FILE
 * to us — the bytes only exist inside their private sandbox — so capture means driving their own
 * save/view UI and picking the file up out of the public gallery folder afterwards. That is
 * ImageSaver's job; this file only locates the bubbles.
 */
data class ImageBubble(
    val source: String,          // "VIBER" | "MESSENGER"
    val chat: String,
    val sender: String,
    val timeText: String,        // as shown in the UI ("3:52 PM"); "" when the app didn't say
    val count: Int,              // photos in this bubble
    val open: AccessibilityNodeInfo, // tap this to open the viewer on the FIRST VISIBLE photo
    val topPx: Int,              // top edge of the bubble on screen; negative/small = cut off above
) {
    /**
     * Stable identity for the bubble, so re-reading the same chat later recognises the album it
     * already saved instead of starting a second one. Deliberately does NOT include anything about
     * the image bytes — it has to be known before a single photo is saved.
     *
     * The apps show a time of day with no date, so the capture date is prepended. A bubble read
     * either side of midnight can therefore key differently; the cost is one duplicate album in the
     * backend on a chat read after 00:00, which is rarer and cheaper than the alternative of keying
     * on nothing and merging unrelated albums.
     */
    fun albumKey(): String = sha256("$source|$chat|$sender|${timestamp()}|$count")

    /** PH wall-clock ISO for the bubble: today's date at the shown time, else now. */
    fun timestamp(): String = ISO.format(Date(millis()))

    /** The bubble's moment in epoch millis — today's date at the time the app showed, else now. */
    fun millis(): Long {
        val now = Date()
        return (parseTimeToday(timeText, now) ?: now).time
    }

    companion object {
        private val ISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Manila")
        }
        private val DAY = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Manila")
        }
        private val DAY_TIME = SimpleDateFormat("yyyy-MM-dd h:mm a", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Manila")
            isLenient = true
        }

        /** "3:52 PM" → today at 3:52 PM, PH time. Null when it isn't a time we recognise. */
        private fun parseTimeToday(timeText: String, now: Date): Date? {
            val t = timeText.trim()
            if (!Regex("""^\d{1,2}:\d{2}\s*[AaPp][Mm]$""").matches(t)) return null
            return try {
                DAY_TIME.parse("${DAY.format(now)} ${t.uppercase(Locale.US)}")
            } catch (_: Exception) {
                null
            }
        }

        private fun sha256(s: String): String =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(s.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}

private fun AccessibilityNodeInfo.idName(): String =
    viewIdResourceName?.substringAfterLast('/') ?: ""

private fun AccessibilityNodeInfo.desc(): String = contentDescription?.toString() ?: ""

private fun AccessibilityNodeInfo.rect(): Rect = Rect().also { getBoundsInScreen(it) }

/** Depth-first pre-order = roughly top-to-bottom visual order, same walk MessageParsers uses. */
private fun walk(node: AccessibilityNodeInfo?, action: (AccessibilityNodeInfo) -> Unit) {
    node ?: return
    action(node)
    for (i in 0 until node.childCount) walk(node.getChild(i), action)
}

/**
 * MESSENGER — resource ids are obfuscated, so everything here keys off content-desc strings, which
 * are what the app exposes to TalkBack and change far less often than internal ids.
 *
 *   album tile   Button cd="Image"                     (2-4+ per bubble, one grid cell each)
 *   single photo Button cd="Received photo message"
 *   attribution  ImageView cd="Forward photo sent by <name> on <time>"  ← sender AND time, free
 *
 * "Sent photo message" is OUR OWN outgoing photo and is skipped: the point is capturing what the
 * field sends in, and saving our own uploads back off the screen is pure noise.
 */
object MessengerImageParser {
    private const val FORWARD_PREFIX = "Forward photo sent by "

    fun parse(root: AccessibilityNodeInfo, chat: String): List<ImageBubble> {
        // Album tiles grouped by their parent — one parent ViewGroup per album bubble.
        val albums = LinkedHashMap<AccessibilityNodeInfo, MutableList<AccessibilityNodeInfo>>()
        val singles = ArrayList<AccessibilityNodeInfo>()
        // Attribution nodes, kept with their vertical centre so a bubble can claim the one sitting
        // beside it (Messenger renders it as a sibling to the RIGHT of the bubble, not inside it).
        val attributions = ArrayList<Pair<Int, String>>()

        walk(root) { n ->
            when {
                n.desc() == "Image" -> {
                    val parent = n.parent
                    if (parent != null) albums.getOrPut(parent) { ArrayList() }.add(n)
                    else singles.add(n)   // orphan tile — treat as its own bubble rather than drop it
                }
                n.desc() == "Received photo message" -> singles.add(n)
                n.desc().startsWith(FORWARD_PREFIX) -> {
                    val r = n.rect()
                    attributions.add((r.top + r.bottom) / 2 to n.desc())
                }
            }
        }

        // "Forward photo sent by Ike Qluidato on 3:52 PM" → sender + time for whichever bubble the
        // node sits level with. Nearest vertical centre wins; a bubble with no attribution still
        // gets captured, just with a weaker album key.
        fun attributionFor(bounds: Rect): Pair<String, String> {
            val mid = (bounds.top + bounds.bottom) / 2
            val best = attributions.minByOrNull { kotlin.math.abs(it.first - mid) } ?: return "" to ""
            // Only trust it if it actually overlaps this bubble's vertical span — otherwise a bubble
            // with no attribution would steal the neighbouring bubble's sender.
            if (best.first < bounds.top || best.first > bounds.bottom) return "" to ""
            val body = best.second.removePrefix(FORWARD_PREFIX)
            val sender = body.substringBefore(" on ").trim()
            val time = body.substringAfter(" on ", "").trim()
            return sender to time
        }

        val out = ArrayList<ImageBubble>()
        for ((_, tiles) in albums) {
            // Grid order: top-to-bottom, then left-to-right — the order they were sent.
            val ordered = tiles.sortedWith(compareBy({ it.rect().top }, { it.rect().left }))
            val bounds = Rect()
            ordered.forEach { bounds.union(it.rect()) }
            val (sender, time) = attributionFor(bounds)
            out.add(ImageBubble("MESSENGER", chat, sender, time, ordered.size, ordered.first(), bounds.top))
        }
        for (n in singles) {
            val (sender, time) = attributionFor(n.rect())
            out.add(ImageBubble("MESSENGER", chat, sender, time, 1, n, n.rect().top))
        }
        return out.sortedBy { it.open.rect().top }
    }
}

/**
 * VIBER — resource ids are readable, so bubbles are found by id and the sender comes from the same
 * `nameView` inheritance rule the text parser already relies on: the name appears only on the FIRST
 * message of a consecutive run, and everything after it belongs to the last-seen sender.
 *
 *   imageView      the photo inside the bubble
 *   nameView       sender (groups only)
 *   timestampView  the bubble's time
 *
 * Viber shows album photos as separate bubbles rather than one grid, so every bubble here is a
 * single photo. Viber also writes the file to Pictures/Viber by ITSELF once the photo is on screen
 * (its Save-to-gallery setting), which is why the saver has less to do for this app, not more.
 */
object ViberImageParser {
    fun parse(root: AccessibilityNodeInfo, chat: String): List<ImageBubble> {
        val out = ArrayList<ImageBubble>()
        var currentSender = ""
        // Photos seen but not yet given a time: Viber renders timestampView AFTER the image within
        // the same row, so a bubble is completed by the next timestamp that follows it.
        var pending: AccessibilityNodeInfo? = null

        fun flush(time: String) {
            val n = pending ?: return
            pending = null
            out.add(ImageBubble("VIBER", chat, currentSender.ifBlank { chat }, time, 1, n, n.rect().top))
        }

        walk(root) { n ->
            when (n.idName()) {
                "nameView" -> n.text?.toString()?.let { if (it.isNotBlank()) currentSender = it }
                "imageView" -> {
                    flush("")             // previous photo never met a timestamp — keep it anyway
                    pending = n
                }
                "timestampView" -> flush(n.text?.toString() ?: "")
            }
        }
        flush("")
        return out
    }
}
