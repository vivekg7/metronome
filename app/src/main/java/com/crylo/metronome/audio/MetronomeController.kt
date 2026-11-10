package com.crylo.metronome.audio

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.crylo.metronome.core.MetronomeSettings
import com.crylo.metronome.core.Preset
import com.crylo.metronome.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet

/**
 * App-wide owner of the metronome: settings, presets and the audio engine. It lives in the
 * Application rather than the Activity so playback survives the screen turning off or the
 * activity being recreated; [MetronomeService] only keeps the process in the foreground.
 */
class MetronomeController(private val context: Context, private val store: SettingsStore) {
    private val main = Handler(Looper.getMainLooper())
    private val _settings = MutableStateFlow(store.loadMetronome())
    private val _playing = MutableStateFlow(false)
    private val _presets = MutableStateFlow(store.loadPresets())

    val settings: StateFlow<MetronomeSettings> = _settings.asStateFlow()
    val playing: StateFlow<Boolean> = _playing.asStateFlow()
    val presets: StateFlow<List<Preset>> = _presets.asStateFlow()

    // The tempo trainer reports from the audio thread; hop to the main thread before touching state.
    val engine = ClickEngine { bpm -> main.post { update { it.withBpm(bpm) } } }.apply { settings = _settings.value }

    fun update(transform: (MetronomeSettings) -> MetronomeSettings) {
        val s = _settings.updateAndGet(transform)
        engine.settings = s
        store.saveMetronome(s)
    }

    fun toggle() = if (_playing.value) stop() else play()

    fun play() {
        if (_playing.value) return
        _playing.value = true
        context.startForegroundService(Intent(context, MetronomeService::class.java))
    }

    fun stop() {
        engine.stop()
        _playing.value = false
        context.stopService(Intent(context, MetronomeService::class.java))
    }

    /** Called by the service once it is in the foreground. */
    internal fun startEngine() {
        engine.settings = _settings.value
        engine.start(context.getSystemService(AudioManager::class.java))
    }

    /** Called when the service goes away for any reason, so audio never outlives it. */
    internal fun onServiceDestroyed() {
        engine.stop()
        _playing.value = false
    }

    fun savePreset(name: String) = setPresets(_presets.value + Preset(name, _settings.value))

    fun loadPreset(preset: Preset) = update { preset.settings }

    fun deletePreset(index: Int) = setPresets(_presets.value.filterIndexed { i, _ -> i != index })

    fun movePreset(index: Int, delta: Int) {
        val list = _presets.value.toMutableList()
        val target = index + delta
        if (index !in list.indices || target !in list.indices) return
        list.add(target, list.removeAt(index))
        setPresets(list)
    }

    private fun setPresets(list: List<Preset>) {
        _presets.update { list }
        store.savePresets(list)
    }
}
