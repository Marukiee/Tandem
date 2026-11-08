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

    @Test
    fun `it fills the whole window without a break`() {
        val (timings, amplitudes) = WakeSwell.waveform(0, window)
        assertEquals(window, timings.sum())
        assertTrue(amplitudes.all { it in 1..255 }, "a 0 would be a break")
    }

    @Test
    fun `it starts from nothing and never reaches full strength before the alarm`() {
        val (_, amplitudes) = WakeSwell.waveform(0, window)
        assertTrue(amplitudes.first() < 10, "starts at ${amplitudes.first()}")
        val strongest = amplitudes.indices.maxBy { amplitudes[it] }
        assertTrue(strongest.toDouble() / amplitudes.size in 0.75..0.9, "highest at ${strongest.toDouble() / amplitudes.size}")
        assertTrue(amplitudes[strongest] in 100..160, "highest ${amplitudes[strongest]}")
        assertTrue(amplitudes.last() < amplitudes[strongest] / 2, "ends at ${amplitudes.last()}")
    }

    @Test
    fun `the first hill is soft and the dip after it softer`() {
        val hill = WakeSwell.amplitude(WakeSwell.strength(0.31))
        val dip = WakeSwell.amplitude(WakeSwell.strength(0.5))
        assertTrue(hill in 25..70, "hill $hill")
        assertTrue(dip < hill / 2, "dip $dip")
    }

    @Test
    fun `only someone still asleep after the alarm gets full strength`() {
        val (timings, amplitudes) = WakeSwell.waveform(0, window, WakeSwell.MAX_AFTER_MS)
        assertEquals(window + WakeSwell.MAX_AFTER_MS, timings.sum())
        val atAlarm = (window / WakeSwell.STEP_MS).toInt()
        val risen = atAlarm + (WakeSwell.RISE_AFTER_MS / WakeSwell.STEP_MS).toInt()
        assertTrue(amplitudes.take(atAlarm).all { it < 255 })
        assertEquals(255, amplitudes[risen])
        assertEquals(255, amplitudes.last())
    }

    @Test
    fun `it never jolts, not at the alarm either`() {
        val (_, amplitudes) = WakeSwell.waveform(0, window, WakeSwell.MAX_AFTER_MS)
        amplitudes.toList().zipWithNext().forEach { (before, after) -> assertTrue(kotlin.math.abs(after - before) <= 4, "$before to $after") }
    }

    @Test
    fun `a window that began earlier picks up where it is`() {
        val (timings, amplitudes) = WakeSwell.waveform(window / 2, window)
        assertEquals(window / 2, timings.sum())
        assertEquals(WakeSwell.amplitude(WakeSwell.strength(0.5 + WakeSwell.STEP_MS / 2.0 / window)), amplitudes.first())
    }
}

class AwakeAfterAlarmTest {
    private val alarm = 1_000_000L

    private fun still(watch: AwakeAfterAlarm, from: Long, until: Long) {
        var t = from
        while (t < until) {
            watch.add(t, 0f, 0f, 1f)
            t += 40
        }
    }

    @Test
    fun `turning over before the alarm says nothing`() {
        val watch = AwakeAfterAlarm(alarm)
        var t = alarm - 30_000
        while (t < alarm) {
            watch.add(t, if ((t / 2000) % 2 == 0L) 0f else 0.9f, 0f, if ((t / 2000) % 2 == 0L) 1f else 0.4f)
            t += 40
        }
        assertFalse(watch.awake)
    }

    @Test
    fun `a wrist that lies still after the alarm is not awake, one that moves is`() {
        val watch = AwakeAfterAlarm(alarm)
        still(watch, alarm, alarm + 20_000)
        assertFalse(watch.awake)
        var t = alarm + 20_000
        while (t < alarm + 35_000) {
            watch.add(t, 0.7f, 0.2f, 0.6f)
            t += 40
        }
        assertTrue(watch.awake)
    }
}
