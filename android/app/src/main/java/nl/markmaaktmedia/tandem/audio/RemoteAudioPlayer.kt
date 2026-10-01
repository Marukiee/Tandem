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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.data.TandemPrefs
import nl.markmaaktmedia.tandem.engine.ActionReceiver
import nl.markmaaktmedia.tandem.engine.Channels
import nl.markmaaktmedia.tandem.engine.EngineHost
import uniffi.tandem_core.TandemAudioSink
import uniffi.tandem_core.TandemEvent

/**
 * Plays the sound of the Mac through this phone, so the phone can be chosen as the Mac's speaker.
 *
 * The Mac sends 16 bit samples as datagrams. They go through a [JitterBuffer] into an [AudioTrack] in
 * low latency mode, so the volume buttons of the phone are the volume. A notification says that it
 * is playing and has a Stop button. One Mac at a time; a second start while one plays is refused.
 */
class RemoteAudioPlayer(
    private val context: Context,
    private val prefs: TandemPrefs,
    private val host: EngineHost,
    private val scope: CoroutineScope,
) : TandemAudioSink {

    private class Session(val device: String, val stream: Int, val track: AudioTrack, val buffer: JitterBuffer, val focus: AudioFocusRequest?) {
        @Volatile var running = true
        var thread: Thread? = null
    }

    private val lock = Any()
    private var session: Session? = null
    private val audio = context.getSystemService(AudioManager::class.java)

    // With the screen off the phone sleeps as soon as it may, and its Wi-Fi saves power by holding packets back.
    // Both are kept awake while sound plays, which is what a music player does, and let go again when it stops.
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /** The name of the device whose sound is playing, or null. For the settings page. */
    private val _playingFrom = MutableStateFlow<String?>(null)
    val playingFrom: StateFlow<String?> = _playingFrom.asStateFlow()

    // ---- From the engine ------------------------------------------------------

    /** Called for every packet, on the connection's own task: only queues. */
    override fun onAudio(from: String, stream: UByte, seq: UInt, pcm: ByteArray) {
        val s = synchronized(lock) { session } ?: return
        if (s.device != from) return
        s.buffer.push(stream.toInt(), seq.toLong(), pcm)
    }

    fun onEvent(event: TandemEvent) {
        when (event) {
            is TandemEvent.AudioStart -> scope.launch { start(event.from, event.stream.toInt(), event.sampleRate.toInt(), event.channels.toInt()) }
            is TandemEvent.AudioStop -> synchronized(lock) { session }?.takeIf { it.device == event.from && it.stream == event.stream.toInt() }?.let { stop(tell = false) }
            // The Mac went away: nothing more will come, so do not sit in silence.
            is TandemEvent.Disconnected -> synchronized(lock) { session }?.takeIf { it.device == event.id }?.let { stop(tell = false) }
            else -> Unit
        }
    }

    // ---- Playing --------------------------------------------------------------

    private suspend fun start(device: String, stream: Int, rate: Int, channels: Int, name: String? = null, force: Boolean = false) {
        if (!force && !prefs.audioOutput.first()) {
            // The person has not allowed it: say so, so the Mac does not keep sending into the void.
            refuse(device, stream)
            return
        }
        // A new start from the same Mac replaces the old; another Mac has to wait its turn.
        synchronized(lock) { session }?.let { if (it.device != device) return refuse(device, stream) }
        stop(tell = false)

        val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val frameBytes = channels * 2
        val bytesPerMs = rate * frameBytes / 1000
        val minimum = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) return refuse(device, stream)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(mask).build())
                .setBufferSizeInBytes(maxOf(minimum, bytesPerMs * BUFFER_MS))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "could not open the speaker", e)
            return refuse(device, stream)
        }

        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            // Something else taking the speaker (a call, other music) ends this, rather than fighting it.
            .setOnAudioFocusChangeListener { change -> if (change == AudioManager.AUDIOFOCUS_LOSS) stop(tell = true) }
            .build()
        // Not getting the focus is no reason not to play: the Mac asked for it.
        runCatching { audio.requestAudioFocus(focus) }

        holdAwake()
        val buffer = JitterBuffer(frameBytes, bytesPerMs)
        buffer.reset(stream)
        val s = Session(device, stream, track, buffer, focus)
        synchronized(lock) { session = s }
        val label = name ?: host.device(device)?.name ?: device
        _playingFrom.value = label
        s.thread = Thread({ pump(s, bytesPerMs) }, "tandem-audio").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        showNotification(label)
    }

    /**
     * Plays a tone through the same path the Mac's sound takes: opening the speaker, the buffer, the
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
            if (synchronized(lock) { session }?.device == TEST_DEVICE) stop(tell = false)
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
                if (!started && written >= PREBUFFER_MS * bytesPerMs) {
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

    /** Stops playing. [tell] sends the Mac a stop too, for when this end decided. */
    fun stop(tell: Boolean) {
        val s = synchronized(lock) { session.also { session = null } } ?: return
        s.running = false
        s.focus?.let { runCatching { audio.abandonAudioFocusRequest(it) } }
        letSleep()
        _playingFrom.value = null
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        if (tell) scope.launch { runCatching { host.engine?.sendAudioStop(s.device, s.stream.toUByte()) } }
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

    private fun showNotification(deviceName: String) {
        if (!Channels.canPost(context)) return
        val stop = PendingIntent.getBroadcast(
            context, 0, Intent(context, ActionReceiver::class.java).setAction(ActionReceiver.STOP_AUDIO),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, Channels.AUDIO)
            .setSmallIcon(R.drawable.ic_stat_tandem)
            .setContentTitle(context.getString(R.string.audio_playing_title, deviceName))
            .setContentText(context.getString(R.string.audio_playing_text))
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, context.getString(R.string.audio_stop), stop)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val TAG = "RemoteAudioPlayer"
        const val NOTIFICATION_ID = 0x7A01
        const val TEST_DEVICE = "tandem-test-tone"
        const val TEST_STREAM = 250
        /** What the speaker holds at most. Bigger is steadier, smaller is closer to live. */
        const val BUFFER_MS = 200
        /** How much is written before the speaker starts, to ride out a slow packet. */
        const val PREBUFFER_MS = 90L
        const val UNDERRUN_NS = 150_000_000L
        const val MAX_HOLD_MS = 6 * 60 * 60 * 1000L
    }
}
