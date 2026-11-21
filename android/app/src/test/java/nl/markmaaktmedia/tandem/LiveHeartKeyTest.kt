package nl.markmaaktmedia.tandem

import nl.markmaaktmedia.tandem.watch.LIVE_HEART_HERE
import nl.markmaaktmedia.tandem.watch.isLocalHeartAsker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveHeartKeyTest {
    @Test
    fun `each screen of this phone has its own key and none is a computer`() {
        val glance = "$LIVE_HEART_HERE:glance"
        val heart = "$LIVE_HEART_HERE:heart"
        assertTrue(glance != heart)
        assertTrue(isLocalHeartAsker(glance) && isLocalHeartAsker(heart))
        assertFalse(isLocalHeartAsker("3fa9c01d"))
    }

    @Test
    fun `a page that lets go after the next one asked leaves the next one asking`() {
        val askers = mutableMapOf<String, Long>()
        askers["$LIVE_HEART_HERE:glance"] = 1
        askers["$LIVE_HEART_HERE:heart"] = 2
        askers.remove("$LIVE_HEART_HERE:glance")
        assertEquals(setOf("$LIVE_HEART_HERE:heart"), askers.keys)
    }
}
