package nl.markmaaktmedia.tandem

import nl.markmaaktmedia.tandem.ui.screens.orderDevices
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceOrderTest {
    private data class D(val id: String, val name: String, val reach: Int)

    private fun order(items: List<D>, pinned: Set<String> = emptySet()) =
        orderDevices(items, pinned, { it.id }, { it.reach }, { it.name }).map { it.id }

    @Test fun whatIsConnectedComesBeforeWhatIsAway() {
        val items = listOf(D("a", "Zebra", 3), D("b", "Mac", 0), D("c", "Phone", 1), D("d", "Laptop", 2))
        assertEquals(listOf("b", "c", "d", "a"), order(items))
    }

    @Test fun aPinnedDeviceStaysOnTopEvenWhenItIsAway() {
        val items = listOf(D("a", "Zebra", 3), D("b", "Mac", 0))
        assertEquals(listOf("a", "b"), order(items, pinned = setOf("a")))
    }

    @Test fun withinAGroupTheNameDecidesWithoutRegardForCase() {
        val items = listOf(D("a", "mac mini", 0), D("b", "Laptop", 0), D("c", "Desktop", 0))
        assertEquals(listOf("c", "b", "a"), order(items))
    }

    @Test fun pinnedDevicesAreOrderedLikeTheRest() {
        val items = listOf(D("a", "Zebra", 0), D("b", "Mac", 3), D("c", "Phone", 0))
        assertEquals(listOf("c", "a", "b"), order(items, pinned = setOf("c", "a")))
    }

    @Test fun nothingToOrderIsNothing() {
        assertEquals(emptyList<String>(), order(emptyList()))
    }
}
