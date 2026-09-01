package com.audiodj.capture

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import androidx.core.content.ContextCompat
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Milestone 0 capture spike. Uses AudioPlaybackCapture (API 29+) to grab whatever
 * capturable apps are playing, reports a live dB level, and saves 10s WAV clips on demand.
 * Audio-only (never creates a VirtualDisplay) so it is exempt from the Android 15 QPR1+/16
 * keyguard auto-stop — lock the screen and the meter should keep moving.
 */
class AudioCaptureService : Service() {

    companion object {
        const val ACTION_START = "com.audiodj.capture.START"
        const val ACTION_STOP = "com.audiodj.capture.STOP"
        const val ACTION_SAVE = "com.audiodj.capture.SAVE"
        const val ACTION_PREFLIGHT = "com.audiodj.capture.PREFLIGHT" // Gate 2.6
        const val ACTION_LK_CONNECT = "com.audiodj.capture.LK_CONNECT"     // Gate 3.1 (service-owned LiveKit)
        const val ACTION_LK_DISCONNECT = "com.audiodj.capture.LK_DISCONNECT"
        const val ACTION_LEVEL = "com.audiodj.capture.LEVEL"
        const val ACTION_LOG = "com.audiodj.capture.LOG"
        const val EXTRA_RESULT_CODE = "rc"
        const val EXTRA_DATA = "data"
        const val SAMPLE_RATE = 48000
        const val CHANNELS = 2
        private const val CHANNEL_ID = "capture"
        private const val NOTIF_ID = 42
    }

    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    @Volatile private var running = false
    @Volatile private var stopping = false
    @Volatile private var saveRemainingFrames = 0
    @Volatile private var saveBuf: ByteArrayOutputStream? = null
    private var worker: Thread? = null

    // Debug: let `adb shell am broadcast -a com.audiodj.capture.DO_SAVE` grab a WAV
    // while another app (YouTube etc.) is foreground and playing.
    private val saveReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { requestSave() }
    }
    private var receiverRegistered = false

    // Gate 3.1: service-owned LiveKit session. LOCAL_PROOF (capture meter/WAV) and
    // LIVEKIT_PUBLISH must be mutually exclusive — never two AudioPlaybackCapture AudioRecords.
    private enum class Mode { IDLE, LOCAL_PROOF, LIVEKIT_PUBLISH }
    @Volatile private var mode = Mode.IDLE
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var lkSession: Gate2LiveKit? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopEverything(); stopSelf(); return START_NOT_STICKY }
            ACTION_SAVE -> { requestSave(); return START_NOT_STICKY }
            ACTION_LK_CONNECT -> { lkConnect(); return START_NOT_STICKY }
            ACTION_LK_DISCONNECT -> { lkDisconnect(); stopSelf(); return START_NOT_STICKY }
        }
        // A MediaProjection consent token cannot be recreated after process death, so never
        // sticky-restart (that would deliver a null intent with no token).
        if (intent == null) { log("null start intent (no projection token) — stopping"); stopSelf(); return START_NOT_STICKY }
        if (running) { log("already capturing — ignoring duplicate START"); return START_NOT_STICKY }
        if (mode == Mode.LIVEKIT_PUBLISH) { log("LiveKit session active — disconnect it before LOCAL_PROOF capture"); stopSelf(); return START_NOT_STICKY }
        stopping = false
        startForegroundCompat()
        try {
            val rc = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
            if (rc != Activity.RESULT_OK || data == null) { log("no projection token"); stopSelf(); return START_NOT_STICKY }
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mpm.getMediaProjection(rc, data)
            projection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { log("MediaProjection onStop() — capture ended by system/user"); stopEverything(); stopSelf() }
            }, Handler(Looper.getMainLooper()))
            if (intent.action == ACTION_PREFLIGHT) runPreflight() else startCapture()
        } catch (e: Exception) {
            log("START failed: ${e.javaClass.simpleName}: ${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** Gate 2.6: does LiveKit 2.27.0 ScreenAudioCapturer.initAudioRecord succeed on THIS device?
     *  (bytecode shows allocateDirect + a hasArray()==false rejection path — verify empirically). */
    private fun runPreflight() {
        try {
            val direct = java.nio.ByteBuffer.allocateDirect(1920)
            // On Android/ART direct buffers DO have a backing array (hasArray()==true), unlike a desktop JVM.
            log("directBuffer.hasArray=${direct.hasArray()} isDirect=${direct.isDirect}")
            val cap = io.livekit.android.audio.ScreenAudioCapturer(projection!!).apply { gain = 1.0f }
            val ok = cap.initAudioRecord(android.media.AudioFormat.ENCODING_PCM_16BIT, 2, 48_000)
            val ar = cap.javaClass.getDeclaredField("audioRecord").let {
                it.isAccessible = true; it.get(cap) as? android.media.AudioRecord
            }
            val state = ar?.state          // 1 = STATE_INITIALIZED
            val recState = ar?.recordingState  // 3 = RECORDSTATE_RECORDING
            android.util.Log.i("Gate26", "screenAudioInit=$ok state=$state recordingState=$recState")
            log("Gate2.6 screenAudioInit=$ok state=$state recordingState=$recState")
            cap.releaseAudioResources()
        } catch (e: Exception) {
            android.util.Log.e("Gate26", "preflight EXCEPTION", e)
            log("Gate2.6 preflight EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            stopEverything(); stopSelf()
        }
    }

    private fun startCapture() {
        try {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val bufBytes = maxOf(minBuf, SAMPLE_RATE * CHANNELS * 2 / 5) // ~200ms
            val r = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufBytes)
                .setAudioPlaybackCaptureConfig(config)
                .build()
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                log("AudioRecord NOT initialized — is RECORD_AUDIO granted?"); stopSelf(); return
            }
            record = r
            r.startRecording()
            running = true
            mode = Mode.LOCAL_PROOF
            // DEBUG-ONLY test hook (adb-triggerable). Never exported in release builds.
            if (BuildConfig.DEBUG && !receiverRegistered) {
                ContextCompat.registerReceiver(this, saveReceiver, IntentFilter("com.audiodj.capture.DO_SAVE"), ContextCompat.RECEIVER_EXPORTED)
                receiverRegistered = true
            }
            log("capture started (48kHz stereo). Play an app now; watch the meter.")
            worker = Thread { loop() }.also { it.start() }
        } catch (e: Exception) {
            log("startCapture failed: ${e.javaClass.simpleName}: ${e.message}")
            stopSelf()
        }
    }

    private fun loop() {
        val buf = ShortArray(4096)
        val monoSamples = ShortArray(FftCalculator.FFT_SIZE)
        val fftBands = FloatArray(FftCalculator.BANDS)
        var monoIndex = 0
        var lastUi = 0L
        var lastLog = 0L

        while (running) {
            val n = record?.read(buf, 0, buf.size) ?: -999
            if (n < 0) { android.util.Log.e("AuxCapture", "AudioRecord.read error=$n — stopping capture loop"); log("AudioRecord.read error=$n"); break }
            if (n == 0) continue
            var sumsq = 0.0
            var peak = 0
            for (i in 0 until n) {
                val s = buf[i].toInt()
                sumsq += (s * s).toDouble()
                val a = if (s < 0) -s else s
                if (a > peak) peak = a
            }

            // Downmix stereo to mono for FFT calculation
            var i = 0
            while (i < n - 1) {
                val monoVal = ((buf[i].toInt() + buf[i + 1].toInt()) / 2).toShort()
                monoSamples[monoIndex] = monoVal
                monoIndex = (monoIndex + 1) % FftCalculator.FFT_SIZE
                i += CHANNELS
            }

            val rms = sqrt(sumsq / n)
            val db = if (rms > 0) 20.0 * log10(rms / 32768.0) else -120.0
            val pdb = if (peak > 0) 20.0 * log10(peak / 32768.0) else -120.0
            val now = SystemClock.elapsedRealtime()
            if (now - lastUi >= 50) {
                FftCalculator.computeSpectrum(monoSamples, fftBands)
                broadcastLevel(db.toFloat(), pdb.toFloat(), fftBands, true)
                lastUi = now
            }
            if (now - lastLog >= 1000) {
                android.util.Log.i("AuxCapture", String.format(Locale.US, "level=%.1f dBFS peak=%.1f (%s)", db, pdb, if (db > -70) "AUDIO" else "silent"))
                lastLog = now
            }

            val sb = saveBuf
            if (saveRemainingFrames > 0 && sb != null) {
                val bytes = ByteArray(n * 2)
                for (i in 0 until n) {
                    val v = buf[i].toInt()
                    bytes[i * 2] = (v and 0xFF).toByte()
                    bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                }
                sb.write(bytes)
                saveRemainingFrames -= n / CHANNELS
                if (saveRemainingFrames <= 0) finalizeSave()
            }
        }
    }

    private fun requestSave() {
        if (!running) { log("not capturing — press Start Capture first"); return }
        saveBuf = ByteArrayOutputStream()
        saveRemainingFrames = SAMPLE_RATE * 10 // 10 seconds of frames
        log("recording 10s clip…")
    }

    private fun finalizeSave() {
        val sb = saveBuf ?: return
        saveBuf = null
        saveRemainingFrames = 0
        try {
            val pcm = sb.toByteArray()
            val f = File(getExternalFilesDir(null), "clip_${System.currentTimeMillis()}.wav")
            writeWav(f, pcm)
            log("saved ${pcm.size / 1024}KB WAV -> ${f.absolutePath}")
        } catch (e: Exception) {
            log("save failed: ${e.message}")
        }
    }

    private fun writeWav(f: File, pcm: ByteArray) {
        val byteRate = SAMPLE_RATE * CHANNELS * 2
        val dataLen = pcm.size
        fun i4(v: Int) = byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(), ((v shr 16) and 0xff).toByte(), ((v shr 24) and 0xff).toByte())
        fun i2(v: Int) = byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte())
        f.outputStream().use { o ->
            o.write("RIFF".toByteArray()); o.write(i4(36 + dataLen)); o.write("WAVE".toByteArray())
            o.write("fmt ".toByteArray()); o.write(i4(16)); o.write(i2(1)); o.write(i2(CHANNELS))
            o.write(i4(SAMPLE_RATE)); o.write(i4(byteRate)); o.write(i2(CHANNELS * 2)); o.write(i2(16))
            o.write("data".toByteArray()); o.write(i4(dataLen)); o.write(pcm)
        }
    }

    private fun stopEverything() {
        if (stopping) return          // guard against projection.stop() -> onStop() re-entry
        stopping = true
        running = false
        try { record?.stop() } catch (_: Exception) {}   // unblock any in-flight read() FIRST
        try { worker?.join(800) } catch (_: Exception) {}
        worker = null
        try { record?.release() } catch (_: Exception) {}
        record = null
        try { if (receiverRegistered) { unregisterReceiver(saveReceiver); receiverRegistered = false } } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        if (mode == Mode.LOCAL_PROOF) mode = Mode.IDLE
        broadcastLevel(-120f, -120f, null, false)
    }

    override fun onDestroy() {
        stopEverything()
        try { lkSession?.disconnect() } catch (_: Exception) {}
        lkSession = null
        try { serviceScope.cancel() } catch (_: Exception) {}
        super.onDestroy()
    }

    // Gate 3.1 — service owns the LiveKit Room (survives Activity backgrounding); publishes 0 tracks.
    private fun lkConnect() {
        if (running || mode == Mode.LOCAL_PROOF) { log("capture (LOCAL_PROOF) active — stop it before LiveKit connect"); stopSelf(); return }
        if (mode == Mode.LIVEKIT_PUBLISH) { log("LiveKit already connected"); return }
        mode = Mode.LIVEKIT_PUBLISH
        startForegroundMic()
        val api = BuildConfig.DEV_TOKEN_API.ifEmpty { "http://127.0.0.1:8790/dev/token" }
        lkSession = Gate2LiveKit(this) { m -> log(m) }
        lkSession!!.connect(serviceScope, api)
        log("service-owned LiveKit connect (Gate 3.1, publishing 0 tracks)")
    }

    private fun lkDisconnect() {
        lkSession?.disconnect()
        lkSession = null
        mode = Mode.IDLE
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        log("service-owned LiveKit disconnected")
    }

    private fun startForegroundMic() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Capture", NotificationManager.IMPORTANCE_LOW))
        }
        val notif: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("auxcrew — LiveKit session")
            .setContentText("Connected (0 tracks)")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else
            startForeground(NOTIF_ID, notif)
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Capture", NotificationManager.IMPORTANCE_LOW))
        }
        val notif: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AuxCapture running")
            .setContentText("Capturing playback audio")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else
            startForeground(NOTIF_ID, notif)
    }

    private fun broadcastLevel(db: Float, peak: Float, fft: FloatArray? = null, capturing: Boolean = true) {
        val intent = Intent(ACTION_LEVEL).apply {
            setPackage(packageName)
            putExtra("db", db)
            putExtra("peak", peak)
            putExtra("capturing", capturing)
            if (fft != null) {
                putExtra("fft", fft)
            }
        }
        sendBroadcast(intent)
    }

    private fun log(msg: String) {
        android.util.Log.i("AuxCapture", msg)
        sendBroadcast(Intent(ACTION_LOG).setPackage(packageName).putExtra("msg", msg))
    }
}
