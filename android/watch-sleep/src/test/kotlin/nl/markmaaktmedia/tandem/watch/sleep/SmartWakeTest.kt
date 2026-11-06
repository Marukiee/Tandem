package nl.markmaaktmedia.tandem.watch.sleep

import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SmartWakeTest {
    private val alarm = 10_000_000L

    private fun still(wake: SmartWake, from: Long, until: Long): Long? {
        var t = from
        while (t < until) {
            if (wake.onSample(t, 0f, 0f, 1f)) return t
            t += 200
        }
        return null
    }

    @Test
    fun `nothing happens before the half hour before the alarm`() {
        val wake = SmartWake(alarm)
        var t = alarm - 40 * 60_000
        while (t < wake.windowStartMs) {
            val angle = Math.toRadians((t / 1000 % 90).toDouble())
            assertFalse(wake.onSample(t, sin(angle).toFloat(), 0f, cos(angle).toFloat()))
            t += 200
        }
    }

    @Test
    fun `a wrist that moves in the half hour wakes at once`() {
        val wake = SmartWake(alarm)
        val start = wake.windowStartMs
        assertEquals(null, still(wake, start, start + 5 * 60_000))
        var t = start + 5 * 60_000
        var woke = false
        while (t < start + 6 * 60_000 && !woke) {
            val angle = Math.toRadians(((t - start) / 100 % 90).toDouble())
            woke = wake.onSample(t, sin(angle).toFloat(), 0f, cos(angle).toFloat())
            t += 200
        }
        assertTrue(woke)
    }

    @Test
    fun `someone who lies still is woken shortly before the alarm`() {
        val wake = SmartWake(alarm)
        assertEquals(alarm - SmartWake.LAST_CHANCE_MS, still(wake, wake.windowStartMs, alarm))
    }

    @Test
    fun `it happens only once`() {
        val wake = SmartWake(alarm)
        still(wake, wake.windowStartMs, alarm)
        assertFalse(wake.onSample(alarm - 1000, 0f, 0f, 1f))
    }

    @Test
    fun `after the alarm it stays out of the way`() {
        assertFalse(SmartWake(alarm).onSample(alarm + 1000, 0f, 0f, 1f))
    }
}
