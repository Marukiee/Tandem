package nl.markmaaktmedia.tandem.live

import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.hypot
import uniffi.tandem_core.TandemMediaInput

/**
 * Turns what a computer's mouse and keyboard do into touches on this phone. The pointer is somewhere on the screen, the
 * left button is the finger: it goes down when the button does, follows the pointer while it is held, and comes up with the
 * button, played live as a touch that goes on (so a drag is seen while it happens, and holding still is a long press). Where
 * Android will not let a touch go on, the old way is taken: the whole tap or swipe played when the button comes up.
 * The right button is Back, the middle one Home. Scrolling is a swipe in the direction of the content.
 */
class RemoteInput(private val context: Context) {
    private var x = 0f
    private var y = 0f
    private var held = false
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L

    private val width get() = context.resources.displayMetrics.widthPixels.toFloat()
    private val height get() = context.resources.displayMetrics.heightPixels.toFloat()

    private val main = Handler(Looper.getMainLooper())

    /** What came in for scrolling and has not been played yet, and whether a swipe is being played now. */
    private var scrollDx = 0f
    private var scrollDy = 0f
    private var scrolling = false

    /** Everything is dealt with on one thread, in the order it came, so a swipe that is under way can be followed by the next. */
    fun handle(input: TandemMediaInput) {
        main.post { handleNow(input) }
    }

    private fun handleNow(input: TandemMediaInput) {
        val service = TandemAccessibilityService.instance ?: return
        when (input) {
            is TandemMediaInput.PointerAbs -> {
                x = (input.x * width).coerceIn(0f, width - 1)
                y = (input.y * height).coerceIn(0f, height - 1)
                if (live != null) follow(service)
            }
            is TandemMediaInput.PointerRel -> {
                x = (x + input.dx).coerceIn(0f, width - 1)
                y = (y + input.dy).coerceIn(0f, height - 1)
                if (live != null) follow(service)
            }
            is TandemMediaInput.Button -> button(service, input.button.toInt(), input.down)
            is TandemMediaInput.Scroll -> {
                // Two fingers on a trackpad send a stream of small steps. Played one by one they would be taps that
                // never move, so they are added up and played as one swipe at a time, the next as soon as the last ended.
                scrollDx += input.dx.toFloat()
                scrollDy += input.dy.toFloat()
                if (!scrolling) playScroll(service)
            }
            is TandemMediaInput.Key -> if (input.down) key(service, input.code.toInt(), input.text)
            is TandemMediaInput.Text -> service.type(input.text)
        }
    }

    /** The content follows the fingers, so a scroll of the content is a swipe the same way. */
    private fun playScroll(service: TandemAccessibilityService) {
        val dx = (scrollDx * SCROLL_GAIN).coerceIn(-width * 0.6f, width * 0.6f)
        val dy = (scrollDy * SCROLL_GAIN).coerceIn(-height * 0.6f, height * 0.6f)
        scrollDx = 0f
        scrollDy = 0f
        if (abs(dx) < 2f && abs(dy) < 2f) return
        // The swipe needs room to go in the direction it goes: it starts where the pointer is, moved away from the edge when
        // the pointer is too close to it.
        val fromX = (if (dx > 0) minOf(x, width - 1 - dx) else maxOf(x, -dx)).coerceIn(0f, width - 1)
        val fromY = (if (dy > 0) minOf(y, height - 1 - dy) else maxOf(y, -dy)).coerceIn(0f, height - 1)
        val toX = (fromX + dx).coerceIn(0f, width - 1)
        val toY = (fromY + dy).coerceIn(0f, height - 1)
        val duration = (hypot(toX - fromX, toY - fromY) / SCROLL_PX_PER_MS).toLong().coerceIn(SCROLL_MIN_MS, SCROLL_MAX_MS)
        scrolling = true
        service.swipe(fromX, fromY, toX, toY, duration) {
            main.post {
                scrolling = false
                if (abs(scrollDx) >= 1f || abs(scrollDy) >= 1f) playScroll(service)
            }
        }
    }

    // ---- The live touch ------------------------------------------------------------------------------------------------------

    /** The touch that is down now and goes on, with where its last piece ended; null when no finger is down this way. */
    private var live: GestureDescription.StrokeDescription? = null
    private var liveX = 0f
    private var liveY = 0f
    /** A piece is being played: the next waits for it, or Android cancels the touch. */
    private var playing = false
    /** The button came up while a piece was being played: the touch ends after it. */
    private var ending = false
    /** When Android last cancelled a touch that goes on: for a while taps and swipes are played whole, when the button comes up. */
    private var liveRefusedAt = 0L
    private val liveRefused get() = SystemClock.uptimeMillis() - liveRefusedAt < LIVE_RETRY_MS && liveRefusedAt != 0L

    private fun point(fromX: Float, fromY: Float, toX: Float, toY: Float) = Path().apply {
        moveTo(fromX, fromY)
        if (fromX != toX || fromY != toY) lineTo(toX, toY)
    }

    private fun startLive(service: TandemAccessibilityService) {
        val first = GestureDescription.StrokeDescription(point(x, y, x, y), 0, PIECE_MS, true)
        live = first
        liveX = x
        liveY = y
        play(service, first)
    }

    /** The pointer moved with the finger down: the next piece goes there once the last one was played. */
    private fun follow(service: TandemAccessibilityService) {
        if (!playing) next(service)
    }

    private fun next(service: TandemAccessibilityService) {
        val current = live ?: return
        val end = ending
        // A piece that does not move keeps the finger where it is: that is how a press is held long.
        val piece = runCatching { current.continueStroke(point(liveX, liveY, x, y), 0, PIECE_MS, !end) }.getOrNull()
        if (piece == null) {
            giveUpLive()
            return
        }
        liveX = x
        liveY = y
        if (end) {
            live = null
            ending = false
        } else {
            live = piece
        }
        play(service, piece)
    }

    private fun play(service: TandemAccessibilityService, piece: GestureDescription.StrokeDescription) {
        playing = true
        service.stroke(piece) { played ->
            main.post {
                playing = false
                if (!played) {
                    giveUpLive()
                    return@post
                }
                // Moved meanwhile, or let go: the next piece. Held still: a piece that stays, so the finger stays down.
                if (live != null) next(service)
            }
        }
    }

    private fun giveUpLive() {
        live = null
        ending = false
        playing = false
        liveRefusedAt = SystemClock.uptimeMillis()
    }

    private fun button(service: TandemAccessibilityService, button: Int, down: Boolean) {
        when (button) {
            0 -> if (down) {
                held = true
                downX = x
                downY = y
                downAt = SystemClock.uptimeMillis()
                if (!liveRefused) startLive(service)
            } else if (held) {
                held = false
                if (live != null) {
                    // The finger comes up with the next piece, where the pointer is now.
                    ending = true
                    if (!playing) next(service)
                    return
                }
                val duration = SystemClock.uptimeMillis() - downAt
                if (hypot(x - downX, y - downY) < SLOP) {
                    service.press(downX, downY, if (duration > LONG_PRESS_MS) duration else TAP_MS)
                } else {
                    service.swipe(downX, downY, x, y, duration.coerceIn(80, 1500))
                }
            }
            1 -> if (down) service.back()
            2 -> if (down) service.home()
            3, 4 -> if (down) service.recents()
        }
    }

    /** USB HID usages: Escape is Back, Backspace takes a character off, Return goes in as a line break. */
    private fun key(service: TandemAccessibilityService, code: Int, text: String) {
        when (code) {
            HID_ESCAPE -> service.back()
            HID_BACKSPACE -> service.backspace()
            HID_ENTER -> service.type("\n")
            else -> if (text.isNotEmpty()) service.type(text)
        }
    }

    private companion object {
        /** How long one piece of a live touch is: about a frame of the screen. */
        const val PIECE_MS = 16L
        /** How long the whole taps and swipes are kept after Android cancelled a live touch (the person touched the phone, say). */
        const val LIVE_RETRY_MS = 10_000L
        const val SLOP = 24f
        const val TAP_MS = 50L
        const val LONG_PRESS_MS = 450L
        /** How far the content goes per pixel the Mac scrolled, how fast the swipe is, and how short or long one may be. */
        const val SCROLL_GAIN = 1.6f
        const val SCROLL_PX_PER_MS = 2.2f
        const val SCROLL_MIN_MS = 60L
        const val SCROLL_MAX_MS = 260L
        const val HID_ENTER = 0x28
        const val HID_ESCAPE = 0x29
        const val HID_BACKSPACE = 0x2A
    }
}
