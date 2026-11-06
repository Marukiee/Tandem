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
}
