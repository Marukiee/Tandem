package nl.markmaaktmedia.tandem.watch.track

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import nl.markmaaktmedia.tandem.watch.R
import nl.markmaaktmedia.tandem.watch.link.WatchLink
import nl.markmaaktmedia.tandem.watch.protocol.Message
import nl.markmaaktmedia.tandem.watch.sleep.NapGuess
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

/** A wrist can not tell lying still from sleeping, the person can. Without an answer it never happened, so nothing made up stays behind. */
object NapQuestion {
    private const val CHANNEL = "naps"
    private const val NOTIFICATION_ID = 310
    private const val ASK_FOR_MS = 4 * 60 * 60 * 1000L
    private const val REQUEST_YES = 311
    private const val REQUEST_NO = 312
    internal const val ACTION_YES = "nl.markmaaktmedia.tandem.watch.NAP_YES"
    internal const val ACTION_NO = "nl.markmaaktmedia.tandem.watch.NAP_NO"

    fun ask(context: Context, guess: NapGuess) {
        val app = context.applicationContext
        TrackingStore(app).pendingNap = guess
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, app.getString(R.string.channel_naps), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(guess.startMs))
        val notification = Notification.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.sym_bedtime)
            .setContentTitle(app.getString(R.string.nap_question))
            .setContentText(app.getString(R.string.nap_question_when, time, guess.sleepMinutes))
            .addAction(action(app, R.drawable.sym_check, R.string.nap_yes, ACTION_YES, REQUEST_YES))
            .addAction(action(app, R.drawable.sym_close, R.string.nap_no, ACTION_NO, REQUEST_NO))
            .setTimeoutAfter(ASK_FOR_MS)
            .setLocalOnly(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun action(app: Context, icon: Int, label: Int, action: String, request: Int): Notification.Action {
        val intent = Intent(app, NapAnswerReceiver::class.java).setAction(action)
        val pending = PendingIntent.getBroadcast(app, request, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Action.Builder(Icon.createWithResource(app, icon), app.getString(label), pending).build()
    }

    fun answer(context: Context, slept: Boolean) {
        val app = context.applicationContext
        app.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        val store = TrackingStore(app)
        val guess = store.pendingNap ?: return
        store.pendingNap = null
        if (!slept) return
        Tracking.publishNap(store.saveNap(guess.startMs, guess.sleepMinutes))
        WatchLink.sendHealth(app, guess.toRecord())
    }

    private fun NapGuess.toRecord() = Message.HealthRecord(
        id = "nap-$startMs",
        workout = false,
        startMs = startMs,
        endMs = endMs,
        sleepMinutes = sleepMinutes,
        awakeMinutes = awakeMinutes,
        awakenings = 0,
        efficiencyPercent = (100.0 * sleepMinutes / (sleepMinutes + awakeMinutes)).roundToInt(),
        lightMinutes = null,
        deepMinutes = null,
        remMinutes = null,
        stages = emptyList(),
        heartRateMean = meanHeartRate,
        heartRateLow = lowestHeartRate,
        heartRateHigh = null,
        steps = 0,
    )
}

class NapAnswerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WatchLink.start(context)
        NapQuestion.answer(context, intent.action == NapQuestion.ACTION_YES)
    }
}
