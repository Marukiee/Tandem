package nl.markmaaktmedia.tandem.watch.pointer

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.hypot

data class PointerDelta(val dx: Int, val dy: Int)

/**
 * Turns the turning of a forearm into pointer movement. Same idea as the phone's presentation pointer in the app, with
 * two additions a wrist needs: a resting arm is never quite still, and a pointer has to be both precise and able to
 * cross a screen with a small movement.
 *
 * Assumes the usual way of wearing it, with the crown towards the hand: the forearm turning sideways is a turn around
 * the axis out of the screen, and lifting it is a turn around the axis across the wrist. With the crown towards the
 * elbow lifting turns the other way, which is what [invertVertical] is for.
 */
class GyroPointer(var invertVertical: Boolean = false, var sensitivity: Float = 1f) {
    private var lastNs = 0L
    private var yaw = 0f
    private var pitch = 0f
    private var carryX = 0f
    private var carryY = 0f

    /** Gyroscope values in radians per second, in the watch's own axes. Null when nothing whole has moved yet. */
    fun update(timestampNs: Long, axisAcrossWrist: Float, axisOutOfScreen: Float): PointerDelta? {
        val previous = lastNs
        lastNs = timestampNs
        if (previous == 0L || timestampNs <= previous) return null
        // Samples held back by a busy watch must not make the pointer jump.
        val seconds = ((timestampNs - previous) / 1e9f).coerceAtMost(MAX_STEP_SECONDS)

        // Heavy smoothing while aiming, light while crossing the screen: a hand trembles a little all the time, and what a slow
        // pointer shows of that is shaking, while a fast one has to follow the arm without lag.
        val cutoff = MIN_CUTOFF_HZ + CUTOFF_PER_RADIAN * hypot(yaw, pitch)
        val follow = 1f - exp(-seconds * 2f * PI.toFloat() * cutoff)
        yaw += (axisOutOfScreen - yaw) * follow
        pitch += (axisAcrossWrist - pitch) * follow
        val speed = hypot(yaw, pitch)
        if (speed < DEAD_ZONE) {
            // Whatever is left over belongs to a movement that has ended.
            carryX = 0f
            carryY = 0f
            return null
        }
        // Taking the dead zone off the speed, rather than cutting at it, avoids a jolt when a movement starts.
        val kept = (speed - DEAD_ZONE) / speed
        val fast = ((speed - DEAD_ZONE) / FAST_SPEED).coerceIn(0f, 1f)
        val gain = (SLOW_PIXELS_PER_RADIAN + (FAST_PIXELS_PER_RADIAN - SLOW_PIXELS_PER_RADIAN) * fast) * sensitivity

        carryX += -yaw * kept * seconds * gain
        carryY += (if (invertVertical) -pitch else pitch) * kept * seconds * gain
        val dx = carryX.toInt()
        val dy = carryY.toInt()
        if (dx == 0 && dy == 0) return null
        carryX -= dx
        carryY -= dy
        return PointerDelta(dx, dy)
    }

    /** After a pause: what happened in between says nothing about now. */
    fun reset() {
        lastNs = 0L
        yaw = 0f
        pitch = 0f
        carryX = 0f
        carryY = 0f
    }

    private companion object {
        /** Radians per second. An arm held still by a person moves a little less than this. */
        const val DEAD_ZONE = 0.04f
        /** Below the trembling of a hand at rest, which is around 8 to 12 times a second. */
        const val MIN_CUTOFF_HZ = 3.5f
        const val CUTOFF_PER_RADIAN = 10f
        const val MAX_STEP_SECONDS = 0.05f
        const val FAST_SPEED = 1.5f

        /** Around the 1400 of the phone's pointer: slower when aiming, faster when crossing a screen. */
        const val SLOW_PIXELS_PER_RADIAN = 900f
        const val FAST_PIXELS_PER_RADIAN = 2800f
    }
}

/** The crown as a row of steps, for what moves one at a time such as slides. */
class CrownSteps(private val pixelsPerStep: Float = 120f) {
    private var carry = 0f

    fun update(pixels: Float): Int {
        carry += pixels
        val steps = (carry / pixelsPerStep).toInt()
        carry -= steps * pixelsPerStep
        return steps
    }
}
