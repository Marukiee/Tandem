package nl.markmaaktmedia.tandem.watch.protocol

import nl.markmaaktmedia.tandem.watch.protocol.LinkPriorityRules.Level
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinkPriorityTest {
    private val second = 1_000L
    private val hour = 3_600_000L

    private fun settled(): LinkPriorityRules {
        val rules = LinkPriorityRules()
        rules.linkUp(0)
        // The screen is off and nothing happened for long enough.
        assertEquals(Level.LOW_POWER, rules.wanted(31 * second))
        rules.applied(rules.change(31 * second)!!)
        return rules
    }

    @Test
    fun aNewLinkStaysBalancedThroughItsSetup() {
        val rules = LinkPriorityRules()
        rules.linkUp(0)
        assertEquals(Level.BALANCED, rules.wanted(0))
        assertEquals(Level.BALANCED, rules.wanted(29 * second))
        assertNull(rules.change(29 * second))
        assertEquals(Level.LOW_POWER, rules.wanted(30 * second))
    }

    @Test
    fun aQuietLinkWithTheScreenOffRests() {
        val rules = settled()
        assertEquals(Level.LOW_POWER, rules.applied)
        assertNull(rules.change(10 * 60 * second))
    }

    @Test
    fun theScreenGoingOnBalancesAtOnceAndOffRestsAfterTheSettleTime() {
        val rules = settled()
        rules.screen(true, 100 * second)
        assertEquals(Level.BALANCED, rules.change(100 * second))
        rules.applied(Level.BALANCED)
        // The screen is on for a minute.
        assertNull(rules.change(160 * second))
        rules.screen(false, 160 * second)
        assertNull(rules.change(160 * second))
        assertNull(rules.change(174 * second))
        assertEquals(Level.LOW_POWER, rules.change(175 * second))
    }

    @Test
    fun oneMessageDoesNotBalanceTheLinkButATransferDoes() {
        val rules = settled()
        // A ping and its answer.
        rules.traffic(100 * second)
        rules.traffic(100 * second + 300)
        assertNull(rules.change(100 * second + 400))
        // Notification art: many chunks within a moment.
        for (i in 0 until 4) rules.traffic(200 * second + i * 100L)
        assertEquals(Level.BALANCED, rules.change(200 * second + 400))
        rules.applied(Level.BALANCED)
        assertEquals(Level.LOW_POWER, rules.wanted(200 * second + 400 + LinkPriorityRules.SETTLE_MS))
    }

    @Test
    fun chunksSpreadOutAreNotATransfer() {
        val rules = settled()
        for (i in 0 until 20) rules.traffic(100 * second + i * 3 * second)
        assertNull(rules.change(160 * second))
    }

    @Test
    fun theTransferKeepsItBalancedWhileItGoesOn() {
        val rules = settled()
        var now = 100 * second
        for (i in 0 until 200) {
            rules.traffic(now)
            now += 100
        }
        assertEquals(Level.BALANCED, rules.wanted(now + 14 * second))
        assertEquals(Level.LOW_POWER, rules.wanted(now + 15 * second))
    }

    @Test
    fun thePointerWantsTheFastestAndTheLinkSettlesAfterIt() {
        val rules = settled()
        rules.fast(true)
        rules.screen(true, 100 * second)
        assertEquals(Level.HIGH, rules.change(100 * second))
        rules.applied(Level.HIGH)
        rules.fast(false)
        // The screen is still on.
        assertEquals(Level.BALANCED, rules.change(110 * second))
        rules.applied(Level.BALANCED)
        rules.screen(false, 120 * second)
        assertEquals(Level.LOW_POWER, rules.wanted(135 * second))
    }

    @Test
    fun aLinkThatKeepsDroppingWhileItRestsStaysBalancedForAWhile() {
        val rules = LinkPriorityRules()
        var now = 0L
        for (i in 1..3) {
            rules.linkUp(now)
            now += 60 * second
            rules.applied(Level.LOW_POWER)
            now += 5 * 60 * second
            rules.linkDown(now)
            now += 10 * second
        }
        rules.linkUp(now)
        now += 10 * 60 * second
        assertEquals(Level.BALANCED, rules.wanted(now))
        // Twelve hours later it may try again.
        assertEquals(Level.LOW_POWER, rules.wanted(now + LinkPriorityRules.BAN_MS))
    }

    @Test
    fun twoDropsAnHourAreNotEnoughToGiveUp() {
        val rules = LinkPriorityRules()
        var now = 0L
        for (i in 1..2) {
            rules.linkUp(now)
            rules.applied(Level.LOW_POWER)
            now += 10 * 60 * second
            rules.linkDown(now)
            now += 10 * second
        }
        rules.linkUp(now)
        assertEquals(Level.LOW_POWER, rules.wanted(now + hour))
    }

    @Test
    fun dropsSpreadOverTheDayDoNotAddUp() {
        val rules = LinkPriorityRules()
        var now = 0L
        for (i in 1..6) {
            rules.linkUp(now)
            rules.applied(Level.LOW_POWER)
            now += 4 * hour
            rules.linkDown(now)
        }
        rules.linkUp(now)
        assertEquals(Level.LOW_POWER, rules.wanted(now + hour))
    }

    @Test
    fun aDropWhileBalancedIsNotHeldAgainstTheResting() {
        val rules = LinkPriorityRules()
        var now = 0L
        for (i in 1..5) {
            rules.linkUp(now)
            now += 10 * second
            rules.linkDown(now)
            now += 5 * second
        }
        rules.linkUp(now)
        assertEquals(Level.LOW_POWER, rules.wanted(now + 40 * second))
    }

    @Test
    fun aNewLinkStartsBalancedWhateverTheOldOneWas() {
        val rules = settled()
        rules.linkDown(1_000 * second)
        assertEquals(Level.BALANCED, rules.applied)
        rules.linkUp(1_010 * second)
        assertEquals(Level.BALANCED, rules.applied)
    }

    @Test
    fun theClientIsToldWhenToLookAgain() {
        val rules = LinkPriorityRules()
        rules.linkUp(0)
        assertEquals(30 * second, rules.checkInMs(0))
        assertEquals(1 * second, rules.checkInMs(29 * second))
        assertEquals(0L, rules.checkInMs(30 * second))
        rules.applied(Level.LOW_POWER)
        assertNull(rules.checkInMs(40 * second))
        rules.screen(true, 50 * second)
        rules.applied(Level.BALANCED)
        assertNull(rules.checkInMs(51 * second))
        rules.screen(false, 60 * second)
        assertEquals(LinkPriorityRules.SETTLE_MS, rules.checkInMs(60 * second))
    }
}

class ScanRestTest {
    private val minute = 60_000L

    @Test
    fun theFirstHalfHourKeepsScanningWithoutRest() {
        assertEquals(null, ScanPolicy.restMs(0, 5 * minute))
        assertEquals(null, ScanPolicy.restMs(0, 29 * minute))
    }

    @Test
    fun afterHalfAnHourItRestsBetweenShortLooks() {
        assertEquals(ScanPolicy.REST_MS, ScanPolicy.restMs(0, 30 * minute))
        assertEquals(ScanPolicy.REST_MS, ScanPolicy.restMs(0, 5 * 60 * minute))
    }

    @Test
    fun stintsFollowTheSpeedAndEndWhereTheNextBegins() {
        assertEquals(60_000L, ScanPolicy.stintMs(0, 0, null))
        assertEquals(60_000L, ScanPolicy.stintMs(0, 60_000, null))
        // Low power runs in cycles of ten minutes, and the last is cut at the start of the long absence.
        assertEquals(ScanPolicy.CYCLE_MS, ScanPolicy.stintMs(0, 2 * minute, null))
        assertEquals(10 * minute, ScanPolicy.stintMs(0, 12 * minute, null))
        assertEquals(8 * minute, ScanPolicy.stintMs(0, 22 * minute, null))
        assertEquals(ScanPolicy.LOOK_MS, ScanPolicy.stintMs(0, 30 * minute, null))
        assertEquals(ScanPolicy.LOOK_MS, ScanPolicy.stintMs(0, 6 * 60 * minute, null))
    }

    @Test
    fun aStintIsNeverShorterThanTheStackAllows() {
        assertEquals(ScanPolicy.MIN_STINT_MS, ScanPolicy.stintMs(0, 29 * minute + 59_000, null))
        assertEquals(ScanPolicy.MIN_STINT_MS, ScanPolicy.stintMs(0, 59_000, null))
    }

    @Test
    fun theCycleOfAnAbsentPhoneScansAFifthOfTheTime() {
        val on = ScanPolicy.LOOK_MS.toDouble()
        val off = ScanPolicy.REST_MS.toDouble()
        assertEquals(true, on / (on + off) <= 0.2 + 1e-9)
    }
}
