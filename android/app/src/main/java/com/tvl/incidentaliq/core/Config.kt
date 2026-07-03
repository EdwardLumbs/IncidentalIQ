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
}
