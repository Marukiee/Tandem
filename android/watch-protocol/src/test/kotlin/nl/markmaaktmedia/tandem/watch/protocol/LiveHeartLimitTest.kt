package nl.markmaaktmedia.tandem.watch.protocol

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveHeartLimitTest {
    private val minute = 60_000L

    @Test
    fun aPersonWhoWatchesGetsTheNumberFor15Minutes() {
        val limit = LiveHeartLimit()
        for (at in 0..14 * minute step 30_000) assertTrue(limit.ask(at), "at $at")
    }

    @Test
    fun aWindowThatNeverStopsAskingGetsARestAfterwards() {
        val limit = LiveHeartLimit()
        var measuring = 0L
        var at = 0L
        // Asked every half minute for a whole day, as a page that stays open behind other windows does.
        while (at < 24 * 60 * minute) {
            if (limit.ask(at)) measuring += 30_000
            at += 30_000
        }
        // 15 of every 45 minutes: a third of the day instead of all of it.
        assertTrue(measuring <= 24 * 60 * minute / 3 + 15 * minute, "measured $measuring")
        assertTrue(measuring >= 24 * 60 * minute / 3 - 15 * minute, "measured $measuring")
    }

    @Test
    fun theRestIsOverAfterHalfAnHour() {
        val limit = LiveHeartLimit()
        limit.ask(0)
        assertFalse(limit.ask(15 * minute))
        assertFalse(limit.ask(44 * minute))
        assertTrue(limit.ask(46 * minute))
    }

    @Test
    fun someoneWhoLooksAwayAndComesBackStartsAgain() {
        val limit = LiveHeartLimit()
        limit.ask(0)
        limit.ask(10 * minute)
        limit.stopped()
        assertTrue(limit.ask(12 * minute))
        assertTrue(limit.ask(26 * minute))
        assertFalse(limit.ask(28 * minute))
    }
}
