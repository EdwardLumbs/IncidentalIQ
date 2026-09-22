package com.tvl.incidentaliq.core

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * The two grants photo capture needs, and why neither can be avoided.
 *
 * READ (photos): Viber and Messenger save into the PUBLIC gallery folders, not into our app's
 * storage, so reading what they just wrote is reading someone else's media.
 *
 * WRITE/DELETE ("All files access", MANAGE_EXTERNAL_STORAGE): since Android 11 an app may not
 * delete another app's media file without either this grant or a per-file confirmation dialog.
 * A confirmation dialog is useless on a phone that sits unattended in a drawer — it would block
 * the read cycle waiting for a tap nobody is there to make. Without the grant everything still
 * WORKS, photos still upload; they just accumulate in the gallery instead of being cleaned up.
 *
 * This is a sideloaded APK on a dedicated device, so the Play Store's restrictions on
 * MANAGE_EXTERNAL_STORAGE don't apply. Both are one-time toggles in Settings.
 */
object ImagePermissions {

    /** Can we READ what Viber/Messenger saved? Without this, capture cannot work at all. */
    fun canRead(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) return true
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
                   else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Can we DELETE them afterwards? Without this the phone slowly fills; nothing else breaks.
     *
     * Takes a Context it doesn't read, to match canRead(ctx) at the call sites — this is a pair of
     * checks callers always ask together, and one of them silently not needing the argument is a
     * worse surprise than an unused parameter.
     */
    @Suppress("UNUSED_PARAMETER")
    fun canDelete(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

    /** The permission this build asks for at runtime (the All-files toggle is Settings-only). */
    fun readPermission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    /** Settings screen for the All-files toggle, scoped to this app where the OS allows it. */
    fun allFilesSettingsIntent(ctx: Context): Intent =
        if (Build.VERSION.SDK_INT >= 30) {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${ctx.packageName}"),
            )
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
        }

    /** One line for the status log / UI. */
    fun status(ctx: Context): String = when {
        !canRead(ctx) -> "MISSING: photo access — photo capture is OFF (grant Photos, then All files access)"
        !canDelete(ctx) -> "OK: photo capture on — but no All files access, so saved photos stay on the phone"
        else -> "OK: photo capture enabled (read + delete)"
    }
}
