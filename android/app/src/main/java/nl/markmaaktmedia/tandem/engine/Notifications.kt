package nl.markmaaktmedia.tandem.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import nl.markmaaktmedia.tandem.R

/** Notification channels. Created once; the names are what people see in system settings. */
object Channels {
    const val CONNECTION = "connection"
    const val TRANSFERS = "transfers"
    const val INCOMING = "incoming"
    const val SCREENSHOT = "screenshot"
    const val HOTSPOT = "hotspot"

    fun create(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        fun channel(id: String, name: Int, description: Int, importance: Int) =
            NotificationChannel(id, context.getString(name), importance).apply {
                this.description = context.getString(description)
                setShowBadge(false)
            }
        manager.createNotificationChannels(
            listOf(
                channel(CONNECTION, R.string.channel_connection, R.string.channel_connection_desc, NotificationManager.IMPORTANCE_MIN),
                channel(TRANSFERS, R.string.channel_transfers, R.string.channel_transfers_desc, NotificationManager.IMPORTANCE_LOW),
                channel(INCOMING, R.string.channel_incoming, R.string.channel_incoming_desc, NotificationManager.IMPORTANCE_DEFAULT),
                channel(SCREENSHOT, R.string.channel_screenshot, R.string.channel_screenshot_desc, NotificationManager.IMPORTANCE_HIGH),
                channel(HOTSPOT, R.string.channel_hotspot, R.string.channel_hotspot_desc, NotificationManager.IMPORTANCE_DEFAULT),
            ),
        )
    }

    fun canPost(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()
}
