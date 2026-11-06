package nl.markmaaktmedia.tandem.watch.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Handler
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import nl.markmaaktmedia.tandem.watch.R

/** Makes this watch findable: sound as an alarm, so silent mode does not hide it, and vibration in case it has no volume. */
class FindAlerts(
    private val context: Context,
    private val handler: Handler,
    private val onChange: (Boolean) -> Unit,
) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
    private var ringtone: Ringtone? = null
    private var active = false
    private val giveUp = Runnable { stop() }

    fun start() {
        if (active) return
        active = true
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_find), NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(false)
                setSound(null, null)
            },
        )
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.sym_watch)
                .setContentTitle(context.getString(R.string.watch_ringing))
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setOngoing(true)
                .setLocalOnly(true)
                .addAction(R.drawable.sym_check, context.getString(R.string.found_it), ActionReceiver.foundIntent(context))
                .build(),
        )
        ringtone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)?.let { RingtoneManager.getRingtone(context, it) }?.apply {
            audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            isLooping = true
            play()
        }
        vibrator.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 500, 300), 0),
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM),
        )
        handler.postDelayed(giveUp, GIVE_UP_MS)
        onChange(true)
    }

    fun stop() {
        if (!active) return
        active = false
        handler.removeCallbacks(giveUp)
        ringtone?.stop()
        ringtone = null
        vibrator.cancel()
        manager.cancel(NOTIFICATION_ID)
        onChange(false)
    }

    private companion object {
        const val CHANNEL = "find"
        const val NOTIFICATION_ID = 400
        const val GIVE_UP_MS = 60_000L
    }
}
