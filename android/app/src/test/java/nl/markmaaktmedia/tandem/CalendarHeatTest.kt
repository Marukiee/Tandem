package nl.markmaaktmedia.tandem

import nl.markmaaktmedia.tandem.ui.screens.settings.CalendarHeat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarHeatTest {
    private fun inRange(values: List<Float?>) = values.filterNotNull().forEach { assertTrue("$it", !it.isNaN() && it in -1f..1f) }

    @Test
    fun theUsualIsTheRoundedMeanOfTheDaysWithAValue() {
        assertEquals(58L, CalendarHeat.usual(listOf(57L, null, 58L, 59L, 58L)))
        assertEquals(59L, CalendarHeat.usual(listOf(58L, 59L)))
        assertNull(CalendarHeat.usual(listOf(null, null)))
    }

    @Test
    fun heartDaysAboveAreWarmBelowAreCoolAndNearTheUsualNeutral() {
        val levels = CalendarHeat.heart(listOf(60L, 64L, 56L, 61L, null))
        // Usual 60: 64 is the furthest above, 56 the furthest below, 61 is within 1.
        assertEquals(0f, levels[0]!!, 0f)
        assertEquals(1f, levels[1]!!, 0.001f)
        assertEquals(-1f, levels[2]!!, 0.001f)
        assertEquals(0f, levels[3]!!, 0f)
        assertNull(levels[4])
    }

    @Test
    fun aMonthWithOneOrOnlyEqualValuesStaysNeutralAndNeverNaN() {
        assertEquals(listOf(0f), CalendarHeat.heart(listOf(58L)))
        assertEquals(listOf(0f, 0f, null), CalendarHeat.heart(listOf(58L, 58L, null)))
        assertEquals(listOf(null, null), CalendarHeat.heart(listOf(null, null)))
        inRange(CalendarHeat.heart(listOf(40L, 200L, 58L, 59L)))
    }

    @Test
    fun sharesGrowTowardTheGoalAndStayInRange() {
        val shares = CalendarHeat.shares(listOf(0L, 4000L, 8000L, 20000L, null), 8000L)
        assertEquals(0.15f, shares[0]!!, 0.001f)
        assertEquals(0.575f, shares[1]!!, 0.001f)
        assertEquals(1f, shares[2]!!, 0f)
        assertEquals(1f, shares[3]!!, 0f)
        assertNull(shares[4])
        assertTrue(CalendarHeat.met(8000L, 8000L))
        assertFalse(CalendarHeat.met(7999L, 8000L))
    }

    @Test
    fun withoutAGoalTheHighestDayIsFullAndZeroesDoNotDivideByZero() {
        assertEquals(listOf(0.575f, 1f), CalendarHeat.shares(listOf(5L, 10L), null).map { Math.round(it!! * 1000) / 1000f })
        assertEquals(listOf(0.15f, 0.15f), CalendarHeat.shares(listOf(0L, 0L), null))
        assertEquals(listOf(0.15f), CalendarHeat.shares(listOf(0L), 0L))
        assertFalse(CalendarHeat.met(0L, 0L))
    }

    @Test
    fun thePickedDayIsTodayWithAValueOrElseTheLastDayWithOne() {
        assertEquals(2, CalendarHeat.defaultPick(listOf(1L, null, 3L, null), 2))
        assertEquals(2, CalendarHeat.defaultPick(listOf(1L, null, 3L, null), 3))
        assertEquals(2, CalendarHeat.defaultPick(listOf(1L, null, 3L, null), null))
        assertNull(CalendarHeat.defaultPick(listOf(null, null), 1))
    }
}
