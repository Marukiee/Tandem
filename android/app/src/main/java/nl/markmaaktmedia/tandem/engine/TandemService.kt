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
import nl.markmaaktmedia.tandem.capture.CaptureActivity
import nl.markmaaktmedia.tandem.hotspot.HotspotModule
import nl.markmaaktmedia.tandem.mirror.ListenerWatchdog
import nl.markmaaktmedia.tandem.share.ClipboardPictures
import nl.markmaaktmedia.tandem.share.ClipboardSendActivity
import nl.markmaaktmedia.tandem.share.ScreenshotWatcher
import nl.markmaaktmedia.tandem.share.ShareShortcuts
import uniffi.tandem_core.TandemCaptureKind
import uniffi.tandem_core.TandemEvent
import uniffi.tandem_core.TandemMediaRole
import uniffi.tandem_core.TandemMediaKind

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
    private lateinit var sms: nl.markmaaktmedia.tandem.messages.SmsBridge
    private lateinit var focus: FocusShare
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
        sms = nl.markmaaktmedia.tandem.messages.SmsBridge(this, host, graph.scope).also { it.start() }
        FeatureReport.start(this, lifecycleScope)
        nl.markmaaktmedia.tandem.wifi.WifiShare.get(this).start()
        focus = FocusShare(this, host, graph.prefs, lifecycleScope).also { it.start() }

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
        sms.stop()
        focus.stop()
        graph.media.stop()
        graph.audio.stop(tell = true)
        multicast?.takeIf { it.isHeld }?.release()
        FindPhone.stop()
        nl.markmaaktmedia.tandem.wifi.WifiShare.get(this).stop()
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
                if (event.incoming && (event.offer in clipboardOffers || event.location?.let(ClipboardPictures::waits) == true)) {
                    // A picture or files copied on that device: on this phone's clipboard, not as a file that came in.
                    event.location?.takeIf { event.error == null }?.let { clipboardFile(event.offer, it) }
                } else if (event.incoming && event.error == null && event.location != null) {
                    postReceived(event.name, host.device(event.peer)?.name ?: "?", event.location!!)
                }
            }

            is TandemEvent.ShareOffered -> {
                val device = host.device(event.from)
                // Files that the person dragged over with the mouse of that computer are taken without asking: it was them.
                val dragged = event.origin == uniffi.tandem_core.TandemShareOrigin.Drag && graph.pointerIn.isPeer(event.from)
                // A picture or files copied on a device that shares the clipboard with this phone: taken without asking, for the clipboard.
                val copied = event.origin == uniffi.tandem_core.TandemShareOrigin.Clipboard && device?.clipboardEnabled == true &&
                    event.items.sumOf { it.size.toLong() } <= EngineHost.CLIPBOARD_FILES_LIMIT
                if (copied) {
                    clipboardOffers += event.offer
                    runCatching { host.engine?.acceptOffer(event.from, event.offer) }
                } else if (dragged) runCatching { host.engine?.acceptOffer(event.from, event.offer) }
                else if (device != null && !device.autoAccept) postOffer(device.name, event.from, event.offer.toLong(), event.items.size)
            }

            is TandemEvent.Ring -> if (event.on) FindPhone.start(this) else FindPhone.stop()
            is TandemEvent.Ringing -> FindDevices.reported(this, event.from, event.on)

            is TandemEvent.CallAction -> calls.handle(event)
            is TandemEvent.Dial -> calls.dial(event.number)
            is TandemEvent.Hotspot -> hotspot.onEvent(event)

            // The link of a screen that is looked at got poor: said once, over the picture, and nothing when it is good again.
            is TandemEvent.MediaQuality -> if (event.poor) {
                val name = graph.host.device(event.peer)?.name ?: getString(R.string.capture_mac_fallback)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(this, getString(R.string.link_poor, name), android.widget.Toast.LENGTH_LONG).show()
                }
            }
            is TandemEvent.SideListen -> nl.markmaaktmedia.tandem.live.SideListener.listen(this, event.from, event.id, event.quiet)
            is TandemEvent.OpenSettings -> FeatureReport.open(this, event.page, event.from)
            is TandemEvent.SoundRequested -> nl.markmaaktmedia.tandem.audio.ScreenSound.requested(this, event.from, event.on)
            is TandemEvent.BtSpeakerState -> nl.markmaaktmedia.tandem.live.PhoneSound.onState(this, event.from, event.on, event.error)
            is TandemEvent.MediaStarted -> if (event.role == TandemMediaRole.VIEWER && event.kind == TandemMediaKind.SCREEN) {
                nl.markmaaktmedia.tandem.audio.ScreenSound.viewStarted(this, event.peer)
            }
            is TandemEvent.MediaEnded -> when (event.role) {
                TandemMediaRole.VIEWER -> nl.markmaaktmedia.tandem.audio.ScreenSound.viewEnded(this, event.peer)
                TandemMediaRole.HOST -> nl.markmaaktmedia.tandem.audio.ScreenSound.hostEnded(this, event.peer)
            }
            is TandemEvent.MediaOffered -> if (event.kind == TandemMediaKind.SCREEN) offerDisplay(event.from, event.extend)
            is TandemEvent.CaptureRequested -> postCaptureRequest(event)
            is TandemEvent.CaptureCancelled -> getSystemService(NotificationManager::class.java).cancel(captureNotificationId(event.id.toLong()))

            is TandemEvent.Notification -> Unit
            is TandemEvent.Wifi -> nl.markmaaktmedia.tandem.wifi.WifiShare.get(this).onEvent(event)
            is TandemEvent.Sms -> sms.onEvent(event)
            is TandemEvent.Focus -> focus.follow(event)

            else -> Unit
        }
    }

    // ---- The clipboard for pictures and files ----------------------------------------------------------------------------------

    /** Offers that came in as a clipboard, with the files of each that are in so far. */
    private val clipboardOffers = mutableSetOf<ULong>()
    private val clipboardFiles = mutableMapOf<ULong, MutableList<android.net.Uri>>()

    /** Everything of the offer that is in so far goes on the clipboard together, so several files are pasted as one. */
    private fun clipboardFile(offer: ULong, location: String) {
        if (ClipboardPictures.waits(location)) {
            ClipboardPictures.put(this, location, getString(R.string.app_name))
            clipboardOffers.remove(offer)
            return
        }
        val uris = clipboardFiles.getOrPut(offer) { mutableListOf() }.apply { add(android.net.Uri.parse(location)) }
        val clip = android.content.ClipData.newUri(contentResolver, getString(R.string.app_name), uris.first())
        uris.drop(1).forEach { clip.addItem(android.content.ClipData.Item(it)) }
        runCatching { getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(clip) }
        if (clipboardOffers.size > 20) clipboardOffers.remove(clipboardOffers.first())
        if (clipboardFiles.size > 20) clipboardFiles.remove(clipboardFiles.keys.first())
    }

    // ---- Notifications ---------------------------------------------------------

    /**
     * A computer asks this phone to be its second screen. With the app open the screen opens at once, since the person just pressed
     * the button on that computer; otherwise it is a heads-up that opens it, because an app may not take over the screen from behind.
     */
    /** A computer offers a screen: one of its own for this phone ([extend]), or what is on its display, to look at. */
    private fun offerDisplay(from: String, extend: Boolean) {
        val graph = graph
        if (graph.appVisible) {
            if (extend) graph.openDisplay.value = from else graph.openMirror.value = from
            return
        }
        val name = graph.host.device(from)?.name ?: getString(R.string.capture_mac_fallback)
        val screen = Intent(this, MainActivity::class.java).setAction(if (extend) MainActivity.ACTION_OPEN_DISPLAY else MainActivity.ACTION_OPEN_MIRROR)
            .putExtra(MainActivity.EXTRA_DEVICE, from)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        // The person started it on the computer, so with permission to draw over other apps (which also lets the app open a
        // screen from the background) it opens by itself, the same as an incoming share request. Otherwise a notification
        // that has to be tapped, because Android does not let a background app open a screen.
        if (android.provider.Settings.canDrawOverlays(this) && runCatching { startActivity(screen) }.isSuccess) return
        val open = PendingIntent.getActivity(this, 0x4449, screen, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, Channels.CAPTURE)
            .setSmallIcon(R.drawable.ic_stat_tandem)
            .setContentTitle(getString(if (extend) R.string.display_offer_title else R.string.mirror_offer_title, name))
            .setContentText(getString(R.string.display_offer_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(120_000)
            .build()
        getSystemService(NotificationManager::class.java).notify(0x4449, notification)
    }

    private fun captureNotificationId(request: Long): Int = 0x4341 + (request xor (request ushr 32)).toInt().and(0xFFFF)

    /** A computer asks for a photo, a scan or a picture: a heads-up that opens the camera screen. */
    private fun postCaptureRequest(event: TandemEvent.CaptureRequested) {
        val name = graph.host.device(event.from)?.name ?: getString(R.string.capture_mac_fallback)
        val request = event.id.toLong()
        val (title, text) = when (event.kind) {
            TandemCaptureKind.PHOTO -> R.string.capture_notif_photo to R.string.capture_notif_text_photo
            TandemCaptureKind.DOCUMENT -> R.string.capture_notif_document to R.string.capture_notif_text_document
            TandemCaptureKind.PICTURE -> R.string.capture_notif_picture to R.string.capture_notif_text_picture
        }
        val open = PendingIntent.getActivity(
            this, captureNotificationId(request),
            CaptureActivity.intent(this, event.from, request, event.kind),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancel = PendingIntent.getBroadcast(
            this, captureNotificationId(request),
            Intent(this, ActionReceiver::class.java).setAction(ActionReceiver.CANCEL_CAPTURE)
                .putExtra(ActionReceiver.EXTRA_DEVICE, event.from).putExtra(ActionReceiver.EXTRA_OFFER, request),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, Channels.CAPTURE)
            .setSmallIcon(R.drawable.ic_stat_tandem)
            .setContentTitle(getString(title, name))
            .setContentText(getString(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(120_000)
            .addAction(0, getString(R.string.capture_notif_cancel), cancel)
            .build()
        getSystemService(NotificationManager::class.java).notify(captureNotificationId(request), notification)
    }

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
        val host = graph.host
        val active = host.transfers.value.filter { it.state == TransferItem.State.Active }
        val manager = getSystemService(NotificationManager::class.java)
        if (active.isEmpty()) {
            manager.cancel(PROGRESS_ID)
            return
        }
        val total = active.sumOf { it.total }
        val done = active.sumOf { it.done }
        val incoming = active.any { it.incoming }
        // The whole set, when it is known: "5 of 23" and not only the few that run at this moment.
        host.dropStaleBatches()
        val batch = host.batches.value.values.firstOrNull { it.incoming == incoming }
        val title = when {
            batch != null -> getString(R.string.transfer_batch, minOf(batch.done + 1, batch.total), batch.total)
            active.size == 1 -> active[0].name
            else -> getString(R.string.transfer_many, active.size)
        }
        manager.notify(
            PROGRESS_ID,
            NotificationCompat.Builder(this, Channels.TRANSFERS)
                .setSmallIcon(if (incoming) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_upload)
                .setContentTitle(title)
                .setContentText(getString(if (incoming) R.string.transfer_receiving else R.string.transfer_sending))
                .setProgress(
                    100,
                    if (batch != null) ((batch.done + active.sumOf { it.fraction.toDouble() } / active.size) * 100 / batch.total).toInt().coerceIn(0, 100)
                    else if (total > 0) (done * 100 / total).toInt() else 0,
                    batch == null && total <= 0,
                )
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open())
                .build(),
        )
    }

    /** What arrived from each device in the last while, so a batch of photos is one notification that counts up and not one for each. */
    private val arrived = HashMap<String, MutableList<String>>()
    private val arrivedAt = HashMap<String, Long>()

    private fun postReceived(name: String, from: String, location: String) {
        if (!Channels.canPost(this)) return
        val now = System.currentTimeMillis()
        val names = synchronized(arrived) {
            // A new batch starts after a quiet minute.
            if (now - (arrivedAt[from] ?: 0L) > 60_000L) arrived[from] = mutableListOf()
            arrivedAt[from] = now
            arrived.getOrPut(from) { mutableListOf() }.also { it.add(name) }.toList()
        }
        if (names.size > 1) {
            val manager = getSystemService(NotificationManager::class.java)
            val style = NotificationCompat.InboxStyle()
            names.takeLast(6).forEach { style.addLine(it) }
            manager.notify(
                from.hashCode(),
                NotificationCompat.Builder(this, Channels.INCOMING)
                    .setSmallIcon(R.drawable.ic_stat_tandem)
                    .setContentTitle(
                        graph.host.batches.value.values.firstOrNull { it.incoming && it.total > names.size }
                            ?.let { getString(R.string.received_of, names.size, it.total) }
                            ?: resources.getQuantityString(R.plurals.received_many, names.size, names.size),
                    )
                    .setContentText(getString(R.string.received_from, from))
                    .setStyle(style)
                    .setContentIntent(open())
                    .setOnlyAlertOnce(true)
                    .setAutoCancel(true)
                    .setDeleteIntent(null)
                    .build(),
            )
            // The ones that came before in this batch are not shown on their own any more.
            return
        }
        val uri = Uri.parse(location)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, contentResolver.getType(uri) ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        getSystemService(NotificationManager::class.java).notify(
            from.hashCode(),
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
