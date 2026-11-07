package nl.markmaaktmedia.tandem.watch.sound

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FindToneTest {
    private val tone = FindTone.cycle()

    @Test
    fun oneRoundLastsThreeChirpsAndAPause() {
        // 3 x 110 ms of chirp, 2 x 60 ms between them and 420 ms of quiet.
        assertEquals(FindTone.SAMPLE_RATE * 870 / 1000, tone.size)
    }

    @Test
    fun itLoopsWithoutAClick() {
        assertTrue(abs(tone.first().toInt()) < 200, "starts at ${tone.first()}")
        assertTrue(abs(tone.last().toInt()) < 200, "ends at ${tone.last()}")
    }

    @Test
    fun itIsLoudWithoutClipping() {
        val peak = tone.maxOf { abs(it.toInt()) }
        assertTrue(peak > Short.MAX_VALUE * 0.9, "peak $peak")
        assertTrue(peak < Short.MAX_VALUE, "peak $peak")
    }

    @Test
    fun theChirpsSitWhereASmallSpeakerIsLoudest() {
        val chirp = FindTone.SAMPLE_RATE * 110 / 1000
        val crossings = (1 until chirp).count { (tone[it - 1] < 0) != (tone[it] < 0) }
        val hz = crossings / 2.0 / 0.110
        assertTrue(hz in 2_000.0..3_600.0, "about $hz Hz")
    }
}
