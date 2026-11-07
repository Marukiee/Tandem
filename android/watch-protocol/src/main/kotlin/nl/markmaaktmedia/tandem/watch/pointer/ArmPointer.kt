package nl.markmaaktmedia.tandem.watch.pointer

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The pointer follows where the forearm points, from the orientation the watch works out from its gyroscope and accelerometer (a game
 * rotation vector). Left and right is the forearm turning around the vertical, up and down it lifting, whatever way the wrist is turned:
 * the pointer that followed the axes of the watch itself went askew as soon as the wrist was turned, and needed odd arm positions. Turning
 * the wrist alone does not move it.
 *
 * The steadying is WearMouse's (github.com/ginkage/wearmouse, Apache License 2.0, by Ivan Podogov): a change of angle smaller than a few
 * pixels is held back, more and more the smaller it is, so a hand that trembles stands still and one that moves is followed at once. So is
 * the speed: a quarter turn is about a screen and a half, the same at any pace, as a computer adds its own acceleration to a mouse.
 */
class ArmPointer(var invertVertical: Boolean = false, var sensitivity: Float = 1f) {
    private var yaw = 0.0
    private var pitch = 0.0
    private var started = false
    private var restX = 0.0
    private var restY = 0.0

    /** A game rotation vector as Android gives it: x, y, z and w of a unit quaternion. */
    fun update(x: Float, y: Float, z: Float, w: Float): PointerDelta? {
        // Where the x axis of the watch points in the world, which on a wrist is along the forearm.
        val fx = 1 - 2 * (y * y + z * z)
        val fy = 2 * (x * y + w * z)
        val fz = 2 * (x * z - w * y)
        val newYaw = atan2(fy.toDouble(), fx.toDouble())
        val newPitch = asin(fz.toDouble().coerceIn(-1.0, 1.0))
        if (newYaw.isNaN() || newPitch.isNaN()) return null
        if (!started) {
            yaw = newYaw
            pitch = newPitch
            started = true
            return null
        }
        val steadyYaw = steady(yaw, newYaw)
        val steadyPitch = steady(pitch, newPitch)
        val turned = wrap(yaw - steadyYaw)
        val lifted = pitch - steadyPitch
        yaw = steadyYaw
        pitch = steadyPitch

        val gain = PIXELS_PER_RADIAN * sensitivity
        // Turning to the right turns the forearm clockwise seen from above, which is a smaller angle.
        restX += turned * gain
        restY += lifted * gain * (if (invertVertical) -1 else 1)
        val dx = restX.roundToInt()
        val dy = restY.roundToInt()
        restX -= dx
        restY -= dy
        return if (dx == 0 && dy == 0) null else PointerDelta(dx, dy)
    }

    /** Starts again from where the arm is now, as after a pause, so the time it rested does not come out as one jump. */
    fun reset() {
        started = false
        restX = 0.0
        restY = 0.0
    }

    private fun steady(old: Double, new: Double): Double {
        val delta = wrap(old - new)
        val hold = (1 - (abs(delta) * PIXELS_PER_RADIAN / STEADY_PIXELS).pow(3)).coerceAtLeast(0.0)
        return new + hold * delta
    }

    private fun wrap(angle: Double): Double {
        var a = angle
        while (a <= -PI) a += 2 * PI
        while (a >= PI) a -= 2 * PI
        return a
    }

    private companion object {
        /** WearMouse's 1024 pixels for an eighth of a turn. */
        const val PIXELS_PER_RADIAN = 1024.0 / (PI / 4)
        const val STEADY_PIXELS = 16.0
    }
}
