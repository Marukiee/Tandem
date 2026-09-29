package nl.markmaaktmedia.tandem.ui.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.engine.EngineHost
import uniffi.tandem_core.TandemInput

/**
 * Everything the remote screen sends to the other device.
 *
 * Messages that must arrive in order (a button going down and up, a key going down and
 * up, text) go through one queue and are sent one after the other. Sending each from
 * its own coroutine let a key-up overtake its key-down. Pointer and scroll are
 * datagrams, sent at once, because a late pointer move is worthless.
 *
 * The queue is drained on the app scope, not the screen's, so that a button released
 * while the screen is going away still gets its up message out.
 */
class RemoteLink(
    private val host: EngineHost,
    private val id: String,
    private val scope: CoroutineScope,
) {
    private val queue = Channel<TandemInput>(Channel.UNLIMITED)

    // How many things hold each mouse button. The trackpad's drag and the on-screen
    // button can both hold the left one, and it only goes up when both are done.
    private val holds = IntArray(2)

    fun start() {
        scope.launch {
            for (message in queue) runCatching { host.engine?.sendInput(id, message) }
        }
    }

    fun pointer(dx: Int, dy: Int) {
        runCatching { host.engine?.sendPointer(id, dx.toWire(), dy.toWire()) }
    }

    fun scroll(dx: Int, dy: Int) {
        runCatching { host.engine?.sendScroll(id, dx.toWire(), dy.toWire()) }
    }

    fun send(input: TandemInput) {
        queue.trySend(input)
    }

    fun click(button: Int, count: Int) = send(TandemInput.Click(button.toUByte(), count.toUByte()))

    /** A key pressed and let go, with [mods] held for it. */
    fun key(code: Short, mods: Int) {
        send(TandemInput.Key(code.toUShort(), true, mods.toUByte()))
        send(TandemInput.Key(code.toUShort(), false, mods.toUByte()))
    }

    fun hold(button: Int, down: Boolean) {
        if (button !in holds.indices) return
        if (down) {
            if (holds[button]++ == 0) send(TandemInput.Button(button.toUByte(), true))
        } else if (holds[button] > 0) {
            if (--holds[button] == 0) send(TandemInput.Button(button.toUByte(), false))
        }
    }

    /** The screen is going away: let go of anything still held and stop sending. */
    fun release() {
        for (button in holds.indices) {
            if (holds[button] > 0) send(TandemInput.Button(button.toUByte(), false))
            holds[button] = 0
        }
        queue.close()
    }

    private fun Int.toWire(): Short = coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
}
