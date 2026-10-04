package nl.markmaaktmedia.tandem.ui.remote

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One finger on the pad. Positions are in pixels, [id] is stable while the finger stays down. */
data class Touch(val id: Long, val x: Float, val y: Float)

enum class SwipeDirection { Up, Down, Left, Right }

/** The moments that get a small physical answer in the hand. */
enum class PadFeedback { Click, DragStart, Swipe }

/** What the classifier decided. The screen turns these into wire messages. */
interface TouchpadOutput {
    fun pointer(dx: Int, dy: Int)
    fun scroll(dx: Int, dy: Int)
    fun click(button: Int, count: Int)
    fun button(button: Int, down: Boolean)
    fun swipe(direction: SwipeDirection)
    fun feedback(kind: PadFeedback) {}

    /** The left button went down for a drag ([active]) or came up again, so the screen can show that it is held. */
    fun dragging(active: Boolean) {}
}

class TouchpadConfig(
    /** How far a finger may wander and still count as held in place. */
    val slop: Float,
    /** How far three fingers travel before it counts as a swipe. */
    val swipeDistance: Float,
    /**
     * A press shorter than this is a tap, one that lasts this long picks the pointer up.
     * Like pressing a mouse button: the finger stays put for a moment, the button goes down,
     * and moving the same finger drags. Long enough that a tap or a pause before a move
     * never does it, short enough that it feels like part of the touch and not a wait.
     */
    val holdMs: Long = 280,
    /** How soon after a tap the next touch still belongs to it. */
    val doubleTapMs: Long = 300,
    /**
     * Fingers of one deliberate gesture land within a moment of each other. A third
     * finger that arrives later than this is a resting hand, not a swipe.
     */
    val swipeLandingMs: Long = 250,
)

/**
 * Turns raw fingers into trackpad gestures. No Android in here, so the rules are
 * unit tested and the Compose side only has to feed it frames.
 *
 * The gesture decides itself from the first touch until the last finger leaves:
 *
 * - one finger moves the pointer, a short press is a click, a second short press
 *   right after is a double click
 * - a press that stays put for [TouchpadConfig.holdMs], or a touch right after a tap
 *   that then moves or lasts, holds the left button down so the moves of that finger
 *   drag, and lifting it lets go
 * - two fingers scroll, a short two finger press is a right click
 * - three fingers landing together and travelling swipe (Mission Control and spaces)
 *
 * Nothing moves until a finger has left its slop circle. That is what lets a tap
 * land exactly where the pointer was, and the movement made on the way out of the
 * circle is handed over in one go so the pointer does not lose it.
 */
class TouchpadClassifier(
    private val config: TouchpadConfig,
    private val out: TouchpadOutput,
) {
    private enum class Mode { Pending, Move, Scroll, Drag, Swipe, Dead }

    private class Finger(var x: Float, var y: Float) {
        val startX = x
        val startY = y
    }

    private val fingers = LinkedHashMap<Long, Finger>()
    private var active = false
    private var mode = Mode.Pending
    private var startTime = 0L
    private var maxFingers = 0
    private var beyondSlop = false
    private var followsTap = false
    private var lastTapEnd = NEVER
    private var carryX = 0f
    private var carryY = 0f
    private var originX = 0f
    private var originY = 0f

    /**
     * Feed every pointer event: the fingers that are down right now. An empty list
     * means the last one just lifted.
     */
    fun onFrame(now: Long, touches: List<Touch>) {
        if (touches.isEmpty()) {
            if (active) finish(now)
            return
        }
        if (!active) begin(now)

        var sumDx = 0f
        var sumDy = 0f
        var stayed = 0
        var arrived = false
        val present = HashSet<Long>(touches.size * 2)
        for (t in touches) {
            present += t.id
            val f = fingers[t.id]
            if (f == null) {
                fingers[t.id] = Finger(t.x, t.y)
                arrived = true
            } else {
                sumDx += t.x - f.x
                sumDy += t.y - f.y
                stayed++
                f.x = t.x
                f.y = t.y
            }
        }
        val left = fingers.keys.retainAll(present)
        val count = fingers.size
        if (count > maxFingers) maxFingers = count
        // Only fingers that were already down count towards movement. One that just
        // landed has not moved yet, and averaging it in would halve the first frame.
        val dx = if (stayed > 0) sumDx / stayed else 0f
        val dy = if (stayed > 0) sumDy / stayed else 0f

        if (!beyondSlop && fingers.values.any { hypot(it.x - it.startX, it.y - it.startY) > config.slop }) {
            beyondSlop = true
        }

        // A third finger that lands with the others is a swipe, even if the first two
        // already crept past the slop while it was on its way down.
        if (count >= 3 && arrived && mode != Mode.Drag && mode != Mode.Swipe &&
            mode != Mode.Dead && now - startTime <= config.swipeLandingMs
        ) {
            enterSwipe()
            return
        }

        when (mode) {
            Mode.Pending -> {
                if (count >= 3) {
                    mode = Mode.Dead
                } else if (beyondSlop) when {
                    count >= 2 -> {
                        mode = Mode.Scroll
                        scroll(meanTotalX(), meanTotalY())
                    }
                    maxFingers == 1 && followsTap -> beginDrag(meanTotalX(), meanTotalY())
                    maxFingers == 1 -> {
                        mode = Mode.Move
                        pointer(meanTotalX(), meanTotalY())
                    }
                    // One finger left of a two finger touch. Lifting one must not send
                    // the pointer off, so the rest of the touch does nothing.
                    else -> mode = Mode.Dead
                }
            }

            Mode.Move -> {
                if (count >= 2) {
                    mode = Mode.Scroll
                    resetCarry()
                    scroll(dx, dy)
                } else {
                    pointer(dx, dy)
                }
            }

            Mode.Scroll -> if (count >= 2) scroll(dx, dy)

            Mode.Drag -> pointer(dx, dy)

            Mode.Swipe -> {
                if (count < 3) {
                    mode = Mode.Dead
                } else if (arrived || left) {
                    setOrigin()
                } else {
                    checkSwipe()
                }
            }

            Mode.Dead -> Unit
        }
    }

    /** When [onTimer] wants to be called, or null when nothing is waiting on the clock. */
    fun deadline(): Long? =
        if (active && mode == Mode.Pending && fingers.size == 1 && maxFingers == 1) startTime + config.holdMs else null

    /** A finger that stays put for [TouchpadConfig.holdMs] picks the pointer up. */
    fun onTimer(now: Long) {
        val due = deadline() ?: return
        if (now >= due) beginDrag(0f, 0f)
    }

    /** The gesture was interrupted. Anything held down goes back up so nothing stays stuck. */
    fun cancel() {
        // A finished gesture calls this too, and must keep the tap it may follow.
        if (!active) return
        if (mode == Mode.Drag) letGoOfDrag()
        reset()
        lastTapEnd = NEVER
    }

    private fun begin(now: Long) {
        active = true
        mode = Mode.Pending
        startTime = now
        maxFingers = 0
        beyondSlop = false
        followsTap = now - lastTapEnd <= config.doubleTapMs
        fingers.clear()
        resetCarry()
    }

    private fun finish(now: Long) {
        when (mode) {
            Mode.Drag -> {
                letGoOfDrag()
                lastTapEnd = NEVER
            }

            Mode.Pending -> {
                val quick = now - startTime < config.holdMs
                when {
                    !quick -> lastTapEnd = NEVER
                    maxFingers == 1 && followsTap -> {
                        out.feedback(PadFeedback.Click)
                        out.click(0, 2)
                        lastTapEnd = NEVER
                    }
                    maxFingers == 1 -> {
                        out.feedback(PadFeedback.Click)
                        out.click(0, 1)
                        lastTapEnd = now
                    }
                    maxFingers == 2 -> {
                        out.feedback(PadFeedback.Click)
                        out.click(1, 1)
                        lastTapEnd = NEVER
                    }
                    else -> lastTapEnd = NEVER
                }
            }

            else -> lastTapEnd = NEVER
        }
        reset()
    }

    private fun reset() {
        active = false
        mode = Mode.Pending
        fingers.clear()
        maxFingers = 0
        beyondSlop = false
        followsTap = false
        resetCarry()
    }

    private fun beginDrag(totalX: Float, totalY: Float) {
        mode = Mode.Drag
        resetCarry()
        out.button(0, true)
        out.feedback(PadFeedback.DragStart)
        out.dragging(true)
        // What the finger did on the way out of the slop circle belongs to the drag.
        pointer(totalX, totalY)
    }

    private fun letGoOfDrag() {
        out.button(0, false)
        out.dragging(false)
    }

    private fun enterSwipe() {
        mode = Mode.Swipe
        resetCarry()
        setOrigin()
    }

    private fun setOrigin() {
        originX = fingers.values.map { it.x }.average().toFloat()
        originY = fingers.values.map { it.y }.average().toFloat()
    }

    private fun checkSwipe() {
        val cx = fingers.values.map { it.x }.average().toFloat()
        val cy = fingers.values.map { it.y }.average().toFloat()
        val dx = cx - originX
        val dy = cy - originY
        val major = max(abs(dx), abs(dy))
        val minor = min(abs(dx), abs(dy))
        // A diagonal waits for the finger to make up its mind instead of guessing.
        if (major < config.swipeDistance || major < minor * 1.3f) return
        val direction = if (abs(dx) > abs(dy)) {
            if (dx < 0) SwipeDirection.Left else SwipeDirection.Right
        } else {
            if (dy < 0) SwipeDirection.Up else SwipeDirection.Down
        }
        mode = Mode.Dead
        out.feedback(PadFeedback.Swipe)
        out.swipe(direction)
    }

    private fun meanTotalX() = fingers.values.map { it.x - it.startX }.average().toFloat()
    private fun meanTotalY() = fingers.values.map { it.y - it.startY }.average().toFloat()

    private fun resetCarry() {
        carryX = 0f
        carryY = 0f
    }

    // The wire carries whole pixels. The fraction is kept, so a slow drag that moves
    // less than a pixel per frame still adds up instead of rounding to nothing.
    private fun pointer(dx: Float, dy: Float) {
        val (x, y) = takeWhole(dx, dy)
        if (x != 0 || y != 0) out.pointer(x, y)
    }

    private fun scroll(dx: Float, dy: Float) {
        val (x, y) = takeWhole(dx, dy)
        if (x != 0 || y != 0) out.scroll(x, y)
    }

    private fun takeWhole(dx: Float, dy: Float): Pair<Int, Int> {
        carryX += dx
        carryY += dy
        val x = carryX.roundToInt()
        val y = carryY.roundToInt()
        carryX -= x
        carryY -= y
        return x to y
    }

    private companion object {
        // Far enough back that "now minus this" cannot overflow and is never a double tap.
        const val NEVER = Long.MIN_VALUE / 4
    }
}
