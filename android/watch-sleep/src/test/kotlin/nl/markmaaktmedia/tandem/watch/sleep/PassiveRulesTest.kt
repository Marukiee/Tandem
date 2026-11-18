package nl.markmaaktmedia.tandem.watch.sleep

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PassiveHeartRulesTest {
    private val slot = PassiveHeartRules.SLOT_MS
    private fun heart(at: Long, bpm: Int, reliable: Boolean = true) = PassiveHeart(at, bpm, reliable)

    @Test
    fun manyValuesInOneSlotBecomeOneMiddleValue() {
        val values = PassiveHeartRules.perSlot(listOf(heart(0, 70), heart(10_000, 90), heart(20_000, 72), heart(30_000, 71)))
        assertEquals(listOf(SlotHeart(30_000, 72)), values)
    }

    @Test
    fun eachSlotGetsItsOwnValueOldestFirst() {
        val values = PassiveHeartRules.perSlot(listOf(heart(2 * slot + 5, 80), heart(5, 60), heart(slot + 5, 70)))
        assertEquals(listOf(60, 70, 80), values.map { it.bpm })
        assertEquals(listOf(5L, slot + 5, 2 * slot + 5), values.map { it.timeMs })
    }

    @Test
    fun valuesTheSensorDoesNotStandBehindAreLeftOut() {
        assertTrue(PassiveHeartRules.perSlot(listOf(heart(0, 70, reliable = false), heart(1, 20), heart(2, 300))).isEmpty())
        assertEquals(listOf(70), PassiveHeartRules.perSlot(listOf(heart(0, 70), heart(1, 255, reliable = false))).map { it.bpm })
    }

    @Test
    fun aGoodValueFromTheSystemKeepsOurOwnMeasurementOff() {
        val now = 100 * slot
        assertFalse(PassiveHeartRules.needsOwnMeasurement(now - 2 * slot, now))
        assertFalse(PassiveHeartRules.needsOwnMeasurement(now - PassiveHeartRules.FRESH_MS, now))
    }

    @Test
    fun withoutRecentGoodValuesWeMeasureOurselves() {
        val now = 100 * slot
        // Never heard anything: no Health Services, no permission, or a silent feed.
        assertTrue(PassiveHeartRules.needsOwnMeasurement(0, now))
        assertTrue(PassiveHeartRules.needsOwnMeasurement(now - PassiveHeartRules.FRESH_MS - 1, now))
        // A clock that went back must not keep it off for hours.
        assertTrue(PassiveHeartRules.needsOwnMeasurement(now + 2 * slot, now))
    }
}

class StepFeedTest {
    private val hour = 60 * 60_000L

    @Test
    fun withoutHealthServicesTheCounterCounts() {
        val feed = StepFeed(StepFeed.Source.COUNTER)
        assertEquals(StepFeed.Feed(StepFeed.Source.COUNTER, 1_000, null), feed.onCounter(1_000, 0))
        assertEquals(StepFeed.Source.COUNTER, feed.counting(10 * hour))
    }

    @Test
    fun onceRegisteredHealthServicesCountsAndTheCounterOnlyWatches() {
        val feed = StepFeed(StepFeed.Source.COUNTER)
        feed.onCounter(1_000, 0)
        feed.registered(1)
        assertNull(feed.onCounter(1_100, 2))
        // Its first total is counted from nothing, so the day is not counted twice.
        assertEquals(StepFeed.Feed(StepFeed.Source.HEALTH_SERVICES, 3_500, -1), feed.onDaily(3_500, 3))
        assertEquals(StepFeed.Feed(StepFeed.Source.HEALTH_SERVICES, 3_600, null), feed.onDaily(3_600, 4))
        assertNull(feed.onCounter(1_300, 5))
    }

    @Test
    fun aDeliveryAloneProvesItIsRegistered() {
        val feed = StepFeed(StepFeed.Source.COUNTER)
        // The system started the app because Health Services had something, before registering ran again.
        assertEquals(StepFeed.Feed(StepFeed.Source.HEALTH_SERVICES, 40, -1), feed.onDaily(40, 0))
        assertNull(feed.onCounter(1_000, 1))
    }

    @Test
    fun aSilentHealthServicesWhileTheCounterMovesHandsOverToTheCounter() {
        val feed = StepFeed(StepFeed.Source.HEALTH_SERVICES)
        feed.registered(0)
        feed.onCounter(1_000, 0)
        feed.onDaily(500, hour / 2)
        // Counted since the last word of Health Services: 1_000 was the counter at that moment.
        assertNull(feed.onCounter(1_200, hour))
        assertNull(feed.onCounter(1_400, hour + hour / 2 + 1))
        // Silent for more than two hours while the counter moved: it counts from where it stood when Health Services last spoke.
        assertEquals(StepFeed.Feed(StepFeed.Source.COUNTER, 1_600, 1_000), feed.onCounter(1_600, 3 * hour))
    }

    @Test
    fun theCounterTakesOverFromTheLastTotalItSawWithHealthServices() {
        val feed = StepFeed(StepFeed.Source.HEALTH_SERVICES)
        feed.onCounter(1_000, 0)
        feed.registered(1)
        feed.onDaily(500, 2)
        feed.onCounter(1_100, 3)
        val handOver = feed.onCounter(1_300, 3 * hour)
        assertEquals(1_000L, handOver?.baseline)
        assertEquals(1_300L, handOver?.total)
        assertEquals(StepFeed.Source.COUNTER, feed.fedBy)
        assertEquals(StepFeed.Feed(StepFeed.Source.COUNTER, 1_800, null), feed.onCounter(1_800, 3 * hour + 1))
    }

    @Test
    fun healthServicesSpeakingAgainTakesItBackWithoutCountingTheDayTwice() {
        val feed = StepFeed(StepFeed.Source.HEALTH_SERVICES)
        feed.onCounter(1_000, 0)
        feed.registered(1)
        feed.onDaily(500, 2)
        feed.onCounter(1_300, 3 * hour)
        feed.onCounter(1_800, 3 * hour + 1)
        assertEquals(StepFeed.Feed(StepFeed.Source.HEALTH_SERVICES, 2_500, -1), feed.onDaily(2_500, 4 * hour))
        assertEquals(StepFeed.Feed(StepFeed.Source.HEALTH_SERVICES, 2_600, null), feed.onDaily(2_600, 4 * hour + 1))
    }

    @Test
    fun aSilentHealthServicesWhileNobodyWalksChangesNothing() {
        val feed = StepFeed(StepFeed.Source.HEALTH_SERVICES)
        feed.onCounter(1_000, 0)
        feed.registered(1)
        feed.onDaily(500, 2)
        assertNull(feed.onCounter(1_000, 10 * hour))
        assertEquals(StepFeed.Source.HEALTH_SERVICES, feed.counting(10 * hour))
    }

    @Test
    fun aRegistrationThatNeverDeliversFallsBackOnceTheCounterMoved() {
        val feed = StepFeed(null)
        feed.onCounter(5_000, 0)
        feed.registered(10)
        assertNull(feed.onCounter(5_050, hour))
        // Health Services never counted anything, so the total of the day goes on from what the counter counted before it.
        assertEquals(StepFeed.Feed(StepFeed.Source.COUNTER, 5_300, null), feed.onCounter(5_300, 3 * hour))
    }

    @Test
    fun aWatchThatStartedAgainDoesNotCountAGiantStep() {
        val feed = StepFeed(StepFeed.Source.HEALTH_SERVICES)
        feed.onCounter(90_000, 0)
        feed.registered(1)
        feed.onDaily(500, 2)
        // After a restart the counter starts from zero again, and Health Services stays silent for hours while it moves.
        feed.onCounter(40, hour)
        val handOver = feed.onCounter(200, 3 * hour)
        assertEquals(-1L, handOver?.baseline)
    }

    @Test
    fun aRegistrationThatFailsLetsTheCounterCount() {
        val feed = StepFeed(StepFeed.Source.HEALTH_SERVICES)
        feed.registered(0)
        feed.unregistered()
        assertEquals(StepFeed.Source.COUNTER, feed.counting(1))
        assertEquals(StepFeed.Source.COUNTER, feed.onCounter(100, 1)?.let { feed.fedBy })
    }

    @Test
    fun theFeedAndDailyStepsTogetherNeverCountADayTwice() {
        // A day walked: counter and Health Services agree on the steps, and a switch in the middle must not change the total.
        var steps = DailySteps()
        val feed = StepFeed(null)
        fun count(f: StepFeed.Feed?) {
            if (f != null) steps = steps.count(f, "d")
        }
        count(feed.onCounter(10_000, 0))
        count(feed.onCounter(10_100, 1))
        feed.registered(2)
        count(feed.onDaily(100, 3))
        count(feed.onDaily(400, 4))
        count(feed.onCounter(10_700, 5))
        count(feed.onDaily(700, 6))
        // 100 by the counter, then 400 and 700 by Health Services, which knew of 100 already: 700 of the day.
        assertEquals(700L + 0, steps.steps + 0L)
    }

    @Test
    fun theFirstTotalOfADayFromHealthServicesIsAllOfThatDay() {
        val yesterday = DailySteps().count(StepFeed(StepFeed.Source.HEALTH_SERVICES).onDaily(9_000, 0), "d1")
        assertEquals(9_000L, yesterday.steps)
        // The watch was away all night: the first word of the next day already says 9,500, and that is 9,500 of today.
        val today = yesterday.count(StepFeed.Feed(StepFeed.Source.HEALTH_SERVICES, 9_500, null), "d2")
        assertEquals(9_500L, today.steps)
        assertEquals("d2", today.day)
    }

    @Test
    fun aTotalThatGoesDownOnTheSameDayIsACorrectionAndNotADayOnTopOfTheDay() {
        var steps = DailySteps().count(StepFeed.Feed(StepFeed.Source.HEALTH_SERVICES, 3_000, null), "d")
        steps = steps.count(StepFeed.Feed(StepFeed.Source.HEALTH_SERVICES, 2_900, null), "d")
        assertEquals(3_000L, steps.steps)
        steps = steps.count(StepFeed.Feed(StepFeed.Source.HEALTH_SERVICES, 2_950, null), "d")
        assertEquals(3_050L, steps.steps)
    }

    @Test
    fun aNewDayCountsOnFromItsOwnTotals() {
        var steps = DailySteps().count(StepFeed.Feed(StepFeed.Source.HEALTH_SERVICES, 9_000, null), "d1")
        steps = steps.count(StepFeed.Feed(StepFeed.Source.HEALTH_SERVICES, 40, null), "d2")
        assertEquals(40L, steps.steps)
        steps = steps.count(StepFeed.Feed(StepFeed.Source.HEALTH_SERVICES, 90, null), "d2")
        assertEquals(90L, steps.steps)
    }
}
