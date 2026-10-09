package nl.markmaaktmedia.tandem.live

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.data.TandemPrefs
import nl.markmaaktmedia.tandem.engine.EngineHost
import uniffi.tandem_core.TandemEdge
import uniffi.tandem_core.TandemInput
import uniffi.tandem_core.TandemMediaInput
import uniffi.tandem_core.TandemPointerShare

/**
 * This phone as the next screen of a shared mouse and keyboard (see docs/INPUT_SHARING.md). The mouse of a Mac that sits next to this
 * phone is pushed over the edge of its screen and arrives here at the opposite side: this phone draws a pointer and plays what the
 * hands do as touches, the way it does for a Mac that shows this screen in a window ([RemoteInput]). When the pointer is pushed back out
 * past the edge it came in by, the Mac gets it back.
 *
 * It only happens when the person allowed it for this phone, the accessibility service is on, and the screen is lit. Anything else
 * hands the pointer straight back, so nobody is left on a screen that does not move.
 */
class PointerIn(
    private val context: Context,
    private val host: EngineHost,
    private val prefs: TandemPrefs,
    private val scope: CoroutineScope,
) {
    private val player = RemoteInput(context)
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    @Volatile private var allowed = false

    // The computer that has the pointer now, and where its pointer is on this screen.
    private var device: String? = null
    private var track: PointerTrack? = null
    private var lastSeen = 0L
    private var leftDown = false
    private var carried: String? = null
    private var watch: Job? = null

    init {
        scope.launch {
            prefs.pointerIn.collect {
                allowed = it
                if (!it) end(tell = true)
            }
        }
    }

    private val metrics get() = context.resources.displayMetrics

    /** One point of the Mac's mouse is a little more than a dp of this screen, so a hand covers the screen about as far as on the Mac. */
    private val gain get() = metrics.density * 1.4f

    fun isPeer(id: String): Boolean = synchronized(lock) { device == id }

    fun deviceGone(id: String) {
        if (isPeer(id)) end(tell = false)
    }

    /** A message about the pointer from another computer. */
    fun onShare(from: String, msg: TandemPointerShare) {
        when (msg) {
            is TandemPointerShare.Enter -> enter(from, msg.edge, msg.along)
            is TandemPointerShare.Ping -> {
                touch(from)
                say(from, TandemPointerShare.Pong)
            }
            is TandemPointerShare.Pong, is TandemPointerShare.Size -> touch(from)
            is TandemPointerShare.Leave, is TandemPointerShare.Release -> if (isPeer(from)) end(tell = false)
            is TandemPointerShare.Carry -> synchronized(lock) { if (device == from) carried = msg.text }
        }
    }

    private fun touch(from: String) = synchronized(lock) { if (device == from) lastSeen = SystemClock.elapsedRealtime() }

    private fun enter(from: String, edge: TandemEdge, along: Float) {
        val service = TandemAccessibilityService.instance
        val lit = context.getSystemService(PowerManager::class.java)?.isInteractive == true
        val busy = synchronized(lock) { device != null && device != from }
        if (!allowed || service == null || !lit || busy) {
            say(from, TandemPointerShare.Leave(along))
            return
        }
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()
        // The pointer left the Mac by `edge` and comes in at the opposite side of this screen.
        val arrived = PointerTrack.enter(w, h, edge, along)
        val (nx, ny) = arrived.x to arrived.y
        synchronized(lock) {
            device = from
            track = arrived
            lastSeen = SystemClock.elapsedRealtime()
            leftDown = false
            carried = null
        }
        player.handle(TandemMediaInput.PointerAbs(nx / w, ny / h))
        main.post { service.showCursor(nx, ny) }
        // How far the mouse of the Mac has to move to cross this screen, in its own units, so it can follow the pointer over here.
        say(from, TandemPointerShare.Size(width = (w / gain).toUInt(), height = (h / gain).toUInt()))
        startWatch()
    }

    /** Input from the computer that has the pointer. False when it does not have it. */
    fun onInput(from: String, input: TandemInput): Boolean {
        synchronized(lock) {
            if (device != from) return false
            lastSeen = SystemClock.elapsedRealtime()
        }
        when (input) {
            is TandemInput.Pointer -> move(from, input.dx.toFloat(), input.dy.toFloat())
            is TandemInput.Button -> button(input.button.toInt(), input.down)
            is TandemInput.Click -> {
                repeat(input.count.toInt().coerceAtLeast(1)) {
                    player.handle(TandemMediaInput.Button(input.button, true, 1u))
                    player.handle(TandemMediaInput.Button(input.button, false, 1u))
                }
            }
            is TandemInput.Scroll -> player.handle(TandemMediaInput.Scroll(input.dx, input.dy))
            is TandemInput.Key -> key(input.code.toInt(), input.down, input.mods.toInt())
            is TandemInput.Text -> player.handle(TandemMediaInput.Text(input.text))
            is TandemInput.Media -> Unit
        }
        return true
    }

    private fun move(from: String, dx: Float, dy: Float) {
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()
        val (leave, nx, ny) = synchronized(lock) {
            val t = track ?: return
            // A drag that is pushed against the edge stays on this screen until the button comes up.
            val along = t.move(dx * gain, dy * gain, hold = leftDown)
            Triple(along, t.x, t.y)
        }
        if (leave != null) {
            end(tell = false)
            say(from, TandemPointerShare.Leave(leave))
            return
        }
        player.handle(TandemMediaInput.PointerAbs(nx / w, ny / h))
        val service = TandemAccessibilityService.instance
        main.post { service?.showCursor(nx, ny) }
    }

    private fun button(button: Int, down: Boolean) {
        if (button == 0) synchronized(lock) { leftDown = down }
        player.handle(TandemMediaInput.Button(button.toUByte(), down, 1u))
        if (button == 0 && !down) {
            val text = synchronized(lock) { carried.also { carried = null } } ?: return
            // Text that was dragged over is let go where the pointer is: the tap above puts the cursor there, then the text goes in. A
            // field that cannot take it gets it on the clipboard instead.
            main.postDelayed({
                val typed = TandemAccessibilityService.instance?.type(text) == true
                if (!typed) {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Tandem", text))
                }
            }, 300)
        }
    }

    /** The keys of a Mac: Escape is Back, Delete takes a character off, and what is printed on a key is typed (US layout). */
    private fun key(code: Int, down: Boolean, mods: Int) {
        if (!down) return
        // Shortcuts (Command, Control) are the Mac's own business.
        if (mods and (2 or 8) != 0) return
        when (code) {
            MacKeyText.ESCAPE -> player.handle(TandemMediaInput.Key(0x29u, true, 0u, ""))
            MacKeyText.DELETE -> player.handle(TandemMediaInput.Key(0x2Au, true, 0u, ""))
            else -> MacKeyText.text(code, shift = mods and 1 != 0)?.let { player.handle(TandemMediaInput.Text(it)) }
        }
    }

    /** Looks every half second whether the computer is still there and the screen still lit. */
    private fun startWatch() {
        watch?.cancel()
        watch = scope.launch {
            while (isActive) {
                delay(500)
                val gone = synchronized(lock) { device == null }
                if (gone) return@launch
                val silent = synchronized(lock) { SystemClock.elapsedRealtime() - lastSeen > SILENCE_MS }
                val lit = context.getSystemService(PowerManager::class.java)?.isInteractive == true
                if (silent || !lit || TandemAccessibilityService.instance == null) {
                    end(tell = !silent)
                    return@launch
                }
            }
        }
    }

    /** The pointer goes. When [tell] is set the computer that had it is told, so it takes the pointer back at once. */
    private fun end(tell: Boolean) {
        val was = synchronized(lock) {
            val d = device
            device = null
            track = null
            leftDown = false
            carried = null
            d
        }
        watch?.cancel()
        watch = null
        val service = TandemAccessibilityService.instance
        main.post { service?.hideCursor() }
        if (was != null && tell) say(was, TandemPointerShare.Leave(0.5f))
    }

    private fun say(to: String, msg: TandemPointerShare) {
        scope.launch(Dispatchers.IO) { runCatching { host.engine?.sendPointerShare(to, msg) } }
    }

    private companion object {
        /** The Mac asks twice a second. */
        const val SILENCE_MS = 3500L
    }
}
