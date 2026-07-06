package com.tvl.incidentaliq.core

import android.content.Context

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

    // Default backend base URL. Fill in your deployed Worker URL here, or set at runtime.
    // e.g. "https://tripops-monitor.<your-subdomain>.workers.dev"
    private const val DEFAULT_URL = ""
    // Shared secret sent as `Authorization: Bearer <token>` — must match the Worker's API_TOKEN.
    // Set at runtime with setApiToken(...) so it isn't baked into source control.
    private const val DEFAULT_TOKEN = ""

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
     * tracked list means "capture everything" so nothing is dropped before you configure it. Match
     * is case-insensitive: true if any candidate string CONTAINS a tracked name (so a distinctive
     * substring of the group name is enough, and extra decoration around it doesn't break it).
     */
    fun isGroupTracked(ctx: Context, source: String, candidates: List<String>): Boolean {
        val tracked = trackedGroups(ctx, source)
        if (tracked.isEmpty()) return true
        val cands = candidates.filter { it.isNotBlank() }.map { it.lowercase() }
        return tracked.any { t ->
            val tl = t.lowercase()
            cands.any { c -> c.contains(tl) }
        }
    }
}
