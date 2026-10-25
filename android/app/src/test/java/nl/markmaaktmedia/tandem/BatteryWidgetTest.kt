package nl.markmaaktmedia.tandem

import nl.markmaaktmedia.tandem.widget.BatteryShape
import nl.markmaaktmedia.tandem.widget.BatteryTone
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
    fun tones() {
        assertEquals(BatteryTone.Normal, c.tone(20, false, false))
        assertEquals(BatteryTone.Low, c.tone(19, false, false))
        assertEquals(BatteryTone.Low, c.tone(10, false, false))
        assertEquals(BatteryTone.Critical, c.tone(9, false, false))
        assertEquals(BatteryTone.Critical, c.tone(0, false, false))
        // On the charger it is no warning, low or not; away is away whatever the level.
        assertEquals(BatteryTone.Normal, c.tone(5, true, false))
        assertEquals(BatteryTone.Away, c.tone(5, false, true))
    }

    @Test
    fun onlyAFullBatteryClosesTheRing() {
        assertEquals(360f, c.sweep(100), 0f)
        assertTrue(c.sweep(99) < 345f)
        assertEquals(0f, c.sweep(0), 0f)
    }

    @Test
    fun phonesAreCookiesComputersClovers() {
        assertEquals(BatteryShape.Cookie, c.shapeOf("phone"))
        assertEquals(BatteryShape.Clover, c.shapeOf("laptop"))
        assertEquals(BatteryShape.Clover, c.shapeOf("windows"))
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

    @Test
    fun ringsShrinkToTheCellTheyAreGiven() {
        val size = BatteryWidgetLogic.Size.Large
        // Two rows of three in a widget only a little over the step to large: smaller rings, and both rows still fit.
        val tight = BatteryWidgetLogic.fit(size, 6, 335f, 198f)
        assertTrue(tight.diameter < 80)
        assertTrue(2 * BatteryWidgetLogic.itemHeight(tight.diameter, tight.names) + BatteryWidgetLogic.rowGap(size) <= 198f)
        // With room to spare they grow into it.
        assertTrue(BatteryWidgetLogic.fit(size, 6, 400f, 400f).diameter > 80)
    }

    @Test
    fun aFewDevicesInASquareWidgetBecomeRows() {
        val small = BatteryWidgetLogic.Size.Small
        // A 2 by 2 widget with three devices: three rows that share the height.
        val rows = BatteryWidgetLogic.plan(small, 3, 150f, 150f) as BatteryWidgetLogic.Plan.Rows
        assertTrue(3 * rows.height + 2 * BatteryWidgetLogic.ROW_GAP <= 150)
        assertTrue(rows.diameter < rows.height)
        // One device fills the widget instead of sitting small in the middle.
        val one = BatteryWidgetLogic.plan(small, 1, 150f, 150f) as BatteryWidgetLogic.Plan.Single
        assertTrue(one.diameter > 76)
        assertTrue(BatteryWidgetLogic.itemHeight(one.diameter, one.names) <= 150f)
        // Wide and low: the ring beside the level.
        assertTrue((BatteryWidgetLogic.plan(BatteryWidgetLogic.Size.Medium, 1, 320f, 90f) as BatteryWidgetLogic.Plan.Single).beside)
        // Two devices in a large widget would be two tall pills: big rings instead.
        assertTrue(BatteryWidgetLogic.plan(BatteryWidgetLogic.Size.Large, 2, 330f, 330f) is BatteryWidgetLogic.Plan.Grid)
        // Six devices keep the grid.
        assertTrue(BatteryWidgetLogic.plan(BatteryWidgetLogic.Size.Large, 6, 320f, 320f) is BatteryWidgetLogic.Plan.Grid)
    }
}
