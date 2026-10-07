package nl.markmaaktmedia.tandem.live

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.hypot
import uniffi.tandem_core.TandemMediaInput

/**
 * Turns what a computer's mouse and keyboard do into touches on this phone. The pointer is somewhere on the screen, the
 * left button is the finger: pressed and let go in the same place is a tap, held long a long press, and moved a swipe.
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
            }
            is TandemMediaInput.PointerRel -> {
                x = (x + input.dx).coerceIn(0f, width - 1)
                y = (y + input.dy).coerceIn(0f, height - 1)
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

    private fun button(service: TandemAccessibilityService, button: Int, down: Boolean) {
        when (button) {
            0 -> if (down) {
                held = true
                downX = x
                downY = y
                downAt = SystemClock.uptimeMillis()
            } else if (held) {
                held = false
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
