package com.tvl.incidentaliq.core

import android.content.Context

/**
 * Master ON/OFF switch for capture. When OFF, the NotificationListener ignores everything,
 * so nothing is read or saved. Persisted in SharedPreferences so it survives app restarts.
 * Default = ON.
 */
object Monitoring {
    private const val PREFS = "tripops_prefs"
    private const val KEY = "monitoring_enabled"

    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, enabled).apply()
        AppLog.write("MON", "monitoring turned ${if (enabled) "ON" else "OFF"}")
    }
}
