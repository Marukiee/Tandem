package nl.markmaaktmedia.tandem.engine

import android.net.NetworkCapabilities

/**
 * Whether the network the phone is on costs: mobile data, or a Wi-Fi that is metered. The core waits on a phone that is on such a network
 * (it does not go looking for a laptop that has been gone for days, and asks about large files first).
 *
 * A VPN (Tailscale) says it is not metered and, for a moment while it reconnects, has no transport of the network underneath it. So the
 * mobile data under a VPN counts, and a VPN that says nothing about what is under it does not change the answer.
 */
object NetworkCost {
    /** What the system says about the network that is the default one, as far as cost goes. */
    data class Caps(
        val vpn: Boolean,
        val cellular: Boolean,
        val wifi: Boolean,
        val ethernet: Boolean,
        val notMetered: Boolean,
    )

    fun of(capabilities: NetworkCapabilities?): Caps? = capabilities?.let {
        Caps(
            vpn = it.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            cellular = it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            wifi = it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            ethernet = it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
            notMetered = it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        )
    }

    /** `previous`: what was decided before, kept when the network says too little to decide now. */
    fun costly(caps: Caps?, previous: Boolean): Boolean = when {
        caps == null -> previous
        caps.cellular -> true
        // A VPN that has not said what runs under it (it just reconnected): it called itself free, which tells nothing.
        caps.vpn && !caps.wifi && !caps.ethernet -> previous
        else -> !caps.notMetered
    }
}
