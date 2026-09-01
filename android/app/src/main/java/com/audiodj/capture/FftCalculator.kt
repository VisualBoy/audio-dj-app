package com.audiodj.capture

import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Fast Fourier Transform (FFT) utility for processing audio PCM samples into 64 log-spaced frequency spectrum magnitude bands.
 */
object FftCalculator {

    const val FFT_SIZE = 1024
    const val BANDS = 64

    private val cosTable = FloatArray(FFT_SIZE / 2)
    private val sinTable = FloatArray(FFT_SIZE / 2)
    private val window = FloatArray(FFT_SIZE)
    private val bitRev = IntArray(FFT_SIZE)

    init {
        // Precalculate Hann window
        for (i in 0 until FFT_SIZE) {
            window[i] = (0.5 * (1.0 - cos(2.0 * Math.PI * i / (FFT_SIZE - 1)))).toFloat()
        }

        // Precalculate Trig tables
        for (i in 0 until FFT_SIZE / 2) {
            val angle = -2.0 * Math.PI * i / FFT_SIZE
            cosTable[i] = cos(angle).toFloat()
            sinTable[i] = sin(angle).toFloat()
        }

        // Precalculate Bit Reversal table
        val bits = Integer.numberOfTrailingZeros(FFT_SIZE)
        for (i in 0 until FFT_SIZE) {
            var rev = 0
            for (j in 0 until bits) {
                if ((i and (1 shl j)) != 0) {
                    rev = rev or (1 shl (bits - 1 - j))
                }
            }
            bitRev[i] = rev
        }
    }

    /**
     * Compute 64 logarithmic frequency spectrum bands (values 0.0 to 1.0) from mono short PCM samples.
     */
    fun computeSpectrum(monoSamples: ShortArray, outBands: FloatArray) {
        if (outBands.size != BANDS) return
        val count = minOf(monoSamples.size, FFT_SIZE)
        val real = FloatArray(FFT_SIZE)
        val imag = FloatArray(FFT_SIZE)

        // Apply window function and bit reversal
        for (i in 0 until count) {
            val rev = bitRev[i]
            real[rev] = monoSamples[i] * window[i]
            imag[rev] = 0f
        }

        // Cooley-Tukey Radix-2 FFT
        var size = 2
        while (size <= FFT_SIZE) {
            val halfSize = size / 2
            val step = FFT_SIZE / size
            for (i in 0 until FFT_SIZE step size) {
                for (j in 0 until halfSize) {
                    val k = j * step
                    val c = cosTable[k]
                    val s = sinTable[k]
                    val re = real[i + j + halfSize]
                    val im = imag[i + j + halfSize]

                    val tReal = re * c - im * s
                    val tImag = re * s + im * c

                    real[i + j + halfSize] = real[i + j] - tReal
                    imag[i + j + halfSize] = imag[i + j] - tImag
                    real[i + j] += tReal
                    imag[i + j] += tImag
                }
            }
            size *= 2
        }

        // Compute magnitude of positive frequencies (FFT_SIZE / 2 bins)
        val numBins = FFT_SIZE / 2
        val mags = FloatArray(numBins)
        for (i in 0 until numBins) {
            val r = real[i]
            val im = imag[i]
            val mag = sqrt(r * r + im * im)
            // Convert to dBFS scale (normalized)
            val db = if (mag > 0) 20f * log10(mag / (32768f * FFT_SIZE)) else -100f
            // Map -70 dBFS..0 dBFS to 0.0..1.0
            mags[i] = ((db + 70f) / 70f).coerceIn(0f, 1f)
        }

        // Map FFT bins to 64 logarithmic frequency bands
        val minBin = 1
        val maxBin = numBins - 1
        for (b in 0 until BANDS) {
            val binStart = minBin * Math.pow(maxBin.toDouble() / minBin, b.toDouble() / BANDS)
            val binEnd = minBin * Math.pow(maxBin.toDouble() / minBin, (b + 1).toDouble() / BANDS)

            val iStart = binStart.toInt().coerceIn(0, maxBin)
            val iEnd = maxOf(iStart + 1, binEnd.toInt().coerceIn(0, maxBin + 1))

            var maxVal = 0f
            for (i in iStart until iEnd) {
                if (mags[i] > maxVal) maxVal = mags[i]
            }
            outBands[b] = maxVal
        }
    }
}
