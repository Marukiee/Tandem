package nl.markmaaktmedia.tandem

import nl.markmaaktmedia.tandem.hotspot.BleState
import nl.markmaaktmedia.tandem.hotspot.HotspotPolicy
import nl.markmaaktmedia.tandem.hotspot.HotspotUsage
import nl.markmaaktmedia.tandem.hotspot.Refusal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

class HotspotUsageTest {
    @Test fun encodeAndDecodeKeepTheDays() {
        val days = mapOf("2026-10-01" to 500L, "2026-10-02" to 1_500L)
        val text = HotspotUsage.encode(days)
        // Newest first.
        assertEquals("2026-10-02=1500;2026-10-01=500", text)
        assertEquals(days, HotspotUsage.decode(text))
    }

    @Test fun aDamagedStoreIsNotFatal() {
        assertEquals(emptyMap<String, Long>(), HotspotUsage.decode(null))
        assertEquals(emptyMap<String, Long>(), HotspotUsage.decode(""))
        assertEquals(
            mapOf("2026-10-01" to 7L),
            HotspotUsage.decode("junk;2026-10-01=7;2026-13-45=9;2026-10-02=x;=5;2026-10-03=-4"),
        )
    }

    @Test fun addingStacksOnTheDayAndDropsTheOldest() {
        var days = emptyMap<String, Long>()
        days = HotspotUsage.add(days, "2026-10-01", 100)
        days = HotspotUsage.add(days, "2026-10-01", 50)
        assertEquals(150L, days["2026-10-01"])
        // Nothing to add changes nothing.
        assertEquals(days, HotspotUsage.add(days, "2026-10-02", 0))

        var long = emptyMap<String, Long>()
        for (d in 1..(HotspotUsage.KEEP_DAYS + 5)) {
            long = HotspotUsage.add(long, java.time.LocalDate.of(2026, 1, 1).plusDays(d.toLong()).toString(), 1)
        }
        assertEquals(HotspotUsage.KEEP_DAYS, long.size)
        // The oldest days are the ones that went.
        assertNull(long["2026-01-02"])
    }

    @Test fun aRestartOfTheSystemCounterIsNotNegative() {
        assertEquals(30L, HotspotUsage.delta(100, 130))
        assertEquals(0L, HotspotUsage.delta(100, 100))
        // The counter started again from zero: everything since is new.
        assertEquals(40L, HotspotUsage.delta(1_000_000, 40))
        // A first reading has nothing to compare with.
        assertEquals(0L, HotspotUsage.delta(-1, 5_000))
        assertEquals(0L, HotspotUsage.delta(5, -1))
    }

    @Test fun theDayFollowsTheZoneOfThePerson() {
        // 23:30 UTC on the 1st is already the 2nd in Amsterdam.
        val late = java.time.ZonedDateTime.of(2026, 10, 1, 23, 30, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertEquals("2026-10-01", HotspotUsage.dayKey(late, ZoneId.of("UTC")))
        assertEquals("2026-10-02", HotspotUsage.dayKey(late, ZoneId.of("Europe/Amsterdam")))
    }

    @Test fun recentListsDaysWithUseNewestFirst() {
        val days = mapOf("2026-10-01" to 5L, "2026-10-02" to 0L, "2026-10-03" to 9L, "2026-09-30" to 2L)
        assertEquals(listOf("2026-10-03" to 9L, "2026-10-01" to 5L), HotspotUsage.recent(days, 2))
    }

    @Test fun theDailyLimitRefusesLastAndHasItsOwnCodes() {
        assertEquals(Refusal.Limit, HotspotPolicy.refusal(true, 80, false, false, false, limitReached = true))
        // Battery and roaming are said first.
        assertEquals(Refusal.Battery, HotspotPolicy.refusal(true, 10, false, false, false, limitReached = true))
        assertEquals(Refusal.Roaming, HotspotPolicy.refusal(true, 80, false, true, false, limitReached = true))
        assertNull(HotspotPolicy.refusal(true, 80, false, false, false, limitReached = false))
        assertEquals(BleState.RefusedLimit, HotspotPolicy.stateFor(Refusal.Limit))
        assertEquals(9, BleState.RefusedLimit.code)
        assertEquals("limit", HotspotPolicy.errorCode(Refusal.Limit))
    }
}
