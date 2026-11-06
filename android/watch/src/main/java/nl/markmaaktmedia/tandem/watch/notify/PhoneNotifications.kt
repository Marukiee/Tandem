package nl.markmaaktmedia.tandem.watch.notify

import nl.markmaaktmedia.tandem.watch.track.Tracking
import nl.markmaaktmedia.tandem.watch.track.TrackingKind
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import java.io.File
import nl.markmaaktmedia.tandem.watch.R
import nl.markmaaktmedia.tandem.watch.protocol.Message
import nl.markmaaktmedia.tandem.watch.protocol.NotificationMsg

/**
 * Shows phone notifications as ordinary Wear OS notifications, so they look and behave like the system's own.
 * The phone's key is the notification tag, which also lets a resync work out what is gone after a restart.
 */
class PhoneNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val icons = File(context.noBackupFilesDir, "app-icons")

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_notifications), NotificationManager.IMPORTANCE_HIGH),
        )
    }

    fun keepIcon(icon: Message.AppIcon) {
        if (icon.image.size > Message.AppIcon.MAX_BYTES || !PACKAGE_NAME.matches(icon.appId)) return
        runCatching {
            icons.mkdirs()
            File(icons, icon.appId).writeBytes(icon.image)
        }
    }

    private fun iconOf(appId: String): Bitmap? =
        File(icons, appId).takeIf { PACKAGE_NAME.matches(appId) && it.isFile }?.let { BitmapFactory.decodeFile(it.path) }

    fun post(n: NotificationMsg) {
        // Wear OS ignores the small icon and draws this one in its place, with Tandem's as a badge. Without one Tandem's own stays.
        val icon = iconOf(n.appId)
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_tandem)
            .setLargeIcon(icon)
            .setContentTitle(n.title.ifBlank { n.appName })
            .setContentText(n.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(n.text))
            .setSubText(n.subText ?: n.appName)
            .setWhen(n.ts)
            .setShowWhen(true)
            .setLocalOnly(true)
            .setOnlyAlertOnce(true)
            // A buzz on the wrist would wake the one whose sleep is being measured.
            .setSilent(n.silent || Tracking.ui.value.active == TrackingKind.SLEEP)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setDeleteIntent(ActionReceiver.dismissIntent(context, n.key))
            // On a locked watch only the app shows: it may be off the wrist or lying on a table.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_tandem)
                    .setLargeIcon(icon)
                    .setContentTitle(context.getString(R.string.hidden_notification, n.appName))
                    .build(),
            )
        n.progress?.let { builder.setProgress(100, it, false) }
        n.buttons.forEach { button ->
            val action = NotificationCompat.Action.Builder(
                R.drawable.ic_stat_tandem,
                button.title,
                ActionReceiver.buttonIntent(context, n.key, button.id, button.isReply),
            )
            if (button.isReply) {
                action.addRemoteInput(RemoteInput.Builder(ActionReceiver.REPLY_KEY).setLabel(button.title).build())
            }
            builder.addAction(action.build())
        }
        manager.notify(n.key, NOTIFICATION_ID, builder.build())
    }

    fun remove(key: String) {
        manager.cancel(key, NOTIFICATION_ID)
    }

    /** Anything not in [keys] is gone from the phone. */
    fun reconcile(keys: List<String>) {
        val current = keys.toSet()
        shownTags().filterNot(current::contains).forEach(::remove)
    }

    /** The icons go too: they say which apps were on the phone this watch is no longer paired with. */
    fun clear() {
        shownTags().forEach(::remove)
        icons.deleteRecursively()
    }

    private fun shownTags() = manager.activeNotifications
        .filter { it.id == NOTIFICATION_ID && it.tag != null }
        .map { it.tag }

    private companion object {
        const val CHANNEL = "phone"
        const val NOTIFICATION_ID = 100

        /** The file name comes from the other side of the link, and no package name can climb out of the folder. */
        val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_.]*")
    }
}

/** Requests are told apart by their code; a hash collision between two notifications is harmless for this short life. */
internal fun requestCode(vararg parts: String): Int = parts.joinToString("|").hashCode()

internal fun mutableBroadcast(context: Context, code: Int, intent: Intent, mutable: Boolean): PendingIntent =
    PendingIntent.getBroadcast(
        context,
        code,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE,
    )
