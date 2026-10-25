package nl.markmaaktmedia.tandem.engine

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.first
import nl.markmaaktmedia.tandem.MainActivity
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.graph
import uniffi.tandem_core.TandemFeature

/**
 * The tour of what Tandem can do on this phone, built from the list the other devices get too ([FeatureReport]), so the two never say
 * something different. Each thing that is not ready can be set up now, left for later, or put away as not for this person. What waits for
 * later comes back once, as one notification a few days on, and only while it is still not ready. Nothing stays on a page as a warning.
 */
object SetupTour {
    /** A new version shows the tour once more after an update, also to whoever finished the first run long ago. */
    const val VERSION = 1

    private const val REMIND_AFTER_MS = 3L * 24 * 60 * 60 * 1000
    private const val NOTIFICATION = 0x544F

    /** The things of the tour: what the other devices are told this phone does, without the ones that never need anything (saving a Wi-Fi). */
    fun items(features: List<TandemFeature>): List<TandemFeature> = features.filter { it.id !in ALWAYS }

    private val ALWAYS = setOf("wifi.join")

    suspend fun later(context: Context, id: String) {
        val prefs = context.graph.prefs
        prefs.setTourLater(prefs.tourLater.first() + id)
        prefs.setTourSkip(prefs.tourSkip.first() - id)
        if (prefs.tourRemindAt.first() == 0L) prefs.setTourRemindAt(System.currentTimeMillis() + REMIND_AFTER_MS)
    }

    suspend fun skip(context: Context, id: String) {
        val prefs = context.graph.prefs
        prefs.setTourSkip(prefs.tourSkip.first() + id)
        prefs.setTourLater(prefs.tourLater.first() - id)
    }

    /** Back on the list: neither later nor not for me. */
    suspend fun undo(context: Context, id: String) {
        val prefs = context.graph.prefs
        prefs.setTourSkip(prefs.tourSkip.first() - id)
        prefs.setTourLater(prefs.tourLater.first() - id)
    }

    /** Looked at with the list of [FeatureReport]: when the day has come and something left for later is still not ready, one notification. */
    suspend fun remindIfDue(context: Context, features: List<TandemFeature>) {
        val prefs = context.graph.prefs
        val at = prefs.tourRemindAt.first()
        if (at == 0L || System.currentTimeMillis() < at) return
        prefs.setTourRemindAt(0L)
        val later = prefs.tourLater.first()
        val waiting = features.filter { !it.ready && it.id in later }
        // What got ready in the meantime needs no reminder.
        prefs.setTourLater(waiting.map { it.id }.toSet())
        if (waiting.isEmpty() || !Permissions.notifications(context)) return
        val open = Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_TOUR)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val tap = PendingIntent.getActivity(context, NOTIFICATION, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = if (waiting.size == 1) waiting[0].name else context.getString(R.string.tour_remind_text, waiting.size, waiting.joinToString(", ") { it.name })
        val notification = NotificationCompat.Builder(context, Channels.UPDATE)
            .setSmallIcon(R.drawable.ic_stat_tandem)
            .setContentTitle(context.getString(R.string.tour_remind_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification)
    }
}
