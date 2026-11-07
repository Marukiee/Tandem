package nl.markmaaktmedia.tandem.watch.sleep

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val T0 = 1_780_000_000_000L
private const val MIN = 60_000L

private class Day(sleepingHeart: Int? = 58) {
    val detector = NapDetector().apply {
        setAllowed(true)
        setSleepingHeart(sleepingHeart)
    }
    var now = T0
    var steps = 1000L
    val found = mutableListOf<NapGuess>()

    /** A heart rate every five minutes, like the watch measures during the day. Null for none. */
    fun minutes(count: Int, moved: Boolean = false, heart: Int? = 60, usable: Boolean = true): Day {
        repeat(count) {
            if (heart != null && (now - T0) / MIN % 5 == 0L) detector.onHeart(now + 1000, heart)
            detector.onMinute(now, moved, usable, steps)?.let { found += it }
            now += MIN
        }
        return this
    }

    fun skip(minutes: Int): Day {
        now += minutes * MIN
        return this
    }
}

class NapDetectorTest {
    @Test
    fun `a nap ends where the wrist last lay still`() {
        val day = Day().minutes(10, moved = true).minutes(45).minutes(10, moved = true)
        val nap = assertNotNull(day.found.singleOrNull())
        assertEquals(T0 + 10 * MIN, nap.startMs)
        assertEquals(T0 + 55 * MIN, nap.endMs)
        assertEquals(45, nap.sleepMinutes)
        assertEquals(0, nap.awakeMinutes)
        assertEquals(60, nap.meanHeartRate)
    }

    @Test
    fun `a still afternoon far above the sleeping heart rate is no nap`() {
        val day = Day(sleepingHeart = 58).minutes(90, heart = 72).minutes(10, moved = true)
        assertTrue(day.found.isEmpty())
    }

    @Test
    fun `typing at a desk never gets that far`() {
        val day = Day()
        repeat(40) {
            day.minutes(2)
            day.minutes(1, moved = true)
        }
        assertTrue(day.found.isEmpty())
    }

    @Test
    fun `less than twenty still minutes is not worth asking about`() {
        val day = Day().minutes(15).minutes(10, moved = true)
        assertTrue(day.found.isEmpty())
    }

    @Test
    fun `walking with a still arm is no nap`() {
        val day = Day().minutes(25)
        day.steps += 200
        day.minutes(10, moved = true)
        assertTrue(day.found.isEmpty())
    }

    @Test
    fun `a watch that lies somewhere has no heart rate and is no nap`() {
        val day = Day().minutes(60, heart = null).minutes(10, moved = true)
        assertTrue(day.found.isEmpty())
    }

    @Test
    fun `without a known night the heart rate can not rule anything out`() {
        val day = Day(sleepingHeart = null).minutes(30, heart = 72).minutes(10, moved = true)
        assertEquals(1, day.found.size)
    }

    @Test
    fun `a movement or two in a nap stays one nap`() {
        val day = Day().minutes(25).minutes(2, moved = true).minutes(25).minutes(10, moved = true)
        val nap = assertNotNull(day.found.singleOrNull())
        assertEquals(50, nap.sleepMinutes)
        assertEquals(2, nap.awakeMinutes)
    }

    @Test
    fun `taking the watch off ends the stretch`() {
        val day = Day().minutes(30).minutes(1, usable = false)
        assertEquals(1, day.found.size)
        assertEquals(T0 + 30 * MIN, day.found.single().endMs)
    }

    @Test
    fun `no samples for a while end the stretch where they stopped`() {
        val day = Day().minutes(30).skip(30).minutes(1)
        assertEquals(1, day.found.size)
        assertEquals(T0 + 30 * MIN, day.found.single().endMs)
    }

    @Test
    fun `a stretch that is forgotten says nothing`() {
        val day = Day().minutes(30)
        day.detector.setAllowed(false)
        day.minutes(10, moved = true)
        day.detector.setAllowed(true)
        day.minutes(10, moved = true)
        assertTrue(day.found.isEmpty())
    }

    @Test
    fun `three hours is no nap any more`() {
        val day = Day().minutes(200).minutes(10, moved = true)
        assertTrue(day.found.isEmpty())
    }
}

class NapAnalyzerTest {
    private class Script {
        val motion = ArrayList<Double>()
        val moved = ArrayList<Boolean>()
        val worn = ArrayList<Boolean>()
        val hearts = ArrayList<Int?>()

        fun add(minutes: Int, movedNow: Boolean = false, heart: Int? = 60, wornNow: Boolean = true): Script {
            repeat(minutes * 2) {
                motion += 1.0
                moved += movedNow
                worn += wornNow
                hearts += heart
            }
            return this
        }

        fun summary() = NapAnalyzer.analyze(
            Night(T0, emptyArray(), motion.toDoubleArray(), moved.toBooleanArray(), worn.toBooleanArray(), hearts.toTypedArray(), 0),
        )
    }

    @Test
    fun `a nap by hand has a length and a heart rate but no stages`() {
        val nap = assertNotNull(Script().add(5, movedNow = true, heart = 70).add(30).add(5, movedNow = true, heart = 70).summary())
        assertEquals(T0 + 5 * MIN, nap.onsetMs)
        assertEquals(T0 + 35 * MIN, nap.wakeMs)
        assertEquals(30, nap.sleepMinutes)
        assertEquals(60, nap.meanHeartRate)
        assertNull(nap.stages)
        assertTrue(nap.epochs.isEmpty())
    }

    @Test
    fun `a nap without any heart rate still has its length`() {
        val nap = assertNotNull(Script().add(30, heart = null).summary())
        assertEquals(30, nap.sleepMinutes)
        assertNull(nap.meanHeartRate)
    }

    @Test
    fun `less than ten still minutes is no nap`() {
        assertNull(Script().add(8).add(5, movedNow = true).summary())
    }

    @Test
    fun `a short movement stays inside the nap`() {
        val nap = assertNotNull(Script().add(20).add(3, movedNow = true).add(20).summary())
        assertEquals(40, nap.sleepMinutes)
        assertEquals(3, nap.wakeAfterOnsetMinutes)
        assertEquals(1, nap.awakenings)
    }

    @Test
    fun `being up for long splits it and the longest part wins`() {
        val nap = assertNotNull(Script().add(15).add(20, movedNow = true).add(30).summary())
        assertEquals(30, nap.sleepMinutes)
        assertEquals(T0 + 35 * MIN, nap.onsetMs)
    }

    @Test
    fun `a watch off the wrist is not asleep`() {
        assertNull(Script().add(40, wornNow = false).summary())
    }
}
