package nl.markmaaktmedia.tandem.watch

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import android.util.Log
import nl.markmaaktmedia.tandem.watch.protocol.NotifButton
import nl.markmaaktmedia.tandem.watch.protocol.NotificationMsg
import nl.markmaaktmedia.tandem.watch.protocol.NotificationRules
import uniffi.tandem_core.tandemFindCode

/** Turns the notifications Tandem's listener sees into what the watch shows, and carries the watch's answers back. */
class WatchNotifications(private val listener: NotificationListenerService) {
    private val packageManager = listener.packageManager

    fun snapshot(): List<NotificationMsg> {
        val ranking = listener.currentRanking
        // The system throws when the listener is being unbound; treat that as "nothing to show".
        return runCatching { listener.activeNotifications.orEmpty().mapNotNull { toMessage(it, ranking) } }.getOrDefault(emptyList())
    }

    fun ranking(): RankingMap? = runCatching { listener.currentRanking }.getOrNull()

    fun perform(key: String, button: String, reply: String?, dismiss: Boolean) {
        if (dismiss) {
            runCatching { listener.cancelNotification(key) }
            return
        }
        val sbn = runCatching { listener.getActiveNotifications(arrayOf(key)) }.getOrNull()?.firstOrNull() ?: return
        try {
            if (button.isEmpty()) {
                sbn.notification.contentIntent?.send()
                return
            }
            val action = button.toIntOrNull()?.let { sbn.notification.actions?.getOrNull(it) } ?: return
            val inputs = action.remoteInputs?.filter { it.allowFreeFormInput }.orEmpty()
            if (reply != null && inputs.isNotEmpty()) {
                val fill = Intent()
                val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, reply) } }
                RemoteInput.addResultsToIntent(action.remoteInputs, fill, results)
                action.actionIntent.send(listener, 0, fill)
            } else {
                action.actionIntent.send()
            }
        } catch (e: PendingIntent.CanceledException) {
            Log.i(TAG, "Action expired: ${e.message}")
        }
    }

    fun toMessage(sbn: StatusBarNotification, ranking: RankingMap?): NotificationMsg? {
        val n = sbn.notification
        if (sbn.packageName == listener.packageName) return null
        // Summaries repeat their children; ongoing entries are services and timers, not things to read.
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0 || n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return null
        if (n.category == Notification.CATEGORY_CALL && sbn.packageName == defaultDialer()) return null

        val rank = Ranking()
        val importance = if (ranking?.getRanking(sbn.key, rank) == true) rank.importance else NotificationManager.IMPORTANCE_DEFAULT
        if (importance == NotificationManager.IMPORTANCE_NONE || importance == NotificationManager.IMPORTANCE_MIN) return null

        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = lastMessage(extras)
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return null

        // Codes stay off the watch: it can be read on someone else's wrist or lying on a table.
        val otp = NotificationRules.findOtp(title, text) ?: tandemFindCode("$title $text")
        val shownTitle = if (otp != null) NotificationRules.maskOtp(title, otp) else title
        val shownText = if (otp != null) NotificationRules.maskOtp(text, otp) else text

        val progressMax = extras.getInt(Notification.EXTRA_PROGRESS_MAX)
        val indeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)
        return NotificationMsg(
            key = sbn.key,
            appId = sbn.packageName,
            appName = NotificationRules.clip(appName(sbn.packageName), NotificationRules.MAX_TITLE),
            title = NotificationRules.clip(shownTitle, NotificationRules.MAX_TITLE),
            text = NotificationRules.clip(shownText, NotificationRules.MAX_TEXT),
            subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
                ?.let { NotificationRules.clip(it, NotificationRules.MAX_TITLE) },
            ts = sbn.postTime,
            ongoing = false,
            silent = importance < NotificationManager.IMPORTANCE_DEFAULT,
            buttons = n.actions.orEmpty().withIndex()
                .filter { !it.value.title.isNullOrBlank() }
                .take(NotificationRules.MAX_BUTTONS)
                .map { (index, action) ->
                    NotifButton(
                        id = index.toString(),
                        title = NotificationRules.clip(action.title.toString(), NotificationRules.MAX_TITLE),
                        isReply = action.remoteInputs?.any { it.allowFreeFormInput } == true,
                    )
                },
            otp = null,
            progress = if (progressMax > 0 && !indeterminate) {
                (100 * extras.getInt(Notification.EXTRA_PROGRESS) / progressMax).coerceIn(0, 100)
            } else {
                null
            },
        )
    }

    /** Chat apps keep the newest text in MessagingStyle; EXTRA_TEXT often only says "3 new messages". */
    private fun lastMessage(extras: Bundle): String? {
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable::class.java) ?: return null
        val last = messages.lastOrNull() as? Bundle ?: return null
        val text = last.getCharSequence("text")?.toString() ?: return null
        val sender = last.getCharSequence("sender")?.toString()
        return if (sender.isNullOrBlank()) text else "$sender: $text"
    }

    private fun appName(packageId: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageId, 0)).toString()
    }.getOrDefault(packageId)

    private fun defaultDialer(): String? = listener.getSystemService(TelecomManager::class.java)?.defaultDialerPackage

    private companion object {
        const val TAG = "TandemWatch"
    }
}
