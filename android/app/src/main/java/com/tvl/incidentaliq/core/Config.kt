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

    // Default backend base URL. Fill in your deployed Worker URL here, or set at runtime.
    // e.g. "https://tripops-monitor.<your-subdomain>.workers.dev"
    private const val DEFAULT_URL = ""

    fun backendUrl(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_URL, DEFAULT_URL)
            ?.trim()?.trimEnd('/') ?: ""

    fun setBackendUrl(ctx: Context, url: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_URL, url.trim().trimEnd('/')).apply()
        AppLog.write("CFG", "backend URL set to ${url.trim()}")
    }

    fun isConfigured(ctx: Context): Boolean = backendUrl(ctx).isNotBlank()
}
