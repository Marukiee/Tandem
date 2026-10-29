package nl.markmaaktmedia.tandem.audio

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import nl.markmaaktmedia.tandem.MainActivity
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.engine.Channels
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.live.LiveNotifications

/**
 * This phone as the microphone of a computer (`MicRequest` in proto.rs). The computer asks, the phone records and sends what it hears
 * on audio stream 10, and the computer plays it into its own microphone device (Tandem Microphone on a Mac, a virtual source on Linux).
 *
 * Android lets a service use the microphone only when it was started while Tandem was on screen. With Tandem in front it starts at
 * once; otherwise the question is a notification, and the tap on it is what lets it start. The ongoing notification and the system's
 * green dot say it is on for as long as it is.
 */
object MicShare {
    private const val NOTIFICATION_ID = 0x4D49

    /** The computer asked for the microphone, or to stop it. */
    fun requested(context: Context, from: String, on: Boolean) {
        if (!on) return stopFor(context, from)
        val open = MicShareActivity.forPeer(context, from).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (inFront(context) && runCatching { context.startActivity(open) }.isSuccess) return
        val name = context.graph.host.device(from)?.name ?: context.getString(R.string.feature_device_fallback)
        val tap = PendingIntent.getActivity(context, NOTIFICATION_ID, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, Channels.CAPTURE)
            .setSmallIcon(R.drawable.ic_stat_tandem)
            .setContentTitle(context.getString(R.string.mic_request_title, name))
            .setContentText(context.getString(R.string.mic_request_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setTimeoutAfter(45_000)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    /** The computer said stop, or went away. */
    fun stopFor(context: Context, peer: String) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        if (MicShareService.peer == peer) {
            runCatching { context.startService(Intent(context, MicShareService::class.java).setAction(MicShareService.ACTION_STOP)) }
        }
    }

    private fun inFront(context: Context): Boolean {
        if (context.graph.appVisible) return true
        val state = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)
        return state.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }
}

/** Asks for the microphone permission when it is not there yet, then starts [MicShareService] while Tandem is on screen, and closes. */
class MicShareActivity : ComponentActivity() {
    private lateinit var peer: String

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) begin() else refuse()
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        peer = intent.getStringExtra(MicShareService.EXTRA_PEER) ?: return finish()
        if (savedInstanceState != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            begin()
            finish()
        } else {
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun begin() {
        ContextCompat.startForegroundService(
            this, Intent(this, MicShareService::class.java).setAction(MicShareService.ACTION_BEGIN).putExtra(MicShareService.EXTRA_PEER, peer),
        )
    }

    /** No permission: the computer is told at once, so it says why there instead of waiting. */
    private fun refuse() {
        Toast.makeText(applicationContext, getString(R.string.mic_permission_needed), Toast.LENGTH_LONG).show()
        val engine = graph.host.engine ?: return
        val to = peer
        graph.scope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { engine.sendAudioStop(to, MicShareService.STREAM.toUByte()) } }
    }

    companion object {
        fun forPeer(context: Context, peer: String): Intent =
            Intent(context, MicShareActivity::class.java).putExtra(MicShareService.EXTRA_PEER, peer)
    }
}

/**
 * Records the microphone and sends it to one computer: 16 bit mono at 48 kHz, 10 ms per datagram, which keeps the delay of a call low.
 * Recorded for voice: the phone's echo canceller keeps out what the phone itself plays, which matters when it is the computer's
 * speaker at the same time.
 */
class MicShareService : Service() {
    @Volatile private var running = false
    private var thread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_BEGIN -> begin(intent.getStringExtra(EXTRA_PEER).orEmpty())
            ACTION_STOP -> finish()
        }
        return START_NOT_STICKY
    }

    private fun begin(to: String) {
        if (to.isEmpty()) return
        val name = graph.host.device(to)?.name ?: getString(R.string.feature_device_fallback)
        val notification = LiveNotifications.active(
            this, getString(R.string.mic_sending, name),
            open = LiveNotifications.activity(this, 21, Intent(this, MainActivity::class.java)),
            stop = PendingIntent.getService(
                this, 22, Intent(this, MicShareService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        try {
            ServiceCompat.startForeground(this, LiveNotifications.ACTIVE_ID + 5, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            Log.w(TAG, "could not go to the foreground", e)
            return finish()
        }
        // Another computer had it: it ends there, this one takes over.
        stopRecording()
        peer = to
        running = true
        thread = Thread({ pump(to) }, "tandem-mic").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun pump(to: String) {
        val engine = graph.host.engine ?: return finish()
        val record = try {
            val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minimum * 4)
        } catch (e: Exception) {
            Log.w(TAG, "could not open the microphone", e)
            null
        }
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            record?.release()
            runCatching { runBlocking { engine.sendAudioStop(to, STREAM.toUByte()) } }
            return finish()
        }
        try {
            runBlocking { engine.sendAudioStart(to, STREAM.toUByte(), RATE.toUInt(), 1.toUByte()) }
            val limit = engine.audioPayloadLimit(to).toInt() / 2 * 2
            val size = if (limit >= PACKET) PACKET else limit.coerceAtLeast(2)
            val buffer = ByteArray(size)
            var seq = 0u
            var failures = 0
            record.startRecording()
            while (running && peer == to) {
                val read = record.read(buffer, 0, size)
                if (read <= 0) {
                    if (read < 0) break
                    continue
                }
                try {
                    engine.sendAudio(to, STREAM.toUByte(), seq++, buffer.copyOf(read / 2 * 2))
                    failures = 0
                } catch (e: Exception) {
                    // A moment without a link is a gap; a long one is the end.
                    if (++failures > 500) break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "the microphone stopped", e)
        } finally {
            runCatching { record.stop() }
            record.release()
            runCatching { runBlocking { engine.sendAudioStop(to, STREAM.toUByte()) } }
            if (peer == to) finish()
        }
    }

    private fun stopRecording() {
        running = false
        thread = null
        peer = null
    }

    private fun finish() {
        stopRecording()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MicShare"
        const val ACTION_BEGIN = "nl.markmaaktmedia.tandem.MIC_BEGIN"
        const val ACTION_STOP = "nl.markmaaktmedia.tandem.MIC_STOP"
        const val EXTRA_PEER = "peer"

        /** `MIC_STREAM` in proto.rs: apart from this phone's own sound (9). */
        const val STREAM = 10
        private const val RATE = 48_000
        /** 10 ms of mono 16 bit. */
        private const val PACKET = 960

        /** The computer that hears this phone's microphone now, or null. */
        @Volatile var peer: String? = null
            private set
    }
}
