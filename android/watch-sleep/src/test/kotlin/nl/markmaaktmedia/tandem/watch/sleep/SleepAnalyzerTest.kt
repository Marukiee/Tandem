package nl.markmaaktmedia.tandem.watch.sleep

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val T0 = 1_780_000_000_000L
private const val MIN = 60_000L

/** Stages written by minute, as if the model said so. */
private class Script {
    val stages = ArrayList<SleepStage>()
    val worn = ArrayList<Boolean>()
    val hearts = ArrayList<Int?>()
    val motion = ArrayList<Double>()
    val moved = ArrayList<Boolean>()

    fun add(minutes: Int, stage: SleepStage, heart: Int? = 55, wornNow: Boolean = true, hasMotion: Boolean = true, movedNow: Boolean = false): Script {
        repeat(minutes * 2) {
            stages += stage
            worn += wornNow
            hearts += heart
            motion += if (hasMotion) 1.0 else 0.0
            moved += movedNow
        }
        return this
    }

    fun summary(lead: Int = 0): SleepSummary? {
        val night = Night(T0, emptyArray(), motion.toDoubleArray(), moved.toBooleanArray(), worn.toBooleanArray(), hearts.toTypedArray(), lead)
        return SleepAnalyzer.summarize(night, stages.toTypedArray())
    }
}

class SleepAnalyzerTest {
    @Test
    fun `finds the night and adds up the stages`() {
        val summary = assertNotNull(
            Script().add(30, SleepStage.WAKE, 75)
                .add(120, SleepStage.LIGHT).add(60, SleepStage.DEEP).add(10, SleepStage.WAKE, 70).add(90, SleepStage.REM)
                .add(20, SleepStage.WAKE, 75)
                .summary(),
        )
        assertEquals(T0 + 30 * MIN, summary.onsetMs)
        assertEquals(T0 + 310 * MIN, summary.wakeMs)
        assertEquals(270, summary.sleepMinutes)
        assertEquals(10, summary.wakeAfterOnsetMinutes)
        assertEquals(1, summary.awakenings)
        assertEquals(StageMinutes(light = 120, deep = 60, rem = 90), summary.stages)
        assertEquals(96, summary.efficiencyPercent)
        assertEquals(55, summary.lowestHeartRate)
        assertEquals(280 * 2, summary.epochs.size)
    }

    @Test
    fun `a moment that looks like sleep does not move the edges`() {
        val summary = assertNotNull(
            Script().add(20, SleepStage.WAKE).add(2, SleepStage.LIGHT).add(70, SleepStage.WAKE)
                .add(100, SleepStage.LIGHT)
                .add(70, SleepStage.WAKE).add(3, SleepStage.REM).add(5, SleepStage.WAKE)
                .summary(),
        )
        assertEquals(T0 + 92 * MIN, summary.onsetMs)
        assertEquals(100, summary.sleepMinutes)
    }

    @Test
    fun `half an hour is not a night`() {
        assertNull(Script().add(30, SleepStage.WAKE).add(30, SleepStage.LIGHT).add(30, SleepStage.WAKE).summary())
        assertNull(Script().add(90, SleepStage.WAKE).summary())
    }

    @Test
    fun `being up for a long time splits the night and the longest part wins`() {
        val summary = assertNotNull(
            Script().add(90, SleepStage.LIGHT).add(70, SleepStage.WAKE).add(200, SleepStage.LIGHT).summary(),
        )
        assertEquals(T0 + 160 * MIN, summary.onsetMs)
        assertEquals(200, summary.sleepMinutes)
    }

    @Test
    fun `a watch off the wrist or without data is not asleep`() {
        val off = assertNotNull(
            Script().add(100, SleepStage.LIGHT).add(40, SleepStage.LIGHT, wornNow = false).add(100, SleepStage.LIGHT).summary(),
        )
        assertEquals(200, off.sleepMinutes)
        assertEquals(40, off.wakeAfterOnsetMinutes)
        val gap = assertNotNull(
            Script().add(100, SleepStage.LIGHT).add(30, SleepStage.LIGHT, hasMotion = false).add(100, SleepStage.LIGHT).summary(),
        )
        assertEquals(200, gap.sleepMinutes)
    }

    @Test
    fun `no heart rate and no movement for a long time means the watch lies somewhere`() {
        val summary = assertNotNull(
            Script().add(120, SleepStage.LIGHT).add(40, SleepStage.LIGHT, heart = null).add(120, SleepStage.LIGHT).summary(),
        )
        assertEquals(240, summary.sleepMinutes)
    }

    @Test
    fun `a short gap in the heart rate changes nothing`() {
        val summary = assertNotNull(
            Script().add(120, SleepStage.LIGHT).add(10, SleepStage.LIGHT, heart = null).add(120, SleepStage.LIGHT).summary(),
        )
        assertEquals(250, summary.sleepMinutes)
    }

    @Test
    fun `without any heart rate the duration stays but the stages go`() {
        val summary = assertNotNull(Script().add(200, SleepStage.LIGHT, heart = null).summary())
        assertEquals(200, summary.sleepMinutes)
        assertNull(summary.stages)
        assertNull(summary.meanHeartRate)
    }

    @Test
    fun `the time before a recording that started by itself counts as falling asleep`() {
        val summary = assertNotNull(Script().add(100, SleepStage.DEEP).summary(lead = 50))
        // The night starts where the person lay down, and that time is awake in bed, not sleep.
        assertEquals(T0 - 25 * MIN, summary.onsetMs)
        assertEquals(100, summary.sleepMinutes)
        assertEquals(StageMinutes(light = 0, deep = 100, rem = 0), summary.stages)
        assertEquals(SleepStage.WAKE, summary.epochs.first().stage)
        assertEquals(80, summary.efficiencyPercent)
        assertEquals(0, summary.wakeAfterOnsetMinutes)
        // Together with what came before, a bit under an hour of recording is a night too.
        assertNotNull(Script().add(45, SleepStage.LIGHT).summary(lead = 50))
    }

    @Test
    fun `a few minutes awake at the start of a recording are falling asleep`() {
        val summary = assertNotNull(Script().add(10, SleepStage.WAKE).add(100, SleepStage.LIGHT).summary())
        assertEquals(T0, summary.onsetMs)
        assertEquals(100, summary.sleepMinutes)
        assertEquals(SleepStage.WAKE, summary.epochs.first().stage)
        assertEquals(220, summary.epochs.size)
        assertEquals(0, summary.wakeAfterOnsetMinutes)
    }

    @Test
    fun `sleep long after the start owes nothing to the time before the recording`() {
        val summary = assertNotNull(Script().add(60, SleepStage.WAKE).add(100, SleepStage.LIGHT).summary(lead = 50))
        assertEquals(T0 + 60 * MIN, summary.onsetMs)
        assertEquals(100, summary.sleepMinutes)
        assertTrue(summary.epochs.size == 200)
    }

    @Test
    fun `a night becomes stretches of one stage`() {
        val epochs = listOf(SleepStage.LIGHT, SleepStage.LIGHT, SleepStage.DEEP, SleepStage.WAKE, SleepStage.WAKE, SleepStage.WAKE)
            .mapIndexed { i, stage -> SleepEpoch(i * 30_000L, stage, null) }
        val summary = SleepSummary(0, 0, 0, 0, 0, 0, 0, null, null, null, epochs)
        assertEquals(
            listOf(SleepStage.LIGHT to 60, SleepStage.DEEP to 30, SleepStage.WAKE to 90),
            summary.stageRuns(),
        )
    }
}
