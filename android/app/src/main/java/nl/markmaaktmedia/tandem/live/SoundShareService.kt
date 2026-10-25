package nl.markmaaktmedia.tandem.live

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import nl.markmaaktmedia.tandem.MainActivity
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.graph

/**
 * Sends what this phone plays to another device, which plays it there. The sound is taken from the apps through the system's
 * playback capture, which needs the permission to record the screen (the system's own question, every time unless Shizuku
 * set the app-op that answers it, see [ProjectionGrant]) and the permission to record audio. Apps that do not allow their sound to be captured stay silent, and that is theirs to decide.
 * It goes to one device at a time: a new one takes the place of the last.
 *
 * The sound goes as it is: 16 bit, 48 kHz, two channels, in packets that fit one datagram, the way the computer's own
 * sound goes to the phone. The capture is a copy of what plays, taken before the volume of the phone, so the media of the
 * phone are muted while it goes (it played on both, and twice is no speaker), and unmuted after. The volume keys of the phone
 * still turn the other device up and down: they lift the mute for a moment, Tandem takes their level and mutes again.
 */
class SoundShareService : Service() {
    /** One capture for one device. A new one replaces it, so what ends an old one must not end the service. */
    private class Sending(val peer: String, val projection: MediaProjection) {
        val since = SystemClock.elapsedRealtime()
        @Volatile var running = true
        /** The device said no: it is not sent a stop of its own. */
        @Volatile var refused = false
        var thread: Thread? = null
    }

    @Volatile private var sending: Sending? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> finish()
            ACTION_BEGIN -> begin(intent)
            ACTION_REFUSED -> refused(intent.getStringExtra(EXTRA_PEER).orEmpty())
        }
        return START_NOT_STICKY
    }

    /**
     * The device the sound goes to sent a stop: it does not play the sound of other devices (right after the start), or its
     * person stopped it there. The sending ends and the person here is told which.
     */
    private fun refused(from: String) {
        val s = sending?.takeIf { it.peer == from } ?: return
        s.refused = true
        val name = graph.host.device(from)?.name ?: getString(R.string.feature_device_fallback)
        val early = SystemClock.elapsedRealtime() - s.since < 3_000
        Toast.makeText(this, getString(if (early) R.string.output_refused else R.string.output_stopped_there, name), Toast.LENGTH_LONG).show()
        finish()
    }

    private fun notification(peerName: String) = LiveNotifications.active(
        this, getString(R.string.sound_sending, peerName),
        open = PendingIntent.getActivity(this, 11, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
        stop = PendingIntent.getService(
            this, 12, Intent(this, SoundShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        ),
    )

    private fun begin(intent: Intent) {
        val peer = intent.getStringExtra(EXTRA_PEER).orEmpty()
        if (peer.isEmpty()) return
        val name = graph.host.device(peer)?.name ?: getString(R.string.feature_device_fallback)
        try {
            ServiceCompat.startForeground(this, LiveNotifications.ACTIVE_ID + 1, notification(name), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } catch (e: Exception) {
            Log.w(TAG, "could not go to the foreground", e)
            return finish()
        }
        // The sound went somewhere else until now: that device is told it ends, and this one takes its place. Let go of first, so
        // what ends the old capture does not end the service, and ended only once the new projection is there, so the service
        // is never without one.
        val previous = sending
        sending = null
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
        val granted = data?.let { runCatching { getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, it) }.getOrNull() }
        previous?.let { end(it) }
        if (granted == null) return finish()
        val s = Sending(peer, granted)
        granted.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (sending === s) finish()
            }
        }, null)

        val record = try {
            val config = AudioPlaybackCaptureConfiguration.Builder(granted)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                // What Tandem plays itself is the sound of other devices: sent on, it could go back to where it came from.
                .excludeUid(Process.myUid())
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
            val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            AudioRecord.Builder().setAudioPlaybackCaptureConfig(config).setAudioFormat(format)
                .setBufferSizeInBytes((minimum * 4).coerceAtLeast(RATE / 5 * FRAME)).build()
        } catch (e: Exception) {
            // No permission to record audio, or the system refused the capture.
            Log.w(TAG, "could not capture the sound", e)
            runCatching { granted.stop() }
            return finish()
        }

        sending = s
        _active.value = peer
        if (!listening) {
            listening = true
            androidx.core.content.ContextCompat.registerReceiver(
                this, volumeChanged, android.content.IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
        s.thread = Thread({ pump(s, record) }, "tandem-sound-out").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /**
     * The media volume of this phone as a gain, 0 to 1: its steps are a scale for the ear, so half way is about a quarter of the level.
     * The level from before the mute, since the media are muted while the sound goes elsewhere: a muted stream says 0.
     */
    private fun volumeGain(audio: AudioManager): Float {
        if (!audio.isStreamMute(AudioManager.STREAM_MUSIC)) audible = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val level = audible.coerceAtLeast(0).toFloat() / max
        return level * level
    }

    /** The media volume the last time the phone was not muted. */
    @Volatile private var audible = 0

    /** Whether Tandem muted the media of this phone, so it unmutes them after, and only then. */
    @Volatile private var mutedHere = false

    /** Mutes the media of this phone while their sound plays elsewhere; again after a volume key lifted the mute. */
    private fun keepMuted(audio: AudioManager) {
        if (audio.isStreamMute(AudioManager.STREAM_MUSIC)) return
        audible = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        runCatching { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0) }
        mutedHere = true
    }

    private fun unmute() {
        if (!mutedHere) return
        mutedHere = false
        runCatching { getSystemService(AudioManager::class.java).adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0) }
    }

    /** A volume key: seen at once, so the phone is heard for as short as can be before it is muted again. */
    private val volumeChanged = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (sending != null) keepMuted(getSystemService(AudioManager::class.java))
        }
    }
    private var listening = false

    /** 16-bit samples times [gain], in place. Untouched at full volume. */
    private fun scale(pcm: ByteArray, gain: Float) {
        if (gain >= 0.999f) return
        var i = 0
        while (i + 1 < pcm.size) {
            val value = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort() * gain
            val out = value.toInt()
            pcm[i] = out.toByte()
            pcm[i + 1] = (out shr 8).toByte()
            i += 2
        }
    }

    /** Reads what the apps play and sends it, in whole sample frames that fit one datagram. */
    private fun pump(s: Sending, record: AudioRecord) {
        val engine = graph.host.engine
        if (engine == null) {
            if (sending === s) finish()
            return
        }
        try {
            runBlocking { engine.sendAudioStart(s.peer, STREAM.toUByte(), RATE.toUInt(), 2.toUByte()) }
            val limit = engine.audioPayloadLimit(s.peer).toInt() / FRAME * FRAME
            val size = (if (limit >= 400) limit else DEFAULT_PACKET).coerceAtMost(MAX_PACKET)
            val buffer = ByteArray(size)
            var seq = 0u
            var failures = 0
            // The volume keys turn the device that is the speaker up and down: what is recorded comes before the volume of the phone.
            val audio = getSystemService(AudioManager::class.java)
            var gain = volumeGain(audio)
            var gainReadAt = 0L
            record.startRecording()
            keepMuted(audio)
            while (s.running) {
                val read = record.read(buffer, 0, size)
                if (read <= 0) {
                    if (read < 0) break
                    continue
                }
                val now = SystemClock.uptimeMillis()
                if (now - gainReadAt > GAIN_EVERY_MS) {
                    gainReadAt = now
                    gain = volumeGain(audio)
                    keepMuted(audio)
                }
                val bytes = if (read == size) buffer else buffer.copyOf(read / FRAME * FRAME)
                scale(bytes, gain)
                try {
                    engine.sendAudio(s.peer, STREAM.toUByte(), seq++, bytes.copyOf())
                    failures = 0
                } catch (e: Exception) {
                    // The link is gone for a moment: a few lost packets are a gap, a long failure is the end.
                    if (++failures > 200) break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "the sound stopped", e)
        } finally {
            runCatching { record.stop() }
            runCatching { record.release() }
            if (!s.refused) runCatching { runBlocking { engine.sendAudioStop(s.peer, STREAM.toUByte()) } }
            if (sending === s) finish()
        }
    }

    private fun end(s: Sending) {
        s.running = false
        runCatching { s.projection.stop() }
    }

    private fun finish() {
        sending?.let { end(it) }
        sending = null
        _active.value = null
        stopListening()
        unmute()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        sending?.let { end(it) }
        sending = null
        _active.value = null
        stopListening()
        unmute()
        super.onDestroy()
    }

    private fun stopListening() {
        if (!listening) return
        listening = false
        runCatching { unregisterReceiver(volumeChanged) }
    }

    companion object {
        private const val TAG = "SoundShare"
        const val ACTION_BEGIN = "nl.markmaaktmedia.tandem.SOUND_BEGIN"
        const val ACTION_STOP = "nl.markmaaktmedia.tandem.SOUND_STOP"
        private const val ACTION_REFUSED = "nl.markmaaktmedia.tandem.SOUND_REFUSED"
        const val EXTRA_PEER = "peer"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        /** The stream number of this phone's sound, apart from the ones the computer's sound uses. */
        const val STREAM = 9
        private const val RATE = 48_000
        private const val FRAME = 4
        /** How often the media volume is read while sending. */
        private const val GAIN_EVERY_MS = 250L
        private const val DEFAULT_PACKET = 1152
        private const val MAX_PACKET = 3840

        private val _active = MutableStateFlow<String?>(null)

        /** The device the sound is going to right now, or null. */
        val active: StateFlow<String?> = _active.asStateFlow()

        fun stop(context: Context) {
            if (_active.value != null) context.startService(Intent(context, SoundShareService::class.java).setAction(ACTION_STOP))
        }

        /** A stop of this phone's stream came from [from]: when the sound goes there, that is a no. */
        fun refused(context: Context, from: String) {
            if (_active.value == from) {
                context.startService(Intent(context, SoundShareService::class.java).setAction(ACTION_REFUSED).putExtra(EXTRA_PEER, from))
            }
        }
    }
}
