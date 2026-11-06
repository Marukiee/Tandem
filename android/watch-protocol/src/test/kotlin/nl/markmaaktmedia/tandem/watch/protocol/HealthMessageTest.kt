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
