package nl.markmaaktmedia.tandem.engine

import android.content.Context
import android.content.Intent

/**
 * Asks Tailscale to connect when a device of the circle cannot be reached on the network this phone is on. The core says so only for a
 * device that does not answer on the local network, that is known by a Tailscale address, while this phone has none of its own.
 *
 * Tailscale for Android has a receiver for this: a broadcast with the action below, sent straight to its package. If the app is not
 * installed, or does not take the broadcast, nothing happens, and nothing else is touched.
 */
object TailscaleAuto {
    private const val PACKAGE = "com.tailscale.ipn"
    private const val ACTION = "com.tailscale.ipn.CONNECT_VPN"

    fun connect(context: Context) {
        runCatching {
            context.sendBroadcast(Intent(ACTION).setClassName(PACKAGE, "$PACKAGE.IPNReceiver"))
        }
    }
}
