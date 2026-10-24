package nl.markmaaktmedia.tandem.live

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import nl.markmaaktmedia.tandem.graph
import uniffi.tandem_core.TandemSideLevels
import uniffi.tandem_core.tandemSideAnalyze
import uniffi.tandem_core.tandemSideListenMs

/**
 * A computer plays a sweep from its left and its right speaker to find out on which side of it this phone lies (see `side.rs` in the core):
 * this records it for a few seconds and says how loud each side was. Android lets an app use the microphone only while it is on the screen,
 * so with Tandem in the background the answer is that nothing was heard, and the computer says to open Tandem.
 *
 * A computer that checks by itself (`quiet`, sweeps too high to hear) gets no answer while a call or another app has the microphone:
 * recording then would take it away from them, and without the word that the microphone is on, the computer plays nothing.
 */
object SideListener {
    private const val TAG = "SideListener"
    private const val RATE = 48_000

    fun listen(context: Context, from: String, id: ULong, quiet: Boolean) {
        val graph = context.graph
        graph.scope.launch(Dispatchers.IO) {
            val engine = graph.host.engine ?: return@launch
            val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            val free = !quiet || !microphoneInUse(context)
            val levels = if (allowed && free && graph.appVisible) {
                // The computer plays as soon as it hears that the microphone is on.
                runCatching { record { runBlocking { runCatching { engine.sideReady(from, id) } } } }
                    .onFailure { Log.w(TAG, "could not record", it) }.getOrNull()
            } else {
                null
            }
            runCatching { engine.sideHeard(from, id, levels) }
        }
    }

    private fun microphoneInUse(context: Context): Boolean {
        val audio = context.getSystemService(AudioManager::class.java) ?: return false
        return audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION ||
            audio.activeRecordingConfigurations.isNotEmpty()
    }

    @Suppress("MissingPermission")
    private fun record(ready: () -> Unit): TandemSideLevels? {
        val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        // Unprocessed where the phone has it: noise suppression takes a steady tone for noise, and automatic gain hides the difference.
        val recorder = listOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)
            .firstNotNullOfOrNull { source ->
                runCatching {
                    AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, maxOf(minimum, RATE / 2))
                        .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
                }.getOrNull()
            } ?: return null
        val samples = FloatArray((RATE.toLong() * tandemSideListenMs().toLong() / 1000).toInt())
        try {
            recorder.startRecording()
            ready()
            var at = 0
            while (at < samples.size) {
                val read = recorder.read(samples, at, minOf(4096, samples.size - at), AudioRecord.READ_BLOCKING)
                if (read <= 0) break
                at += read
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
        return tandemSideAnalyze(samples.toList(), RATE.toUInt())
    }
}
