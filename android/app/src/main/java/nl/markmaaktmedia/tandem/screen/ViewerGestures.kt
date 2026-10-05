package nl.markmaaktmedia.tandem.screen

import nl.markmaaktmedia.tandem.ui.remote.Touch
import kotlin.math.abs
import kotlin.math.hypot

enum class ViewerFeedback { Click, LongPress, DragStart }

/** What the gestures decided. The screen turns these into input for the Mac and moves for the picture. */
interface ViewerOutput {
    /** The pointer moves by this much, in pixels of the phone's screen. */
    fun pointerMove(dx: Float, dy: Float)

    /** The pointer goes to this place of the screen (touch mode). */
    fun pointerTo(x: Float, y: Float)
    fun button(button: Int, down: Boolean, clicks: Int)
    fun click(button: Int, clicks: Int) {
        button(button, true, clicks)
        button(button, false, clicks)
    }

    /** The content follows the fingers: a finger going down means the content goes down. */
    fun scroll(dx: Float, dy: Float)
    fun zoom(factor: Float, focalX: Float, focalY: Float)
    fun pan(dx: Float, dy: Float)
    fun feedback(kind: ViewerFeedback) {}

    /** The left button is held for a drag, or let go. */
    fun dragging(active: Boolean) {}
}

class ViewerConfig(
    /** How far a finger may wander and still count as held in place. */
    val slop: Float,
    /** A press that lasts this long is a hold: a drag when moved, a plain click when lifted. */
    val holdMs: Long = 350,
    /** How soon after a tap the next touch still belongs to it. */
    val doubleTapMs: Long = 300,
)

/**
 * Turns fingers into what a person does at a computer, on a phone that shows the screen of that computer. No Android in
 * here, so the rules are unit tested and the Compose side only feeds it frames.
 *
 * Trackpad mode (the default), the pointer is somewhere on the screen and the finger steers it:
 * - one finger moves the pointer, a short tap clicks, a second tap soon after is a double click
 * - press and hold, then move: drag with the left button. Held and lifted without moving is a plain click
 * - a tap followed at once by a touch that moves drags too
 * - two fingers: moving together scrolls, moving apart or together zooms the picture, a short tap is a right click
 * - three fingers move the zoomed picture
 *
 * Touch mode, the finger is the pointer: the same, except that a touch goes to the place that is touched, and one finger
 * that moves drags from where it landed.
 *
 * Nothing happens until a finger leaves its slop circle, which is what lets a tap land exactly where it was meant to,
 * and the movement made on the way out of the circle is handed over in one go so nothing is lost.
 */
class ViewerGestures(private val config: ViewerConfig, private val out: ViewerOutput) {
    /** Touch mode instead of trackpad mode. May change between gestures. */
    var direct = false

    private enum class Mode { Pending, Move, Drag, DirectDrag, Two, Scroll, Pinch, Pan, Dead }

    private class Finger(var x: Float, var y: Float) {
        val startX = x
        val startY = y
    }

    private val fingers = LinkedHashMap<Long, Finger>()
    private var primary = -1L
    private var active = false
    private var mode = Mode.Pending
    private var startTime = 0L
    private var maxFingers = 0
    private var beyondSlop = false
    private var armed = false
    private var followsTap = false
    private var lastTapEnd = NEVER
    private var baseDistance = 0f
    private var baseX = 0f
    private var baseY = 0f
    private var lastDistance = 0f
    private var lastX = 0f
    private var lastY = 0f

    /** Feed every pointer event: the fingers that are down right now. An empty list means the last one lifted. */
    fun onFrame(now: Long, touches: List<Touch>) {
        if (touches.isEmpty()) {
            if (active) finish(now)
            return
        }
        if (!active) begin(now)

        var arrived = false
        val present = HashSet<Long>(touches.size * 2)
        for (t in touches) {
            present += t.id
            if (!fingers.containsKey(t.id)) {
                fingers[t.id] = Finger(t.x, t.y)
                if (primary < 0) primary = t.id
                arrived = true
            }
        }
        val left = fingers.keys.retainAll(present)
        if (primary !in fingers) primary = fingers.keys.firstOrNull() ?: -1L
        val count = fingers.size
        if (count > maxFingers) maxFingers = count

        // How far the primary finger went since the last frame.
        val before = previous[primary]
        for (t in touches) fingers[t.id]?.let { it.x = t.x; it.y = t.y }
        val moveX = fingers[primary]?.let { f -> before?.let { f.x - it.first } } ?: 0f
        val moveY = fingers[primary]?.let { f -> before?.let { f.y - it.second } } ?: 0f
        previous = fingers.mapValues { it.value.x to it.value.y }
        val (cx, cy) = centre()

        if (!beyondSlop && fingers[primary]?.let { hypot(it.x - it.startX, it.y - it.startY) > config.slop } == true) {
            beyondSlop = true
        }

        // A change in the number of fingers during a gesture that only used one is a new gesture of two or three.
        if (arrived || left) {
            when {
                count >= 3 && mode != Mode.Drag && mode != Mode.DirectDrag -> {
                    mode = Mode.Pan
                    lastX = cx
                    lastY = cy
                    return
                }
                count == 2 && (mode == Mode.Pending || mode == Mode.Move) && arrived -> {
                    mode = Mode.Two
                    val (a, b) = twoFingers()
                    baseDistance = hypot(a.x - b.x, a.y - b.y)
                    lastDistance = baseDistance
                    baseX = cx
                    baseY = cy
                    lastX = cx
                    lastY = cy
                    return
                }
                (mode == Mode.Scroll || mode == Mode.Pinch || mode == Mode.Pan || mode == Mode.Two) && count < 2 -> {
                    mode = Mode.Dead
                    return
                }
                (mode == Mode.Scroll || mode == Mode.Pinch || mode == Mode.Two) && count == 2 -> {
                    // A third finger came and went: pick the two-finger gesture up again from here.
                    lastX = cx
                    lastY = cy
                    val (a, b) = twoFingers()
                    lastDistance = hypot(a.x - b.x, a.y - b.y)
                    return
                }
                mode == Mode.Pan && count == 2 -> {
                    mode = Mode.Dead
                    return
                }
            }
        }

        when (mode) {
            Mode.Pending -> if (beyondSlop && count == 1) {
                val finger = fingers.getValue(primary)
                val totalX = finger.x - finger.startX
                val totalY = finger.y - finger.startY
                when {
                    armed || followsTap -> beginDrag(finger, totalX, totalY)
                    direct -> beginDrag(finger, totalX, totalY)
                    else -> {
                        mode = Mode.Move
                        out.pointerMove(totalX, totalY)
                    }
                }
            }

            Mode.Move -> out.pointerMove(moveX, moveY)
            Mode.Drag -> out.pointerMove(moveX, moveY)
            Mode.DirectDrag -> fingers[primary]?.let { out.pointerTo(it.x, it.y) }

            Mode.Two -> {
                val (a, b) = twoFingers()
                val distance = hypot(a.x - b.x, a.y - b.y)
                val spread = abs(distance - baseDistance)
                val shift = hypot(cx - baseX, cy - baseY)
                if (maxOf(spread, shift) > config.slop * 1.2f) {
                    if (spread >= shift * 0.9f) {
                        mode = Mode.Pinch
                        out.pan(cx - baseX, cy - baseY)
                        if (baseDistance > 0f) out.zoom(distance / baseDistance, cx, cy)
                    } else {
                        mode = Mode.Scroll
                        out.scroll(cx - baseX, cy - baseY)
                    }
                    lastDistance = distance
                    lastX = cx
                    lastY = cy
                }
            }

            Mode.Scroll -> {
                out.scroll(cx - lastX, cy - lastY)
                lastX = cx
                lastY = cy
            }

            Mode.Pinch -> {
                val (a, b) = twoFingers()
                val distance = hypot(a.x - b.x, a.y - b.y)
                out.pan(cx - lastX, cy - lastY)
                if (lastDistance > 0f && distance > 0f) out.zoom(distance / lastDistance, cx, cy)
                lastDistance = distance
                lastX = cx
                lastY = cy
            }

            Mode.Pan -> {
                out.pan(cx - lastX, cy - lastY)
                lastX = cx
                lastY = cy
            }

            Mode.Dead -> Unit
        }
    }

    private var previous: Map<Long, Pair<Float, Float>> = emptyMap()

    /** When [onTimer] wants to be called, or null when nothing waits on the clock. */
    fun deadline(): Long? =
        if (active && mode == Mode.Pending && fingers.size == 1 && maxFingers == 1 && !armed) startTime + config.holdMs else null

    /** A finger that stays put for [ViewerConfig.holdMs] is a hold. */
    fun onTimer(now: Long) {
        val due = deadline() ?: return
        if (now >= due && !beyondSlop) {
            armed = true
            out.feedback(ViewerFeedback.LongPress)
        }
    }

    /** The gesture was interrupted. Anything held goes back up so nothing stays stuck. */
    fun cancel() {
        if (!active) return
        if (mode == Mode.Drag || mode == Mode.DirectDrag) letGoOfDrag()
        reset()
        lastTapEnd = NEVER
    }

    private fun begin(now: Long) {
        active = true
        mode = Mode.Pending
        startTime = now
        maxFingers = 0
        beyondSlop = false
        armed = false
        followsTap = now - lastTapEnd <= config.doubleTapMs
        fingers.clear()
        primary = -1L
        previous = emptyMap()
    }

    private fun finish(now: Long) {
        val quick = now - startTime < config.holdMs
        when (mode) {
            Mode.Drag, Mode.DirectDrag -> {
                letGoOfDrag()
                lastTapEnd = NEVER
            }

            Mode.Pending -> {
                val finger = fingers[primary] ?: fingers.values.firstOrNull()
                when {
                    // A hold that was lifted without moving is a plain click: holding is for dragging, as on the trackpad
                    // page, and the right button is two fingers.
                    maxFingers == 1 && (armed || !quick) -> {
                        out.feedback(ViewerFeedback.Click)
                        if (direct && finger != null) out.pointerTo(finger.x, finger.y)
                        out.click(0, 1)
                        lastTapEnd = NEVER
                    }

                    maxFingers == 1 -> {
                        out.feedback(ViewerFeedback.Click)
                        if (direct && finger != null) out.pointerTo(finger.x, finger.y)
                        out.click(0, if (followsTap) 2 else 1)
                        lastTapEnd = if (followsTap) NEVER else now
                    }

                    else -> lastTapEnd = NEVER
                }
            }

            Mode.Two -> {
                // Two fingers that tapped and did not move: a right click, as on a trackpad.
                if (quick) {
                    out.feedback(ViewerFeedback.Click)
                    if (direct) {
                        val (cx, cy) = centre()
                        out.pointerTo(cx, cy)
                    }
                    out.click(1, 1)
                }
                lastTapEnd = NEVER
            }

            else -> lastTapEnd = NEVER
        }
        reset()
    }

    private var lastCentre = 0f to 0f

    private fun reset() {
        active = false
        mode = Mode.Pending
        fingers.clear()
        primary = -1L
        maxFingers = 0
        beyondSlop = false
        armed = false
        followsTap = false
        previous = emptyMap()
    }

    private fun beginDrag(finger: Finger, totalX: Float, totalY: Float) {
        if (direct) {
            out.pointerTo(finger.startX, finger.startY)
            mode = Mode.DirectDrag
        } else {
            mode = Mode.Drag
        }
        out.button(0, true, 1)
        out.feedback(ViewerFeedback.DragStart)
        out.dragging(true)
        if (direct) out.pointerTo(finger.x, finger.y) else out.pointerMove(totalX, totalY)
    }

    private fun letGoOfDrag() {
        out.button(0, false, 1)
        out.dragging(false)
    }

    private fun centre(): Pair<Float, Float> {
        if (fingers.isEmpty()) return lastCentre
        var x = 0f
        var y = 0f
        for (f in fingers.values) {
            x += f.x
            y += f.y
        }
        lastCentre = (x / fingers.size) to (y / fingers.size)
        return lastCentre
    }

    private fun twoFingers(): Pair<Finger, Finger> {
        val list = fingers.values.toList()
        return list[0] to list[1]
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE / 4
    }
}
