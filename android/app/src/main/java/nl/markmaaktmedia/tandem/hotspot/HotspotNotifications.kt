package nl.markmaaktmedia.tandem.hotspot

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.core.app.NotificationCompat
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.engine.Channels

/** The two notifications of the hotspot feature. */
class HotspotNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    /** Made here, not in Channels: it needs a higher importance than the ongoing one. */
    private fun ensureRequestChannel() {
        val channel = NotificationChannel(CHANNEL_REQUEST, context.getString(R.string.hotspot_channel_request), NotificationManager.IMPORTANCE_HIGH)
        channel.description = context.getString(R.string.hotspot_channel_request_desc)
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    /** The phone cannot start the hotspot alone. Heads up, and one tap opens the switch. */
    fun postRequest() {
        if (!Channels.canPost(context)) return
        ensureRequestChannel()
        manager.notify(
            REQUEST_ID,
            NotificationCompat.Builder(context, CHANNEL_REQUEST)
                .setSmallIcon(R.drawable.ic_stat_tandem)
                .setContentTitle(context.getString(R.string.hotspot_request_title))
                .setContentText(context.getString(R.string.hotspot_request_text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setContentIntent(openSettings())
                .setAutoCancel(true)
                // A request nobody answers in a few minutes is stale: the Mac gave up too.
                .setTimeoutAfter(REQUEST_TIMEOUT_MS)
                .build(),
        )
    }

    fun cancelRequest() = manager.cancel(REQUEST_ID)

    /**
     * Shown for as long as a Mac asked for the hotspot and it is on. With Shizuku the
     * button turns it off; without, it can only take you to the switch.
     */
    fun postInUse(automatic: Boolean, clients: Int?, usedBytes: Long = 0L) {
        if (!Channels.canPost(context)) return
        val text = when {
            !automatic -> context.getString(R.string.hotspot_in_use_manual)
            clients != null && clients > 0 -> context.getString(R.string.hotspot_in_use_clients, clients)
            else -> context.getString(R.string.hotspot_in_use_auto)
        } + if (usedBytes > 0) " · " + context.getString(R.string.hotspot_in_use_data, android.text.format.Formatter.formatShortFileSize(context, usedBytes)) else ""
        val stop = if (automatic) {
            PendingIntent.getBroadcast(
                context, 0, Intent(context, HotspotActionReceiver::class.java).setAction(HotspotActionReceiver.STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        } else {
            openSettings()
        }
        manager.notify(
            IN_USE_ID,
            NotificationCompat.Builder(context, Channels.HOTSPOT)
                .setSmallIcon(R.drawable.ic_stat_tandem)
                .setContentTitle(context.getString(R.string.hotspot_in_use_title))
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(openSettings())
                .addAction(0, context.getString(R.string.action_stop), stop)
                .build(),
        )
    }

    fun cancelInUse() = manager.cancel(IN_USE_ID)

    /** The hotspot was switched off because the Mac used up what it was allowed. */
    fun postLimitReached(limitMb: Long) {
        if (!Channels.canPost(context)) return
        manager.notify(
            LIMIT_ID,
            NotificationCompat.Builder(context, Channels.HOTSPOT)
                .setSmallIcon(R.drawable.ic_stat_tandem)
                .setContentTitle(context.getString(R.string.hotspot_limit_title))
                .setContentText(context.getString(R.string.hotspot_limit_text, limitMb))
                .setAutoCancel(true)
                .setContentIntent(openSettings())
                .build(),
        )
    }

    fun cancelAll() {
        cancelRequest()
        cancelInUse()
    }

    private fun openSettings(): PendingIntent = PendingIntent.getActivity(
        context, 1, Intent(context, OpenTetherSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL_REQUEST = "hotspot_request"
        private const val REQUEST_ID = 71
        private const val IN_USE_ID = 72
        private const val LIMIT_ID = 73
        private const val REQUEST_TIMEOUT_MS = 3 * 60_000L
    }
}

/** The system screen with the hotspot switch, wherever this phone keeps it. */
object TetherSettings {
    fun open(context: Context) {
        val candidates = listOf(
            // Stock Android and most others.
            Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"),
            Intent().setClassName("com.android.settings", "com.android.settings.Settings\$TetherSettingsActivity"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in candidates) {
            val started = runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
            if (started) return
        }
    }
}

/**
 * A notification may not start the system settings from a service or receiver on
 * recent Android, but an activity of ours may. This one has no screen: it opens the
 * hotspot switch and is gone.
 */
class OpenTetherSettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TetherSettings.open(this)
        finish()
    }
}

/** The Stop button of the "in use" notification. */
class HotspotActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == STOP) HotspotModule.current?.controller?.request(on = false)
    }

    companion object {
        const val STOP = "nl.markmaaktmedia.tandem.HOTSPOT_STOP"
    }
}
