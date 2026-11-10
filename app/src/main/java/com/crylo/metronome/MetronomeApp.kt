package com.crylo.metronome

import android.app.Application
import com.crylo.metronome.audio.MetronomeController
import com.crylo.metronome.data.SettingsStore

class MetronomeApp : Application() {
    val store by lazy { SettingsStore(this) }
    val controller by lazy { MetronomeController(this, store) }
}
