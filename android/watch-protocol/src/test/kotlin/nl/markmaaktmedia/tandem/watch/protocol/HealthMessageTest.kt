package nl.markmaaktmedia.tandem.watch.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

class HealthMessageTest {
    private val night = Message.HealthRecord(
        id = "sleep-20261105-230000",
        workout = false,
        startMs = 1_793_918_866_000,
        endMs = 1_793_947_666_000,
        sleepMinutes = 441,
        awakeMinutes = 12,
        awakenings = 2,
        efficiencyPercent = 93,
        lightMinutes = 230,
        deepMinutes = 0,
        remMinutes = 121,
        stages = listOf(StageRun(StageRun.LIGHT, 1800), StageRun(StageRun.REM, 930), StageRun(StageRun.WAKE, 30)),
        heartRateMean = 57,
        heartRateLow = 46,
        heartRateHigh = null,
        steps = 0,
    )

    @Test
    fun aDayCarriesItsHeartRate() {
        val day = night.copy(id = "day-2026-11-07", day = true, activeMinutes = 34, heartCurve = List(288) { if (it % 2 == 0) 0 else 200 })
        val back = MessageCodec.decode(MessageCodec.encode(day)) as Message.HealthRecord
        assertEquals(day, back)
        assertEquals(200, back.heartCurve[1])
    }

    @Test
    fun todaySurvivesTheLink() {
        val today = Message.HeartToday(List(288) { it % 230 })
        assertEquals(today, MessageCodec.decode(MessageCodec.encode(today)))
        assertEquals(Message.HeartAllDay(false), MessageCodec.decode(MessageCodec.encode(Message.HeartAllDay(false))))
    }

    @Test
    fun aDayFromAnOlderWatchHasNoHeartRate() {
        // What an older watch sends stops right after the active minutes: without the heart curve and the steps per hour.
        val old = MessageCodec.encode(night.copy(day = true)).dropLast(2).toByteArray()
        val back = MessageCodec.decode(old) as Message.HealthRecord
        assertEquals(emptyList(), back.heartCurve)
        assertEquals(emptyList(), back.stepCurve)
    }

    @Test
    fun aDayCarriesItsStepsPerHour() {
        val day = night.copy(id = "day-2026-11-09", day = true, steps = 9_300, stepCurve = List(24) { if (it in 8..20) 700 else 15 })
        val back = MessageCodec.decode(MessageCodec.encode(day)) as Message.HealthRecord
        assertEquals(day, back)
        assertEquals(700, back.stepCurve[12])
    }

    @Test
    fun todaysStepsPerHourSurviveTheLink() {
        val status = Message.WatchStatus(61, charging = false, stepsToday = 3_491, activeMinutesToday = 21, stepsByHour = List(24) { it * 40 })
        assertEquals(status, MessageCodec.decode(MessageCodec.encode(status)))
        // A watch from before stops after the active minutes.
        val old = MessageCodec.encode(status.copy(stepsByHour = emptyList())).dropLast(1).toByteArray()
        assertEquals(status.copy(stepsByHour = emptyList()), MessageCodec.decode(old))
    }

    @Test
    fun aNightSurvivesTheLink() {
        assertEquals(night, MessageCodec.decode(MessageCodec.encode(night)))
    }

    @Test
    fun noStagesIsNotTheSameAsZeroMinutes() {
        val unknown = night.copy(lightMinutes = null, deepMinutes = null, remMinutes = null, stages = emptyList())
        assertEquals(unknown, MessageCodec.decode(MessageCodec.encode(unknown)))
    }

    @Test
    fun aWorkoutSurvivesTheLink() {
        val workout = night.copy(id = "workout-1", workout = true, heartRateHigh = 171, steps = 4_200, stages = emptyList())
        assertEquals(workout, MessageCodec.decode(MessageCodec.encode(workout)))
    }

    @Test
    fun theAnswerSurvivesTheLink() {
        assertEquals(Message.HealthAck("a"), MessageCodec.decode(MessageCodec.encode(Message.HealthAck("a"))))
    }

    @Test
    fun aLongNightStaysFarUnderTheMessageLimit() {
        val restless = night.copy(stages = List(2880) { StageRun(it % 4, 30) })
        assert(MessageCodec.encode(restless).size < MAX_MESSAGE_BYTES)
    }
}
