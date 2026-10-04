package nl.markmaaktmedia.tandem.live

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import nl.markmaaktmedia.tandem.R
import uniffi.tandem_core.TandemMediaKind

/**
 * The two notifications of sharing the screen or the camera: the question when a Mac asks, and the one that stays for
 * as long as something is being shown. The second cannot be swiped away and says in plain words what is going out, with
 * the way to stop it in reach. Channels are made here, when first needed, so the shared notification code stays as it is.
 */
object LiveNotifications {
    const val CHANNEL_ASK = "live_ask"
    const val CHANNEL_ACTIVE = "live_active"
    const val ACTIVE_ID = 0x4C56

    private fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ASK) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ASK, context.getString(R.string.live_channel_ask), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.live_channel_ask_desc)
                },
            )
        }
        if (manager.getNotificationChannel(CHANNEL_ACTIVE) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ACTIVE, context.getString(R.string.live_channel_active), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.live_channel_active_desc)
                    setShowBadge(false)
                },
            )
        }
    }

    fun askId(session: ULong): Int = 0x4C00 + (session.toLong() xor (session.toLong() ushr 32)).toInt().and(0xFF)

    /** A Mac wants to see the screen or the camera. Opens the question when tapped, and on a locked phone by itself. */
    fun ask(context: Context, mac: String, kind: TandemMediaKind, open: PendingIntent, deny: PendingIntent): Notification {
        ensureChannels(context)
        val camera = kind == TandemMediaKind.CAMERA
        return NotificationCompat.Builder(context, CHANNEL_ASK)
            .setSmallIcon(R.drawable.ic_stat_tandem)
            .setContentTitle(context.getString(if (camera) R.string.live_ask_camera else R.string.live_ask_screen, mac))
            .setContentText(context.getString(R.string.live_ask_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .addAction(0, context.getString(R.string.live_deny), deny)
            .build()
    }

    /** What is being shown right now. Ongoing, so it stays; Stop is on it. */
    fun active(context: Context, text: String, open: PendingIntent, stop: PendingIntent): Notification {
        ensureChannels(context)
        return NotificationCompat.Builder(context, CHANNEL_ACTIVE)
            .setSmallIcon(R.drawable.ic_stat_tandem)
            .setContentTitle(context.getString(R.string.live_active_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.live_stop), stop)
            .build()
    }

    fun activity(context: Context, code: Int, intent: Intent): PendingIntent =
        PendingIntent.getActivity(context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}
