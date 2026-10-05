package nl.markmaaktmedia.tandem.live

import android.content.Context
import android.os.SystemClock
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

    fun handle(input: TandemMediaInput) {
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
                // The content follows the fingers, so a scroll of the content is a swipe the same way.
                val dx = input.dx.toFloat().coerceIn(-width / 2, width / 2)
                val dy = input.dy.toFloat().coerceIn(-height / 2, height / 2)
                if (dx != 0f || dy != 0f) service.swipe(x, y, (x + dx).coerceIn(0f, width - 1), (y + dy).coerceIn(0f, height - 1), SCROLL_MS)
            }
            is TandemMediaInput.Key -> if (input.down) key(service, input.code.toInt(), input.text)
            is TandemMediaInput.Text -> service.type(input.text)
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
        const val SCROLL_MS = 220L
        const val HID_ENTER = 0x28
        const val HID_ESCAPE = 0x29
        const val HID_BACKSPACE = 0x2A
    }
}
