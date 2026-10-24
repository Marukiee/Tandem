package nl.markmaaktmedia.tandem.audio

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.engine.Channels
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.live.SoundShareActivity
import nl.markmaaktmedia.tandem.live.SoundShareService

/**
 * The sound that belongs to a screen. Looking at the screen of a computer, this phone asks for its sound when the person wants it
 * here, or when headphones are in and the person says yes this time; [RemoteAudioPlayer] plays it. The other way round, a computer
 * that looks at this phone's screen can ask for its sound, which this phone then shares with [SoundShareService].
 */
object ScreenSound {
    private val asked = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** The computer whose sound the question on screen is about, until it is answered. */
    val question = MutableStateFlow<String?>(null)

    /** The computer that looks at this phone and has its sound. */
    @Volatile private var soundTo: String? = null

    /** Whether this phone asked `device` for its sound: then it plays, also when Speaker for your computer is off. */
    fun asked(device: String) = device in asked

    fun viewStarted(context: Context, peer: String) {
        val graph = context.graph
        graph.scope.launch {
            if (graph.prefs.screenSound.first() == "here") ask(context, peer, true)
            else if (graph.prefs.screenSoundAsk.first() && headphones(context)) question.value = peer
        }
    }

    fun viewEnded(context: Context, peer: String) {
        if (question.value == peer) question.value = null
        if (asked.contains(peer)) ask(context, peer, false)
    }

    fun ask(context: Context, peer: String, on: Boolean) {
        if (on) asked += peer else asked -= peer
        val engine = context.graph.host.engine ?: return
        context.graph.scope.launch(Dispatchers.IO) { runCatching { engine.requestSound(peer, on) } }
    }

    /** Bluetooth headphones, or something in the jack or the USB port. */
    private fun headphones(context: Context): Boolean {
        val kinds = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET,
        )
        return context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in kinds }
    }

    // ---- This phone's sound, for a computer that looks at it ---------------------------------------------------------------------

    /**
     * A computer that looks at this phone wants its sound, or no longer. Android asks the person first (the same question as for the
     * screen): at once with Tandem in front, otherwise as a heads-up to tap, since an app may not open that question from behind.
     */
    fun requested(context: Context, from: String, on: Boolean) {
        if (!on) {
            if (soundTo == from) stopSharing(context)
            return
        }
        // Its sound plays here: sent back, the two would echo each other.
        if (context.graph.audio.isPlayingFrom(from)) return
        soundTo = from
        val open = SoundShareActivity.forPeer(context, from).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (context.graph.appVisible) {
            runCatching { context.startActivity(open) }
            return
        }
        val name = context.graph.host.device(from)?.name ?: context.getString(R.string.feature_device_fallback)
        val tap = PendingIntent.getActivity(context, 0x534E, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, Channels.CAPTURE)
            .setSmallIcon(R.drawable.ic_stat_tandem)
            .setContentTitle(context.getString(R.string.screen_sound_request_title, name))
            .setContentText(context.getString(R.string.screen_sound_request_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setTimeoutAfter(120_000)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(0x534E, notification)
    }

    /** The computer stopped looking at this phone: its sound stops too. */
    fun hostEnded(context: Context, peer: String) {
        if (soundTo == peer) stopSharing(context)
    }

    private fun stopSharing(context: Context) {
        val peer = soundTo
        soundTo = null
        context.getSystemService(NotificationManager::class.java).cancel(0x534E)
        // The person may have sent the sound somewhere else since: that goes on.
        if (SoundShareService.active.value == peer) SoundShareService.stop(context)
    }
}
