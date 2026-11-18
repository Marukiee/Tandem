package nl.markmaaktmedia.tandem.watch.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import nl.markmaaktmedia.tandem.watch.protocol.ScanPolicy.Speed

class ScanPolicyTest {
    private val minute = 60_000L

    @Test
    fun theFirstMinuteAfterALossIsFast() {
        assertEquals(Speed.LOW_LATENCY, ScanPolicy.speed(lostAt = 0, nowMs = 0, fastFor = null))
        assertEquals(Speed.LOW_LATENCY, ScanPolicy.speed(0, 59_000, null))
        assertEquals(Speed.BALANCED, ScanPolicy.speed(0, minute, null))
        assertEquals(Speed.LOW_POWER, ScanPolicy.speed(0, 2 * minute, null))
    }

    @Test
    fun aRetryInTheSameLossKeepsItsFastMinute() {
        assertEquals(Speed.LOW_LATENCY, ScanPolicy.speed(lostAt = 100, nowMs = 5_000, fastFor = 100))
    }

    @Test
    fun aLinkThatComesAndGoesIsNotLookedForAtFullSpeedEveryTime() {
        // Lost at 0 and fast; lost again at 3 minutes: that one gets no fast minute.
        assertEquals(Speed.BALANCED, ScanPolicy.speed(lostAt = 3 * minute, nowMs = 3 * minute, fastFor = 0))
        assertEquals(Speed.LOW_POWER, ScanPolicy.speed(lostAt = 3 * minute, nowMs = 6 * minute, fastFor = 0))
    }

    @Test
    fun aLossLongAfterTheLastOneIsFastAgain() {
        assertEquals(Speed.LOW_LATENCY, ScanPolicy.speed(lostAt = 10 * minute, nowMs = 10 * minute, fastFor = 0))
    }
}
