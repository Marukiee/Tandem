package nl.markmaaktmedia.tandem.hotspot

import uniffi.tandem_core.TandemWifiNetwork
import uniffi.tandem_core.TandemWifiSecurity

/**
 * What a Mac is told about this phone's own hotspot: its name, once, and again when it changes. Pure, so the rules are tested without a
 * phone. Only Shizuku can read the name (see [ShellWifi.hotspot]), so a phone without it says nothing, and the Mac finds out when joining fails.
 */
object HotspotNaming {
    /** The longest a network name can be (802.11). A longer one is a mistake and goes nowhere. */
    private const val MAX_SSID_BYTES = 32

    /** `SoftApConfiguration.SECURITY_TYPE_*`: 0 open, 1 WPA2, 2 WPA3 with WPA2, 3 WPA3, 4 and 5 are OWE (encrypted but no password). */
    fun securityOf(type: Int): TandemWifiSecurity = when (type) {
        0 -> TandemWifiSecurity.OPEN
        1, 2 -> TandemWifiSecurity.WPA2
        3 -> TandemWifiSecurity.WPA3
        else -> TandemWifiSecurity.OTHER
    }

    /** What the shell service answers with: the kind, whether it is hidden, then the name, which may hold any character. */
    internal fun encode(hotspot: ShellWifi.Hotspot): String = "${hotspot.security}:${if (hotspot.hidden) 1 else 0}:${hotspot.ssid}"

    fun parse(message: String): TandemWifiNetwork? {
        val parts = message.split(':', limit = 3)
        if (parts.size != 3) return null
        val type = parts[0].toIntOrNull() ?: return null
        val ssid = parts[2]
        if (ssid.isEmpty() || ssid.toByteArray().size > MAX_SSID_BYTES) return null
        return TandemWifiNetwork(ssid, securityOf(type), parts[1] == "1")
    }

    /**
     * The computers to tell [name] to: those that are connected and have not heard this name. A computer that went away is forgotten, so
     * one that comes back (a Mac that restarted) hears it again.
     */
    fun peersToTell(name: String?, reachable: Set<String>, told: Map<String, String>): List<String> =
        if (name.isNullOrEmpty()) emptyList() else reachable.filter { told[it] != name }.sorted()

    fun stillTold(told: Map<String, String>, reachable: Set<String>): Map<String, String> = told.filterKeys { it in reachable }

    /** How long a read of the own hotspot stands: it is a call into the shell through Shizuku, too much for every tick with the screen off. */
    const val REREAD_MS = 5 * 60_000L

    /**
     * Whether the read made at [readAt] under [readKey] can be used again at [now] ([key] is what the read depended on: the switch,
     * the Macs in reach and the state of Shizuku). A clock that went back counts as stale.
     */
    fun readStands(readKey: Any?, readAt: Long, key: Any, now: Long, maxAgeMs: Long = REREAD_MS): Boolean =
        readKey == key && now - readAt in 0 until maxAgeMs
}
