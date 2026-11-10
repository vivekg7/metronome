package com.crylo.metronome.core

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/** What kind of click a tick produces. */
enum class Voice { ACCENT, BEAT, SUB }

/** One scheduled click: the audio frame it starts on and where it sits in the bar. */
data class TickEvent(val frame: Long, val bar: Long, val beat: Int, val sub: Int, val accent: Accent)

/**
 * Click sounds are synthesised once at the output sample rate instead of shipped as samples:
 * no resampling, no decoding on the audio thread, and nothing to license.
 */
object ClickSynth {
    fun render(sound: ClickSound, voice: Voice, sampleRate: Int): FloatArray {
        val pitch = when (voice) {
            Voice.ACCENT -> 1.5
            Voice.BEAT -> 1.0
            Voice.SUB -> 0.8
        }
        val gain = when (voice) {
            Voice.ACCENT -> 0.9
            Voice.BEAT -> 0.7
            Voice.SUB -> 0.4
        }
        return when (sound) {
            ClickSound.CLICK -> tone(sampleRate, 0.025, gain, decay = 0.004, 1700.0 * pitch to 1.0)
            ClickSound.WOOD -> tone(sampleRate, 0.06, gain, decay = 0.012, 800.0 * pitch to 1.0, 2160.0 * pitch to 0.4)
            ClickSound.BEEP -> beep(sampleRate, 0.05, gain, 880.0 * pitch)
            ClickSound.COWBELL -> tone(
                sampleRate, 0.15, gain * 0.8, decay = 0.05,
                562.0 * pitch to 1.0, 845.0 * pitch to 0.8, 1686.0 * pitch to 0.2, 2535.0 * pitch to 0.15,
            )
        }
    }

    /** Sum of sine partials under an exponential decay, with a 0.5 ms attack to avoid a hard step. */
    private fun tone(
        sampleRate: Int,
        seconds: Double,
        gain: Double,
        decay: Double,
        vararg partials: Pair<Double, Double>,
    ): FloatArray {
        val n = (seconds * sampleRate).toInt()
        val attack = 0.0005 * sampleRate
        val norm = partials.sumOf { it.second }
        return FloatArray(n) { i ->
            val t = i.toDouble() / sampleRate
            var s = 0.0
            for ((f, a) in partials) s += a * sin(2 * PI * f * t)
            val env = exp(-t / decay) * min(1.0, i / attack)
            (gain * env * s / norm).toFloat()
        }
    }

    private fun beep(sampleRate: Int, seconds: Double, gain: Double, f: Double): FloatArray {
        val n = (seconds * sampleRate).toInt()
        val ramp = 0.003 * sampleRate
        return FloatArray(n) { i ->
            val env = min(1.0, min(i / ramp, (n - 1 - i) / ramp))
            (gain * env * sin(2 * PI * f * i / sampleRate)).toFloat()
        }
    }
}

/**
 * Generates the metronome's audio, one buffer at a time, by counting frames.
 *
 * Clicks are placed at exact frame offsets inside the buffer being rendered, so their timing
 * depends only on the sample rate -- never on when the audio thread happens to wake up. The
 * output is identical whatever buffer size the caller uses.
 *
 * Not thread-safe: call [render] from one thread. Settings changes are handed over through
 * [settings], a single volatile reference that is picked up at the next buffer.
 */
class ClickRenderer(
    private val sampleRate: Int,
    initial: MetronomeSettings,
    private val onTempoChanged: (Int) -> Unit = {},
) {
    @Volatile
    var settings: MetronomeSettings = initial

    private var applied: MetronomeSettings = initial
    private var bpm = initial.bpm
    private var ticksPerBeat = initial.subdivision.ticksPerBeat
    private val clock = TickClock(TickClock.framesPerTick(sampleRate, bpm, ticksPerBeat))
    private val trainer = TempoTrainer(initial.trainer)
    private var samples = loadSamples(initial.sound)

    private var frame = 0L
    private var bar = 0L
    private var beat = 0
    private var sub = 0

    // Up to four overlapping click voices; a new click never cuts the tail of the last one.
    private val voiceData = arrayOfNulls<FloatArray>(4)
    private val voicePos = IntArray(4)

    /** Frame index of the first frame the next [render] call will produce. */
    val position: Long get() = frame

    private fun loadSamples(sound: ClickSound) = Voice.entries.map { ClickSynth.render(sound, it, sampleRate) }

    private fun applySettings(s: MetronomeSettings) {
        if (s.sound != applied.sound) samples = loadSamples(s.sound)
        trainer.update(s.trainer)
        if (s.bpm != bpm || s.subdivision.ticksPerBeat != ticksPerBeat) {
            bpm = s.bpm
            ticksPerBeat = s.subdivision.ticksPerBeat
            clock.setPeriod(TickClock.framesPerTick(sampleRate, bpm, ticksPerBeat))
        }
        applied = s
    }

    /** Fills `out[0 until frames]` and reports every tick that starts inside it to [onTick]. */
    fun render(out: FloatArray, frames: Int, onTick: (TickEvent) -> Unit = {}) {
        val s = settings
        if (s !== applied) applySettings(s)
        out.fill(0f, 0, frames)
        val end = frame + frames
        var cursor = 0
        while (true) {
            val tickFrame = clock.nextFrame
            if (tickFrame >= end) break
            val offset = (tickFrame - frame).toInt().coerceAtLeast(0)
            mixVoices(out, cursor, offset)
            cursor = offset
            val accent = applied.accentAt(beat)
            val voice = when {
                sub > 0 -> Voice.SUB
                accent == Accent.ACCENT -> Voice.ACCENT
                accent == Accent.NORMAL -> Voice.BEAT
                else -> null
            }
            if (voice != null) startVoice(samples[voice.ordinal])
            onTick(TickEvent(tickFrame, bar, beat, sub, accent))
            advanceTick()
        }
        mixVoices(out, cursor, frames)
        frame = end
    }

    private fun advanceTick() {
        clock.advance()
        sub++
        if (sub < ticksPerBeat) return
        sub = 0
        beat++
        if (beat < applied.timeSignature.beats) return
        beat = 0
        bar++
        trainer.onBarCompleted(bpm)?.let { newBpm ->
            bpm = newBpm
            clock.setPeriod(TickClock.framesPerTick(sampleRate, bpm, ticksPerBeat))
            onTempoChanged(newBpm)
        }
    }

    private fun startVoice(data: FloatArray) {
        var slot = voiceData.indexOfFirst { it == null }
        if (slot < 0) slot = voicePos.indices.maxBy { voicePos[it] } // steal the oldest
        voiceData[slot] = data
        voicePos[slot] = 0
    }

    private fun mixVoices(out: FloatArray, from: Int, to: Int) {
        if (to <= from) return
        for (v in voiceData.indices) {
            val data = voiceData[v] ?: continue
            var p = voicePos[v]
            val n = min(to - from, data.size - p)
            for (i in 0 until n) out[from + i] += data[p + i]
            p += n
            if (p >= data.size) voiceData[v] = null else voicePos[v] = p
        }
    }
}
