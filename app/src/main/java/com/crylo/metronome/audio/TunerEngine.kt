package com.crylo.metronome.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import com.crylo.metronome.core.YinPitchDetector
import kotlin.math.sqrt

/**
 * Reads the microphone and runs pitch detection on a sliding window. Audio stays in memory:
 * each window is analysed and overwritten, nothing is stored or sent anywhere.
 */
class TunerEngine(private val onPitch: (Double?) -> Unit) {
    @Volatile private var running = false
    private var thread: Thread? = null

    /** Starts listening; returns false if the microphone is unavailable or not permitted. */
    fun start(context: Context): Boolean {
        if (running) return true
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        // UNPROCESSED skips noise suppression and AGC, which colour the signal; fall back to
        // VOICE_RECOGNITION, the least-processed source every device has.
        val unprocessed = context.getSystemService(AudioManager::class.java)
            .getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val minBytes = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        if (minBytes <= 0) return false
        val record = try {
            AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, maxOf(minBytes, HOP * 4 * Float.SIZE_BYTES))
        } catch (_: Exception) {
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        running = true
        thread = Thread({ loop(record) }, "tuner-audio").apply { start() }
        return true
    }

    private fun loop(record: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val detector = YinPitchDetector(RATE)
        val window = FloatArray(detector.requiredSamples)
        val hop = FloatArray(HOP)
        try {
            record.startRecording()
            while (running) {
                var read = 0
                while (read < HOP && running) {
                    val n = record.read(hop, read, HOP - read, AudioRecord.READ_BLOCKING)
                    if (n < 0) return
                    read += n
                }
                // Slide the analysis window along by one hop.
                System.arraycopy(window, HOP, window, 0, window.size - HOP)
                System.arraycopy(hop, 0, window, window.size - HOP, HOP)
                onPitch(if (rms(window) < SILENCE_RMS) null else detector.detect(window)?.frequency)
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }

    private fun rms(x: FloatArray): Double {
        var sum = 0.0
        for (v in x) sum += v * v
        return sqrt(sum / x.size)
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    private companion object {
        const val RATE = 44100 // the one rate every Android device must support for input
        const val HOP = 1024 // ~23 ms between readings
        const val SILENCE_RMS = 0.002
    }
}
