package com.tvl.incidentaliq

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import com.tvl.incidentaliq.databinding.ActivityMainBinding
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

        binding.btnStartService.setOnClickListener {
            startForegroundService(Intent(this, MonitorForegroundService::class.java))
            log("Foreground service started")
        }

        binding.btnWakeScreen.setOnClickListener {
            WakeLockHelper.wake(this)
            log("Wake screen triggered")
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
        binding.scrollLog.post { binding.scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        super.onDestroy()
        LogBus.listener = null
    }
}
