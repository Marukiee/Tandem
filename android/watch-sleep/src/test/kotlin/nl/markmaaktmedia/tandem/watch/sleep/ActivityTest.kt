package nl.markmaaktmedia.tandem.watch.sleep

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkoutAnalyzerTest {
    @Test
    fun `summarises heart rate, steps and duration`() {
        val summary = WorkoutAnalyzer.analyze(
            listOf(
                RawRecord.Steps(0, 1000),
                RawRecord.HeartRate(1, 100, 3),
                RawRecord.HeartRate(2, 0, 0),
                RawRecord.HeartRate(3, 140, 3),
                RawRecord.Steps(30 * 60_000L, 3500),
            ),
        )!!
        assertEquals(30, summary.minutes)
        assertEquals(120, summary.meanHeartRate)
        assertEquals(140, summary.highestHeartRate)
        assertEquals(2500, summary.steps)
    }

    @Test
    fun `nothing recorded gives nothing`() {
        assertNull(WorkoutAnalyzer.analyze(emptyList()))
    }
}

class DailyStepsTest {
    @Test
    fun `counts during the day and starts over the next day`() {
        var state = DailySteps().update("a", 1000)
        assertEquals(0, state.steps)
        state = state.update("a", 1500)
        assertEquals(500, state.steps)
        state = state.update("b", 1600)
        assertEquals(100, state.steps)
    }

    @Test
    fun `a restart of the watch never gives a negative count`() {
        var state = DailySteps().update("a", 1000).update("a", 1200)
        state = state.update("a", 50)
        assertEquals(250, state.steps)
    }

    @Test
    fun `a brisk minute counts once`() {
        var state = DailySteps().update("a", 1000, 10)
        for (total in 1010L..1150L step 10) state = state.update("a", total, 11)
        assertEquals(1, state.activeMinutes)
    }

    @Test
    fun `an easy walk is no active minute`() {
        var state = DailySteps().update("a", 1000, 10)
        for (minute in 11L..20L) state = state.update("a", 1000 + (minute - 10) * 40, minute)
        assertEquals(0, state.activeMinutes)
    }

    @Test
    fun `minutes reported in one go count as the minutes they were`() {
        val state = DailySteps().update("a", 1000, 10).update("a", 1500, 15)
        assertEquals(5, state.activeMinutes)
        val steady = DailySteps().update("a", 1000, 10).update("a", 1200, 15)
        assertEquals(2, steady.activeMinutes)
    }

    @Test
    fun `a new day starts its active minutes from nothing`() {
        val state = DailySteps().update("a", 1000, 10).update("a", 1200, 11).update("b", 1210, 12)
        assertEquals(0, state.activeMinutes)
        assertEquals(10, state.steps)
    }

    @Test
    fun `steps land in the hour they were taken in, and a new day starts at zero`() {
        var steps = DailySteps().update("2026-11-10", 1_000, atMinute = 0, atHour = 7)
        steps = steps.update("2026-11-10", 1_400, atMinute = 5, atHour = 7)
        steps = steps.update("2026-11-10", 2_000, atMinute = 70, atHour = 8)
        assertEquals(24, steps.hourly.size)
        assertEquals(400, steps.hourly[7])
        assertEquals(600, steps.hourly[8])
        assertEquals(steps.steps.toInt(), steps.hourly.sum())
        val next = steps.update("2026-11-11", 2_100, atMinute = 900, atHour = 0)
        assertEquals(100, next.hourly[0])
        assertEquals(0, next.hourly[8])
    }
}
