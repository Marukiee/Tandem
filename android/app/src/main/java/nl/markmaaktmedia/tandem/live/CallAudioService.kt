package nl.markmaaktmedia.tandem.live

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
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
 * Routes call audio between phone and one computer.
 * Phone captures its microphone and sends it to the Mac (stream 20).
 * The Mac plays sound on the phone through playback injection (stream 21).
 */
class CallAudioService : android.app.Service() {
    @Volatile private var running = false
    @Volatile private var peer: String? = null
    @Volatile private var streamNum: Byte = 0
    @Volatile private var sampleRate: Int = 16_000
    private val shizuku by lazy { nl.markmaaktmedia.tandem.hotspot.ShizukuBridge(this@CallAudioService) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> begin(
                intent.getStringExtra(EXTRA_PEER).orEmpty(),
                intent.getByteExtra(EXTRA_STREAM, 0.toByte()).toByte(),
                intent.getIntExtra(EXTRA_SAMPLE_RATE, 16_000),
            )

            ACTION_STOP -> finish()
            ACTION_CAPTURE_START -> captureBegin(intent.getStringExtra(EXTRA_PEER).orEmpty())
            ACTION_CAPTURE_STOP -> captureEnd()
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

    /** Start Shizuku capture of the voice link (CAPTURE_AUDIO_OUTPUT). */
    private fun captureBegin(peerVal: String) {
        peer = peerVal
        running = true
        val name = graph.host.device(peerVal)?.name ?: getString(R.string.feature_device_fallback)
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID + 2, notification(this, "$name (Shizuku)"), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            Log.w(TAG, "could not go to the foreground for capture", e)
            return finish()
        }
        Thread({ pumpCapture(peerVal) }, "tandem-capture").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /** Stop Shizuku capture and return audio to the phone's speakers. */
    private fun captureEnd() {
        running = false
        val myPeer = peer ?: return
        peer = null
        // Tell the Mac to stop receiving call audio.
        graph.host.engine?.let { engine ->
            runBlocking { engine.sendCallAudioStop(myPeer, 20u) }
        }
        // Stop Shizuku capture.
        runBlocking { shizuku.captureAudioStop() }
    }

    private fun pumpCapture(to: String) {
        running = true
        val engine = graph.host.engine ?: return captureEnd()
        // Start Shizuku's CAPTURE_AUDIO_OUTPUT (actual voice link, not mic).
        runBlocking { shizuku.captureAudioStart() }
        // Tell the Mac to start receiving call audio.
        runBlocking { engine.sendCallAudioStart(to, 20u, 16_000u) }
        val rate = 16_000
        val record = try {
            val minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            // Shizuku's CAPTURE_AUDIO_OUTPUT gives us the call audio mix.
            AudioRecord(
                android.media.MediaRecorder.AudioSource.MIC,
                rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minimum * 4
            )
        } catch (e: Exception) {
            Log.w(TAG, "could not open capture audio", e)
            null
        }
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            record?.release()
            return captureEnd()
        }
        try {
            val limit = engine.audioPayloadLimit(to).toInt() / 2 * 2
            val size = if (limit >= PACKET) PACKET else limit.coerceAtLeast(2)
            val buffer = ByteArray(size)
            var seq = 0u
            startPlayback(rate)
            record.startRecording()
            while (running) {
                val read = record.read(buffer, 0, size)
                if (read <= 0) {
                    if (read < 0) break
                    continue
                }
                try {
                    runBlocking { engine.sendCallAudioData(to, 20u, seq++, buffer.copyOf(read / 2 * 2)) }
                } catch (e: Exception) {
                    Log.w(TAG, "capture data send failed", e)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "capture stopped", e)
        } finally {
            runCatching { record.stop() }
            record.release()
            captureEnd()
        }
    }

    private fun finish() {
        running = false
        peer = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        runCatching { playbackTrack?.stop() }
        runCatching { playbackTrack?.release() }
        playbackTrack = null
        stopSelf()
    }

    private fun startPlayback(rate: Int) {
        if (playbackTrack != null) return
        val minBufferSize = android.media.AudioTrack.getMinBufferSize(
            rate, android.media.AudioFormat.CHANNEL_OUT_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT
        )
        playbackTrack = android.media.AudioTrack(
            android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(2)
                .build(),
            android.media.AudioFormat.Builder()
                .setSampleRate(rate)
                .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                .build(),
            minBufferSize * 4,
            android.media.AudioTrack.MODE_STREAM,
            0
        ).apply {
            play()
        }
        Log.d(TAG, "playback started: rate=$rate bufferSize=$minBufferSize")
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    companion object {
        @Volatile var playbackTrack: android.media.AudioTrack? = null

        private const val TAG = "CallAudio"
        private const val NOTIFICATION_ID = 0x4341
        const val ACTION_START = "nl.markmaaktmedia.tandem.CALL_AUDIO_START"
        const val ACTION_STOP = "nl.markmaaktmedia.tandem.CALL_AUDIO_STOP"
        const val ACTION_CAPTURE_START = "nl.markmaaktmedia.tandem.CAPTURE_AUDIO_START"
        const val ACTION_CAPTURE_STOP = "nl.markmaaktmedia.tandem.CAPTURE_AUDIO_STOP"
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
            val track = playbackTrack
            if (track == null || track.state != android.media.AudioTrack.STATE_INITIALIZED) return
            try {
                track.write(data, 0, data.size, android.media.AudioTrack.WRITE_BLOCKING)
            } catch (e: Exception) {
                Log.w(TAG, "playback write failed", e)
            }
        }

        /** Capture the voice link via Shizuku (CAPTURE_AUDIO_OUTPUT) instead of VOICE_COMMUNICATION. */
        fun captureStart(context: Context, peer: String) {
            if (peer.isEmpty()) return
            val intent = Intent(context, CallAudioService::class.java).setAction(ACTION_CAPTURE_START)
                .putExtra(EXTRA_PEER, peer)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Stop Shizuku capture and go back to the phone's speakers. */
        fun captureStop(context: Context, peer: String) {
            if (peer.isEmpty()) return
            val intent = Intent(context, CallAudioService::class.java).setAction(ACTION_CAPTURE_STOP)
            ContextCompat.startForegroundService(context, intent)
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
