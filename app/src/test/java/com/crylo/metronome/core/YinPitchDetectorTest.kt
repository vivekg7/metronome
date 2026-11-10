package com.crylo.metronome.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class YinPitchDetectorTest {
    private val rate = 44100
    private val detector = YinPitchDetector(rate)

    /** Frequencies spanning bass E1 to C6, plus off-grid values so nothing lines up by luck. */
    private val frequencies = listOf(41.2, 55.0, 82.41, 110.0, 146.83, 196.0, 246.94, 329.63, 333.3, 440.0, 523.25, 659.26, 880.0, 1046.5, 1318.5)

    private fun wave(f: Double, partials: (Int) -> Double, maxHarmonic: Int = 1, noise: Double = 0.0, phase: Double = 0.3): FloatArray {
        val random = Random(42)
        return FloatArray(detector.requiredSamples) { i ->
            var s = 0.0
            for (h in 1..maxHarmonic) {
                if (h * f >= rate / 2.0) break
                s += partials(h) * sin(2 * PI * h * f * i / rate + phase * h)
            }
            (0.5 * s + noise * (random.nextDouble() * 2 - 1)).toFloat()
        }
    }

    private fun assertWithinCents(expected: Double, samples: FloatArray, tolerance: Double = 2.0) {
        val estimate = detector.detect(samples)
        assertNotNull("no pitch for $expected Hz", estimate)
        val error = NoteMath.cents(estimate!!.frequency, expected)
        assert(abs(error) <= tolerance) { "expected $expected Hz, got ${estimate.frequency} ($error cents)" }
    }

    @Test fun pureSines() = frequencies.forEach { f -> assertWithinCents(f, wave(f, { 1.0 })) }

    @Test fun sawtoothRichInHarmonics() = frequencies.forEach { f ->
        assertWithinCents(f, wave(f, { h -> 1.0 / h }, maxHarmonic = 20))
    }

    @Test fun squareOddHarmonics() = frequencies.forEach { f ->
        assertWithinCents(f, wave(f, { h -> if (h % 2 == 1) 1.0 / h else 0.0 }, maxHarmonic = 15))
    }

    /** A plucked string often has a stronger second harmonic than fundamental; YIN must not jump an octave. */
    @Test fun weakFundamentalStaysOnTheRightOctave() = listOf(82.41, 110.0, 196.0, 329.63).forEach { f ->
        val amplitudes = doubleArrayOf(0.0, 0.5, 1.0, 0.6, 0.4, 0.3, 0.2)
        assertWithinCents(f, wave(f, { h -> amplitudes.getOrElse(h) { 0.1 } }, maxHarmonic = 10))
    }

    @Test fun toleratesNoise() = listOf(110.0, 440.0, 880.0).forEach { f ->
        assertWithinCents(f, wave(f, { h -> 1.0 / h }, maxHarmonic = 10, noise = 0.05))
    }

    @Test fun silenceAndNoiseGiveNoPitch() {
        assertNull(detector.detect(FloatArray(detector.requiredSamples)))
        val random = Random(1)
        assertNull(detector.detect(FloatArray(detector.requiredSamples) { (random.nextDouble() * 2 - 1).toFloat() }))
    }

    @Test fun clarityIsHighForCleanTone() {
        assertEquals(1.0, detector.detect(wave(440.0, { 1.0 }))!!.clarity, 0.05)
    }
}
