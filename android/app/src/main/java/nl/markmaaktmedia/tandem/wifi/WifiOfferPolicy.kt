package nl.markmaaktmedia.tandem.wifi

import uniffi.tandem_core.TandemWifiNetwork
import uniffi.tandem_core.TandemWifiSecurity

/**
 * When the phone offers to take over the Wi-Fi of another device. Pure, so the rules are tested without a phone. The same rules as
 * `wifi::worth_offering` in the core, which the computers use.
 *
 * Only a phone that is on no Wi-Fi at all is offered one. The network has to be one the phone can join and the other device can hand
 * over, not one the phone knows (saved, as Shizuku tells, or been on: it joins that one by itself), not one the person said no to,
 * and not one it already asked about. Then it has to be near: in the last scan when Tandem may see nearby networks, and otherwise
 * only while the device can be reached over Bluetooth alone. And all that for a moment ([settled]): leaving one access point for
 * another, or coming home, the phone is on no Wi-Fi for a few seconds.
 */
object WifiOfferPolicy {
    data class Phone(
        /** The network the phone is on, null for none or when Android keeps the name from Tandem. */
        val ssid: String?,
        /** The names in the last scan, null when Tandem may not see nearby networks. */
        val nearby: Set<String>?,
        /** On Wi-Fi now, also when Android keeps its name from Tandem. */
        val onWifi: Boolean = ssid != null,
    )

    fun joinable(network: TandemWifiNetwork): Boolean =
        network.ssid.isNotEmpty() && !network.locked &&
            network.security in setOf(TandemWifiSecurity.OPEN, TandemWifiSecurity.WPA2, TandemWifiSecurity.WPA3)

    fun shouldOffer(
        network: TandemWifiNetwork?,
        phone: Phone,
        bluetoothOnly: Boolean,
        declined: Set<String>,
        asked: Set<String>,
        known: Set<String> = emptySet(),
    ): Boolean {
        if (network == null || phone.onWifi || !joinable(network)) return false
        val ssid = network.ssid
        if (ssid == phone.ssid || ssid in known || ssid in declined || ssid in asked) return false
        val nearby = phone.nearby ?: return bluetoothOnly
        // A hidden network never shows up in a scan by its name.
        return ssid in nearby || (network.hidden && bluetoothOnly)
    }

    /** How long a network has to stay worth offering before it is: the phone joining a network it saved takes a few seconds. */
    const val SETTLE_MS = 10_000L

    fun settled(since: Long, now: Long): Boolean = now - since >= SETTLE_MS

    /** A no holds until the computer moves to another network: then the old one may be offered again later. */
    fun forgetDeclined(declined: Set<String>, before: TandemWifiNetwork?, after: TandemWifiNetwork?): Set<String> =
        if (before != null && before.ssid != after?.ssid) declined - before.ssid else declined
}
