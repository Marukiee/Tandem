package nl.markmaaktmedia.tandem.live

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.engine.Channels
import nl.markmaaktmedia.tandem.graph

/**
 * Where "send this phone's sound to a device" starts, from the button on the phone or from a computer that looks at this phone's
 * screen and asks for its sound. It picks the way by itself, and the person has no choice to make:
 *
 * 1. Shizuku is ready: the app-op PROJECT_MEDIA is set ([ProjectionGrant]), so the system's capture intent answers at once and the
 *    sound goes as it always did ([SoundShareService]), without the screen recording question.
 * 2. Otherwise, to a Linux or Windows computer that says `audio.bt_sink`: that computer connects to this phone as its Bluetooth
 *    speaker (A2DP sink), from its own side, so this phone needs no permission at all. It has to be paired with the phone.
 * 3. Otherwise the capture with the question, as before.
 *
 * A way that fails falls through to the next without a word; only what goes wrong with the last one is told.
 */
object PhoneSound {
    private const val TAG = "PhoneSound"
    private const val ANSWER_MS = 15_000L
    private const val NOTIFICATION_ID = 0x534E
    const val BT_SINK = "audio.bt_sink"

    /** The computer that plays this phone's sound over Bluetooth now. */
    private val bluetooth = MutableStateFlow<String?>(null)
    private val _connecting = MutableStateFlow<String?>(null)

    /** The computer asked to connect over Bluetooth right now, until it answers. */
    val connecting: StateFlow<String?> = _connecting.asStateFlow()

    /** Per computer asked, its answer: empty when it plays the sound, otherwise why not. */
    private val waiting = HashMap<String, CompletableDeferred<String>>()

    /** Stops this phone asked for itself, so the answer to them is not news for the person. */
    private val stopping = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The device this phone's sound goes to now, by whichever way, or null. */
    val active: StateFlow<String?> = combine(SoundShareService.active, bluetooth) { capture, bt -> capture ?: bt }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** Whether the sound goes to [peer] right now. Read straight from the sources, so it is right before the flow has run. */
    fun sendingTo(peer: String) = SoundShareService.active.value == peer || bluetooth.value == peer

    /**
     * Sends the sound to [peer]. [asked] when the computer asked for it (it looks at this phone's screen): then a question that
     * cannot open because Tandem is behind is a notification that says so.
     */
    fun start(context: Context, peer: String, asked: Boolean = false) {
        val app = context.applicationContext
        val graph = app.graph
        // Pressed on a screen of Tandem: that screen opens the next step, which Android always allows while it is in front.
        val screen = context as? Activity
        // Its sound plays here: sent back, the two would echo each other.
        if (graph.audio.isPlayingFrom(peer)) {
            say(app, R.string.output_loop, peer)
            return
        }
        if (_connecting.value == peer) return
        graph.scope.launch {
            // One device at a time: most phones keep one Bluetooth speaker, and the capture has one target.
            val current = bluetooth.value
            if (current != null && current != peer) stopBluetooth(app, current)

            if (ProjectionGrant.ensure(app)) return@launch openCapture(app, screen, peer, asked)

            if (worthBluetooth(app, peer)) {
                if (SoundShareService.active.value != null) SoundShareService.stop(app)
                _connecting.value = peer
                val error = try {
                    askComputer(app, peer)
                } finally {
                    _connecting.value = null
                }
                if (error == null) {
                    bluetooth.value = peer
                    cancelQuestion(app)
                    say(app, R.string.sound_bluetooth_on, peer)
                    return@launch
                }
                Log.i(TAG, "no Bluetooth speaker at $peer: $error")
            }
            openCapture(app, screen, peer, asked)
        }
    }

    /** Stops the sound, wherever it goes. */
    fun stop(context: Context) {
        val app = context.applicationContext
        SoundShareService.stop(app)
        bluetooth.value?.let { stopBluetooth(app, it) }
    }

    /** Stops the sound if it goes to [peer], and nothing else. */
    fun stopFor(context: Context, peer: String) {
        if (SoundShareService.active.value == peer) SoundShareService.stop(context)
        if (bluetooth.value == peer) stopBluetooth(context.applicationContext, peer)
        cancelQuestion(context)
    }

    /** A computer answered a Bluetooth request, or its Bluetooth link with this phone ended. */
    fun onState(context: Context, from: String, on: Boolean, error: String) {
        val pending = synchronized(waiting) { waiting.remove(from) }
        if (pending != null) {
            pending.complete(if (on) "" else error.ifEmpty { "refused" })
            return
        }
        if (!on && bluetooth.value == from) {
            bluetooth.value = null
            if (!stopping.remove(from)) say(context.applicationContext, R.string.output_stopped_there, from)
        } else if (!on) {
            stopping.remove(from)
        }
    }

    // ---- Bluetooth ---------------------------------------------------------------------------------------------------------------

    private fun worthBluetooth(context: Context, peer: String): Boolean {
        val device = context.graph.host.device(peer) ?: return false
        if (!device.online || BT_SINK !in device.caps) return false
        return runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true }.getOrDefault(false)
    }

    /** The name the computer knows this phone by among its paired devices. Android hands apps no Bluetooth address to match on. */
    private fun bluetoothName(context: Context): String {
        val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        val name = if (allowed) runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter?.name }.getOrNull() else null
        return name?.takeIf { it.isNotBlank() } ?: context.graph.host.myName
    }

    /** Null when the computer plays the sound now, otherwise why not (no answer counts: an older computer skips the request). */
    private suspend fun askComputer(context: Context, peer: String): String? {
        val engine = context.graph.host.engine ?: return "no engine"
        val answer = CompletableDeferred<String>()
        synchronized(waiting) { waiting[peer] = answer }
        return try {
            engine.requestBtSpeaker(peer, true, bluetoothName(context))
            val said = withTimeoutOrNull(ANSWER_MS) { answer.await() } ?: "no answer"
            said.ifEmpty { null }
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        } finally {
            synchronized(waiting) { if (waiting[peer] === answer) waiting.remove(peer) }
        }
    }

    private fun stopBluetooth(context: Context, peer: String) {
        if (bluetooth.value == peer) bluetooth.value = null
        stopping += peer
        val engine = context.graph.host.engine ?: return
        context.graph.scope.launch(Dispatchers.IO) { runCatching { engine.requestBtSpeaker(peer, false, bluetoothName(context)) } }
    }

    // ---- The capture -------------------------------------------------------------------------------------------------------------

    /**
     * Opens [SoundShareActivity], which asks for the microphone permission if needed and launches the capture intent (answered at
     * once when the app-op is set). An activity may not open from behind, so then it is a notification to tap.
     */
    private fun openCapture(context: Context, screen: Activity?, peer: String, asked: Boolean) {
        val open = SoundShareActivity.forPeer(context, peer).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val from = screen?.takeIf { !it.isFinishing && !it.isDestroyed } ?: context.takeIf { inFront(it) }
        if (from != null && runCatching { from.startActivity(open) }.isSuccess) return
        val name = deviceName(context, peer)
        val tap = PendingIntent.getActivity(context, NOTIFICATION_ID, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, Channels.CAPTURE)
            .setSmallIcon(R.drawable.ic_stat_tandem)
            .setContentTitle(
                if (asked) context.getString(R.string.screen_sound_request_title, name) else context.getString(R.string.sound_send_title, name),
            )
            .setContentText(context.getString(if (asked) R.string.screen_sound_request_text else R.string.sound_tap_to_start))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setTimeoutAfter(120_000)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    /** Tandem is on screen. Asked of the system too, since a second MainActivity that stops can leave graph.appVisible false. */
    private fun inFront(context: Context): Boolean {
        if (context.graph.appVisible) return true
        val state = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)
        return state.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private fun cancelQuestion(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun deviceName(context: Context, peer: String) =
        context.graph.host.device(peer)?.name ?: context.getString(R.string.feature_device_fallback)

    private fun say(context: Context, @StringRes text: Int, peer: String) {
        val message = context.getString(text, deviceName(context, peer))
        Handler(Looper.getMainLooper()).post { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
    }
}
