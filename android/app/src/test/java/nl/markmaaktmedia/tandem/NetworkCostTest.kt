package nl.markmaaktmedia.tandem

import nl.markmaaktmedia.tandem.engine.NetworkCost
import nl.markmaaktmedia.tandem.engine.NetworkCost.Caps
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkCostTest {
    private fun caps(vpn: Boolean = false, cellular: Boolean = false, wifi: Boolean = false, ethernet: Boolean = false, notMetered: Boolean = false) =
        Caps(vpn, cellular, wifi, ethernet, notMetered)

    @Test fun mobileDataCosts() {
        assertTrue(NetworkCost.costly(caps(cellular = true), previous = false))
    }

    @Test fun mobileDataUnderAVpnThatCallsItselfFreeStillCosts() {
        // Tailscale: not metered, with the transport of the data underneath it.
        assertTrue(NetworkCost.costly(caps(vpn = true, cellular = true, notMetered = true), previous = false))
    }

    @Test fun aVpnThatHasNotSaidWhatRunsUnderItKeepsWhatWasDecided() {
        val reconnecting = caps(vpn = true, notMetered = true)
        assertTrue(NetworkCost.costly(reconnecting, previous = true))
        assertFalse(NetworkCost.costly(reconnecting, previous = false))
    }

    @Test fun noNetworkKeepsWhatWasDecided() {
        assertTrue(NetworkCost.costly(null, previous = true))
        assertFalse(NetworkCost.costly(null, previous = false))
    }

    @Test fun aFreeWifiDoesNotCostAndAMeteredOneDoes() {
        assertFalse(NetworkCost.costly(caps(wifi = true, notMetered = true), previous = true))
        assertTrue(NetworkCost.costly(caps(wifi = true, notMetered = false), previous = false))
    }

    @Test fun wifiUnderAVpnDoesNotCost() {
        assertFalse(NetworkCost.costly(caps(vpn = true, wifi = true, notMetered = true), previous = true))
    }
}
