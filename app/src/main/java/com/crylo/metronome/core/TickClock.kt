package com.crylo.metronome.core

import kotlin.math.floor

/**
 * Where each click falls, in audio frames.
 *
 * Tick `n` after the last tempo change sits at `anchor + n * period`, computed fresh each time
 * rather than by adding the period up tick after tick. A period is rarely a whole number of
 * frames (120 BPM in triplets at 44.1 kHz is 7350 exactly, 113 BPM is 7805.3097...), and
 * repeated addition would let the rounding error accumulate into audible drift over a long
 * session. Recomputing from the anchor keeps every tick within half a frame of its exact time.
 */
class TickClock(private var period: Double, private var anchor: Double = 0.0) {
    private var n = 0L

    init {
        require(period > 0) { "period must be positive" }
    }

    /** Exact (fractional) frame of the next tick. */
    val nextExact: Double get() = anchor + n * period

    /** Frame on which the next tick starts. */
    val nextFrame: Long get() = floor(nextExact + 0.5).toLong()

    fun advance() {
        n++
    }

    /** Applies a new period from the next (already scheduled) tick onwards. */
    fun setPeriod(newPeriod: Double) {
        require(newPeriod > 0) { "period must be positive" }
        if (newPeriod == period) return
        anchor = nextExact
        n = 0
        period = newPeriod
    }

    companion object {
        fun framesPerTick(sampleRate: Int, bpm: Int, ticksPerBeat: Int): Double =
            sampleRate * 60.0 / (bpm.toDouble() * ticksPerBeat)
    }
}

/** Counts bars and decides when the tempo trainer should step the tempo up. */
class TempoTrainer(private var settings: TrainerSettings) {
    private var bars = 0

    fun update(newSettings: TrainerSettings) {
        if (newSettings != settings) {
            settings = newSettings
            bars = 0
        }
    }

    fun reset() {
        bars = 0
    }

    /** Called at the end of each bar; returns the new tempo when it should change, else null. */
    fun onBarCompleted(currentBpm: Int): Int? {
        val s = settings
        if (!s.enabled || s.everyBars < 1 || s.stepBpm < 1 || currentBpm >= s.targetBpm) return null
        bars++
        if (bars < s.everyBars) return null
        bars = 0
        return Tempo.clamp(minOf(currentBpm + s.stepBpm, s.targetBpm))
    }
}
