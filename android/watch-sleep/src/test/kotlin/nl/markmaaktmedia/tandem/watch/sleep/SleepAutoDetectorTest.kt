package nl.markmaaktmedia.tandem.watch.sleep

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val MINUTE = 60_000L
private const val STEP = 200L

class SleepAutoDetectorTest {
    private class Run(val detector: SleepAutoDetector = SleepAutoDetector()) {
        var now = 1_000_000_000L
        val decisions = mutableListOf<Pair<Long, AutoSleepDecision>>()

        fun still(minutes: Int) = feed(minutes) { 0.0 }

        /** A wrist whose angle keeps swinging. */
        fun active(minutes: Int) = feed(minutes) { t -> 40.0 * sin(2 * PI * t / 7_000.0) }

        /** Still, with one change of posture every [every] minutes. */
        fun restless(minutes: Int, every: Int) {
            repeat(minutes) { m ->
                if (m % every == 0) feed(1) { t -> 30.0 * sin(2 * PI * t / 7_000.0) } else feed(1) { 0.0 }
            }
        }

        /** Level wrist with the small shaking of a hand that types. */
        fun typing(minutes: Int) = feed(minutes, shake = 0.05f) { 0.0 }

        private fun feed(minutes: Int, shake: Float = 0f, degrees: (Long) -> Double) {
            val end = now + minutes * MINUTE
            var flip = false
            while (now < end) {
                val a = degrees(now) * PI / 180.0
                flip = !flip
                val z = (sin(a) + if (flip) shake else -shake).toFloat()
                detector.onSample(now, 0f, cos(a).toFloat(), z)?.let { decisions += now to it }
                now += STEP
            }
        }
    }

    @Test
    fun `a night starts after lying still and ends after getting up`() {
        val run = Run()
        run.active(30)
        val asleepAt = run.now
        run.still(40)
        val start = assertIs<AutoSleepDecision.Start>(run.decisions.single().second)
        assertTrue(kotlin.math.abs(start.stillSinceMs - asleepAt) <= 3 * MINUTE, "started ${(start.stillSinceMs - asleepAt) / MINUTE} min off")
        run.restless(7 * 60, every = 20)
        assertEquals(1, run.decisions.size, "ordinary turning over must not end the night")
        val upAt = run.now
        run.active(15)
        assertIs<AutoSleepDecision.Stop>(run.decisions.last().second)
        assertTrue(run.decisions.last().first - upAt <= 12 * MINUTE)
    }

    @Test
    fun `a short time awake at night does not end it`() {
        val run = Run()
        run.still(30)
        run.active(5)
        run.still(30)
        assertEquals(1, run.decisions.size)
        assertTrue(run.detector.isRecording)
    }

    @Test
    fun `no night starts outside the window, on the charger or off the wrist`() {
        Run().apply { detector.setAllowed(false); still(60); assertTrue(decisions.isEmpty()) }
        Run().apply { detector.setCharging(true); still(60); assertTrue(decisions.isEmpty()) }
        Run().apply { detector.setWorn(false); still(60); assertTrue(decisions.isEmpty()) }
    }

    @Test
    fun `sitting at a desk does not start a night`() {
        Run().apply { typing(60); assertTrue(decisions.isEmpty()) }
        Run().apply { restless(120, every = 5); assertTrue(decisions.isEmpty()) }
    }

    @Test
    fun `using the watch breaks the stillness`() {
        val run = Run()
        repeat(12) {
            run.still(5)
            run.detector.onInteraction()
        }
        assertTrue(run.decisions.isEmpty())
    }

    @Test
    fun `taking the watch off or charging it ends the night`() {
        Run().apply {
            still(30)
            detector.setWorn(false)
            still(15)
            assertIs<AutoSleepDecision.Stop>(decisions.last().second)
        }
        Run().apply {
            still(30)
            detector.setCharging(true)
            still(5)
            assertIs<AutoSleepDecision.Stop>(decisions.last().second)
        }
    }

    @Test
    fun `stopped by hand it stays out of the way for a while`() {
        val run = Run()
        run.still(30)
        run.detector.onStoppedByHand(run.now)
        run.still(120)
        assertEquals(1, run.decisions.size)
        run.still(120)
        assertEquals(2, run.decisions.size)
    }

    @Test
    fun `a gap in the data breaks the stillness`() {
        val run = Run()
        run.still(20)
        run.now += 30 * MINUTE
        run.still(10)
        assertTrue(run.decisions.isEmpty())
    }

    @Test
    fun `the window covers the night`() {
        assertTrue(SleepAutoDetector.inWindow(23))
        assertTrue(SleepAutoDetector.inWindow(3))
        assertFalse(SleepAutoDetector.inWindow(15))
        assertEquals(165, SleepAutoDetector.minutesToWindowChange(18, 15))
        assertEquals(14 * 60, SleepAutoDetector.minutesToWindowChange(21, 0))
        assertEquals(1, SleepAutoDetector.minutesToWindowChange(10, 59))
    }
}
