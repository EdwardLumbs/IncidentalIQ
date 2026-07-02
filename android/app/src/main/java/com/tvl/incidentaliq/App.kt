package com.tvl.incidentaliq

import android.app.Application
import com.tvl.incidentaliq.sync.Uploader

class App : Application() {
    companion object {
        lateinit var instance: App
    }
    override fun onCreate() {
        super.onCreate()
        instance = this
        // Guaranteed background upload of buffered messages (every ~30 min, network-aware).
        Uploader.schedulePeriodic(this)
    }
}
