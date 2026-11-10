package com.crylo.metronome.core

import kotlin.math.roundToInt

object Tempo {
    const val MIN = 20
    const val MAX = 300

    fun clamp(bpm: Int) = bpm.coerceIn(MIN, MAX)

    /** The traditional Italian marking for a tempo; ranges follow common metronome markings. */
    fun marking(bpm: Int): TempoMarking = TempoMarking.entries.last { bpm >= it.fromBpm }
}

enum class TempoMarking(val fromBpm: Int) {
    GRAVE(0), LARGO(40), ADAGIO(66), ANDANTE(76), MODERATO(108), ALLEGRO(120), PRESTO(168), PRESTISSIMO(200)
}

/**
 * Tap tempo: the BPM is the average interval over the last [maxTaps] taps, which smooths out
 * the jitter of any single tap. A pause longer than [resetAfterMs] starts a fresh measurement,
 * so tapping a new tempo is not dragged towards the old one.
 */
class TapTempo(private val maxTaps: Int = 6, private val resetAfterMs: Long = 2000) {
    private val taps = ArrayDeque<Long>()

    /** Records a tap at [timeMs] (a monotonic clock) and returns the BPM once there are two taps. */
    fun tap(timeMs: Long): Int? {
        val last = taps.lastOrNull()
        if (last != null && (timeMs - last > resetAfterMs || timeMs <= last)) taps.clear()
        taps.addLast(timeMs)
        while (taps.size > maxTaps) taps.removeFirst()
        if (taps.size < 2) return null
        val averageInterval = (taps.last() - taps.first()).toDouble() / (taps.size - 1)
        return Tempo.clamp((60_000.0 / averageInterval).roundToInt())
    }

    fun reset() = taps.clear()
}
