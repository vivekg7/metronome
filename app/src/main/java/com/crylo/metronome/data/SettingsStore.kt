package com.crylo.metronome.data

import android.content.Context
import androidx.core.content.edit
import com.crylo.metronome.core.Instrument
import com.crylo.metronome.core.MetronomeSettings
import com.crylo.metronome.core.NoteMath
import com.crylo.metronome.core.Preset
import com.crylo.metronome.core.PresetCodec

/** Everything the app remembers, in one private SharedPreferences file on the device. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("metronome", Context.MODE_PRIVATE)

    fun loadMetronome(): MetronomeSettings =
        prefs.getString(KEY_METRONOME, null)?.let(PresetCodec::decodeSettings) ?: MetronomeSettings()

    fun saveMetronome(s: MetronomeSettings) = prefs.edit { putString(KEY_METRONOME, PresetCodec.encodeSettings(s)) }

    fun loadPresets(): List<Preset> = PresetCodec.decodePresets(prefs.getString(KEY_PRESETS, "") ?: "")

    fun savePresets(presets: List<Preset>) = prefs.edit { putString(KEY_PRESETS, PresetCodec.encodePresets(presets)) }

    var a4: Int
        get() = prefs.getInt(KEY_A4, NoteMath.A4_DEFAULT).coerceIn(NoteMath.A4_MIN, NoteMath.A4_MAX)
        set(value) = prefs.edit { putInt(KEY_A4, value) }

    var instrument: Instrument
        get() = Instrument.entries.firstOrNull { it.name == prefs.getString(KEY_INSTRUMENT, null) } ?: Instrument.CHROMATIC
        set(value) = prefs.edit { putString(KEY_INSTRUMENT, value.name) }

    private companion object {
        const val KEY_METRONOME = "metronome"
        const val KEY_PRESETS = "presets"
        const val KEY_A4 = "a4"
        const val KEY_INSTRUMENT = "instrument"
    }
}
