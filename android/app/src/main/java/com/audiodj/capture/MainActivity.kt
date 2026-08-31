package com.audiodj.capture

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioPlaybackConfiguration
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var levelText: TextView
    private lateinit var peakText: TextView
    private lateinit var levelBar: ProgressBar
    private lateinit var activeSourcesText: TextView
    private lateinit var logText: TextView
    private lateinit var logScrollView: ScrollView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var saveBtn: Button
    private lateinit var btnCopyLog: Button
    private lateinit var btnClearLog: Button
    private lateinit var mpm: MediaProjectionManager
    private lateinit var audioManager: AudioManager
    private lateinit var gate2: Gate2LiveKit
    private val ts = SimpleDateFormat("HH:mm:ss", Locale.US)
    // dev.token.api comes from local.properties (gitignored) via BuildConfig — no LAN IP in source.
    private val tokenApi = BuildConfig.DEV_TOKEN_API.ifEmpty { "http://127.0.0.1:8790/dev/token" }

    private var preflightMode = false

    private val playbackCallback = if (Build.VERSION.SDK_INT >= 26) {
        object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>?) {
                updateActiveAudioSources(configs)
            }
        }
    } else null

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
                statusText.text = "CAPTURING AUDIO"
                statusText.setTextColor(ContextCompat.getColor(this, R.color.accent_green_stroke))
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

        statusText = findViewById(R.id.statusText)
        levelText = findViewById(R.id.levelText)
        peakText = findViewById(R.id.peakText)
        levelBar = findViewById(R.id.levelBar)
        activeSourcesText = findViewById(R.id.activeSourcesText)
        logText = findViewById(R.id.logText)
        logScrollView = findViewById(R.id.logScrollView)
        startBtn = findViewById(R.id.startBtn)
        stopBtn = findViewById(R.id.stopBtn)
        saveBtn = findViewById(R.id.saveBtn)
        btnCopyLog = findViewById(R.id.btnCopyLog)
        btnClearLog = findViewById(R.id.btnClearLog)
        mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        setupCollapsiblePanels()
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
            statusText.text = getString(R.string.status_ready)
            statusText.setTextColor(ContextCompat.getColor(this, R.color.text_green))
            levelText.text = "—"
            peakText.text = "peak: —"
            levelBar.progress = 0
            appendLog("stopped")
        }
        saveBtn.setOnClickListener {
            startService(Intent(this, AudioCaptureService::class.java).setAction(AudioCaptureService.ACTION_SAVE))
        }
        btnCopyLog.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("AuxCapture Logs", logText.text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
        }
        btnClearLog.setOnClickListener {
            logText.text = ""
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

        appendLog("Ready. Tap START CAPTURE, approve permissions, then play music.")
    }

    private fun setupCollapsiblePanels() {
        setupPanel(R.id.headerServiceConnections, R.id.contentServiceConnections, R.id.chevronServiceConnections)
        setupPanel(R.id.headerCaptureSetup, R.id.contentCaptureSetup, R.id.chevronCaptureSetup)
        setupPanel(R.id.headerStatusLog, R.id.contentStatusLog, R.id.chevronStatusLog)
    }

    private fun setupPanel(headerId: Int, contentId: Int, chevronId: Int) {
        val header = findViewById<View>(headerId)
        val content = findViewById<View>(contentId)
        val chevron = findViewById<ImageView>(chevronId)

        header.setOnClickListener {
            if (content.visibility == View.VISIBLE) {
                content.visibility = View.GONE
                chevron.rotation = 0f
            } else {
                content.visibility = View.VISIBLE
                chevron.rotation = 180f
            }
        }
    }

    private fun updateActiveAudioSources(configs: List<AudioPlaybackConfiguration>?) {
        if (Build.VERSION.SDK_INT < 26) return
        val currentConfigs = configs ?: audioManager.activePlaybackConfigurations
        val activeApps = mutableSetOf<String>()

        for (config in currentConfigs) {
            val usage = config.audioAttributes.usage
            if (usage == AudioAttributes.USAGE_MEDIA ||
                usage == AudioAttributes.USAGE_GAME ||
                usage == AudioAttributes.USAGE_UNKNOWN) {

                val pkgName = getPackageNameFromConfig(config)
                if (!pkgName.isNullOrEmpty() && pkgName != packageName) {
                    val appLabel = getAppName(pkgName)
                    activeApps.add(appLabel)
                }
            }
        }

        runOnUiThread {
            if (activeApps.isEmpty()) {
                activeSourcesText.text = "None"
                activeSourcesText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            } else {
                activeSourcesText.text = activeApps.joinToString(", ")
                activeSourcesText.setTextColor(ContextCompat.getColor(this, R.color.accent_green_stroke))
            }
        }
    }

    private fun getPackageNameFromConfig(config: AudioPlaybackConfiguration): String? {
        return try {
            val method = config.javaClass.getMethod("getClientUid")
            val uid = method.invoke(config) as? Int ?: return null
            packageManager.getPackagesForUid(uid)?.firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private fun getAppName(packageName: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            packageName
        }
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

        if (Build.VERSION.SDK_INT >= 26 && playbackCallback != null) {
            audioManager.registerAudioPlaybackCallback(playbackCallback, null)
            updateActiveAudioSources(audioManager.activePlaybackConfigurations)
        }
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= 26 && playbackCallback != null) {
            audioManager.unregisterAudioPlaybackCallback(playbackCallback)
        }
    }

    private fun appendLog(m: String) {
        runOnUiThread {
            logText.append("${ts.format(Date())} - $m\n")
            logScrollView.post { logScrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }
}
