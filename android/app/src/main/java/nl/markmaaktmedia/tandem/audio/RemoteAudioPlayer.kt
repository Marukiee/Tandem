package nl.markmaaktmedia.tandem.audio

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.data.TandemPrefs
import nl.markmaaktmedia.tandem.engine.ActionReceiver
import nl.markmaaktmedia.tandem.engine.Channels
import nl.markmaaktmedia.tandem.engine.EngineHost
import nl.markmaaktmedia.tandem.live.SoundShareService
import uniffi.tandem_core.TandemAudioSink
import uniffi.tandem_core.TandemEvent

/**
 * Plays the sound of other devices through this phone, so the phone can be chosen as their speaker.
 *
 * A device sends 16 bit samples as datagrams. They go through a [JitterBuffer] into an [AudioTrack] in
 * low latency mode, so the volume buttons of the phone are the volume. Each device that plays has a
 * track of its own and the system mixes them. A notification says who is playing and has a Stop button.
 */
class RemoteAudioPlayer(
    private val context: Context,
    private val prefs: TandemPrefs,
    private val host: EngineHost,
    private val scope: CoroutineScope,
) : TandemAudioSink {

    private class Session(val device: String, val name: String, val stream: Int, val track: AudioTrack, val buffer: JitterBuffer, val prebufferMs: Long) {
        @Volatile var running = true
        var thread: Thread? = null
    }

    private val lock = Any()
    /** By the device that sends. Guarded by [lock], as is [focus]. */
    private val sessions = HashMap<String, Session>()
    private var focus: AudioFocusRequest? = null
    private val audio = context.getSystemService(AudioManager::class.java)

    // With the screen off the phone sleeps as soon as it may, and its Wi-Fi saves power by holding packets back.
    // Both are kept awake while sound plays, which is what a music player does, and let go again when it stops.
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /** Whether the sound of [device] plays here now. */
    fun isPlayingFrom(device: String) = synchronized(lock) { device in sessions }

    // ---- From the engine ------------------------------------------------------

    /** Called for every packet, on the connection's own task: only queues. */
    override fun onAudio(from: String, stream: UByte, seq: UInt, pcm: ByteArray) {
        val s = synchronized(lock) { sessions[from] } ?: return
        s.buffer.push(stream.toInt(), seq.toLong(), pcm)
    }

    fun onEvent(event: TandemEvent) {
        when (event) {
            // The sound of a screen this phone looks at and asked for plays also when Speaker for your other devices is off.
            is TandemEvent.AudioStart -> scope.launch {
                start(event.from, event.stream.toInt(), event.sampleRate.toInt(), event.channels.toInt(), force = ScreenSound.asked(event.from))
            }
            // From a device that plays here, its sound ends. From the one this phone sends its sound to, it will not, or no longer, play it.
            is TandemEvent.AudioStop -> {
                if (synchronized(lock) { sessions[event.from] }?.stream == event.stream.toInt()) end(event.from, tell = false)
                else if (event.stream.toInt() == SoundShareService.STREAM) SoundShareService.refused(context, event.from)
            }
            // The device went away: nothing more will come, so do not sit in silence.
            is TandemEvent.Disconnected -> end(event.id, tell = false)
            else -> Unit
        }
    }

    // ---- Playing --------------------------------------------------------------

    private suspend fun start(device: String, stream: Int, rate: Int, channels: Int, name: String? = null, force: Boolean = false) {
        // The person has not allowed it: say so, so the device does not keep sending into the void. And a device never plays here
        // while this phone sends its sound there, so a stop from it always says which of the two it ends.
        if ((!force && !prefs.audioOutput.first()) || SoundShareService.active.value == device) return refuse(device, stream)
        // A new start from the same device replaces its old one.
        end(device, tell = false)

        val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val frameBytes = channels * 2
        val bytesPerMs = rate * frameBytes / 1000
        val delay = AudioDelay.fromIndex(prefs.audioDelay.first())
        val minimum = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) return refuse(device, stream)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(mask).build())
                .setBufferSizeInBytes(maxOf(minimum, bytesPerMs * delay.trackMs))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "could not open the speaker", e)
            return refuse(device, stream)
        }

        val buffer = JitterBuffer(frameBytes, bytesPerMs, maxMs = delay.maxMs, keepMs = delay.keepMs)
        buffer.reset(stream)
        val s = Session(device, name ?: host.device(device)?.name ?: device, stream, track, buffer, delay.prebufferMs)
        synchronized(lock) {
            sessions[device] = s
            if (sessions.size == 1) {
                holdFocus()
                holdAwake()
            }
        }
        s.thread = Thread({ pump(s, bytesPerMs) }, "tandem-audio").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        showNotification()
    }

    /**
     * One focus for all the devices that play: a request of its own for each would take the focus from the one before, which
     * would then stop. Called with [lock] held.
     */
    private fun holdFocus() {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            // Something else taking the speaker (a call, other music) ends this, rather than fighting it.
            .setOnAudioFocusChangeListener { change -> if (change == AudioManager.AUDIOFOCUS_LOSS) stop(tell = true) }
            .build()
        focus = request
        // Not getting the focus is no reason not to play: the device asked for it.
        runCatching { audio.requestAudioFocus(request) }
    }

    /**
     * Plays a tone through the same path the sound of another device takes: opening the speaker, the buffer, the
     * notification. For the developer options, to hear that this phone can be a speaker at all.
     */
    fun playTestTone(seconds: Int = 20) {
        scope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val rate = 48_000
            start(TEST_DEVICE, TEST_STREAM, rate, 2, name = context.getString(R.string.audio_test_name), force = true)
            val frames = 288
            var phase = 0.0
            val step = 2 * Math.PI * 440.0 / rate
            val packets = seconds * rate / frames
            for (seq in 0 until packets) {
                val pcm = ByteArray(frames * 4)
                for (frame in 0 until frames) {
                    val sample = (Math.sin(phase) * 9000).toInt().toShort()
                    phase += step
                    for (channel in 0..1) {
                        pcm[frame * 4 + channel * 2] = (sample.toInt() and 0xFF).toByte()
                        pcm[frame * 4 + channel * 2 + 1] = (sample.toInt() shr 8).toByte()
                    }
                }
                onAudio(TEST_DEVICE, TEST_STREAM.toUByte(), seq.toUInt(), pcm)
                // In real time, as a capture would deliver it.
                kotlinx.coroutines.delay(frames * 1000L / rate)
            }
            kotlinx.coroutines.delay(300)
            end(TEST_DEVICE, tell = false)
        }
    }

    /** Moves packets from the buffer to the speaker, keeping a small cushion in the speaker. */
    private fun pump(s: Session, bytesPerMs: Int) {
        var written = 0L
        var started = false
        var idleSince = 0L
        try {
            while (s.running) {
                val chunk = s.buffer.take(20)
                if (chunk == null) {
                    // Nothing for a while: let the speaker run dry and build the cushion up again, instead of
                    // playing every new packet the moment it arrives, which is a crackle each time.
                    if (started) {
                        if (idleSince == 0L) idleSince = System.nanoTime()
                        if (System.nanoTime() - idleSince > UNDERRUN_NS) {
                            s.track.pause()
                            s.track.flush()
                            started = false
                            written = 0
                        }
                    }
                    continue
                }
                idleSince = 0L
                s.track.write(chunk, 0, chunk.size)
                written += chunk.size
                if (!started && written >= s.prebufferMs * bytesPerMs) {
                    s.track.play()
                    started = true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "the speaker stopped", e)
        } finally {
            runCatching { s.track.stop() }
            runCatching { s.track.release() }
        }
    }

    /** Stops the sound of every device. [tell] sends each a stop too, for when this end decided. */
    fun stop(tell: Boolean) {
        synchronized(lock) { sessions.keys.toList() }.forEach { end(it, tell) }
    }

    /** Stops the sound of [device]. [tell] sends it a stop too, for when this end decided. */
    private fun end(device: String, tell: Boolean) {
        val s = synchronized(lock) {
            val gone = sessions.remove(device) ?: return
            if (sessions.isEmpty()) {
                focus?.let { runCatching { audio.abandonAudioFocusRequest(it) } }
                focus = null
                letSleep()
            }
            gone
        }
        s.running = false
        showNotification()
        if (tell) refuse(s.device, s.stream)
    }

    private fun holdAwake() {
        runCatching {
            // A timeout, so a stop that never comes cannot keep the phone awake for good.
            wakeLock = context.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tandem:audio")
                .apply { setReferenceCounted(false); acquire(MAX_HOLD_MS) }
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 34) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = context.applicationContext.getSystemService(WifiManager::class.java)
                .createWifiLock(mode, "tandem:audio")
                .apply { setReferenceCounted(false); acquire() }
        }.onFailure { Log.w(TAG, "could not keep the phone awake", it) }
    }

    private fun letSleep() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    private fun refuse(device: String, stream: Int) {
        scope.launch { runCatching { host.engine?.sendAudioStop(device, stream.toUByte()) } }
    }

    // ---- Notification ----------------------------------------------------------

    /** One notification for all the devices that play, with their names, and none when nothing plays. */
    private fun showNotification() {
        val names = synchronized(lock) { sessions.values.map { it.name } }
        val manager = context.getSystemService(NotificationManager::class.java)
        if (names.isEmpty()) return manager.cancel(NOTIFICATION_ID)
        if (!Channels.canPost(context)) return
        val stop = PendingIntent.getBroadcast(
            context, 0, Intent(context, ActionReceiver::class.java).setAction(ActionReceiver.STOP_AUDIO),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, Channels.AUDIO)
            .setSmallIcon(R.drawable.ic_stat_tandem)
            .setContentTitle(context.getString(R.string.audio_playing_title, names.joinToString(", ")))
            .setContentText(context.getString(R.string.audio_playing_text))
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, context.getString(R.string.audio_stop), stop)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val TAG = "RemoteAudioPlayer"
        const val NOTIFICATION_ID = 0x7A01
        const val TEST_DEVICE = "tandem-test-tone"
        const val TEST_STREAM = 250
        const val UNDERRUN_NS = 150_000_000L
        const val MAX_HOLD_MS = 6 * 60 * 60 * 1000L
        val attributes: AudioAttributes =
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    }
}
