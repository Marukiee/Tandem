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
 * The right button is Back, the middle one Home. Scrolling with two fingers on a trackpad is a finger of its own: it goes down
 * where the pointer is, moves with the scroll (the run-out of the trackpad too) and comes up a moment after the scrolling stops,
 * so the content follows it and flings as under a real finger. The steps of a trackpad are small: played one by one as swipes
 * they were too short for Android to see a swipe in them, and were taken for taps.
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

    /** What came in for scrolling and has not been played yet, and whether a swipe is being played now (the way without a live touch). */
    private var scrollDx = 0f
    private var scrollDy = 0f
    private var scrolling = false

    /** The live touch is the finger of a scroll, not of the left button, and where that finger is. */
    private var scrollTouch = false
    private var fingerX = 0f
    private var fingerY = 0f
    /** The scrolling stopped: its finger comes up. */
    private val scrollStopped = Runnable {
        val service = TandemAccessibilityService.instance ?: return@Runnable
        endScrollTouch(service)
    }

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
            is TandemMediaInput.Scroll -> scroll(service, input.dx.toFloat(), input.dy.toFloat())
            is TandemMediaInput.Key -> if (input.down) key(service, input.code.toInt(), input.text)
            is TandemMediaInput.Text -> service.type(input.text)
        }
    }

    private fun scroll(service: TandemAccessibilityService, dx: Float, dy: Float) {
        // A drag with the button is going on: that finger is busy.
        if (held) return
        if (liveRefused) {
            scrollDx += dx
            scrollDy += dy
            if (!scrolling) playScroll(service)
            return
        }
        if (live == null || !scrollTouch) {
            scrollTouch = true
            fingerX = x.coerceIn(SCROLL_EDGE, width - SCROLL_EDGE)
            fingerY = y.coerceIn(SCROLL_EDGE, height - SCROLL_EDGE)
            startLive(service, fingerX, fingerY)
        }
        val nx = fingerX + dx * SCROLL_GAIN
        val ny = fingerY + dy * SCROLL_GAIN
        main.removeCallbacks(scrollStopped)
        if (nx !in SCROLL_EDGE..width - SCROLL_EDGE || ny !in SCROLL_EDGE..height - SCROLL_EDGE) {
            // The finger ran out of screen: it comes up where it is, and the next step puts it down again at the pointer.
            endScrollTouch(service)
            return
        }
        fingerX = nx
        fingerY = ny
        follow(service)
        main.postDelayed(scrollStopped, SCROLL_STOP_MS)
    }

    private fun endScrollTouch(service: TandemAccessibilityService) {
        main.removeCallbacks(scrollStopped)
        if (!scrollTouch || live == null) {
            scrollTouch = false
            return
        }
        ending = true
        if (!playing) next(service)
    }

    /** Without a live touch: what was scrolled is played as a swipe the same way, once it is long enough to be seen as one. */
    private fun playScroll(service: TandemAccessibilityService) {
        val dx = (scrollDx * SCROLL_GAIN).coerceIn(-width * 0.6f, width * 0.6f)
        val dy = (scrollDy * SCROLL_GAIN).coerceIn(-height * 0.6f, height * 0.6f)
        if (abs(dx) < SLOP * 2 && abs(dy) < SLOP * 2) return
        scrollDx = 0f
        scrollDy = 0f
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
    /** Counts the pieces played, so the answer about one that was replaced is not taken for the one after it. */
    private var touches = 0
    /** The button came up while a piece was being played: the touch ends after it. */
    private var ending = false
    /** When Android last cancelled a touch that goes on: for a while taps and swipes are played whole, when the button comes up. */
    private var liveRefusedAt = 0L
    private val liveRefused get() = SystemClock.uptimeMillis() - liveRefusedAt < LIVE_RETRY_MS && liveRefusedAt != 0L

    private fun point(fromX: Float, fromY: Float, toX: Float, toY: Float) = Path().apply {
        moveTo(fromX, fromY)
        if (fromX != toX || fromY != toY) lineTo(toX, toY)
    }

    /** Where the finger of the live touch goes: the finger of a scroll, or the pointer. */
    private val aimX get() = if (scrollTouch) fingerX else x
    private val aimY get() = if (scrollTouch) fingerY else y

    private fun startLive(service: TandemAccessibilityService, atX: Float = x, atY: Float = y) {
        val first = GestureDescription.StrokeDescription(point(atX, atY, atX, atY), 0, PIECE_MS, true)
        live = first
        liveX = atX
        liveY = atY
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
        val (toX, toY) = aimX to aimY
        val piece = runCatching { current.continueStroke(point(liveX, liveY, toX, toY), 0, PIECE_MS, !end) }.getOrNull()
        if (piece == null) {
            giveUpLive()
            return
        }
        liveX = toX
        liveY = toY
        if (end) {
            live = null
            ending = false
            scrollTouch = false
        } else {
            live = piece
        }
        play(service, piece)
    }

    private fun play(service: TandemAccessibilityService, piece: GestureDescription.StrokeDescription) {
        playing = true
        val touch = ++touches
        service.stroke(piece) { played ->
            main.post {
                // A touch that was replaced (a press during a scroll) says it was cancelled: that is not Android refusing.
                if (touch != touches) return@post
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
        scrollTouch = false
        liveRefusedAt = SystemClock.uptimeMillis()
    }

    private fun button(service: TandemAccessibilityService, button: Int, down: Boolean) {
        when (button) {
            0 -> if (down) {
                // The finger of a scroll makes way for the one of the button.
                if (scrollTouch) {
                    main.removeCallbacks(scrollStopped)
                    live = null
                    ending = false
                    playing = false
                    scrollTouch = false
                }
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
        /** How long the trackpad has to be still before the finger of a scroll comes up, and how far from the edge it stays. */
        const val SCROLL_STOP_MS = 140L
        const val SCROLL_EDGE = 60f
        const val HID_ENTER = 0x28
        const val HID_ESCAPE = 0x29
        const val HID_BACKSPACE = 0x2A
    }
}
