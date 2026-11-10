package com.crylo.metronome.core

data class Preset(val name: String, val settings: MetronomeSettings)

/**
 * A tiny line-based format for saved settings and presets: one record per line, fields split
 * by `|`. Hand-rolled because it is a dozen lines, needs no serialisation plugin, and keeps
 * the parsing testable on the JVM (Android's org.json is a stub there). Unknown or broken
 * records are skipped rather than failing the whole list, so a future format change can
 * never wipe someone's setlist.
 */
object PresetCodec {
    private const val VERSION = "1"

    fun encodeSettings(s: MetronomeSettings): String = listOf(
        VERSION,
        s.bpm,
        s.timeSignature.beats,
        s.timeSignature.noteValue,
        s.accents.joinToString("") { it.name.take(1) },
        s.subdivision.name,
        s.sound.name,
        if (s.trainer.enabled) 1 else 0,
        s.trainer.stepBpm,
        s.trainer.everyBars,
        s.trainer.targetBpm,
    ).joinToString("|")

    fun decodeSettings(line: String): MetronomeSettings? = decodeFields(line.split('|'))

    private fun decodeFields(f: List<String>): MetronomeSettings? = runCatching {
        if (f.size < 11 || f[0] != VERSION) return null
        val ts = TimeSignature(f[2].toInt(), f[3].toInt())
        val accents = f[4].map { c -> Accent.entries.first { it.name[0] == c } }
        require(accents.size == ts.beats)
        MetronomeSettings(
            bpm = Tempo.clamp(f[1].toInt()),
            timeSignature = ts,
            accents = accents,
            subdivision = Subdivision.valueOf(f[5]),
            sound = ClickSound.valueOf(f[6]),
            trainer = TrainerSettings(
                enabled = f[7] == "1",
                stepBpm = f[8].toInt(),
                everyBars = f[9].toInt(),
                targetBpm = Tempo.clamp(f[10].toInt()),
            ),
        )
    }.getOrNull()

    fun encodePresets(presets: List<Preset>): String =
        presets.joinToString("\n") { escape(it.name) + "|" + encodeSettings(it.settings) }

    fun decodePresets(text: String): List<Preset> = text.lines().mapNotNull { line ->
        val fields = line.split('|')
        if (fields.size < 2) return@mapNotNull null
        decodeFields(fields.drop(1))?.let { Preset(unescape(fields[0]), it) }
    }

    private fun escape(s: String) = buildString {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '|' -> append("\\p")
            '\n' -> append("\\n")
            else -> append(c)
        }
    }

    private fun unescape(s: String) = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                append(
                    when (s[i + 1]) {
                        'p' -> '|'
                        'n' -> '\n'
                        else -> s[i + 1]
                    },
                )
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }
}
