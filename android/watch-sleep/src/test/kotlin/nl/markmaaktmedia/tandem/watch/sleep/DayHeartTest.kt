package nl.markmaaktmedia.tandem.watch.sleep

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DayHeartTest {
    @Test
    fun `values in the same five minutes become one`() {
        val day = DayHeart("2026-11-07")
        day.add(600, 60)
        day.add(604, 70)
        day.add(605, 90)
        val curve = day.curve()
        assertEquals(288, curve.size)
        assertEquals(65, curve[120])
        assertEquals(90, curve[121])
        assertEquals(0, curve[119])
    }

    @Test
    fun `low, mean and high look at what was measured only`() {
        val day = DayHeart("2026-11-07")
        day.add(0, 50)
        day.add(720, 70)
        day.add(1439, 120)
        assertEquals(50, day.low)
        assertEquals(120, day.high)
        assertEquals(80, day.mean)
        assertEquals(120, day.latest)
    }

    @Test
    fun `a day without measurements has no numbers`() {
        val day = DayHeart("2026-11-07")
        assertNull(day.low)
        assertNull(day.high)
        assertNull(day.mean)
        assertNull(day.latest)
    }

    @Test
    fun `what can not be a heart rate is left out`() {
        val day = DayHeart("2026-11-07")
        day.add(10, 0)
        day.add(10, 400)
        day.add(-1, 60)
        day.add(1440, 60)
        assertNull(day.mean)
    }

    @Test
    fun `a day survives being written and read`() {
        val day = DayHeart("2026-11-07")
        day.add(600, 61)
        day.add(601, 64)
        val read = DayHeart.fromBytes("2026-11-07", day.toBytes())
        assertEquals(day.curve(), read.curve())
        // It goes on counting from where it was, not from the rounded value.
        read.add(602, 70)
        assertEquals(65, read.curve()[120])
    }

    @Test
    fun `a broken file starts the day over`() {
        assertNull(DayHeart.fromBytes("2026-11-07", ByteArray(5)).mean)
        assertNull(DayHeart.fromBytes("2026-11-07", null).mean)
    }

    @Test
    fun `a measurement leaves out its first value and what the sensor did not trust`() {
        val samples = listOf(HeartSample(140, 3), HeartSample(61, 3), HeartSample(62, 2), HeartSample(90, 0), HeartSample(63, 3))
        assertEquals(62, burstHeartRate(samples))
    }

    @Test
    fun `too few values measure nothing`() {
        assertNull(burstHeartRate(emptyList()))
        assertNull(burstHeartRate(listOf(HeartSample(60, 3), HeartSample(61, 3), HeartSample(62, 3))))
        assertNull(burstHeartRate(List(6) { HeartSample(70, 0) }))
    }
}
