package nl.markmaaktmedia.tandem.engine

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.graph

/**
 * Makes the phone ring loudly, even when it is on silent, until someone stops it.
 * Used when you press "Find phone" on another device. The sound is the one picked in Settings ([FindSound]), and the devices that
 * asked hear when it starts and stops.
 */
object FindPhone {
    private var player: MediaPlayer? = null
    private var originalVolume: Int? = null
    private var appContext: Context? = null
    private const val NOTIFICATION_ID = 77

    private val main = Handler(Looper.getMainLooper())
    private var ringing = false

    private val alarmAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    // One short buzz at a time instead of a repeating waveform: a repeating vibration
    // keeps going on some devices when cancel() comes from another component, this one
    // simply stops being renewed and is over within a second.
    private val buzz = object : Runnable {
        override fun run() {
            val context = appContext ?: return
            if (!ringing) return
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
                .vibrate(VibrationEffect.createOneShot(600, VibrationEffect.DEFAULT_AMPLITUDE), alarmAttributes)
            main.postDelayed(this, 1000)
        }
    }

    fun start(context: Context) {
        // Another device that asks while it already rings is told so too.
        if (ringing) return report(context, true)
        appContext = context.applicationContext
        val audio = context.getSystemService(AudioManager::class.java)
        originalVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)

        // A picked sound that is gone (deleted, or not readable any more) falls back to the alarm, so it always rings.
        player = play(context, FindSound.picked(context)) ?: play(context, FindSound.fallback())
        ringing = true
        report(context, true)
        main.post(buzz)

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
    }

    private fun play(context: Context, uri: Uri?): MediaPlayer? {
        uri ?: return null
        val player = MediaPlayer()
        return try {
            player.setAudioAttributes(alarmAttributes)
            player.setDataSource(context, uri)
            player.isLooping = true
            player.prepare()
            player.start()
            player
        } catch (e: Exception) {
            player.release()
            null
        }
    }

    private fun report(context: Context, on: Boolean) {
        val graph = context.graph
        graph.scope.launch { runCatching { graph.host.engine?.ringing(on) } }
    }

    fun stop() {
        if (ringing) appContext?.let { report(it, false) }
        ringing = false
        main.removeCallbacks(buzz)
        player?.let { p -> runCatching { p.stop() }; runCatching { p.release() } }
        player = null
        appContext?.let { context ->
            originalVolume?.let { context.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, it, 0) }
            context.getSystemService(Vibrator::class.java).cancel()
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }
        originalVolume = null
    }
}
