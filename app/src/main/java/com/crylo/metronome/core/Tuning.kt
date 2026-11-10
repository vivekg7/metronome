package com.crylo.metronome.core

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

object NoteMath {
    const val A4_MIN = 430
    const val A4_MAX = 450
    const val A4_DEFAULT = 440

    /** Note names are universal music notation, not translated UI text. */
    private val NAMES = arrayOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")

    /** Fractional MIDI note number of [frequency]; 69 is A4. */
    fun midiOf(frequency: Double, a4: Double = 440.0): Double = 69 + 12 * log2(frequency / a4)

    fun frequencyOf(midi: Int, a4: Double = 440.0): Double = a4 * 2.0.pow((midi - 69) / 12.0)

    /** Signed distance from [reference] to [frequency] in cents (100 per semitone). */
    fun cents(frequency: Double, reference: Double): Double = 1200 * log2(frequency / reference)

    fun name(midi: Int): String = NAMES[Math.floorMod(midi, 12)]

    fun octave(midi: Int): Int = Math.floorDiv(midi, 12) - 1

    fun label(midi: Int): String = name(midi) + octave(midi)

    /** The equal-tempered note nearest to [frequency]. */
    fun nearest(frequency: Double, a4: Double = 440.0): Int = midiOf(frequency, a4).roundToInt()
}

/** Instrument modes for the tuner, each with its standard open strings as MIDI notes. */
enum class Instrument(val strings: List<Int>) {
    CHROMATIC(emptyList()),
    GUITAR(listOf(40, 45, 50, 55, 59, 64)), // E2 A2 D3 G3 B3 E4
    BASS(listOf(28, 33, 38, 43)), // E1 A1 D2 G2
    UKULELE(listOf(67, 60, 64, 69)), // G4 C4 E4 A4 (re-entrant)
    VIOLIN(listOf(55, 62, 69, 76)), // G3 D4 A4 E5
    ;

    /** Index of the string whose pitch is closest to [frequency] (in cents), or null in chromatic mode. */
    fun nearestString(frequency: Double, a4: Double = 440.0): Int? {
        if (strings.isEmpty()) return null
        val midi = NoteMath.midiOf(frequency, a4)
        return strings.indices.minBy { abs(strings[it] - midi) }
    }
}

/** What the tuner shows: the target note, how far off it the input is, and the raw frequency. */
data class TunerReading(
    val frequency: Double,
    val midi: Int,
    val cents: Double,
    val stringIndex: Int?,
)

/**
 * Turns the detector's noisy per-frame estimates into a steady display.
 *
 * - A median over the last three estimates throws away single-frame glitches (octave jumps
 *   on a pluck's attack, mostly).
 * - Cents are smoothed exponentially so the needle glides instead of jittering, but the
 *   smoothing restarts whenever the target note changes so it never slides across notes.
 * - The last reading is held for [holdMs] after the sound stops, so a decaying note can be read.
 *
 * In an instrument mode the target is the nearest string rather than the nearest chromatic
 * note: a string tuned far flat still points at the string it belongs to.
 */
class TunerProcessor(private val holdMs: Long = 1200, private val smoothing: Double = 0.3) {
    var a4: Double = 440.0
    var instrument: Instrument = Instrument.CHROMATIC

    private val recent = ArrayDeque<Double>()
    private var lastTarget = Int.MIN_VALUE
    private var smoothedCents = 0.0
    private var last: TunerReading? = null
    private var lastHeardMs = 0L

    fun process(frequency: Double?, nowMs: Long): TunerReading? {
        if (frequency == null) {
            recent.clear()
            if (nowMs - lastHeardMs > holdMs) {
                last = null
                lastTarget = Int.MIN_VALUE
            }
            return last
        }
        recent.addLast(frequency)
        while (recent.size > 3) recent.removeFirst()
        val f = recent.sorted()[recent.size / 2]

        val stringIndex = instrument.nearestString(f, a4)
        val target = if (stringIndex != null) instrument.strings[stringIndex] else NoteMath.nearest(f, a4)
        val cents = NoteMath.cents(f, NoteMath.frequencyOf(target, a4))
        smoothedCents = if (target != lastTarget) cents else smoothedCents + smoothing * (cents - smoothedCents)
        lastTarget = target
        lastHeardMs = nowMs
        return TunerReading(f, target, smoothedCents, stringIndex).also { last = it }
    }

    fun reset() {
        recent.clear()
        last = null
        lastTarget = Int.MIN_VALUE
    }
}
