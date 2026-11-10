package com.crylo.metronome.core

import kotlin.math.ceil
import kotlin.math.floor

data class PitchEstimate(val frequency: Double, val clarity: Double)

/**
 * Monophonic pitch detection with the YIN algorithm (de Cheveigné & Kawahara, 2002).
 *
 * YIN was chosen over plain autocorrelation or FFT peak picking because its cumulative mean
 * normalised difference makes the "pick the first deep dip" rule robust against octave errors
 * on harmonic-rich signals -- exactly what a plucked string or a bowed note is.
 *
 * The final period is refined with parabolic interpolation on the raw difference function
 * (not the normalised one, whose extra division skews the minimum), which is what gets a
 * synthetic tone to within a fraction of a cent.
 *
 * Needs [requiredSamples] samples per call. Allocation-free after construction.
 */
class YinPitchDetector(
    private val sampleRate: Int,
    private val windowSize: Int = 2048,
    minFrequency: Double = 38.0,
    maxFrequency: Double = 2100.0,
    private val threshold: Double = 0.12,
) {
    private val minLag = floor(sampleRate / maxFrequency).toInt().coerceAtLeast(2)
    private val maxLag = ceil(sampleRate / minFrequency).toInt()
    private val diff = DoubleArray(maxLag + 2)
    private val cmnd = DoubleArray(maxLag + 2)

    val requiredSamples: Int get() = windowSize + maxLag + 2

    fun detect(samples: FloatArray, offset: Int = 0): PitchEstimate? {
        require(samples.size - offset >= requiredSamples) { "need $requiredSamples samples" }
        // Step 1-2: difference function d(tau).
        for (tau in 1..maxLag + 1) {
            var sum = 0.0
            for (j in 0 until windowSize) {
                val delta = (samples[offset + j] - samples[offset + j + tau]).toDouble()
                sum += delta * delta
            }
            diff[tau] = sum
        }
        // Step 3: cumulative mean normalised difference.
        cmnd[0] = 1.0
        var running = 0.0
        for (tau in 1..maxLag + 1) {
            running += diff[tau]
            cmnd[tau] = if (running == 0.0) 1.0 else diff[tau] * tau / running
        }
        // Step 4: first dip under the threshold, then down to its local minimum.
        var tau = -1
        var t = minLag
        while (t <= maxLag) {
            if (cmnd[t] < threshold) {
                while (t + 1 <= maxLag && cmnd[t + 1] < cmnd[t]) t++
                tau = t
                break
            }
            t++
        }
        if (tau < 0) return null
        // Step 5: parabolic interpolation on d(tau) around the chosen lag.
        val refined = if (tau > 1) {
            val a = diff[tau - 1]
            val b = diff[tau]
            val c = diff[tau + 1]
            val denominator = a - 2 * b + c
            if (denominator > 0) tau + 0.5 * (a - c) / denominator else tau.toDouble()
        } else {
            tau.toDouble()
        }
        return PitchEstimate(sampleRate / refined, (1.0 - cmnd[tau]).coerceIn(0.0, 1.0))
    }
}
