package nl.markmaaktmedia.tandem.hotspot

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import uniffi.tandem_core.TandemEngine

/**
 * Clipboard and notifications over the Bluetooth link the Mac opens when there is no network.
 *
 * This only moves writes. The core seals, cuts into pieces and puts them back together, and
 * decides which device a link belongs to once a frame from it opens. What arrives is handled
 * exactly like a message over the network, so nothing above this knows the difference.
 */
class BleMessenger(
    private val scope: CoroutineScope,
    private val engine: () -> TandemEngine?,
) : BleMessageSink {
    private class Piece(val link: String, val bytes: ByteArray)

    private val lock = Any()

    /** Link address to the device id it turned out to belong to. */
    private val links = HashMap<String, String>()
    private val incoming = Channel<Piece>(Channel.UNLIMITED)
    private var server: BleHotspotServer? = null
    private var jobs = mutableListOf<Job>()

    fun attach(server: BleHotspotServer) {
        this.server = server
        if (jobs.isNotEmpty()) return
        // One reader, so the pieces of a message are handled in the order they came in.
        jobs += scope.launch(Dispatchers.IO) {
            for (piece in incoming) handle(piece)
        }
        jobs += scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(PUMP_MS)
                pump()
            }
        }
    }

    fun detach() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        val known = synchronized(lock) { links.values.toList().also { links.clear() } }
        val engine = engine()
        known.forEach { runCatching { engine?.bleLinkDown(it) } }
        server = null
    }

    override fun onWrite(link: String, chunk: ByteArray) {
        incoming.trySend(Piece(link, chunk))
    }

    override fun onLinkClosed(link: String) {
        val peer = synchronized(lock) { links.remove(link) }
        val engine = engine() ?: return
        runCatching { engine.bleDropLink(link) }
        if (peer != null) runCatching { engine.bleLinkDown(peer) }
    }

    private suspend fun handle(piece: Piece) {
        val engine = engine() ?: return
        val from = runCatching { engine.bleReceive(piece.link, piece.bytes) }.getOrNull() ?: return
        val first = synchronized(lock) { links.put(piece.link, from) != from }
        if (first) {
            // The Mac marks the link as this phone's when a frame from it opens, so answer with one.
            val size = server?.chunkSize(piece.link) ?: 20
            engine.bleHello(from, size.toUInt()).forEach { server?.sendMessage(piece.link, it) }
        }
    }

    private fun pump() {
        val engine = engine() ?: return
        val server = server ?: return
        val open = synchronized(lock) { links.toMap() }
        for ((link, peer) in open) {
            if (!server.messagesReady(link)) continue
            val chunks = runCatching { engine.bleTakeOutbox(peer, server.chunkSize(link).toUInt()) }.getOrElse {
                Log.w(TAG, "could not read the outbox", it)
                emptyList()
            }
            chunks.forEach { server.sendMessage(link, it) }
        }
    }

    private companion object {
        const val TAG = "BleMessenger"
        const val PUMP_MS = 400L
    }
}
