package nl.markmaaktmedia.tandem.engine

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.graph
import uniffi.tandem_core.TandemItemState
import uniffi.tandem_core.TandemOutgoingFile
import uniffi.tandem_core.TandemShareOrigin
import uniffi.tandem_core.TandemTransfer
import uniffi.tandem_core.TandemTransferPeer
import uniffi.tandem_core.TandemTransferStage
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What is underway and incoming (P3b): every transfer that runs, waits for a yes, waits in the queue for a device that is away (P3), or
 * failed and can be sent again, read from the journal of the core. Transfers that stopped halfway are [HalfwayTransfers]. See
 * docs/TRANSFERS.md, which the other apps follow.
 */
class UnderwayTransfers(
    private val context: Context,
    private val host: EngineHost,
    private val scope: CoroutineScope,
) {
    enum class Kind {
        /** Outgoing: the device is away, the offer goes when it is back. */
        Queued,

        /** Outgoing: waits for a yes on the other device. Incoming: waits for a yes here. */
        Waiting,
        Running,

        /** Outgoing: files that did not arrive and can be sent again. */
        Failed,
    }

    /** One transfer with one device: an outgoing offer has a row per receiver, so each can be stopped on its own. */
    data class Row(val transfer: TandemTransfer, val peer: TandemTransferPeer, val kind: Kind) {
        val id: String get() = "${if (transfer.incoming) "in" else "out"}-${transfer.offer}-${peer.device}"
        val incoming: Boolean get() = transfer.incoming

        /** The first file that has not ended (or, for a failed row, the first that failed), and how many more there are. */
        val files: List<Int>
            get() {
                val open = peer.items.indices.filter { index ->
                    if (kind == Kind.Failed) peer.items[index].state == TandemItemState.FAILED else !ended(peer.items[index].state)
                }
                return open.ifEmpty { transfer.items.indices.toList() }.filter { it < transfer.items.size }
            }

        /** The bytes there are and the bytes in all, over the files of this row. */
        val bytes: Pair<Long, Long>
            get() = transfer.items.indices.fold(0L to 0L) { (done, total), index ->
                val size = transfer.items[index].size.toLong()
                val progress = peer.items.getOrNull(index)
                val have = if (progress?.state == TandemItemState.DONE) size else minOf(progress?.done?.toLong() ?: 0L, size)
                (done + have) to (total + size)
            }

        /** The code of the first file that failed, for [transferReason]. */
        val error: String? get() = peer.items.firstOrNull { it.state == TandemItemState.FAILED }?.error
    }

    private val _all = MutableStateFlow<List<TandemTransfer>>(emptyList())
    val all: StateFlow<List<TandemTransfer>> = _all

    /** Failed rows the person sent again or put away. */
    private val _putAway = MutableStateFlow<Set<String>>(emptySet())
    val putAway: StateFlow<Set<String>> = _putAway

    private val refreshing = AtomicBoolean(false)

    @Volatile
    private var lastRefresh = 0L

    /**
     * After the start, on every event about a transfer and when a device comes or goes. Progress comes many times a second per file,
     * so with [soon] it reads at most every half second; the end of a file reads at once.
     */
    fun refresh(soon: Boolean = false) {
        if (soon) {
            if (!refreshing.compareAndSet(false, true)) return
            scope.launch(Dispatchers.IO) {
                delay((REFRESH_EVERY_MS - (System.currentTimeMillis() - lastRefresh)).coerceAtLeast(0))
                refreshing.set(false)
                read()
            }
            return
        }
        scope.launch(Dispatchers.IO) { read() }
    }

    private fun read() {
        val engine = host.engine ?: return
        lastRefresh = System.currentTimeMillis()
        val found = runCatching { engine.transfers() }.getOrNull() ?: return
        if (found != _all.value) _all.value = found
        // An offer that went out and was put back in the queue keeps its files as long as the core keeps the offer.
        found.filter { !it.incoming && it.peers.any { peer -> peer.stage == TandemTransferStage.QUEUED } }.forEach { AndroidFiles.queued(it.offer) }
    }

    /** Stops it for that one device; the others an outgoing offer went to keep going (R-B8). */
    fun stop(row: Row) {
        val engine = host.engine ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { engine.cancelTransfer(row.peer.device, row.transfer.offer, row.incoming) }
            read()
        }
    }

    fun accept(row: Row) {
        val engine = host.engine ?: return
        TandemService.offerAnswered(context, row.transfer.offer.toLong())
        scope.launch(Dispatchers.IO) {
            try {
                engine.acceptOffer(row.peer.device, row.transfer.offer)
            } catch (e: Exception) {
                toast(e.message ?: context.getString(R.string.transfer_failed))
            }
            read()
        }
    }

    fun decline(row: Row) {
        val engine = host.engine ?: return
        TandemService.offerAnswered(context, row.transfer.offer.toLong())
        scope.launch(Dispatchers.IO) {
            runCatching { engine.declineOffer(row.peer.device, row.transfer.offer) }
            read()
        }
    }

    /**
     * Sends the files that failed once more, as a new offer to the same device. A file that is no longer there says so instead of
     * failing a second time (the grant of a file of another app does not outlive a restart of the app).
     */
    fun sendAgain(row: Row) {
        val files = row.transfer.items.indices
            .filter { row.peer.items.getOrNull(it)?.state == TandemItemState.FAILED && row.transfer.items[it].source.isNotEmpty() }
            .map { row.transfer.items[it] }
            .map { TandemOutgoingFile(source = it.source, name = it.name, size = it.size, mime = it.mime) }
        scope.launch {
            val reachable = withContext(Dispatchers.IO) { files.filter { AndroidFiles.canRead(context, it.source) } }
            if (reachable.size < files.size) toast(context.getString(R.string.transfer_gone_out))
            if (reachable.isEmpty()) return@launch
            host.sendOutgoing(reachable, listOf(row.peer.device), TandemShareOrigin.Files)
            putAway(row)
            read()
        }
    }

    fun putAway(row: Row) {
        _putAway.update { it + row.id }
    }

    fun deviceName(row: Row): String =
        host.device(row.peer.device)?.name ?: context.getString(R.string.halfway_other_device)

    /** The file name of a row: the first that has not ended, with how many more there are. */
    fun title(row: Row): String {
        val shown = row.files
        val first = shown.firstOrNull() ?: return ""
        val name = row.transfer.items[first].name
        return if (shown.size > 1) context.getString(R.string.halfway_name_more, name, shown.size - 1) else name
    }

    private fun toast(text: String) = android.os.Handler(android.os.Looper.getMainLooper()).post {
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val REFRESH_EVERY_MS = 500L

        fun ended(state: TandemItemState): Boolean =
            state != TandemItemState.WAITING && state != TandemItemState.RUNNING && state != TandemItemState.INTERRUPTED

        /** Which rows the page shows: one per offer per device, in the order of the journal (the newest first). */
        fun rows(all: List<TandemTransfer>, putAway: Set<String>): List<Row> =
            all.flatMap { transfer ->
                transfer.peers.mapNotNull { peer ->
                    val kind = kind(transfer, peer) ?: return@mapNotNull null
                    Row(transfer, peer, kind).takeIf { it.id !in putAway }
                }
            }

        private fun kind(transfer: TandemTransfer, peer: TandemTransferPeer): Kind? {
            // Taken by the clipboard, a drop or Insert from phone: those show themselves, and a question here would only puzzle.
            if (transfer.incoming) {
                when (transfer.origin) {
                    is TandemShareOrigin.Clipboard, is TandemShareOrigin.Drag, is TandemShareOrigin.Capture -> return null
                    else -> Unit
                }
            }
            if (transfer.halfway) return null
            val open = peer.items.any { !ended(it.state) }
            when (peer.stage) {
                TandemTransferStage.QUEUED -> return Kind.Queued
                TandemTransferStage.OFFERED -> return Kind.Waiting
                TandemTransferStage.ACCEPTED -> if (open) return Kind.Running
                else -> Unit
            }
            // Only what was sent from here can be sent again; a file that came in failed is on the list below with its reason.
            val failed = peer.items.any { it.state == TandemItemState.FAILED }
            return if (!transfer.incoming && failed) Kind.Failed else null
        }
    }
}
