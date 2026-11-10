package com.crylo.metronome.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.abs

class TapTempoTest {
    @Test fun averagesTheLastTaps() {
        val tap = TapTempo(maxTaps = 4)
        assertNull(tap.tap(0))
        assertEquals(120, tap.tap(500))
        assertEquals(120, tap.tap(1000))
        assertEquals(122, tap.tap(1480)) // one early tap only nudges the average
        // Jittery taps (500, 500, 480, 520 ms) over the last four still average to 500 ms.
        assertEquals(120, tap.tap(2000))
    }

    @Test fun keepsOnlyTheMostRecentTaps() {
        val tap = TapTempo(maxTaps = 3)
        listOf(0L, 1000, 2000).forEach { tap.tap(it) } // 60 BPM
        assertEquals(80, tap.tap(2500)) // window 1000, 2000, 2500 -> 750 ms average
        assertEquals(120, tap.tap(3000)) // window 2000, 2500, 3000 -> 500 ms average
    }

    @Test fun longPauseStartsOver() {
        val tap = TapTempo(resetAfterMs = 2000)
        tap.tap(0)
        tap.tap(1000)
        assertNull(tap.tap(4000))
        assertEquals(150, tap.tap(4400))
    }

    @Test fun clampsToRange() {
        val tap = TapTempo()
        tap.tap(0)
        assertEquals(Tempo.MAX, tap.tap(50))
    }
}

class TickClockTest {
    @Test fun framesPerTick() {
        assertEquals(24000.0, TickClock.framesPerTick(48000, 120, 1), 0.0)
        assertEquals(7350.0, TickClock.framesPerTick(44100, 120, 3), 1e-9)
        assertEquals(44100.0 * 60 / 113, TickClock.framesPerTick(44100, 113, 1), 1e-9)
    }

    @Test fun noDriftOverALongSession() {
        val period = TickClock.framesPerTick(44100, 113, 3) // 7805.309...
        val clock = TickClock(period)
        repeat(100_000) { clock.advance() }
        // An hour-plus of triplets later, still within half a frame of the exact time.
        assertEquals(100_000 * period, clock.nextFrame.toDouble(), 0.5)
    }

    @Test fun tempoChangeAppliesFromTheNextTick() {
        val clock = TickClock(1000.0)
        clock.advance()
        clock.advance()
        assertEquals(2000L, clock.nextFrame)
        clock.setPeriod(500.0)
        assertEquals(2000L, clock.nextFrame)
        clock.advance()
        assertEquals(2500L, clock.nextFrame)
    }
}

class TempoTrainerTest {
    @Test fun stepsEveryNBarsUpToTarget() {
        val t = TempoTrainer(TrainerSettings(enabled = true, stepBpm = 5, everyBars = 2, targetBpm = 108))
        var bpm = 100
        val changes = mutableListOf<Int>()
        repeat(10) { t.onBarCompleted(bpm)?.let { bpm = it; changes += it } }
        assertEquals(listOf(105, 108), changes)
    }

    @Test fun disabledDoesNothing() {
        val t = TempoTrainer(TrainerSettings(enabled = false, everyBars = 1))
        repeat(5) { assertNull(t.onBarCompleted(100)) }
    }
}

class ClickRendererTest {
    private val rate = 48000

    private fun renderAll(settings: MetronomeSettings, totalFrames: Int, chunk: Int): Pair<FloatArray, List<TickEvent>> {
        val r = ClickRenderer(rate, settings)
        val out = FloatArray(totalFrames)
        val buf = FloatArray(chunk)
        val events = mutableListOf<TickEvent>()
        var done = 0
        while (done < totalFrames) {
            val n = minOf(chunk, totalFrames - done)
            r.render(buf, n) { events += it }
            buf.copyInto(out, done, 0, n)
            done += n
        }
        return out to events
    }

    @Test fun ticksLandOnExactFrames() {
        val (_, events) = renderAll(MetronomeSettings(bpm = 120), rate * 2, 256)
        assertEquals(listOf(0L, 24000L, 48000L, 72000L), events.map { it.frame })
        assertEquals(listOf(0, 1, 2, 3), events.map { it.beat })
        assertEquals(Accent.ACCENT, events[0].accent)
    }

    @Test fun outputDoesNotDependOnBufferSize() {
        val s = MetronomeSettings(bpm = 137, subdivision = Subdivision.TRIPLETS, sound = ClickSound.WOOD)
        val (a, ea) = renderAll(s, rate * 3, 37)
        val (b, eb) = renderAll(s, rate * 3, 4096)
        assertEquals(ea, eb)
        assertArrayEquals(a, b, 0f)
    }

    @Test fun clickStartsOnItsFrameAndSilenceBetween() {
        val (out, _) = renderAll(MetronomeSettings(bpm = 60), rate * 2, 512)
        assert(abs(out[1]) > 0f || abs(out[5]) > 0f)
        assertEquals(0f, out[rate - 1], 0f)
        assertEquals(0f, out[rate / 2], 0f)
        assert(out.slice(rate until rate + 100).any { abs(it) > 0.05f })
    }

    @Test fun subdivisionsAndBarCounting() {
        val s = MetronomeSettings(bpm = 60, timeSignature = TimeSignature(3, 4), accents = MetronomeSettings.defaultAccents(3), subdivision = Subdivision.SIXTEENTHS)
        val (_, events) = renderAll(s, rate * 4, 1000)
        assertEquals(16, events.size)
        assertEquals(12000L, events[1].frame)
        assertEquals(listOf(0, 1, 2, 3), events.take(4).map { it.sub })
        assertEquals(1L, events[12].bar)
        assertEquals(0, events[12].beat)
    }

    @Test fun mutedBeatIsSilentButStillReported() {
        val s = MetronomeSettings(bpm = 60, accents = listOf(Accent.ACCENT, Accent.MUTE, Accent.NORMAL, Accent.NORMAL))
        val (out, events) = renderAll(s, rate * 2, 480)
        assertEquals(Accent.MUTE, events[1].accent)
        assert(out.slice(rate until rate + 2000).all { it == 0f })
    }

    @Test fun trainerRaisesTempoAtBarBoundaries() {
        val changes = mutableListOf<Int>()
        val s = MetronomeSettings(
            bpm = 60,
            timeSignature = TimeSignature(1, 4),
            accents = MetronomeSettings.defaultAccents(1),
            trainer = TrainerSettings(enabled = true, stepBpm = 60, everyBars = 1, targetBpm = 120),
        )
        val r = ClickRenderer(rate, s) { changes += it }
        val events = mutableListOf<TickEvent>()
        val buf = FloatArray(1000)
        repeat(3 * rate / 1000) { r.render(buf, 1000) { events += it } }
        assertEquals(listOf(120), changes)
        // 60 BPM for the first bar, then 120: ticks at 0 s, 1 s, 1.5 s, 2 s, 2.5 s.
        assertEquals(listOf(0L, 48000L, 72000L, 96000L, 120000L), events.map { it.frame })
    }

    @Test fun tempoChangeMidPlayback() {
        val r = ClickRenderer(rate, MetronomeSettings(bpm = 60))
        val buf = FloatArray(rate / 2)
        val events = mutableListOf<TickEvent>()
        r.render(buf, buf.size) { events += it } // 0 .. 0.5 s
        r.settings = r.settings.withBpm(120)
        repeat(5) { r.render(buf, buf.size) { events += it } } // up to 3 s
        // Tick at 1 s was already scheduled at the old tempo; 120 BPM applies from there on.
        assertEquals(listOf(0L, 48000L, 72000L, 96000L, 120000L), events.map { it.frame })
    }
}

class SettingsTest {
    @Test fun timeSignatureChangeKeepsExistingAccents() {
        val s = MetronomeSettings().cycleAccent(1) // beat 2 muted
        val seven = s.withTimeSignature(TimeSignature(7, 8))
        assertEquals(listOf(Accent.ACCENT, Accent.MUTE, Accent.NORMAL, Accent.NORMAL, Accent.NORMAL, Accent.NORMAL, Accent.NORMAL), seven.accents)
        assertEquals(listOf(Accent.ACCENT, Accent.MUTE), seven.withTimeSignature(TimeSignature(2, 4)).accents)
    }

    @Test fun accentCycle() {
        assertEquals(Accent.NORMAL, Accent.ACCENT.next())
        assertEquals(Accent.MUTE, Accent.NORMAL.next())
        assertEquals(Accent.ACCENT, Accent.MUTE.next())
    }

    @Test fun tempoMarkings() {
        assertEquals(TempoMarking.LARGO, Tempo.marking(50))
        assertEquals(TempoMarking.ALLEGRO, Tempo.marking(120))
        assertEquals(TempoMarking.PRESTISSIMO, Tempo.marking(300))
        assertEquals(TempoMarking.GRAVE, Tempo.marking(20))
    }

    @Test fun presetsRoundTrip() {
        val presets = listOf(
            Preset("Warm-up | slow\nline", MetronomeSettings(bpm = 72, subdivision = Subdivision.TRIPLETS)),
            Preset("Song \\ 7/8", MetronomeSettings().withTimeSignature(TimeSignature(7, 8)).cycleAccent(3).copy(sound = ClickSound.COWBELL, trainer = TrainerSettings(true, 2, 8, 140))),
        )
        assertEquals(presets, PresetCodec.decodePresets(PresetCodec.encodePresets(presets)))
    }

    @Test fun brokenRecordsAreSkipped() {
        val good = PresetCodec.encodePresets(listOf(Preset("ok", MetronomeSettings())))
        val decoded = PresetCodec.decodePresets("garbage\nname|9|1|2|3\n$good\n")
        assertEquals(listOf("ok"), decoded.map { it.name })
        assertNull(PresetCodec.decodeSettings("1|120|4|4|AN|NONE|CLICK|0|5|4|160")) // accents don't match beats
    }
}
