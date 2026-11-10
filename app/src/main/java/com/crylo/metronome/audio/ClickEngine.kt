package com.crylo.metronome.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Process
import com.crylo.metronome.core.ClickRenderer
import com.crylo.metronome.core.MetronomeSettings
import com.crylo.metronome.core.TickEvent
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Plays the metronome through a streaming [AudioTrack] fed by [ClickRenderer].
 *
 * Timing comes from the audio stream itself: a dedicated urgent-audio thread renders buffers
 * with the clicks already placed at exact frame offsets and blocks on `write`, so the click
 * grid is as steady as the DAC clock. Nothing is scheduled with Handler or delay, whose
 * wake-up jitter (several ms, much worse under load) is clearly audible in a metronome.
 *
 * The visual beat is driven from the same frame numbers: each rendered tick is queued with its
 * frame, and [pollTick] compares those frames with the frame the hardware is actually playing
 * now (from [AudioTrack.getTimestamp]), so the flash lines up with what you hear, output
 * latency included.
 */
class ClickEngine(private val onTempoChanged: (Int) -> Unit) {
    private val lock = Any()
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    @Volatile private var renderer: ClickRenderer? = null
    private var sampleRate = 48000

    @Volatile private var running = false
    private val pending = ConcurrentLinkedQueue<TickEvent>()
    private var current: TickEvent? = null
    private val timestamp = AudioTimestamp()

    @Volatile
    var settings: MetronomeSettings = MetronomeSettings()
        set(value) {
            field = value
            renderer?.settings = value
        }

    val isRunning: Boolean get() = running

    fun start(manager: AudioManager): Unit = synchronized(lock) {
        if (running) return
        sampleRate = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
        val burst = manager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 256
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val newTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(maxOf(minBytes, burst * 4 * Float.SIZE_BYTES))
            .build()
        val newRenderer = ClickRenderer(sampleRate, settings, onTempoChanged)
        pending.clear()
        current = null
        track = newTrack
        renderer = newRenderer
        running = true
        thread = Thread({ loop(newTrack, newRenderer, burst) }, "metronome-audio").apply { start() }
    }

    private fun loop(track: AudioTrack, renderer: ClickRenderer, burst: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buffer = FloatArray(burst)
        val onTick: (TickEvent) -> Unit = { if (it.sub == 0) pending.add(it) }
        track.play()
        while (running) {
            renderer.render(buffer, burst, onTick)
            var written = 0
            while (written < burst && running) {
                val n = track.write(buffer, written, burst - written, AudioTrack.WRITE_BLOCKING)
                if (n < 0) {
                    running = false
                    break
                }
                written += n
            }
        }
    }

    fun stop(): Unit = synchronized(lock) {
        running = false
        thread?.join(500)
        thread = null
        track?.run {
            runCatching {
                pause()
                flush()
            }
            release()
        }
        track = null
        renderer = null
        pending.clear()
        current = null
    }

    /** The beat the listener is hearing right now, or null before the first click. Call from the UI thread. */
    fun pollTick(): TickEvent? = synchronized(lock) {
        val t = track ?: return null
        val playing = runCatching {
            if (t.getTimestamp(timestamp)) {
                timestamp.framePosition + (System.nanoTime() - timestamp.nanoTime) * sampleRate / 1_000_000_000L
            } else {
                t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            }
        }.getOrNull() ?: return current
        while (true) {
            val next = pending.peek() ?: break
            if (next.frame > playing) break
            current = pending.poll()
        }
        current
    }
}
