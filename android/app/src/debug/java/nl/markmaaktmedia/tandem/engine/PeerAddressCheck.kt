package nl.markmaaktmedia.tandem.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import nl.markmaaktmedia.tandem.graph

/**
 * Debug builds only. Two emulators each sit behind a network of their own and never find each other: this tells one where the other
 * is, through a port the emulator forwards (`adb emu redir add udp:<port>:47820` on the other one). Without extras it lists the devices;
 * with `offer` it writes a pairing link to the log, to give the other one after `scripts/rewrite-pair-uri.py`.
 *
 *     adb shell am broadcast -n nl.markmaaktmedia.tandem.debug/nl.markmaaktmedia.tandem.engine.PeerAddressCheck --ez offer true
 *     adb shell am broadcast -n nl.markmaaktmedia.tandem.debug/nl.markmaaktmedia.tandem.engine.PeerAddressCheck \
 *         --es id <device id> --es addr 10.0.2.2:<port>
 */
class PeerAddressCheck : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val host = context.graph.host
        if (intent.getBooleanExtra("offer", false)) {
            val offer = runCatching { host.engine?.createPairingOffer() }
            Log.i(TAG, "offer ${offer.getOrNull()?.uri ?: offer.exceptionOrNull()}")
            return
        }
        val id = intent.getStringExtra("id")
        val addr = intent.getStringExtra("addr")
        if (id == null || addr == null) {
            host.devices.value.forEach { Log.i(TAG, "${it.id} ${it.platform} online=${it.online} ${it.name}") }
            return
        }
        val added = runCatching { host.engine?.addAddress(id, addr) }
        Log.i(TAG, "add $addr for $id: ${added.exceptionOrNull() ?: "ok"}")
    }

    private companion object {
        const val TAG = "PeerAddressCheck"
    }
}
