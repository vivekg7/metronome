package com.crylo.metronome.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import com.crylo.metronome.MetronomeApp
import com.crylo.metronome.audio.TunerEngine
import com.crylo.metronome.core.Instrument
import com.crylo.metronome.core.NoteMath
import com.crylo.metronome.core.TunerProcessor
import com.crylo.metronome.core.TunerReading
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TunerViewModel(app: Application) : AndroidViewModel(app) {
    private val store = (app as MetronomeApp).store
    private val processor = TunerProcessor().apply {
        a4 = store.a4.toDouble()
        instrument = store.instrument
    }
    private val engine = TunerEngine { f ->
        val reading = synchronized(processor) { processor.process(f, SystemClock.uptimeMillis()) }
        _reading.value = reading
    }

    private val _reading = MutableStateFlow<TunerReading?>(null)
    val reading: StateFlow<TunerReading?> = _reading.asStateFlow()

    private val _a4 = MutableStateFlow(store.a4)
    val a4: StateFlow<Int> = _a4.asStateFlow()

    private val _instrument = MutableStateFlow(store.instrument)
    val instrument: StateFlow<Instrument> = _instrument.asStateFlow()

    private val _micAvailable = MutableStateFlow(true)
    val micAvailable: StateFlow<Boolean> = _micAvailable.asStateFlow()

    fun start() {
        _micAvailable.value = engine.start(getApplication())
    }

    fun stop() {
        engine.stop()
        synchronized(processor) { processor.reset() }
        _reading.value = null
    }

    fun setA4(value: Int) {
        val v = value.coerceIn(NoteMath.A4_MIN, NoteMath.A4_MAX)
        store.a4 = v
        _a4.value = v
        synchronized(processor) {
            processor.a4 = v.toDouble()
            processor.reset()
        }
    }

    fun setInstrument(value: Instrument) {
        store.instrument = value
        _instrument.value = value
        synchronized(processor) {
            processor.instrument = value
            processor.reset()
        }
    }

    override fun onCleared() = engine.stop()
}
