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

class WakePulseTest {
    @Test
    fun `one buzz lasts five to eight seconds`() {
        val total = WakePulse.timings.sum()
        assertEquals(WakePulse.LENGTH_MS, total)
        assertTrue(total in 5_000..8_000, "lasts $total ms")
    }

    @Test
    fun `it only ever grows, from soft to full`() {
        val strengths = WakePulse.amplitudes
        assertEquals(WakePulse.timings.size, strengths.size)
        assertTrue(strengths.first() in 1..40, "starts at ${strengths.first()}")
        assertEquals(255, strengths.last())
        strengths.toList().zipWithNext().forEach { (before, after) -> assertTrue(after >= before) }
    }
}
