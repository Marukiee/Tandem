package nl.markmaaktmedia.tandem.engine

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.MainActivity
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.calls.CallMonitor
import nl.markmaaktmedia.tandem.hotspot.HotspotModule
import nl.markmaaktmedia.tandem.mirror.ListenerWatchdog
import nl.markmaaktmedia.tandem.share.ClipboardSendActivity
import nl.markmaaktmedia.tandem.share.ScreenshotWatcher
import nl.markmaaktmedia.tandem.share.ShareShortcuts
import uniffi.tandem_core.TandemEvent

/**
 * Keeps Tandem alive in the background so devices can reach this phone. It hosts the
 * engine's connections, reports battery and network, and turns events into
 * notifications. The notification it shows is the price Android asks for that, and it
 * is as quiet as the system allows.
 */
class TandemService : LifecycleService() {

    private lateinit var status: StatusReporter
    private lateinit var screenshots: ScreenshotWatcher
    private lateinit var calls: CallMonitor
    private lateinit var hotspot: HotspotModule
    private lateinit var listenerWatchdog: ListenerWatchdog
    private var multicast: WifiManager.MulticastLock? = null
    private var lastProgressPost = 0L

    override fun onCreate() {
        super.onCreate()
        Channels.create(this)
        startForeground(NOTIFICATION_ID, foregroundNotification(emptyList()), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)

        // mDNS needs multicast packets, which Wi-Fi drops to save power unless asked. Asking costs power all day long,
        // on a busy network more than anything else Tandem does, so it is only asked for while a paired device is
        // missing and has to be found (see the collector of the device list below).
        multicast = getSystemService(WifiManager::class.java)
            .createMulticastLock("tandem").apply { setReferenceCounted(false) }

        val host = graph.host
        host.start()
        status = StatusReporter(this, host, graph.scope).also { it.start() }
        screenshots = ScreenshotWatcher(this, host, graph.prefs, graph.scope).also { it.start() }
        calls = CallMonitor(this, host, graph.prefs, graph.scope).also { it.start() }
        hotspot = HotspotModule.get(this).also { it.start { status.resend() } }
        graph.media.start()
        listenerWatchdog = ListenerWatchdog(this, graph.scope).also { it.start() }

        lifecycleScope.launch {
            host.devices.collectLatest { devices ->
                // Everything paired is here: nobody to look for, so the Wi-Fi may filter multicast again.
                val missing = devices.any { !it.online }
                multicast?.let { lock ->
                    if (missing && !lock.isHeld) lock.acquire() else if (!missing && lock.isHeld) lock.release()
                }
                val online = devices.filter { it.online }.map { it.name }
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, foregroundNotification(online))
                ShareShortcuts.update(this@TandemService, devices)
            }
        }
        lifecycleScope.launch { host.events.collect { graph.audio.onEvent(it); handle(it) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onDestroy() {
        status.stop()
        screenshots.stop()
        calls.stop()
        hotspot.stop()
        listenerWatchdog.stop()
        graph.media.stop()
        graph.audio.stop(tell = true)
        multicast?.takeIf { it.isHeld }?.release()
        FindPhone.stop()
        super.onDestroy()
    }

    private fun handle(event: TandemEvent) {
        val host = graph.host
        when (event) {
            is TandemEvent.Connected -> status.resend()

            is TandemEvent.Progress -> {
                val now = System.currentTimeMillis()
                if (now - lastProgressPost > 700) {
                    lastProgressPost = now
                    postProgress()
                }
            }

            is TandemEvent.Finished -> {
                postProgress()
                if (event.incoming && event.error == null && event.location != null) {
                    postReceived(event.name, host.device(event.peer)?.name ?: "?", event.location!!)
                }
            }

            is TandemEvent.ShareOffered -> {
                val device = host.device(event.from)
                if (device != null && !device.autoAccept) postOffer(device.name, event.from, event.offer.toLong(), event.items.size)
            }

            is TandemEvent.Ring -> if (event.on) FindPhone.start(this) else FindPhone.stop()

            is TandemEvent.CallAction -> calls.handle(event)
            is TandemEvent.Dial -> calls.dial(event.number)
            is TandemEvent.Hotspot -> hotspot.onEvent(event)

            is TandemEvent.Notification -> Unit

            else -> Unit
        }
    }

    // ---- Notifications ---------------------------------------------------------

    private fun open(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun foregroundNotification(online: List<String>): Notification {
        val text = if (online.isEmpty()) getString(R.string.service_searching) else getString(R.string.service_connected, online.joinToString(", "))
        val send = PendingIntent.getActivity(
            this, 1, Intent(this, ClipboardSendActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, Channels.CONNECTION)
            .setSmallIcon(R.drawable.ic_stat_tandem)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open())
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.action_send_clipboard), send)
            .build()
    }

    private fun postProgress() {
        val active = graph.host.transfers.value.filter { it.state == TransferItem.State.Active }
        val manager = getSystemService(NotificationManager::class.java)
        if (active.isEmpty()) {
            manager.cancel(PROGRESS_ID)
            return
        }
        val total = active.sumOf { it.total }
        val done = active.sumOf { it.done }
        val incoming = active.any { it.incoming }
        val title = if (active.size == 1) active[0].name else getString(R.string.transfer_many, active.size)
        manager.notify(
            PROGRESS_ID,
            NotificationCompat.Builder(this, Channels.TRANSFERS)
                .setSmallIcon(if (incoming) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_upload)
                .setContentTitle(title)
                .setContentText(getString(if (incoming) R.string.transfer_receiving else R.string.transfer_sending))
                .setProgress(100, if (total > 0) (done * 100 / total).toInt() else 0, total <= 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open())
                .build(),
        )
    }

    private fun postReceived(name: String, from: String, location: String) {
        if (!Channels.canPost(this)) return
        val uri = Uri.parse(location)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, contentResolver.getType(uri) ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        getSystemService(NotificationManager::class.java).notify(
            location.hashCode(),
            NotificationCompat.Builder(this, Channels.INCOMING)
                .setSmallIcon(R.drawable.ic_stat_tandem)
                .setContentTitle(getString(R.string.received_title, name))
                .setContentText(getString(R.string.received_from, from))
                .setContentIntent(PendingIntent.getActivity(this, location.hashCode(), view, PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true)
                .setGroup("received")
                .build(),
        )
    }

    private fun postOffer(deviceName: String, from: String, offer: Long, count: Int) {
        val manager = getSystemService(NotificationManager::class.java)
        fun action(kind: String) = PendingIntent.getBroadcast(
            this, (offer + kind.hashCode()).toInt(),
            Intent(this, ActionReceiver::class.java).setAction(kind).putExtra(ActionReceiver.EXTRA_DEVICE, from)
                .putExtra(ActionReceiver.EXTRA_OFFER, offer),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        manager.notify(
            offer.hashCode(),
            NotificationCompat.Builder(this, Channels.INCOMING)
                .setSmallIcon(R.drawable.ic_stat_tandem)
                .setContentTitle(resources.getQuantityString(R.plurals.offer_files, count, deviceName, count))
                .addAction(0, getString(R.string.action_accept), action(ActionReceiver.ACCEPT))
                .addAction(0, getString(R.string.action_decline), action(ActionReceiver.DECLINE))
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val PROGRESS_ID = 2

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TandemService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TandemService::class.java))
        }
    }
}
