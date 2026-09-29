package nl.markmaaktmedia.tandem.engine

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import nl.markmaaktmedia.tandem.R

/**
 * Makes the phone ring loudly, even when it is on silent, until someone stops it.
 * Used when you press "Find phone" on another device.
 */
object FindPhone {
    private var player: MediaPlayer? = null
    private var originalVolume: Int? = null
    private const val NOTIFICATION_ID = 77

    fun start(context: Context) {
        if (player != null) return
        val audio = context.getSystemService(AudioManager::class.java)
        originalVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)

        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            )
            setDataSource(context, uri)
            isLooping = true
            prepare()
            start()
        }
        context.getSystemService(Vibrator::class.java)
            .vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0))

        val stop = PendingIntent.getBroadcast(
            context, 0, Intent(context, ActionReceiver::class.java).setAction(ActionReceiver.STOP_RING),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        context.getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, Channels.INCOMING)
                .setSmallIcon(R.drawable.ic_stat_tandem)
                .setContentTitle(context.getString(R.string.find_title))
                .setContentText(context.getString(R.string.find_text))
                .setOngoing(true)
                .setFullScreenIntent(stop, false)
                .addAction(0, context.getString(R.string.action_stop), stop)
                .build(),
        )
        appContext = context.applicationContext
    }

    private var appContext: Context? = null

    fun stop() {
        player?.runCatching { stop(); release() }
        player = null
        appContext?.let { context ->
            originalVolume?.let { context.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, it, 0) }
            context.getSystemService(Vibrator::class.java).cancel()
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }
        originalVolume = null
    }
}
