package com.tvl.incidentaliq.core

import android.content.Context
import com.tvl.incidentaliq.BuildConfig

/**
 * Backend connection config. The Worker URL is stored in SharedPreferences so it can be changed
 * at runtime (after deploy) without rebuilding the APK. Set it with `setBackendUrl(...)` or via
 * adb:  adb shell am ... (or the in-app field). Leave blank until the backend is deployed —
 * the uploader no-ops while it's blank.
 */
object Config {
    private const val PREFS = "tripops_prefs"
    private const val KEY_URL = "backend_url"
    private const val KEY_TOKEN = "api_token"
    // Separate tracked-group lists per app. Newline-separated names, stored as one string each.
    private const val KEY_GROUPS_VIBER = "tracked_groups_viber"
    private const val KEY_GROUPS_MESSENGER = "tracked_groups_messenger"
    // Store-only group lists per app: captured + uploaded like normal, but flagged so the backend
    // NEVER sends them to Groq. A raw archive channel. Newline-separated names, one string each.
    private const val KEY_STORE_VIBER = "store_only_groups_viber"
    private const val KEY_STORE_MESSENGER = "store_only_groups_messenger"
    // Immediate-upload group lists per app: same "never sent to Groq" archive as store-only, but ALSO
    // triggers a one-off upload as soon as the message is captured, instead of waiting for the ~30 min
    // periodic worker. For chats where you need it to land in the database right away.
    private const val KEY_IMMEDIATE_VIBER = "immediate_groups_viber"
    private const val KEY_IMMEDIATE_MESSENGER = "immediate_groups_messenger"

    // Deployed backend base URL — the home server, not the old Cloudflare Worker. Stable + public
    // (not a secret), so it's fine hardcoded here. setBackendUrl(...) can still override it at
    // runtime if the URL ever changes.
    private const val DEFAULT_URL = "https://tvl-incidentaliq.bowfin-escalator.ts.net"
    // Shared secret sent as `Authorization: Bearer <token>` — must match the Worker's API_TOKEN.
    // Comes from BuildConfig (fed by gitignored local.properties), so it's compiled into the APK
    // but never committed to source control. setApiToken(...) can still override at runtime.
    private val DEFAULT_TOKEN = BuildConfig.API_TOKEN

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun backendUrl(ctx: Context): String =
        prefs(ctx).getString(KEY_URL, DEFAULT_URL)?.trim()?.trimEnd('/') ?: ""

    fun setBackendUrl(ctx: Context, url: String) {
        prefs(ctx).edit().putString(KEY_URL, url.trim().trimEnd('/')).apply()
        AppLog.write("CFG", "backend URL set to ${url.trim()}")
    }

    fun apiToken(ctx: Context): String =
        prefs(ctx).getString(KEY_TOKEN, DEFAULT_TOKEN)?.trim() ?: ""

    fun setApiToken(ctx: Context, token: String) {
        prefs(ctx).edit().putString(KEY_TOKEN, token.trim()).apply()
        AppLog.write("CFG", "API token set (${token.trim().length} chars)")
    }

    // Ready to upload only when BOTH the URL and the token are set.
    fun isConfigured(ctx: Context): Boolean = backendUrl(ctx).isNotBlank() && apiToken(ctx).isNotBlank()

    // ── Tracked group chats ────────────────────────────────────────────────
    // Two independent lists (Viber vs Messenger) because a group can share a name across apps,
    // and the notification's source already tells us which app fired. Each list is a newline-
    // separated block of names, edited in the UI and persisted here.

    private fun groupsKey(source: String) =
        if (source.equals("VIBER", true)) KEY_GROUPS_VIBER else KEY_GROUPS_MESSENGER

    /** The tracked group names for one app ("VIBER" | "MESSENGER"). Empty = track ALL. */
    fun trackedGroups(ctx: Context, source: String): Set<String> =
        prefs(ctx).getString(groupsKey(source), "")
            ?.split("\n")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet() ?: emptySet()

    /** Save the tracked group names for one app from a raw multi-line text block. */
    fun setTrackedGroups(ctx: Context, source: String, raw: String) {
        val cleaned = raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        prefs(ctx).edit().putString(groupsKey(source), cleaned.joinToString("\n")).apply()
        AppLog.write("CFG", "$source tracked groups set: ${cleaned.size} (${cleaned.joinToString(" | ")})")
    }

    /** The saved list rendered back as one editable text block (one name per line). */
    fun trackedGroupsText(ctx: Context, source: String): String =
        prefs(ctx).getString(groupsKey(source), "") ?: ""

    /**
     * Should we capture this notification? [source] is "VIBER"|"MESSENGER"; [candidates] are the
     * strings that might hold the group name (conversation title, subtext, notif title). An EMPTY
     * tracked list means "capture NOTHING" for that app — fail closed. This is deliberate: the phone
     * sits in many of the owner's PERSONAL chats, so a wiped/unconfigured list must never fall back to
     * hoovering up (and uploading) every private conversation. Match is case-insensitive: true if any
     * candidate string CONTAINS a tracked name (a distinctive substring is enough, and extra
     * decoration around it doesn't break it).
     */
    fun isGroupTracked(ctx: Context, source: String, candidates: List<String>): Boolean {
        val tracked = trackedGroups(ctx, source)
        if (tracked.isEmpty()) return false   // fail closed — never capture untracked personal chats
        val cands = candidates.filter { it.isNotBlank() }.map { it.lowercase() }
        return tracked.any { t ->
            val tl = t.lowercase()
            cands.any { c -> c.contains(tl) }
        }
    }

    // ── Store-only group chats ──────────────────────────────────────────────
    // Mirror of the tracked lists, but for groups we ARCHIVE without classifying. A store-only group
    // is captured + uploaded exactly like a tracked one; the only difference is its messages carry a
    // store_only flag so the backend cron never feeds them to Groq. Same per-app split + matching.

    private fun storeKey(source: String) =
        if (source.equals("VIBER", true)) KEY_STORE_VIBER else KEY_STORE_MESSENGER

    fun storeOnlyGroups(ctx: Context, source: String): Set<String> =
        prefs(ctx).getString(storeKey(source), "")
            ?.split("\n")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet() ?: emptySet()

    fun setStoreOnlyGroups(ctx: Context, source: String, raw: String) {
        val cleaned = raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        prefs(ctx).edit().putString(storeKey(source), cleaned.joinToString("\n")).apply()
        AppLog.write("CFG", "$source store-only groups set: ${cleaned.size} (${cleaned.joinToString(" | ")})")
    }

    fun storeOnlyGroupsText(ctx: Context, source: String): String =
        prefs(ctx).getString(storeKey(source), "") ?: ""

    /**
     * Is this notification's group an archive-only one? Same substring match as [isGroupTracked], but
     * an EMPTY list simply means "no store-only groups" → returns false (safe default; unlike the
     * tracked list there's no personal-chat risk to fail closed against here).
     */
    fun isStoreOnly(ctx: Context, source: String, candidates: List<String>): Boolean {
        val store = storeOnlyGroups(ctx, source)
        if (store.isEmpty()) return false
        val cands = candidates.filter { it.isNotBlank() }.map { it.lowercase() }
        return store.any { t ->
            val tl = t.lowercase()
            cands.any { c -> c.contains(tl) }
        }
    }

    // ── Immediate-upload group chats ─────────────────────────────────────────
    // A third list: same store-only archive semantics (never sent to Groq — see [isStoreOnly]), but
    // capture also fires an immediate upload instead of waiting for the periodic worker. Kept as its
    // own list rather than a modifier on the other two so a chat's urgency is one line in one box, not
    // something you have to also remember to add to store-only.

    private fun immediateKey(source: String) =
        if (source.equals("VIBER", true)) KEY_IMMEDIATE_VIBER else KEY_IMMEDIATE_MESSENGER

    fun immediateGroups(ctx: Context, source: String): Set<String> =
        prefs(ctx).getString(immediateKey(source), "")
            ?.split("\n")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet() ?: emptySet()

    fun setImmediateGroups(ctx: Context, source: String, raw: String) {
        val cleaned = raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        prefs(ctx).edit().putString(immediateKey(source), cleaned.joinToString("\n")).apply()
        AppLog.write("CFG", "$source immediate groups set: ${cleaned.size} (${cleaned.joinToString(" | ")})")
    }

    fun immediateGroupsText(ctx: Context, source: String): String =
        prefs(ctx).getString(immediateKey(source), "") ?: ""

    /** Is this notification's group flagged for immediate upload? Same substring match as the others. */
    fun isImmediate(ctx: Context, source: String, candidates: List<String>): Boolean {
        val immediate = immediateGroups(ctx, source)
        if (immediate.isEmpty()) return false
        val cands = candidates.filter { it.isNotBlank() }.map { it.lowercase() }
        return immediate.any { t ->
            val tl = t.lowercase()
            cands.any { c -> c.contains(tl) }
        }
    }
}
