package nl.markmaaktmedia.tandem.watch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.graph

/**
 * The morning after music played on through half an hour of sleep: once, and at most once a week, whether it should stop by itself from
 * now on. Asked and not switched on behind someone's back, because music that stops by itself is not something to get by surprise.
 */
object SleepMusicQuestion {
    private const val CHANNEL = "sleep_music"
    private const val NOTIFICATION_ID = 7310
    const val ACTION = "nl.markmaaktmedia.tandem.SLEEP_MUSIC"
    const val EXTRA_ANSWER = "answer"
    const val YES = "yes"
    const val NOT_NOW = "not_now"
    const val NEVER = "never"

    fun ask(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.sleep_music_channel), NotificationManager.IMPORTANCE_DEFAULT))
        fun answer(code: Int, value: String, label: Int) = Notification.Action.Builder(
            null,
            context.getString(label),
            PendingIntent.getBroadcast(
                context, code,
                Intent(context, SleepMusicReceiver::class.java).setAction(ACTION).putExtra(EXTRA_ANSWER, value),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        ).build()
        manager.notify(
            NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_tandem)
                .setContentTitle(context.getString(R.string.sleep_music_title))
                .setContentText(context.getString(R.string.sleep_music_text))
                .setStyle(Notification.BigTextStyle().bigText(context.getString(R.string.sleep_music_text)))
                .addAction(answer(1, YES, R.string.sleep_music_yes))
                .addAction(answer(2, NOT_NOW, R.string.sleep_music_not_now))
                .addAction(answer(3, NEVER, R.string.sleep_music_never))
                .setAutoCancel(true)
                .build(),
        )
    }

    fun dismiss(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
}

/** An answer to [SleepMusicQuestion]. Not now only waits: the week before it asks again already counts from when it asked. */
class SleepMusicReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.graph.prefs
        val done = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            when (intent.getStringExtra(SleepMusicQuestion.EXTRA_ANSWER)) {
                SleepMusicQuestion.YES -> prefs.setSleepPauseMusic(true)
                SleepMusicQuestion.NEVER -> prefs.setSleepMusicNeverAsk(true)
            }
            SleepMusicQuestion.dismiss(context)
            done.finish()
        }
    }
}
