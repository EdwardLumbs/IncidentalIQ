package com.tvl.incidentaliq

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val file get() = File(App.instance.filesDir, "monitor.log")

    @Synchronized
    fun write(tag: String, msg: String) {
        val line = "${sdf.format(Date())} [$tag] $msg"
        Log.d(tag, msg)
        LogBus.post("[$tag] $msg")
        try {
            file.appendText("$line\n")
        } catch (e: Exception) {
            Log.e(tag, "Failed to write to monitor.log: ${e.message}")
        }
    }
}
