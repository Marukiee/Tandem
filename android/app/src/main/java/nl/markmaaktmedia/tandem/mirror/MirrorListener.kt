package nl.markmaaktmedia.tandem.mirror

import android.app.Notification
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import nl.markmaaktmedia.tandem.graph
import uniffi.tandem_core.TandemButton
import uniffi.tandem_core.TandemEvent
import uniffi.tandem_core.TandemNotification
import uniffi.tandem_core.TandemPlatform
import uniffi.tandem_core.tandemFindCode

/**
 * Sends this phone's notifications to your other devices, and carries their replies
 * and dismissals back.
 *
 * What is sent is decided here on the phone: only apps you chose (or all of them),
 * never ongoing media or system chatter, and never a notification the app marks as
 * private on the lock screen unless you opted in. Nothing leaves the circle.
 */
class MirrorListener : NotificationListenerService() {

    private val active = HashMap<String, StatusBarNotification>()
    private var eventJob: kotlinx.coroutines.Job? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        _connected.value = true
        val graph = applicationContext.graph
        eventJob?.cancel()
        eventJob = graph.scope.launch {
            graph.host.events.collect { event ->
                if (event is TandemEvent.NotificationAction) handleAction(event)
            }
        }
    }

    override fun onListenerDisconnected() {
        _connected.value = false
        eventJob?.cancel()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        _connected.value = false
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val graph = applicationContext.graph
        if (sbn.packageName == packageName) return
        graph.scope.launch {
            if (!graph.prefs.mirrorNotifications.first()) return@launch
            if (!shouldMirror(sbn, graph)) return@launch
            // The devices that show notifications, which is every one but another phone. Not decided by the per-device
            // switch: on this phone that switch is "show notifications FROM that device", and a Mac sends none, so a
            // person who turned it off for the Mac silently stopped everything going the other way. The Mac decides
            // with its own switch whether to show what arrives. Offline devices are included: with no connection the
            // core sends over Bluetooth if a link is up.
            val targets = graph.host.devices.value.filter { it.platform != TandemPlatform.ANDROID }.map { it.id }
            if (targets.isEmpty()) return@launch
            active[sbn.key] = sbn
            val notification = convert(sbn) ?: return@launch
            sendIcon(graph, sbn, targets)
            // The core says who it reached, and fails when it reached nobody, so a non-empty answer is a real send.
            val reached = runCatching { graph.host.engine?.sendNotification(targets, notification) }.getOrNull()
            if (!reached.isNullOrEmpty()) _lastSent.value = System.currentTimeMillis()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val graph = applicationContext.graph
        if (active.remove(sbn.key) == null) return
        graph.scope.launch {
            val targets = graph.host.devices.value.map { it.id }
            if (targets.isNotEmpty()) runCatching { graph.host.engine?.removeNotification(targets, sbn.key) }
        }
    }

    private suspend fun shouldMirror(sbn: StatusBarNotification, graph: nl.markmaaktmedia.tandem.Graph): Boolean {
        val n = sbn.notification
        // Group summaries just repeat their children.
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        // Ongoing things (music, navigation, downloads) are not worth a banner elsewhere.
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0 && n.category != Notification.CATEGORY_CALL) return false
        if (n.category == Notification.CATEGORY_TRANSPORT || n.category == Notification.CATEGORY_PROGRESS) return false
        return graph.prefs.mirrors(sbn.packageName)
    }

    private fun convert(sbn: StatusBarNotification): TandemNotification? {
        val n = sbn.notification
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return null

        // The app's own label, taken from the notification itself so no package
        // visibility permission is needed.
        val info: ApplicationInfo? = extras.getParcelable("android.appInfo", ApplicationInfo::class.java)
        val appName = info?.loadLabel(packageManager)?.toString() ?: sbn.packageName

        val buttons = n.actions.orEmpty().mapIndexedNotNull { index, action ->
            val label = action.title?.toString() ?: return@mapIndexedNotNull null
            TandemButton(id = index.toString(), title = label, isReply = action.remoteInputs?.isNotEmpty() == true)
        }.take(4)

        return TandemNotification(
            key = sbn.key,
            appId = sbn.packageName,
            appName = appName,
            title = title,
            text = text,
            subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            ts = sbn.postTime.toULong(),
            ongoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0,
            silent = n.priority < Notification.PRIORITY_DEFAULT && Build.VERSION.SDK_INT < 26,
            buttons = buttons,
            // The core's detector first, which knows about banks, dashed codes and what is not a code. The older
            // patterns below stay as a net under it, so nothing that was found before is lost.
            otp = "$title $text".let { tandemFindCode(it) ?: OtpDetector.find(it) },
            progressDone = null,
            progressTotal = null,
        )
    }

    /**
     * The icon of the app goes to every device once, ahead of its first notification, so the other side can show which
     * app a notification came from. A device that could not be reached gets it again with the next one.
     */
    private suspend fun sendIcon(graph: nl.markmaaktmedia.tandem.Graph, sbn: StatusBarNotification, targets: List<String>) {
        val fresh = targets.filter { iconsSent.add("$it|${sbn.packageName}") }
        if (fresh.isEmpty()) return
        val png = appIconPng(sbn)
        val reached = if (png == null) emptyList() else runCatching { graph.host.engine?.sendAppIcon(fresh, sbn.packageName, png) }.getOrNull().orEmpty()
        fresh.filter { it !in reached }.forEach { iconsSent.remove("$it|${sbn.packageName}") }
    }

    /** The launcher icon of the app, as a rounded square: the system mask is not applied when an adaptive icon is drawn by hand. */
    private fun appIconPng(sbn: StatusBarNotification): ByteArray? {
        val info: ApplicationInfo? = sbn.notification.extras.getParcelable("android.appInfo", ApplicationInfo::class.java)
        val drawable = info?.loadIcon(packageManager) ?: runCatching { packageManager.getApplicationIcon(sbn.packageName) }.getOrNull() ?: return null
        val size = 128
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val corner = size * 0.23f
        canvas.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), corner, corner, Path.Direction.CW) })
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun handleAction(event: TandemEvent.NotificationAction) {
        val sbn = active[event.key] ?: activeNotifications.firstOrNull { it.key == event.key } ?: return
        if (event.dismiss) {
            cancelNotification(event.key)
            return
        }
        val index = event.button.toIntOrNull()
        val action = index?.let { sbn.notification.actions?.getOrNull(it) }
        if (action == null) {
            // A plain click: open the app on the phone.
            runCatching { sbn.notification.contentIntent?.send() }
            return
        }
        val reply = event.reply
        val remoteInput = action.remoteInputs?.firstOrNull()
        if (reply != null && remoteInput != null) {
            val intent = android.content.Intent()
            val results = android.os.Bundle().apply { putCharSequence(remoteInput.resultKey, reply) }
            android.app.RemoteInput.addResultsToIntent(action.remoteInputs, intent, results)
            runCatching { action.actionIntent.send(this, 0, intent) }
        } else {
            runCatching { action.actionIntent.send() }
        }
    }

    companion object {
        /** Which device already has the icon of which app, as `device|package`. */
        private val iconsSent: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        private val _connected = MutableStateFlow(false)

        /**
         * True while Android has this service bound. Notification access can be allowed in the settings and still
         * not be running, most often right after an update, and then nothing is mirrored.
         */
        val connected: StateFlow<Boolean> = _connected.asStateFlow()

        private val _lastSent = MutableStateFlow<Long?>(null)

        /** When a notification last went out to another device, or null since the app started. */
        val lastSent: StateFlow<Long?> = _lastSent.asStateFlow()
    }
}

/** The simple patterns that were here first. The core's `tandemFindCode` is asked before this one. */
object OtpDetector {
    private val patterns = listOf(
        Regex("""(?i)(?:code|kode|otp|pin|verification|verificatie|wachtwoord|password)\D{0,25}(\d{4,8})"""),
        Regex("""(\d{4,8})\D{0,20}(?i:is (?:your|je|uw)|is de|is jouw)"""),
        Regex("""(?<!\d)(\d{3}[- ]\d{3})(?!\d)"""),
    )

    fun find(text: String): String? {
        for (pattern in patterns) {
            val match = pattern.find(text) ?: continue
            return match.groupValues[1].replace(Regex("[- ]"), "")
        }
        return null
    }
}
