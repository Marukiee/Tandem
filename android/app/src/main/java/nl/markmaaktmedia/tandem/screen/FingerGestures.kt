package nl.markmaaktmedia.tandem.screen

import nl.markmaaktmedia.tandem.ui.remote.Touch
import kotlin.math.hypot

/**
 * The fingers on the picture of another phone: the finger is a finger there. It goes down at once where it lands, follows, and comes up
 * where it lifts, so the other phone makes a tap, a long press, a swipe or a drag of it the way its own screen would (`RemoteInput` plays
 * it as a touch that goes on). No Android in here, so the rules are unit tested.
 *
 * - Within the slop circle the finger stays where it landed, so a tap or a long press does not wobble.
 * - One finger: the other phone gets one pointer, so a second finger is left alone. A two-finger tap is never Back there.
 * - A touch that lands beside the picture does nothing until all fingers are up.
 */
class FingerGestures(
    private val slop: Float,
    private val out: ViewerOutput,
    /** Whether a point of the view is on the picture. */
    private val onPicture: (Float, Float) -> Boolean,
) {
    private var finger = NONE
    private var startX = 0f
    private var startY = 0f
    private var moved = false
    private var ignoring = false

    /** Every pointer event: the fingers that are down right now. An empty list means the last one lifted. */
    fun onFrame(touches: List<Touch>) {
        if (finger == NONE) {
            if (touches.isEmpty()) {
                ignoring = false
                return
            }
            if (ignoring) return
            val first = touches.first()
            if (!onPicture(first.x, first.y)) {
                ignoring = true
                return
            }
            finger = first.id
            startX = first.x
            startY = first.y
            moved = false
            out.pointerTo(first.x, first.y)
            out.button(0, true, 1)
            return
        }
        val current = touches.firstOrNull { it.id == finger }
        if (current == null) {
            lift()
            // Another finger stays down: it is not a new touch, so it waits until all are up.
            ignoring = touches.isNotEmpty()
            return
        }
        if (!moved && hypot(current.x - startX, current.y - startY) > slop) moved = true
        if (moved) out.pointerTo(current.x, current.y)
    }

    /** The gesture was interrupted: a finger that is down comes up, so nothing stays pressed there. */
    fun cancel() {
        if (finger != NONE) lift()
        ignoring = false
    }

    private fun lift() {
        finger = NONE
        out.button(0, false, 1)
    }

    private companion object {
        const val NONE = Long.MIN_VALUE
    }
}
