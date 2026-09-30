package nl.markmaaktmedia.tandem.engine

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Sends a Wake-on-LAN "magic packet": six bytes of 0xFF and then the sleeping device's hardware
 * address sixteen times, broadcast on the local network.
 *
 * This only ever wakes a device that is on a network cable, that is asleep on power, and that
 * allows it in its own settings (on a Mac: Wake for network access). A laptop asleep with its lid
 * closed usually ignores it, and nothing here can tell whether it worked. The packet is best effort.
 */
object WakeOnLan {
    private const val TAG = "WakeOnLan"
    private const val PORT = 9

    /** True when a packet was sent; that says nothing about whether the device woke. */
    suspend fun send(context: Context, mac: String): Boolean = withContext(Dispatchers.IO) {
        val bytes = parse(mac) ?: return@withContext false
        val packet = ByteArray(6 + 16 * 6) { if (it < 6) 0xFF.toByte() else bytes[(it - 6) % 6] }
        var sent = false
        DatagramSocket().use { socket ->
            socket.broadcast = true
            for (target in broadcastAddresses(context)) {
                repeat(3) {
                    runCatching { socket.send(DatagramPacket(packet, packet.size, target, PORT)) }
                        .onSuccess { sent = true }
                        .onFailure { Log.w(TAG, "could not send to $target", it) }
                }
            }
        }
        sent
    }

    fun parse(mac: String): ByteArray? {
        val parts = mac.trim().split(':', '-')
        if (parts.size != 6) return null
        return runCatching { ByteArray(6) { parts[it].toInt(16).toByte() } }.getOrNull()
    }

    /** The whole-network broadcast, and the one for this network's own range. */
    private fun broadcastAddresses(context: Context): List<InetAddress> {
        val found = mutableListOf(InetAddress.getByName("255.255.255.255"))
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val link = manager.getLinkProperties(manager.activeNetwork)
        link?.linkAddresses?.forEach { address ->
            val ip = address.address as? Inet4Address ?: return@forEach
            val prefix = address.prefixLength
            if (prefix in 1..30) {
                val mask = -1 shl (32 - prefix)
                val raw = ip.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
                val broadcast = raw or mask.inv()
                found += InetAddress.getByAddress(ByteArray(4) { (broadcast ushr (24 - 8 * it)).toByte() })
            }
        }
        return found.distinctBy { it.hostAddress }
    }
}
