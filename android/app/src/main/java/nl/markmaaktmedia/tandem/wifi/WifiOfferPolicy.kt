package nl.markmaaktmedia.tandem.wifi

import uniffi.tandem_core.TandemWifiNetwork
import uniffi.tandem_core.TandemWifiSecurity

/**
 * When the phone offers to take over the Wi-Fi of a computer. Pure, so the rules are tested without a phone.
 *
 * The network has to be one the phone can join, not the one it is on, not one the person said no to, and not one it already
 * asked about. Then it has to be near: in the last scan when Tandem may see nearby networks, and otherwise only while the
 * computer can be reached over Bluetooth alone, which says it is close by and that its network is no way to it now.
 */
object WifiOfferPolicy {
    data class Phone(
        /** The network the phone is on, null for none or when Android keeps the name from Tandem. */
        val ssid: String?,
        /** The names in the last scan, null when Tandem may not see nearby networks. */
        val nearby: Set<String>?,
    )

    fun joinable(network: TandemWifiNetwork): Boolean =
        network.ssid.isNotEmpty() && network.security in setOf(TandemWifiSecurity.OPEN, TandemWifiSecurity.WPA2, TandemWifiSecurity.WPA3)

    fun shouldOffer(
        network: TandemWifiNetwork?,
        phone: Phone,
        bluetoothOnly: Boolean,
        declined: Set<String>,
        asked: Set<String>,
    ): Boolean {
        if (network == null || !joinable(network)) return false
        val ssid = network.ssid
        if (ssid == phone.ssid || ssid in declined || ssid in asked) return false
        val nearby = phone.nearby ?: return bluetoothOnly
        // A hidden network never shows up in a scan by its name.
        return ssid in nearby || (network.hidden && bluetoothOnly)
    }

    /** A no holds until the computer moves to another network: then the old one may be offered again later. */
    fun forgetDeclined(declined: Set<String>, before: TandemWifiNetwork?, after: TandemWifiNetwork?): Set<String> =
        if (before != null && before.ssid != after?.ssid) declined - before.ssid else declined
}
