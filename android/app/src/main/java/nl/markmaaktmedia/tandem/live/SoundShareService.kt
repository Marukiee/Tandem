package nl.markmaaktmedia.tandem.live

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.Log
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
 * Sends what this phone plays to a computer, which plays it there. The sound is taken from the apps through the system's
 * playback capture, which needs the permission to record the screen (the system's own question, every time) and the
 * permission to record audio. Apps that do not allow their sound to be captured stay silent, and that is theirs to decide.
 *
 * The sound goes as it is: 16 bit, 48 kHz, two channels, in packets that fit one datagram, the way the computer's own
 * sound goes to the phone.
 */
class SoundShareService : Service() {
    private var projection: MediaProjection? = null
    private var thread: Thread? = null

    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> finish()
            ACTION_BEGIN -> begin(intent)
        }
        return START_NOT_STICKY
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
        if (running || peer.isEmpty()) return
        val name = graph.host.device(peer)?.name ?: getString(R.string.live_mac_fallback)
        try {
            ServiceCompat.startForeground(this, LiveNotifications.ACTIVE_ID + 1, notification(name), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } catch (e: Exception) {
            Log.w(TAG, "could not go to the foreground", e)
            return finish()
        }
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
        val granted = data?.let { runCatching { getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, it) }.getOrNull() }
        if (granted == null) return finish()
        projection = granted
        granted.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() = finish()
        }, null)

        val record = try {
            val config = AudioPlaybackCaptureConfiguration.Builder(granted)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
            val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            AudioRecord.Builder().setAudioPlaybackCaptureConfig(config).setAudioFormat(format)
                .setBufferSizeInBytes((minimum * 4).coerceAtLeast(RATE / 5 * FRAME)).build()
        } catch (e: Exception) {
            // No permission to record audio, or the system refused the capture.
            Log.w(TAG, "could not capture the sound", e)
            return finish()
        }

        running = true
        _active.value = peer
        thread = Thread({ pump(record, peer) }, "tandem-sound-out").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /** Reads what the apps play and sends it, in whole sample frames that fit one datagram. */
    private fun pump(record: AudioRecord, peer: String) {
        val engine = graph.host.engine
        if (engine == null) return finish()
        try {
            runBlocking { engine.sendAudioStart(peer, STREAM.toUByte(), RATE.toUInt(), 2.toUByte()) }
            val limit = engine.audioPayloadLimit(peer).toInt() / FRAME * FRAME
            val size = (if (limit >= 400) limit else DEFAULT_PACKET).coerceAtMost(MAX_PACKET)
            val buffer = ByteArray(size)
            var seq = 0u
            var failures = 0
            record.startRecording()
            while (running) {
                val read = record.read(buffer, 0, size)
                if (read <= 0) {
                    if (read < 0) break
                    continue
                }
                val bytes = if (read == size) buffer else buffer.copyOf(read / FRAME * FRAME)
                try {
                    engine.sendAudio(peer, STREAM.toUByte(), seq++, bytes.copyOf())
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
            runCatching { runBlocking { engine.sendAudioStop(peer, STREAM.toUByte()) } }
            finish()
        }
    }

    private fun finish() {
        running = false
        _active.value = null
        runCatching { projection?.stop() }
        projection = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        _active.value = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SoundShare"
        const val ACTION_BEGIN = "nl.markmaaktmedia.tandem.SOUND_BEGIN"
        const val ACTION_STOP = "nl.markmaaktmedia.tandem.SOUND_STOP"
        const val EXTRA_PEER = "peer"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        /** The stream number of this phone's sound, apart from the one the computer's sound uses. */
        const val STREAM = 9
        private const val RATE = 48_000
        private const val FRAME = 4
        private const val DEFAULT_PACKET = 1152
        private const val MAX_PACKET = 3840

        private val _active = MutableStateFlow<String?>(null)

        /** The device the sound is going to right now, or null. */
        val active: StateFlow<String?> = _active.asStateFlow()

        fun stop(context: Context) {
            if (_active.value != null) context.startService(Intent(context, SoundShareService::class.java).setAction(ACTION_STOP))
        }
    }
}
