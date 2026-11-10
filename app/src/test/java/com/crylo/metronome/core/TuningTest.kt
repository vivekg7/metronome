package com.crylo.metronome.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class TuningTest {
    @Test fun midiAndFrequencyRoundTrip() {
        assertEquals(69.0, NoteMath.midiOf(440.0), 1e-9)
        assertEquals(60.0, NoteMath.midiOf(261.6256), 1e-4)
        assertEquals(82.4069, NoteMath.frequencyOf(40), 1e-3)
        assertEquals(432.0, NoteMath.frequencyOf(69, 432.0), 1e-9)
        assertEquals(880.0, NoteMath.frequencyOf(81), 1e-9)
    }

    @Test fun namesAndOctaves() {
        assertEquals("A4", NoteMath.label(69))
        assertEquals("C4", NoteMath.label(60))
        assertEquals("B3", NoteMath.label(59))
        assertEquals("C♯5", NoteMath.label(73))
        assertEquals("E1", NoteMath.label(28))
        assertEquals("C-1", NoteMath.label(0))
    }

    @Test fun centsAndNearestNote() {
        assertEquals(100.0, NoteMath.cents(NoteMath.frequencyOf(70), 440.0), 1e-9)
        assertEquals(-1200.0, NoteMath.cents(220.0, 440.0), 1e-9)
        val sharp = 440.0 * Math.pow(2.0, 20 / 1200.0)
        assertEquals(69, NoteMath.nearest(sharp))
        assertEquals(20.0, NoteMath.cents(sharp, NoteMath.frequencyOf(69)), 1e-9)
        // 49 cents sharp of A is still A; 51 cents sharp is A sharp.
        assertEquals(69, NoteMath.nearest(440.0 * Math.pow(2.0, 49 / 1200.0)))
        assertEquals(70, NoteMath.nearest(440.0 * Math.pow(2.0, 51 / 1200.0)))
    }

    @Test fun calibrationShiftsTheReference() {
        // 440 Hz with A4 = 442 is about 7.85 cents flat of A.
        assertEquals(69, NoteMath.nearest(440.0, 442.0))
        assertEquals(-7.85, NoteMath.cents(440.0, NoteMath.frequencyOf(69, 442.0)), 0.01)
    }

    @Test fun nearestString() {
        assertNull(Instrument.CHROMATIC.nearestString(440.0))
        assertEquals(0, Instrument.GUITAR.nearestString(80.0)) // flat low E
        assertEquals(1, Instrument.GUITAR.nearestString(112.0)) // sharp A
        assertEquals(5, Instrument.GUITAR.nearestString(330.0))
        assertEquals(3, Instrument.UKULELE.nearestString(440.0)) // A4 is the fourth string
        assertEquals(0, Instrument.BASS.nearestString(41.0))
        assertEquals(3, Instrument.VIOLIN.nearestString(660.0))
    }

    @Test fun processorTargetsNearestStringInInstrumentMode() {
        val p = TunerProcessor().apply { instrument = Instrument.GUITAR }
        // 100 Hz is between G2 and G#2, but on a guitar it is a flat A string.
        val r = p.process(100.0, 0)!!
        assertEquals(1, r.stringIndex)
        assertEquals(45, r.midi)
        assertEquals(NoteMath.cents(100.0, 110.0), r.cents, 1e-9)
    }

    @Test fun processorSmoothsAndRestartsOnNoteChange() {
        val p = TunerProcessor(smoothing = 0.5)
        val a = NoteMath.frequencyOf(69)
        val tenSharp = a * Math.pow(2.0, 10 / 1200.0)
        repeat(3) { p.process(a, it.toLong()) }
        assertEquals(0.0, p.process(a, 3)!!.cents, 1e-9)
        // Median of [a, a, sharp] is still a; the needle only moves once the change persists.
        assertEquals(0.0, p.process(tenSharp, 4)!!.cents, 1e-9)
        assertEquals(5.0, p.process(tenSharp, 5)!!.cents, 1e-6)
        assertEquals(7.5, p.process(tenSharp, 6)!!.cents, 1e-6)
        // A new note resets the smoothing instead of gliding from the old value.
        val e5 = NoteMath.frequencyOf(76) * Math.pow(2.0, -4 / 1200.0)
        repeat(2) { p.process(e5, 7L + it) }
        val r = p.process(e5, 9)!!
        assertEquals(76, r.midi)
        assertEquals(-4.0, r.cents, 1e-6)
    }

    @Test fun processorHoldsThenClears() {
        val p = TunerProcessor(holdMs = 1000)
        val reading = p.process(440.0, 0)
        assertSame(reading, p.process(null, 500))
        assertNull(p.process(null, 1500))
    }
}
