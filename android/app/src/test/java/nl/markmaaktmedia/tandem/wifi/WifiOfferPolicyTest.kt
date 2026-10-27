package nl.markmaaktmedia.tandem.wifi

import nl.markmaaktmedia.tandem.wifi.WifiOfferPolicy.Phone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.tandem_core.TandemWifiNetwork
import uniffi.tandem_core.TandemWifiSecurity

class WifiOfferPolicyTest {
    private val home = TandemWifiNetwork("Home", TandemWifiSecurity.WPA2, false)

    private fun offer(
        network: TandemWifiNetwork? = home,
        phone: Phone = Phone(ssid = null, nearby = null),
        bluetoothOnly: Boolean = false,
        declined: Set<String> = emptySet(),
        asked: Set<String> = emptySet(),
        known: Set<String> = emptySet(),
    ) = WifiOfferPolicy.shouldOffer(network, phone, bluetoothOnly, declined, asked, known)

    @Test fun aNetworkThePhoneHasBeenOnIsNotOffered() {
        // Coming home: the phone sees its own network before it has joined it again.
        assertFalse(offer(phone = Phone(ssid = null, nearby = setOf("Home")), known = setOf("Home")))
    }

    @Test fun anOfferWaitsUntilThePhoneHadTimeToJoinByItself() {
        assertFalse(WifiOfferPolicy.settled(since = 1_000, now = 30_000))
        assertTrue(WifiOfferPolicy.settled(since = 1_000, now = 1_000 + WifiOfferPolicy.SETTLE_MS))
    }

    @Test fun withoutSeeingNearbyNetworksOnlyAComputerOverBluetoothIsOffered() {
        assertTrue(offer(bluetoothOnly = true))
        // Reachable over the network (at home on the same Wi-Fi, or from far away over Tailscale): nothing to say.
        assertFalse(offer(bluetoothOnly = false))
    }

    @Test fun seeingNearbyNetworksTheScanDecides() {
        assertTrue(offer(phone = Phone(ssid = "Cafe", nearby = setOf("Cafe", "Home"))))
        // Out of range: not even over Bluetooth, the scan knows better.
        assertFalse(offer(phone = Phone(ssid = null, nearby = setOf("Cafe")), bluetoothOnly = true))
    }

    @Test fun theNetworkThePhoneIsOnIsNotOffered() {
        assertFalse(offer(phone = Phone(ssid = "Home", nearby = setOf("Home")), bluetoothOnly = true))
    }

    @Test fun aNoAndAnEarlierQuestionAreRespected() {
        assertFalse(offer(bluetoothOnly = true, declined = setOf("Home")))
        assertFalse(offer(bluetoothOnly = true, asked = setOf("Home")))
        assertTrue(offer(bluetoothOnly = true, declined = setOf("Office")))
    }

    @Test fun enterpriseAndUnknownNetworksAreNeverOffered() {
        assertFalse(offer(network = TandemWifiNetwork("Work", TandemWifiSecurity.ENTERPRISE, false), bluetoothOnly = true))
        assertFalse(offer(network = TandemWifiNetwork("Old", TandemWifiSecurity.OTHER, false), bluetoothOnly = true))
        assertFalse(offer(network = null, bluetoothOnly = true))
        assertTrue(offer(network = TandemWifiNetwork("Free", TandemWifiSecurity.OPEN, false), bluetoothOnly = true))
    }

    @Test fun aHiddenNetworkIsOfferedOverBluetoothEvenThoughNoScanNamesIt() {
        val hidden = TandemWifiNetwork("Attic", TandemWifiSecurity.WPA3, true)
        assertTrue(offer(network = hidden, phone = Phone(ssid = null, nearby = emptySet()), bluetoothOnly = true))
        assertFalse(offer(network = hidden, phone = Phone(ssid = null, nearby = emptySet()), bluetoothOnly = false))
    }

    @Test fun aNoIsForgottenOnlyWhenTheComputerMovesToAnotherNetwork() {
        val office = TandemWifiNetwork("Office", TandemWifiSecurity.WPA2, false)
        assertEquals(setOf("Home"), WifiOfferPolicy.forgetDeclined(setOf("Home"), home, home))
        assertEquals(emptySet<String>(), WifiOfferPolicy.forgetDeclined(setOf("Home"), home, office))
        assertEquals(emptySet<String>(), WifiOfferPolicy.forgetDeclined(setOf("Home"), home, null))
        assertEquals(setOf("Home"), WifiOfferPolicy.forgetDeclined(setOf("Home"), null, office))
    }
}
