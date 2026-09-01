package com.audiodj.capture

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.widget.Toast

class AudioMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Mode {
        VOLUME_METER,
        SPECTRUM_METER
    }

    var mode: Mode = Mode.VOLUME_METER
        set(value) {
            field = value
            invalidate()
        }

    private var dbLevel: Float = -120f
    private var peakDbLevel: Float = -120f
    private var fftBands = FloatArray(NUM_BANDS) { 0f }
    private var spectrumPeaks = FloatArray(NUM_BANDS) { 0f }
    private var isCapturing = false

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1a1d24")
        style = Paint.Style.FILL
    }

    private val meterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        strokeWidth = 3f
    }

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val rectF = RectF()
    private var linearGradient: LinearGradient? = null
    private var lastWidth = 0
    private var lastHeight = 0

    companion object {
        const val NUM_BANDS = 64
        private const val MIN_DB = -60f
        private const val MAX_DB = 3f
        private const val RANGE_DB = MAX_DB - MIN_DB // 63 dB
    }

    init {
        setOnClickListener {
            mode = if (mode == Mode.VOLUME_METER) Mode.SPECTRUM_METER else Mode.VOLUME_METER
            val msg = if (mode == Mode.VOLUME_METER) "Volume Meter" else "Frequency Spectrum (64 Bars)"
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    fun updateLevel(db: Float, peak: Float, capturing: Boolean) {
        this.dbLevel = db
        this.peakDbLevel = peak
        this.isCapturing = capturing
        invalidate()
    }

    fun updateData(db: Float, peak: Float, fft: FloatArray?, capturing: Boolean) {
        this.dbLevel = db
        this.peakDbLevel = peak
        this.isCapturing = capturing
        if (fft != null && fft.size == NUM_BANDS) {
            for (i in 0 until NUM_BANDS) {
                this.fftBands[i] = fft[i].coerceIn(0f, 1f)
                if (this.fftBands[i] > this.spectrumPeaks[i]) {
                    this.spectrumPeaks[i] = this.fftBands[i]
                } else {
                    this.spectrumPeaks[i] = (this.spectrumPeaks[i] * 0.92f).coerceAtLeast(this.fftBands[i])
                }
            }
        } else if (!capturing) {
            fftBands.fill(0f)
            spectrumPeaks.fill(0f)
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && (w != lastWidth || h != lastHeight)) {
            lastWidth = w
            lastHeight = h
            linearGradient = LinearGradient(
                0f, 0f, w.toFloat(), 0f,
                intArrayOf(
                    Color.parseColor("#10b981"), // Green
                    Color.parseColor("#eab308"), // Yellow
                    Color.parseColor("#ef4444")  // Red
                ),
                floatArrayOf(0.0f, 0.75f, 1.0f),
                Shader.TileMode.CLAMP
            )
            meterPaint.shader = linearGradient
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // Draw background container
        rectF.set(0f, 0f, w, h)
        canvas.drawRoundRect(rectF, 8f, 8f, bgPaint)

        if (mode == Mode.VOLUME_METER) {
            drawVolumeMeter(canvas, w, h)
        } else {
            drawSpectrumMeter(canvas, w, h)
        }
    }

    private fun drawVolumeMeter(canvas: Canvas, w: Float, h: Float) {
        if (!isCapturing && dbLevel <= MIN_DB) {
            return
        }

        val pct = ((dbLevel - MIN_DB) / RANGE_DB).coerceIn(0f, 1f)
        val fillWidth = w * pct

        if (fillWidth > 0) {
            rectF.set(0f, 0f, fillWidth, h)
            canvas.drawRoundRect(rectF, 8f, 8f, meterPaint)
        }

        // Draw peak hold line
        val peakPct = ((peakDbLevel - MIN_DB) / RANGE_DB).coerceIn(0f, 1f)
        val peakX = (w * peakPct).coerceIn(0f, w - 2f)
        if (peakPct > 0f) {
            canvas.drawLine(peakX, 0f, peakX, h, peakPaint)
        }
    }

    private fun drawSpectrumMeter(canvas: Canvas, w: Float, h: Float) {
        val barGap = 2f
        val totalGaps = (NUM_BANDS - 1) * barGap
        val barWidth = (w - totalGaps) / NUM_BANDS

        for (i in 0 until NUM_BANDS) {
            val left = i * (barWidth + barGap)
            val right = left + barWidth
            val valPct = if (isCapturing) fftBands[i] else 0f
            val barHeight = h * valPct
            val top = h - barHeight

            // Color gradient mapped across bar index (0: bass green -> mid yellow -> 63: treble red)
            val colorRatio = i.toFloat() / (NUM_BANDS - 1)
            val barColor = when {
                colorRatio < 0.5f -> blendColor(Color.parseColor("#10b981"), Color.parseColor("#eab308"), colorRatio / 0.5f)
                else -> blendColor(Color.parseColor("#eab308"), Color.parseColor("#ef4444"), (colorRatio - 0.5f) / 0.5f)
            }
            barPaint.color = barColor

            if (barHeight > 0) {
                rectF.set(left, top, right, h)
                canvas.drawRect(rectF, barPaint)
            }

            // Draw peak marker for spectrum
            if (isCapturing) {
                val peakPct = spectrumPeaks[i]
                if (peakPct > 0.02f) {
                    val peakY = h - (h * peakPct)
                    canvas.drawLine(left, peakY, right, peakY, peakPaint)
                }
            }
        }
    }

    private fun blendColor(color1: Int, color2: Int, ratio: Float): Int {
        val r = ratio.coerceIn(0f, 1f)
        val ir = 1f - r
        val a = (Color.alpha(color1) * ir + Color.alpha(color2) * r).toInt()
        val red = (Color.red(color1) * ir + Color.red(color2) * r).toInt()
        val g = (Color.green(color1) * ir + Color.green(color2) * r).toInt()
        val b = (Color.blue(color1) * ir + Color.blue(color2) * r).toInt()
        return Color.argb(a, red, g, b)
    }
}
