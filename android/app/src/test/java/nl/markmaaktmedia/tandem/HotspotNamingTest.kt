package nl.markmaaktmedia.tandem

import nl.markmaaktmedia.tandem.hotspot.HotspotNaming
import nl.markmaaktmedia.tandem.hotspot.ShellWifi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.tandem_core.TandemWifiSecurity

class HotspotNamingTest {
    @Test fun theShellAnswerCarriesTheNameWhateverItHolds() {
        // A colon in the name must not be taken for a separator.
        val message = HotspotNaming.encode(ShellWifi.Hotspot("Mark: 5G", 3, true, "secret"))
        assertEquals("3:1:Mark: 5G", message)
        val network = HotspotNaming.parse(message)!!
        assertEquals("Mark: 5G", network.ssid)
        assertEquals(TandemWifiSecurity.WPA3, network.security)
        assertTrue(network.hidden)
    }

    @Test fun theMessageNeverHoldsThePassword() {
        assertTrue("secret" !in HotspotNaming.encode(ShellWifi.Hotspot("Home", 1, false, "secret")))
    }

    @Test fun securityIsMappedToWhatAMacCanJoin() {
        assertEquals(TandemWifiSecurity.OPEN, HotspotNaming.securityOf(0))
        assertEquals(TandemWifiSecurity.WPA2, HotspotNaming.securityOf(1))
        assertEquals(TandemWifiSecurity.WPA2, HotspotNaming.securityOf(2))
        assertEquals(TandemWifiSecurity.WPA3, HotspotNaming.securityOf(3))
        assertEquals(TandemWifiSecurity.OTHER, HotspotNaming.securityOf(4))
        assertEquals(TandemWifiSecurity.OTHER, HotspotNaming.securityOf(99))
    }

    @Test fun anAnswerThatIsNotAHotspotIsIgnored() {
        assertNull(HotspotNaming.parse("not a hotspot"))
        assertNull(HotspotNaming.parse("x:0:Home"))
        assertNull(HotspotNaming.parse("1:0:"))
        assertNull(HotspotNaming.parse("1:0:" + "x".repeat(33)))
    }

    @Test fun aMacIsToldOnceAndAgainWhenTheNameChangesOrItComesBack() {
        val macs = setOf("a", "b")
        assertEquals(listOf("a", "b"), HotspotNaming.peersToTell("Pixel", macs, emptyMap()))
        assertEquals(listOf("b"), HotspotNaming.peersToTell("Pixel", macs, mapOf("a" to "Pixel")))
        assertEquals(listOf("a", "b"), HotspotNaming.peersToTell("Renamed", macs, mapOf("a" to "Pixel", "b" to "Pixel")))
        // A Mac that went away is forgotten, so it hears the name again when it is back.
        assertEquals(mapOf("a" to "Pixel"), HotspotNaming.stillTold(mapOf("a" to "Pixel", "b" to "Pixel"), setOf("a")))
    }

    @Test fun nothingIsSaidWhenTheNameIsUnknown() {
        assertEquals(emptyList<String>(), HotspotNaming.peersToTell(null, setOf("a"), emptyMap()))
        assertEquals(emptyList<String>(), HotspotNaming.peersToTell("", setOf("a"), emptyMap()))
    }

    @Test fun aReadOfTheOwnHotspotStandsForFiveMinutesUnlessTheSituationChanged() {
        val key = Triple(true, setOf("mac"), "ready")
        assertTrue(HotspotNaming.readStands(key, 1_000, key, 1_000 + HotspotNaming.REREAD_MS - 1))
        // After five minutes a renamed hotspot is read again.
        assertFalse(HotspotNaming.readStands(key, 1_000, key, 1_000 + HotspotNaming.REREAD_MS))
        // A Mac that comes in reach, the switch or Shizuku changing: read at once.
        assertFalse(HotspotNaming.readStands(key, 1_000, Triple(true, setOf("mac", "mac2"), "ready"), 2_000))
        assertFalse(HotspotNaming.readStands(key, 1_000, Triple(false, setOf("mac"), "ready"), 2_000))
        assertFalse(HotspotNaming.readStands(key, 1_000, Triple(true, setOf("mac"), "denied"), 2_000))
        // Never read before, or a clock that went back.
        assertFalse(HotspotNaming.readStands(null, 0, key, 2_000))
        assertFalse(HotspotNaming.readStands(key, 5_000, key, 2_000))
    }
}
