package nl.markmaaktmedia.tandem.quickshare

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.graph

/**
 * What Quick Share says outside the app: a card that comes up with Decline and Accept when someone wants to send something,
 * the progress while it comes in, and what arrived. It looks like the card of the Quick Share of Android, because that is what
 * people know.
 */
class QuickShareNotifications(private val app: Application) {
    private val manager = NotificationManagerCompat.from(app)

    init {
        val system = app.getSystemService(NotificationManager::class.java)
        system.createNotificationChannel(
            NotificationChannel(ASK, app.getString(R.string.quickshare_channel_ask), NotificationManager.IMPORTANCE_HIGH).apply {
                description = app.getString(R.string.quickshare_channel_ask_desc)
                setShowBadge(false)
            },
        )
        system.createNotificationChannel(
            NotificationChannel(PROGRESS, app.getString(R.string.quickshare_channel_progress), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
    }

    private fun idOf(id: ULong): Int = (id % 100_000uL).toInt() + BASE

    private fun action(id: ULong, what: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(
            app, idOf(id) * 4 + code,
            Intent(app, QuickShareActionReceiver::class.java).setAction(what).putExtra(EXTRA_ID, id.toLong()),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun summary(item: QuickShareHost.Incoming): String {
        val first = item.files.firstOrNull()?.name ?: item.texts.firstOrNull()?.title ?: app.getString(R.string.quickshare_something)
        val rest = item.files.size + item.texts.size - 1
        return if (rest > 0) app.getString(R.string.quickshare_and_more, first, rest) else first
    }

    private fun post(id: Int, notification: android.app.Notification) {
        if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) return
        runCatching { manager.notify(id, notification) }
    }

    /** The question: who, what, and the PIN to compare. */
    fun ask(item: QuickShareHost.Incoming) {
        val notification = NotificationCompat.Builder(app, ASK)
            .setSmallIcon(R.drawable.ic_stat_tandem)
            .setContentTitle(app.getString(R.string.quickshare_wants_to_share, item.sender))
            .setContentText(summary(item))
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    summary(item) + "\n" + app.getString(R.string.quickshare_pin_line, item.pin),
                ),
            )
            .setSubText(app.getString(R.string.quickshare_pin, item.pin))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(120_000)
            .addAction(0, app.getString(R.string.quickshare_decline), action(item.id, DECLINE, 0))
            .addAction(0, app.getString(R.string.quickshare_accept), action(item.id, ACCEPT, 1))
            .build()
        post(idOf(item.id), notification)
    }

    fun progress(item: QuickShareHost.Incoming) {
        val total = item.total.toLong().coerceAtLeast(1)
        val notification = NotificationCompat.Builder(app, PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_tandem)
            .setContentTitle(app.getString(R.string.quickshare_receiving, item.sender))
            .setContentText(summary(item))
            .setProgress(1000, (item.done.toLong() * 1000 / total).toInt().coerceIn(0, 1000), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, app.getString(R.string.quickshare_cancel), action(item.id, DECLINE, 0))
            .build()
        post(idOf(item.id), notification)
    }

    fun received(item: QuickShareHost.Incoming) {
        val builder = NotificationCompat.Builder(app, PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_tandem)
            .setContentTitle(app.getString(R.string.quickshare_received_from, item.sender))
            .setContentText(if (item.saved.isNullOrEmpty()) app.getString(R.string.quickshare_copied) else summary(item))
            .setAutoCancel(true)
            .setTimeoutAfter(30_000)
        // A link opens from the notification.
        item.link?.let { link ->
            val open = PendingIntent.getActivity(
                app, idOf(item.id), Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.setContentIntent(open).addAction(0, app.getString(R.string.quickshare_open_link), open)
        }
        post(idOf(item.id), builder.build())
    }

    fun failed(item: QuickShareHost.Incoming) {
        val notification = NotificationCompat.Builder(app, PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_tandem)
            .setContentTitle(app.getString(R.string.quickshare_stopped))
            .setContentText(item.failure ?: summary(item))
            .setAutoCancel(true)
            .setTimeoutAfter(20_000)
            .build()
        post(idOf(item.id), notification)
    }

    fun cancel(id: ULong) {
        manager.cancel(idOf(id))
    }

    companion object {
        private const val ASK = "quickshare_ask"
        private const val PROGRESS = "quickshare_progress"
        private const val BASE = 81_000
        const val ACCEPT = "nl.markmaaktmedia.tandem.QUICKSHARE_ACCEPT"
        const val DECLINE = "nl.markmaaktmedia.tandem.QUICKSHARE_DECLINE"
        const val EXTRA_ID = "id"
    }
}

/** The buttons on the card. */
class QuickShareActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val host = context.graph.quickShare
        val id = intent.getLongExtra(QuickShareNotifications.EXTRA_ID, 0).toULong()
        when (intent.action) {
            QuickShareNotifications.ACCEPT -> host.accept(id)
            QuickShareNotifications.DECLINE -> host.decline(id)
        }
    }
}
