package com.tvl.incidentaliq.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import com.tvl.incidentaliq.capture.MonitorForegroundService
import com.tvl.incidentaliq.capture.UITreeAccessibilityService
import com.tvl.incidentaliq.core.Config
import com.tvl.incidentaliq.core.LogBus
import com.tvl.incidentaliq.core.Monitoring
import com.tvl.incidentaliq.core.WakeLockHelper
import com.tvl.incidentaliq.data.MessageStore
import com.tvl.incidentaliq.databinding.ActivityMainBinding
import com.tvl.incidentaliq.sync.Uploader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // MASTER SWITCH. Monitoring ON/OFF now drives BOTH capture (the Monitoring flag the
        // NotificationListener checks) AND the foreground keep-alive service (heartbeat) as ONE unit —
        // so you can never land in the split-brain state (monitoring on, service dead) that let capture
        // run unprotected. ON → set flag + start service; OFF → clear flag + stop service.
        binding.btnToggleMonitor.setOnClickListener {
            val next = !Monitoring.isEnabled(this)
            Monitoring.setEnabled(this, next)
            if (next) {
                startForegroundService(Intent(this, MonitorForegroundService::class.java))
                log("Monitoring ON — capture + keep-alive service started")
            } else {
                stopService(Intent(this, MonitorForegroundService::class.java))
                log("Monitoring OFF — capture stopped + keep-alive service stopped")
            }
            refreshMonitorButton()
        }
        refreshMonitorButton()

        // Opening the app self-heals the service: if monitoring is ON but the service died (e.g. it was
        // killed and never restarted), just launching the app brings it back.
        if (Monitoring.isEnabled(this)) {
            startForegroundService(Intent(this, MonitorForegroundService::class.java))
        }

        // Manual backup control (kept as a force-restart; the master switch above is the real control).
        binding.btnStartService.setOnClickListener {
            startForegroundService(Intent(this, MonitorForegroundService::class.java))
            log("Foreground service started (manual)")
        }

        binding.btnWakeScreen.setOnClickListener {
            WakeLockHelper.wake(this)
            log("Wake screen triggered")
        }

        binding.btnSyncNow.setOnClickListener {
            val pending = MessageStore.pendingCount(this)
            if (!Config.isConfigured(this)) {
                log("Sync: backend URL not set yet — $pending message(s) buffered, nothing uploaded")
            } else {
                log("Sync: uploading now — $pending message(s) buffered")
                Uploader.syncNow(this)
            }
        }

        // Tracked group editors: load the saved lists, save back on tap.
        binding.etViberGroups.setText(Config.trackedGroupsText(this, "VIBER"))
        binding.etMessengerGroups.setText(Config.trackedGroupsText(this, "MESSENGER"))
        binding.btnSaveGroups.setOnClickListener {
            Config.setTrackedGroups(this, "VIBER", binding.etViberGroups.text.toString())
            Config.setTrackedGroups(this, "MESSENGER", binding.etMessengerGroups.text.toString())
            val v = Config.trackedGroups(this, "VIBER").size
            val m = Config.trackedGroups(this, "MESSENGER").size
            log("Saved tracked groups — Viber: $v, Messenger: $m  (0 = capture all)")
        }

        binding.btnDumpTree.setOnClickListener {
            val svc = UITreeAccessibilityService.instance
            if (svc == null) {
                log("Accessibility service not running — opening settings")
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } else {
                svc.dumpTree()
                log("Dump triggered — check logcat for TREE tag")
            }
        }

        LogBus.listener = { msg -> runOnUiThread { log(msg) } }
        log("App started")
        checkPermissions()
    }

    private fun refreshMonitorButton() {
        val on = Monitoring.isEnabled(this)
        binding.btnToggleMonitor.text = if (on) "MONITORING: ON  (tap to stop)" else "MONITORING: OFF  (tap to start)"
    }

    private fun checkPermissions() {
        val nlEnabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true
        if (!nlEnabled) {
            log("MISSING: Notification listener — go to Settings > Notification Access")
        } else {
            log("OK: Notification listener enabled")
        }
        if (UITreeAccessibilityService.instance == null) {
            log("MISSING: Accessibility service — tap Dump Tree to open settings")
        } else {
            log("OK: Accessibility service enabled")
        }
    }

    private fun log(msg: String) {
        binding.tvLog.append("[${sdf.format(Date())}] $msg\n")
        // Keep only the most recent lines on screen so the view doesn't grow forever.
        val text = binding.tvLog.text
        val lines = text.count { it == '\n' }
        if (lines > MAX_UI_LINES) {
            var idx = 0
            var toDrop = lines - KEEP_UI_LINES   // drop the oldest lines
            while (toDrop-- > 0) idx = text.indexOf('\n', idx) + 1
            binding.tvLog.text = text.subSequence(idx, text.length)
        }
        binding.scrollLog.post { binding.scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    companion object {
        private const val MAX_UI_LINES = 500   // trim the on-screen log once it passes this
        private const val KEEP_UI_LINES = 300  // …down to this many most-recent lines
    }

    override fun onDestroy() {
        super.onDestroy()
        LogBus.listener = null
    }
}
