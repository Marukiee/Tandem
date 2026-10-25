package nl.markmaaktmedia.tandem

import nl.markmaaktmedia.tandem.widget.BatteryWidgetColors
import nl.markmaaktmedia.tandem.widget.BatteryWidgetLogic
import nl.markmaaktmedia.tandem.widget.WidgetBattery
import nl.markmaaktmedia.tandem.widget.WidgetDeviceInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryWidgetTest {
    private val c = BatteryWidgetColors

    @Test
    fun colours() {
        assertEquals(c.GREEN, c.tint(20, false))
        assertEquals(c.YELLOW, c.tint(19, false))
        assertEquals(c.YELLOW, c.tint(10, false))
        assertEquals(c.RED, c.tint(9, false))
        assertEquals(c.RED, c.tint(0, false))
        // On the charger it is green, low or not.
        assertEquals(c.GREEN, c.tint(5, true))
    }

    @Test
    fun onlyAFullBatteryClosesTheRing() {
        assertEquals(360f, c.sweep(100), 0f)
        assertTrue(c.sweep(99) < 345f)
        assertEquals(0f, c.sweep(0), 0f)
    }

    @Test
    fun solidColoursLieOnTheCard() {
        assertEquals(c.BACKGROUND, c.solid(c.TEXT, 0f))
        assertEquals(c.TEXT, c.solid(c.TEXT, 1f))
        assertEquals(0xFF, c.solid(c.TRACK) ushr 24)
    }

    @Test
    fun aDeviceThatWentAwayKeepsItsLastLevelDimmed() {
        val before = listOf(WidgetBattery("p", "Phone", "phone", 64, true, false))
        val now = BatteryWidgetLogic.merge(before, listOf(WidgetDeviceInput("p", "Phone", "phone", false, null, false)))
        assertEquals(listOf(WidgetBattery("p", "Phone", "phone", 64, false, true)), now)
    }

    @Test
    fun removedAndUnknownDevicesAreLeftOut() {
        val before = listOf(WidgetBattery("gone", "Old", "phone", 50, false, false))
        val now = BatteryWidgetLogic.merge(before, listOf(WidgetDeviceInput("new", "New", "laptop", true, null, false)))
        assertTrue(now.isEmpty())
    }

    @Test
    fun connectedFirstThenByName() {
        val now = BatteryWidgetLogic.merge(
            emptyList(),
            listOf(
                WidgetDeviceInput("b", "beta", "phone", true, 50, false),
                WidgetDeviceInput("x", "Away", "phone", false, 40, true),
                WidgetDeviceInput("a", "Alpha", "laptop", true, 70, false),
            ),
        )
        assertEquals(listOf("a", "b", "x"), now.map { it.id })
        // A device that is not connected is not charging as far as anyone knows.
        assertFalse(now.last().charging)
    }

    @Test
    fun smallStepsWaitBigOnesShow() {
        val a = listOf(WidgetBattery("p", "Phone", "phone", 50, false, false))
        assertFalse(BatteryWidgetLogic.differsVisibly(a, listOf(a[0].copy(level = 48))))
        assertTrue(BatteryWidgetLogic.differsVisibly(a, listOf(a[0].copy(level = 45))))
        assertTrue(BatteryWidgetLogic.differsVisibly(listOf(a[0].copy(level = 20)), listOf(a[0].copy(level = 19))))
        assertTrue(BatteryWidgetLogic.differsVisibly(a, listOf(a[0].copy(charging = true))))
        assertTrue(BatteryWidgetLogic.differsVisibly(a, emptyList()))
    }

    @Test
    fun fileRoundTrips() {
        val list = listOf(WidgetBattery("p", "Phone \"1\"", "tablet", 7, true, false), WidgetBattery("m", "Mac", "laptop", 100, false, true))
        assertEquals(Pair(list, "My phone"), BatteryWidgetLogic.decode(BatteryWidgetLogic.encode(list, "My phone")))
        assertEquals(Pair(emptyList<WidgetBattery>(), null), BatteryWidgetLogic.decode("not json"))
        assertEquals(Pair(emptyList<WidgetBattery>(), null), BatteryWidgetLogic.decode(null))
    }

    @Test
    fun fewerDevicesGetBiggerRings() {
        val one = BatteryWidgetLogic.layout(BatteryWidgetLogic.Size.Large, 1)
        val six = BatteryWidgetLogic.layout(BatteryWidgetLogic.Size.Large, 6)
        assertTrue(one.diameter > six.diameter)
        assertEquals(3, six.columns)
        assertEquals(2, BatteryWidgetLogic.layout(BatteryWidgetLogic.Size.Small, 4).columns)
        assertEquals(3, BatteryWidgetLogic.layout(BatteryWidgetLogic.Size.Medium, 3).columns)
    }
}
