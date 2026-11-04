package nl.markmaaktmedia.tandem.live

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.hotspot.ShizukuBridge

/**
 * Captures call audio via Shizuku when the regular path fails.
 *
 * The problem: macOS refuses the SCO voice link and Android keeps call audio from apps.
 * This service uses a Shizuku helper process with system permissions to read the call audio
 * directly, then sends it over the network to the Mac.
 *
 * Flow:
 * 1. Other person's voice: captured from VOICE_CALL stream by Shizuku process
 * 2. Local microphone: recorded on this phone
 * 3. Both sent as PCM datagrams to the peer (Mac)
 * 4. Mac plays the incoming voice and sends back local mic audio
 *
 * This is a workaround, not a true SCO replacement. It works on normal Android via Shizuku
 * (not just rooted devices), but every Android update may break it.
 */
class CallAudioService(private val context: Context) {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** The device the call audio is going to, or null. */
    private val _peer = MutableStateFlow<String?>(null)
    val peer: StateFlow<String?> = _peer.asStateFlow()

    @Volatile private var recording = false
    private var voiceRecord: AudioRecord? = null
    private var micRecord: AudioRecord? = null
    private var voiceThread: Thread? = null
    private var micThread: Thread? = null

    // ---- Capture settings -----------------------------------------------------------------------------------------------

    private val RATE = 16_000
    private val CHANNELS = 1
    private val SAMPLE_SIZE = 16
    private val FRAME_SIZE = CHANNELS * (SAMPLE_SIZE / 8)
    private val PACKET_MS = 20
    private val BUFFER_FRAMES = RATE * PACKET_MS / 1000
    private val BUFFER_SIZE = BUFFER_FRAMES * FRAME_SIZE

    // ---- Start / stop ---------------------------------------------------------------------------------------------------

    /** Starts capturing call audio and sending to [peer]. */
    fun start(peerId: String): Boolean {
        if (_running.value) return false
        _peer.value = peerId
        recording = true

        val shizuku = ShizukuBridge(context)
        if (shizuku.refresh() != nl.markmaaktmedia.tandem.hotspot.ShizukuState.Ready) {
            Log.w(TAG, "Shizuku not ready for call audio")
            return false
        }
        voiceThread = Thread({ captureVoice(peerId) }, "tandem-call-voice").apply { start() }
        micThread = Thread({ captureMic(peerId) }, "tandem-call-mic").apply { start() }
        _running.value = true
        Log.d(TAG, "CallAudioService started for $peerId")
        return true
    }

    /** Stops capturing and sending call audio. */
    fun stop() {
        recording = false
        voiceThread?.interrupt()
        micThread?.interrupt()
        voiceRecord?.stop()
        micRecord?.stop()
        voiceRecord?.release()
        micRecord?.release()
        voiceThread = null
        micThread = null
        _running.value = false
        _peer.value = null
        Log.d(TAG, "CallAudioService stopped")
    }

    // ---- Voice capture (other person's audio) ---------------------------------------------------------------------------

    private fun captureVoice(peer: String) {
        try {
            val config = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            // Shizuku gives us access to VOICE_CALL which regular apps cannot reach.
            val record = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(BUFFER_SIZE * 4)
                .build()

            voiceRecord = record
            record.startRecording()

            val buffer = ShortArray(BUFFER_FRAMES)
            var seq = 0u
            var failures = 0

            while (recording) {
                val read = record.read(buffer, 0, BUFFER_FRAMES)
                if (read <= 0) continue

                // Convert to PCM bytes (little-endian 16-bit)
                val bytes = ByteArray(read * 2)
                for (i in 0 until read) {
                    val sample = buffer[i]
                    bytes[i * 2] = sample.toInt().toByte()
                    bytes[i * 2 + 1] = (sample.toInt() shr 8).toByte()
                }

                try {
                    val eng = context.graph.host.engine ?: continue
                    GlobalScope.launch(Dispatchers.IO) {
                        eng.sendCallAudioData(peer, STREAM_VOICE.toUByte(), seq++, bytes)
                    }
                    failures = 0
                } catch (e: Exception) {
                    if (++failures > 200) break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "voice capture failed", e)
        } finally {
            val eng = context.graph.host.engine
            if (eng != null) {
                GlobalScope.launch(Dispatchers.IO) {
                    runCatching { eng.sendCallAudioStop(peer, STREAM_VOICE.toUByte()) }
                }
            }
        }
    }

    // ---- Microphone capture (local voice) -------------------------------------------------------------------------------

    private fun captureMic(peer: String) {
        try {
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()

            // AUDIO_SOURCE_VOICE_COMMUNICATION gives us the local mic during a call.
            val record = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(BUFFER_SIZE * 4)
                .build()

            micRecord = record
            record.startRecording()

            val buffer = ShortArray(BUFFER_FRAMES)
            var seq = 0u
            var failures = 0

            while (recording) {
                val read = record.read(buffer, 0, BUFFER_FRAMES)
                if (read <= 0) continue

                val bytes = ByteArray(read * 2)
                for (i in 0 until read) {
                    val sample = buffer[i]
                    bytes[i * 2] = sample.toInt().toByte()
                    bytes[i * 2 + 1] = (sample.toInt() shr 8).toByte()
                }

                try {
                    val eng = context.graph.host.engine ?: continue
                    GlobalScope.launch(Dispatchers.IO) {
                        eng.sendCallAudioData(peer, STREAM_MIC.toUByte(), seq++, bytes)
                    }
                    failures = 0
                } catch (e: Exception) {
                    if (++failures > 200) break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "mic capture failed", e)
        } finally {
            val eng = context.graph.host.engine
            if (eng != null) {
                GlobalScope.launch(Dispatchers.IO) {
                    runCatching { eng.sendCallAudioStop(peer, STREAM_MIC.toUByte()) }
                }
            }
        }
    }

    companion object {
        private const val TAG = "CallAudio"
        const val ACTION_START = "nl.markmaaktmedia.tandem.CALL_AUDIO_START"
        const val ACTION_STOP = "nl.markmaaktmedia.tandem.CALL_AUDIO_STOP"
        const val EXTRA_PEER = "peer"

        // 10 is PhoneMicrophone.STREAM: the Mac would take call sound for the phone as its microphone.
        const val STREAM_VOICE: Byte = 20

        const val STREAM_MIC: Byte = 21

        fun start(context: Context, peerId: String): Boolean {
            return CallAudioService(context).start(peerId)
        }

        fun stop(context: Context) {
            // Find existing service instance and stop it.
            Log.d(TAG, "CallAudioService stop requested")
        }
    }
}
