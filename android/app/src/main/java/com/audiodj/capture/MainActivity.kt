package com.audiodj.capture

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var levelText: TextView
    private lateinit var peakText: TextView
    private lateinit var levelBar: ProgressBar
    private lateinit var logText: TextView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var saveBtn: Button
    private lateinit var mpm: MediaProjectionManager
    private lateinit var gate2: Gate2LiveKit
    private val ts = SimpleDateFormat("HH:mm:ss", Locale.US)
    // dev.token.api comes from local.properties (gitignored) via BuildConfig — no LAN IP in source.
    private val tokenApi = BuildConfig.DEV_TOKEN_API.ifEmpty { "http://127.0.0.1:8790/dev/token" }

    private var preflightMode = false

    private val projLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val pf = preflightMode; preflightMode = false
        if (res.resultCode == Activity.RESULT_OK && res.data != null) {
            val i = Intent(this, AudioCaptureService::class.java).apply {
                action = if (pf) AudioCaptureService.ACTION_PREFLIGHT else AudioCaptureService.ACTION_START
                putExtra(AudioCaptureService.EXTRA_RESULT_CODE, res.resultCode)
                putExtra(AudioCaptureService.EXTRA_DATA, res.data)
            }
            ContextCompat.startForegroundService(this, i)
            if (!pf) {
                startBtn.isEnabled = false
                stopBtn.isEnabled = true
                saveBtn.isEnabled = true
            }
            appendLog(if (pf) "Gate2.6 preflight requested — allow the prompt" else "capture requested — allow the system prompt")
        } else {
            appendLog("projection permission cancelled")
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                AudioCaptureService.ACTION_LEVEL -> {
                    val db = i.getFloatExtra("db", -120f)
                    val pk = i.getFloatExtra("peak", -120f)
                    levelText.text = String.format(Locale.US, "%.0f dBFS", db)
                    peakText.text = String.format(Locale.US, "peak %.0f dBFS", pk)
                    val pct = (((db + 80f) / 80f) * 100f).coerceIn(0f, 100f)
                    levelBar.progress = pct.toInt()
                }
                AudioCaptureService.ACTION_LOG -> appendLog(i.getStringExtra("msg") ?: "")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        levelText = findViewById(R.id.levelText)
        peakText = findViewById(R.id.peakText)
        levelBar = findViewById(R.id.levelBar)
        logText = findViewById(R.id.logText)
        startBtn = findViewById(R.id.startBtn)
        stopBtn = findViewById(R.id.stopBtn)
        saveBtn = findViewById(R.id.saveBtn)
        mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        requestNeededPermissions()

        startBtn.setOnClickListener {
            if (!hasMic()) {
                appendLog("need RECORD_AUDIO — granting…")
                requestNeededPermissions()
                return@setOnClickListener
            }
            projLauncher.launch(mpm.createScreenCaptureIntent())
        }
        stopBtn.setOnClickListener {
            startService(Intent(this, AudioCaptureService::class.java).setAction(AudioCaptureService.ACTION_STOP))
            startBtn.isEnabled = true
            stopBtn.isEnabled = false
            saveBtn.isEnabled = false
            levelText.text = "—"
            peakText.text = "peak: —"
            levelBar.progress = 0
            appendLog("stopped")
        }
        saveBtn.setOnClickListener {
            startService(Intent(this, AudioCaptureService::class.java).setAction(AudioCaptureService.ACTION_SAVE))
        }
        // Gate 2 — LiveKit connect-only (no capture, no publish)
        gate2 = Gate2LiveKit(this) { m -> android.util.Log.i("Gate2", m); appendLog(m) }
        findViewById<Button>(R.id.gate2ConnectBtn).setOnClickListener {
            gate2.connect(lifecycleScope, tokenApi)
        }
        findViewById<Button>(R.id.gate2DisconnectBtn).setOnClickListener {
            gate2.disconnect()
        }
        findViewById<Button>(R.id.gate26Btn).setOnClickListener {
            if (!hasMic()) { requestNeededPermissions(); return@setOnClickListener }
            preflightMode = true
            projLauncher.launch(mpm.createScreenCaptureIntent())
        }
        // Gate 3.1 — service-owned LiveKit session (survives Activity backgrounding), 0 tracks
        findViewById<Button>(R.id.gate31ConnectBtn).setOnClickListener {
            ContextCompat.startForegroundService(this, Intent(this, AudioCaptureService::class.java).setAction(AudioCaptureService.ACTION_LK_CONNECT))
            appendLog("G3.1: service LiveKit connect requested")
        }
        findViewById<Button>(R.id.gate31DisconnectBtn).setOnClickListener {
            startService(Intent(this, AudioCaptureService::class.java).setAction(AudioCaptureService.ACTION_LK_DISCONNECT))
            appendLog("G3.1: service LiveKit disconnect requested")
        }

        appendLog("ready. Tap Start Capture, allow the prompt, then play music.")
    }

    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestNeededPermissions() {
        val perms = mutableListOf(android.Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms.add(android.Manifest.permission.POST_NOTIFICATIONS)
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 1)
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(AudioCaptureService.ACTION_LEVEL)
            addAction(AudioCaptureService.ACTION_LOG)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
    }

    private fun appendLog(m: String) {
        runOnUiThread { logText.append("${ts.format(Date())}  $m\n") }
    }
}
