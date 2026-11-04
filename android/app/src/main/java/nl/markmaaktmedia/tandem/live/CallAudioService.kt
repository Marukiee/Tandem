package nl.markmaaktmedia.tandem.live

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.runBlocking
import nl.markmaaktmedia.tandem.MainActivity
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.graph

/**
 * Streams the call voice link from this phone to one computer.
 * Uses VOICE_COMMUNICATION source so it captures the other side's audio, not the microphone.
 */
class CallAudioService : android.app.Service() {
    @Volatile private var running = false
    @Volatile private var peer: String? = null
    @Volatile private var streamNum: Byte = 0
    @Volatile private var sampleRate: Int = 16_000

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> begin(
                intent.getStringExtra(EXTRA_PEER).orEmpty(),
                intent.getByteExtra(EXTRA_STREAM, 0.toByte()).toByte(),
                intent.getIntExtra(EXTRA_SAMPLE_RATE, 16_000),
            )

            ACTION_STOP -> finish()
        }
        return android.app.Service.START_NOT_STICKY
    }

    private fun begin(to: String, streamVal: Byte, rate: Int) {
        if (to.isEmpty()) return
        peer = to
        streamNum = streamVal
        sampleRate = rate
        val name = graph.host.device(to)?.name ?: getString(R.string.feature_device_fallback)
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID + 1, notification(this, name), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            Log.w(TAG, "could not go to the foreground", e)
            return finish()
        }
        Thread({ pump(to, streamNum, sampleRate) }, "tandem-call-audio").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun pump(to: String, streamVal: Byte, rate: Int) {
        running = true
        val engine = graph.host.engine ?: return finishIf(to)
        val record = try {
            val minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minimum * 4)
        } catch (e: Exception) {
            Log.w(TAG, "could not open the voice link", e)
            null
        }
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            record?.release()
            return finishIf(to)
        }
        try {
            val limit = engine.audioPayloadLimit(to).toInt() / 2 * 2
            val size = if (limit >= PACKET) PACKET else limit.coerceAtLeast(2)
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
                try {
                    runBlocking { engine.sendCallAudioData(to, streamVal.toUByte(), seq++, buffer.copyOf(read / 2 * 2)) }
                    failures = 0
                } catch (e: Exception) {
                    if (++failures > 500) break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "the call audio stopped", e)
        } finally {
            runCatching { record.stop() }
            record.release()
            finishIf(to)
        }
    }

    private fun finishIf(to: String?) {
        if (peer == to && !running) finish()
    }

    private fun finish() {
        running = false
        peer = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CallAudio"
        private const val NOTIFICATION_ID = 0x4341
        const val ACTION_START = "nl.markmaaktmedia.tandem.CALL_AUDIO_START"
        const val ACTION_STOP = "nl.markmaaktmedia.tandem.CALL_AUDIO_STOP"
        const val EXTRA_PEER = "peer"
        const val EXTRA_STREAM = "stream"
        const val EXTRA_SAMPLE_RATE = "sampleRate"
        private const val RATE = 16_000
        private const val PACKET = 320

        fun start(context: Context, peer: String, streamNum: Byte, sampleRate: Int) {
            if (peer.isEmpty()) return
            val intent = Intent(context, CallAudioService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_PEER, peer)
                .putExtra(EXTRA_STREAM, streamNum.toInt())
                .putExtra(EXTRA_SAMPLE_RATE, sampleRate)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context, peer: String, streamNum: Byte) {
            if (peer.isEmpty()) return
            val intent = Intent(context, CallAudioService::class.java).setAction(ACTION_STOP)
            ContextCompat.startForegroundService(context, intent)
        }

        fun addData(context: Context, peer: String, streamNum: Byte, seq: Int, data: ByteArray) {
            // For now we just capture locally; this is a placeholder for forwarding remote audio.
            Log.d(TAG, "call audio data: peer=$peer stream=$streamNum seq=$seq size=${data.size}")
        }

        private fun notification(context: Context, peerName: String): android.app.Notification {
            val open = PendingIntent.getActivity(
                context, 31, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val stopPending = PendingIntent.getService(
                context, 32, Intent(context, CallAudioService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return nl.markmaaktmedia.tandem.live.LiveNotifications.active(
                context = context,
                text = context.getString(R.string.call_audio_sending, peerName),
                open = open,
                stop = stopPending,
            )
        }
    }
}
