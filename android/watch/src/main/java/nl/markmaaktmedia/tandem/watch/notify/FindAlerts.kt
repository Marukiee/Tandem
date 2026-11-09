package nl.markmaaktmedia.tandem.watch.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Handler
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors
import nl.markmaaktmedia.tandem.watch.R
import nl.markmaaktmedia.tandem.watch.sound.FindTone

/**
 * Makes this watch findable: chirps made to be found by ear, as an alarm so silent mode does not hide them, at the full alarm volume
 * for as long as it rings, and vibration in case the speaker is covered.
 */
class FindAlerts(
    private val context: Context,
    private val handler: Handler,
    private val onChange: (Boolean) -> Unit,
) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
    private var track: AudioTrack? = null
    private var ringtone: Ringtone? = null
    private var volumeBefore: Int? = null
    private var gain = START_GAIN
    private var active = false
    /** Which ringing a track that is being opened belongs to, so one that opens after a stop or a new start is let go. */
    private var ringing = 0
    private val giveUp = Runnable { stop() }

    init {
        // Written once, so a ring does not wait for it.
        audioThread.execute { tone }
    }

    /** Grows to full strength in a few seconds, so a watch still on a wrist does not shriek at once. */
    private val louder = object : Runnable {
        override fun run() {
            gain = (gain + GAIN_STEP).coerceAtMost(1f)
            track?.setVolume(gain)
            if (gain < 1f) handler.postDelayed(this, GAIN_EVERY_MS)
        }
    }

    fun start() {
        if (active) return
        active = true
        val id = ++ringing
        // The buzz and the word to the phone first. Opening the speaker can take a while on a watch, and it happened here on the thread
        // of the link before both: the watch began late and the phone heard late that it rang, while stopping was quick. Now the
        // speaker opens on a thread of its own and joins in when it is ready.
        vibrator.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 500, 300), 0),
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM),
        )
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
        // The alarm volume of a watch is often low, which is what made it hard to hear: up for the ringing, back to what it was after.
        volumeBefore = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0) }
        gain = START_GAIN
        handler.postDelayed(giveUp, GIVE_UP_MS)
        onChange(true)
        audioThread.execute {
            val opened = chirps()
            handler.post {
                if (!active || id != ringing) {
                    opened?.release()
                    return@post
                }
                if (opened != null) {
                    track = opened
                    opened.play()
                    handler.postDelayed(louder, GAIN_EVERY_MS)
                } else {
                    ringtone = alarmTone()
                }
            }
        }
    }

    fun stop() {
        if (!active) return
        active = false
        handler.removeCallbacks(giveUp)
        handler.removeCallbacks(louder)
        track?.run {
            runCatching { stop() }
            release()
        }
        track = null
        ringtone?.stop()
        ringtone = null
        volumeBefore?.let { runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, it, 0) } }
        volumeBefore = null
        vibrator.cancel()
        manager.cancel(NOTIFICATION_ID)
        onChange(false)
    }

    /**
     * The chirps, looped from memory: written once, then played again and again until found. Opened ready to play but not playing, off the
     * thread of the link. Null when the audio will not open.
     */
    private fun chirps(): AudioTrack? = runCatching {
        val pcm = tone
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(FindTone.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
            .apply {
                if (state != AudioTrack.STATE_INITIALIZED) {
                    release()
                    error("audio track did not open")
                }
                write(pcm, 0, pcm.size)
                setLoopPoints(0, pcm.size, -1)
                setVolume(START_GAIN)
            }
    }.getOrNull()

    /** The alarm sound of the watch itself, for a watch whose audio will not take the chirps. */
    private fun alarmTone(): Ringtone? =
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)?.let { RingtoneManager.getRingtone(context, it) }?.apply {
            audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            isLooping = true
            play()
        }

    private companion object {
        /** One for every ring of this app: a track opens here while the link goes on. */
        val audioThread = Executors.newSingleThreadExecutor { Thread(it, "find-audio").apply { isDaemon = true } }
        val tone: ShortArray by lazy { FindTone.cycle() }
        const val CHANNEL = "find"
        const val NOTIFICATION_ID = 400
        const val GIVE_UP_MS = 60_000L
        const val START_GAIN = 0.5f
        const val GAIN_STEP = 0.1f
        const val GAIN_EVERY_MS = 800L
    }
}
