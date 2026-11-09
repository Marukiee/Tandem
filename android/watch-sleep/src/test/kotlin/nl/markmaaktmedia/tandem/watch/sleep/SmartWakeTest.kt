package nl.markmaaktmedia.tandem.watch.sleep

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SmartWakeTest {
    private val alarm = 10_000_000L

    @Test
    fun `nothing happens before the five minutes before the alarm`() {
        val wake = SmartWake(alarm)
        assertEquals(alarm - 5 * 60_000, wake.windowStartMs)
        assertFalse(wake.isTime(wake.windowStartMs - 1))
    }

    @Test
    fun `it starts five minutes before the alarm`() {
        assertTrue(SmartWake(alarm).isTime(alarm - 5 * 60_000))
    }

    @Test
    fun `a batch that arrives late still wakes in time`() {
        assertTrue(SmartWake(alarm).isTime(alarm - 60_000))
    }

    @Test
    fun `it happens only once`() {
        val wake = SmartWake(alarm)
        assertTrue(wake.isTime(wake.windowStartMs))
        assertFalse(wake.isTime(wake.windowStartMs + 1000))
    }

    @Test
    fun `after the alarm it stays out of the way`() {
        assertFalse(SmartWake(alarm).isTime(alarm))
        assertFalse(SmartWake(alarm).isTime(alarm + 1000))
    }
}

class WakeSwellTest {
    private val window = SmartWake.WINDOW_MS
    private val amplitudes = WakeSwell.waveform(0, window).second

    @Test
    fun `it fills the whole window without a break`() {
        assertEquals(window, WakeSwell.waveform(0, window).first.sum())
        assertTrue(amplitudes.all { it in 1..255 }, "a 0 would be a break")
    }

    @Test
    fun `it starts from nothing and never gets strong`() {
        assertTrue(amplitudes.first() < 10, "starts at ${amplitudes.first()}")
        assertTrue(amplitudes.max() <= 70, "at most ${amplitudes.max()} of 255")
    }

    @Test
    fun `it rises and falls a few times, each time a little more`() {
        // Runs of the same strength as one, so a flat top or a slow step counts once.
        val levels = amplitudes.toList().fold(mutableListOf<Int>()) { list, level -> list.apply { if (isEmpty() || last() != level) add(level) } }
        val peaks = (1 until levels.lastIndex).filter { levels[it] > levels[it - 1] && levels[it] > levels[it + 1] }.map { levels[it] }
        assertEquals(4, peaks.size, "peaks $peaks")
        peaks.zipWithNext().forEach { (before, after) -> assertTrue(after > before, "peaks $peaks") }
    }

    @Test
    fun `it never jolts`() {
        amplitudes.toList().zipWithNext().forEach { (before, after) -> assertTrue(kotlin.math.abs(after - before) <= 2, "$before to $after") }
    }

    @Test
    fun `a window that began earlier picks up where it is`() {
        val (timings, rest) = WakeSwell.waveform(window / 2, window)
        assertEquals(window / 2, timings.sum())
        assertEquals(WakeSwell.amplitude(WakeSwell.strength(0.5 + WakeSwell.STEP_MS / 2.0 / window)), rest.first())
    }

    @Test
    fun `the chosen strength moves the top of the swell, and the default is the swell as drawn`() {
        val window = SmartWake.WINDOW_MS
        val drawn = WakeSwell.waveform(0, window).second.max()
        assertEquals(drawn, WakeSwell.waveform(0, window, WakeSwell.DRAWN_TOP_PERCENT).second.max())
        val soft = WakeSwell.waveform(0, window, 20).second.max()
        val strong = WakeSwell.waveform(0, window, 100).second.max()
        kotlin.test.assertTrue(soft < drawn && drawn < strong, "soft $soft, drawn $drawn, strong $strong")
        assertEquals(255, strong)
    }
}
