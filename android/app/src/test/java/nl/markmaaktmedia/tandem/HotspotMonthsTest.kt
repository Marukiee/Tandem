package nl.markmaaktmedia.tandem

import nl.markmaaktmedia.tandem.hotspot.HotspotUsage
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class HotspotMonthsTest {
    private fun day(text: String) = LocalDate.parse(text)

    @Test
    fun aMonthThatStartsOnTheFirstIsTheCalendarMonth() {
        assertEquals(day("2026-10-01"), HotspotUsage.periodStart(day("2026-10-17"), 1))
        assertEquals(day("2026-10-01"), HotspotUsage.periodStart(day("2026-10-01"), 1))
    }

    @Test
    fun aMonthThatStartsLaterBeginsInTheMonthBeforeUntilThatDay() {
        assertEquals(day("2026-09-15"), HotspotUsage.periodStart(day("2026-10-14"), 15))
        assertEquals(day("2026-10-15"), HotspotUsage.periodStart(day("2026-10-15"), 15))
        assertEquals(day("2025-12-20"), HotspotUsage.periodStart(day("2026-01-05"), 20))
    }

    @Test
    fun theStartDayNeverPassesTheTwentyEighth() {
        assertEquals(day("2026-02-28"), HotspotUsage.periodStart(day("2026-03-02"), 31))
        assertEquals(day("2026-10-01"), HotspotUsage.periodStart(day("2026-10-03"), 0))
    }

    @Test
    fun usageIsAddedUpPerMonthAndTheCurrentMonthStartsAtZero() {
        val days = mapOf("2026-09-20" to 100L, "2026-09-30" to 50L, "2026-10-01" to 7L, "2026-08-31" to 1_000L)
        val months = HotspotUsage.periods(days, day("2026-10-17"), 1, 3)
        assertEquals(listOf(7L, 150L, 1_000L), months.map { it.bytes })
        assertEquals(day("2026-10-01"), months[0].start)
        assertEquals(day("2026-11-01"), months[0].endExclusive)
        // The same days, counted by months that begin on the 15th.
        val shifted = HotspotUsage.periods(days, day("2026-10-17"), 15, 2)
        assertEquals(listOf(0L, 157L), shifted.map { it.bytes })
    }

    @Test
    fun aMonthWithNothingInItStillCounts() {
        val months = HotspotUsage.periods(emptyMap(), day("2026-10-17"), 1, 2)
        assertEquals(listOf(0L, 0L), months.map { it.bytes })
    }
}
