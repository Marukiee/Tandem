package nl.markmaaktmedia.tandem.screen

import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Finger movement on the phone as movement on the Mac. The wire carries whole pixels of the streamed picture, so a slow
 * move that is less than a pixel per event is kept and added to the next one instead of rounding away.
 */
class PointerMapper(
    /** 1 is as fast as the picture on the screen; the person can set it higher or lower. */
    var speed: Float = 1f,
) {
    private var carryX = 0f
    private var carryY = 0f

    /**
     * A move of the finger in pixels of the screen, as a move of the pointer in pixels of the picture. [scale] is the
     * number of screen pixels per picture pixel. A fast flick goes further than a slow one, up to about twice as far,
     * so the whole screen can be crossed without lifting the finger and the last few pixels stay precise.
     */
    fun move(dx: Float, dy: Float, scale: Float): Pair<Int, Int> {
        val factor = if (scale > 0f) 1f / scale else 1f
        val gain = speed * (1f + min(hypot(dx, dy) / 40f, 1.2f))
        return take(dx * factor * gain, dy * factor * gain)
    }

    /** The same without the extra reach: scrolling follows the fingers one to one. */
    fun scroll(dx: Float, dy: Float, scale: Float): Pair<Int, Int> {
        val factor = if (scale > 0f) 1f / scale else 1f
        return take(dx * factor, dy * factor)
    }

    fun reset() {
        carryX = 0f
        carryY = 0f
    }

    private fun take(dx: Float, dy: Float): Pair<Int, Int> {
        carryX += dx
        carryY += dy
        val x = carryX.roundToInt().coerceIn(-Short.MAX_VALUE.toInt(), Short.MAX_VALUE.toInt())
        val y = carryY.roundToInt().coerceIn(-Short.MAX_VALUE.toInt(), Short.MAX_VALUE.toInt())
        carryX -= x
        carryY -= y
        return x to y
    }
}
