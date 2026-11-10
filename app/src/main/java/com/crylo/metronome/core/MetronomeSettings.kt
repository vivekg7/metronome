package com.crylo.metronome.core

/** How a single beat of the bar sounds. Tapping a beat dot cycles through these. */
enum class Accent {
    ACCENT, NORMAL, MUTE;

    fun next(): Accent = when (this) {
        ACCENT -> NORMAL
        NORMAL -> MUTE
        MUTE -> ACCENT
    }
}

/** Extra clicks between beats. [ticksPerBeat] is how many clicks make up one beat. */
enum class Subdivision(val ticksPerBeat: Int) {
    NONE(1), EIGHTHS(2), TRIPLETS(3), SIXTEENTHS(4)
}

enum class ClickSound { CLICK, WOOD, BEEP, COWBELL }

/**
 * A time signature. The BPM always counts the [noteValue] note, so 6/8 at 120 BPM plays six
 * eighth-note clicks at 120 per minute -- the convention most metronomes and players use.
 */
data class TimeSignature(val beats: Int, val noteValue: Int) {
    init {
        require(beats in MIN_BEATS..MAX_BEATS) { "beats out of range: $beats" }
        require(noteValue in NOTE_VALUES) { "unsupported note value: $noteValue" }
    }

    override fun toString() = "$beats/$noteValue"

    companion object {
        const val MIN_BEATS = 1
        const val MAX_BEATS = 16
        val NOTE_VALUES = listOf(2, 4, 8, 16)
        val COMMON = listOf(
            TimeSignature(2, 4), TimeSignature(3, 4), TimeSignature(4, 4), TimeSignature(5, 4),
            TimeSignature(6, 8), TimeSignature(7, 8), TimeSignature(9, 8), TimeSignature(12, 8),
        )
    }
}

/** Raise the tempo by [stepBpm] every [everyBars] bars until [targetBpm] is reached. */
data class TrainerSettings(
    val enabled: Boolean = false,
    val stepBpm: Int = 5,
    val everyBars: Int = 4,
    val targetBpm: Int = 160,
)

data class MetronomeSettings(
    val bpm: Int = 120,
    val timeSignature: TimeSignature = TimeSignature(4, 4),
    val accents: List<Accent> = defaultAccents(4),
    val subdivision: Subdivision = Subdivision.NONE,
    val sound: ClickSound = ClickSound.CLICK,
    val trainer: TrainerSettings = TrainerSettings(),
) {
    fun accentAt(beat: Int): Accent = accents.getOrElse(beat) { Accent.NORMAL }

    fun withBpm(value: Int) = copy(bpm = Tempo.clamp(value))

    /** Changes the metre while keeping the accents the user already set on the beats that remain. */
    fun withTimeSignature(ts: TimeSignature): MetronomeSettings {
        val resized = List(ts.beats) { i -> accents.getOrElse(i) { if (i == 0) Accent.ACCENT else Accent.NORMAL } }
        return copy(timeSignature = ts, accents = resized)
    }

    fun cycleAccent(beat: Int): MetronomeSettings {
        if (beat !in accents.indices) return this
        return copy(accents = accents.mapIndexed { i, a -> if (i == beat) a.next() else a })
    }

    companion object {
        fun defaultAccents(beats: Int) = List(beats) { if (it == 0) Accent.ACCENT else Accent.NORMAL }
    }
}
