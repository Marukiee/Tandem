package nl.markmaaktmedia.tandem.engine

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.MainActivity
import nl.markmaaktmedia.tandem.R
import uniffi.tandem_core.TandemResumable
import java.util.concurrent.ConcurrentHashMap

/**
 * Incoming transfers that stopped halfway before this start (P2). Nothing goes on by itself: each waits on the page Transfers
 * and in a notification until the person says go on or throw away. See docs/TRANSFERS.md, which the other apps follow.
 */
class HalfwayTransfers(
    private val context: Context,
    private val host: EngineHost,
    private val scope: CoroutineScope,
) {
    private val _waiting = MutableStateFlow<List<TandemResumable>>(emptyList())
    val waiting: StateFlow<List<TandemResumable>> = _waiting

    /** Thrown away by the person: its files end as cancelled, which needs no row of its own. */
    private val thrownAway = ConcurrentHashMap.newKeySet<String>()

    /** Asked about once per start in a notification; the page keeps asking. */
    private val told = ConcurrentHashMap.newKeySet<String>()

    private val main = Handler(Looper.getMainLooper())

    /** After the start and whenever a device connects, so Go on is ready as soon as the sender is. */
    fun refresh() {
        scope.launch(Dispatchers.IO) {
            val engine = host.engine ?: return@launch
            val found = runCatching { engine.resumableTransfers() }.getOrNull() ?: return@launch
            // Gone from the list without an answer here (the other end gave up, or the three days ran out): its question goes too.
            val before = _waiting.value
            _waiting.value = found
            before.filter { old -> found.none { it.from == old.from && it.offer == old.offer } }.forEach { cancelNotification(it) }
            for (transfer in found) if (told.add(key(transfer.from, transfer.offer))) notify(transfer)
        }
    }

    fun goOn(transfer: TandemResumable) {
        val engine = host.engine ?: return
        // The button is grey while the sender is away, and says why when it is used.
        if (host.device(transfer.from)?.online != true) {
            toast(context.getString(R.string.halfway_toast_away, senderName(transfer)))
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                engine.resumeTransfer(transfer.from, transfer.offer)
                settle(transfer)
            } catch (e: Exception) {
                toast(e.message ?: context.getString(R.string.transfer_failed))
                refresh()
            }
        }
    }

    fun throwAway(transfer: TandemResumable) {
        val engine = host.engine ?: return
        thrownAway.add(key(transfer.from, transfer.offer))
        settle(transfer)
        scope.launch(Dispatchers.IO) {
            runCatching { engine.discardTransfer(transfer.from, transfer.offer) }
            refresh()
        }
    }

    /** A file of a transfer that was thrown away ended: the person chose that, so it gets no row on the page Transfers. */
    fun swallows(offer: ULong, from: String): Boolean = thrownAway.contains(key(from, offer))

    /** What a notification button answered. A tap on the notification itself opens the page, where the question is. */
    fun answer(from: String, offer: ULong, action: String) {
        val transfer = _waiting.value.firstOrNull { it.from == from && it.offer == offer }
        if (transfer == null) {
            // The notification outlived the process that knew the question: the page asks it again.
            context.getSystemService(NotificationManager::class.java).cancel("halfway.${key(from, offer)}".hashCode())
            return
        }
        when (action) {
            ActionReceiver.GO_ON -> goOn(transfer)
            ActionReceiver.THROW_AWAY -> throwAway(transfer)
        }
    }

    fun senderName(transfer: TandemResumable): String =
        host.device(transfer.from)?.name ?: context.getString(R.string.halfway_other_device)

    /** The first file that did not come in whole, and how many more there are. */
    fun title(transfer: TandemResumable): String {
        val open = transfer.items.filter { !it.ended }
        val first = open.firstOrNull() ?: transfer.items.firstOrNull() ?: return ""
        return if (open.size > 1) context.getString(R.string.halfway_name_more, first.name, open.size - 1) else first.name
    }

    private fun settle(transfer: TandemResumable) {
        cancelNotification(transfer)
        _waiting.value = _waiting.value.filterNot { it.from == transfer.from && it.offer == transfer.offer }
    }

    private fun toast(text: String) = main.post { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }

    private fun notificationId(transfer: TandemResumable) = "halfway.${key(transfer.from, transfer.offer)}".hashCode()

    private fun cancelNotification(transfer: TandemResumable) {
        context.getSystemService(NotificationManager::class.java).cancel(notificationId(transfer))
    }

    private fun notify(transfer: TandemResumable) {
        if (!Channels.canPost(context)) return
        fun action(kind: String) = PendingIntent.getBroadcast(
            context, notificationId(transfer) + kind.hashCode(),
            Intent(context, ActionReceiver::class.java).setAction(kind)
                .putExtra(ActionReceiver.EXTRA_DEVICE, transfer.from)
                .putExtra(ActionReceiver.EXTRA_OFFER, transfer.offer.toLong()),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            context, 0,
            MainActivity.internalIntent(context).setAction(MainActivity.ACTION_OPEN_TRANSFERS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        context.getSystemService(NotificationManager::class.java).notify(
            notificationId(transfer),
            NotificationCompat.Builder(context, Channels.INCOMING)
                .setSmallIcon(R.drawable.ic_stat_tandem)
                .setContentTitle(context.getString(R.string.halfway_notification_title, senderName(transfer)))
                .setContentText(context.getString(R.string.halfway_notification_text, title(transfer)))
                .setContentIntent(open)
                .addAction(0, context.getString(R.string.halfway_throw_away), action(ActionReceiver.THROW_AWAY))
                .addAction(0, context.getString(R.string.halfway_go_on), action(ActionReceiver.GO_ON))
                .setAutoCancel(false)
                .build(),
        )
    }

    private fun key(from: String, offer: ULong) = "$from-$offer"

    companion object {
        /** The bytes there are and the bytes in all, over the files of a transfer. */
        fun bytes(transfer: TandemResumable): Pair<Long, Long> =
            transfer.items.fold(0L to 0L) { (done, total), item -> (done + minOf(item.done, item.size).toLong()) to (total + item.size.toLong()) }
    }
}
