package nl.markmaaktmedia.tandem.mirror

import android.app.Notification
import android.content.pm.ApplicationInfo
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.graph
import uniffi.tandem_core.TandemButton
import uniffi.tandem_core.TandemEvent
import uniffi.tandem_core.TandemNotification

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
        val graph = applicationContext.graph
        eventJob = graph.scope.launch {
            graph.host.events.collect { event ->
                if (event is TandemEvent.NotificationAction) handleAction(event)
            }
        }
    }

    override fun onListenerDisconnected() {
        eventJob?.cancel()
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val graph = applicationContext.graph
        if (sbn.packageName == packageName) return
        graph.scope.launch {
            if (!graph.prefs.mirrorNotifications.first()) return@launch
            if (!shouldMirror(sbn, graph)) return@launch
            val targets = graph.host.devices.value.filter { it.online && it.notificationsEnabled }.map { it.id }
            if (targets.isEmpty()) return@launch
            active[sbn.key] = sbn
            val notification = convert(sbn) ?: return@launch
            runCatching { graph.host.engine?.sendNotification(targets, notification) }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val graph = applicationContext.graph
        if (active.remove(sbn.key) == null) return
        graph.scope.launch {
            val targets = graph.host.devices.value.filter { it.online }.map { it.id }
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
            otp = OtpDetector.find("$title $text"),
            progressDone = null,
            progressTotal = null,
        )
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
}

/** Finds a verification code in a text message, so it can go straight to the clipboard. */
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
