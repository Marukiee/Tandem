package nl.markmaaktmedia.tandem.watch.sleep

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Says from samples in g whether a wrist moved: a change of posture, or the small shaking of a hand that stays level. Over all axes,
 * because a hand can shake across gravity without the length of the vector changing at all.
 */
internal class WristMotion {
    private var windowStart = -1L
    private var sumX = 0.0
    private var sumY = 0.0
    private var sumZ = 0.0
    private var sumSquares = 0.0
    private var count = 0
    private var previousAngle: Double? = null

    /** True once for each stretch of a few seconds in which the wrist moved. */
    fun add(timeMs: Long, x: Float, y: Float, z: Float): Boolean {
        if (windowStart < 0) windowStart = timeMs
        val moved = timeMs - windowStart >= WINDOW_MS && close(timeMs)
        sumX += x
        sumY += y
        sumZ += z
        sumSquares += x * x + y * y + z * z
        count++
        return moved
    }

    fun clear() {
        windowStart = -1
        previousAngle = null
        emptyWindow()
    }

    private fun close(timeMs: Long): Boolean {
        var moved = false
        if (count > 1) {
            val angle = Math.toDegrees(atan2(sumZ / count, sqrt((sumX / count) * (sumX / count) + (sumY / count) * (sumY / count))))
            val meanSquared = (sumX * sumX + sumY * sumY + sumZ * sumZ) / (count.toDouble() * count)
            val variance = (sumSquares / count - meanSquared).coerceAtLeast(0.0)
            val before = previousAngle
            moved = (before != null && abs(angle - before) > MOVE_DEGREES) || sqrt(variance) > SHAKE_G
            previousAngle = angle
        }
        windowStart = timeMs
        emptyWindow()
        return moved
    }

    private fun emptyWindow() {
        count = 0
        sumX = 0.0
        sumY = 0.0
        sumZ = 0.0
        sumSquares = 0.0
    }

    private companion object {
        const val WINDOW_MS = 5_000L
        const val MOVE_DEGREES = 5.0
        const val SHAKE_G = 0.02
    }
}

/**
 * Wakes someone gently before their alarm, at a moment their wrist moves. People move more in light sleep than in deep sleep, and this
 * asks no more of a wrist than that. It only adds something: the alarm itself still goes off on the phone.
 */
class SmartWake(val alarmMs: Long) {
    private val motion = WristMotion()
    private var done = false

    val windowStartMs: Long get() = alarmMs - WINDOW_MS

    /** Samples in g, in order. True once: now is the time. */
    fun onSample(timeMs: Long, x: Float, y: Float, z: Float): Boolean {
        if (done || timeMs < windowStartMs || timeMs >= alarmMs) return false
        val moved = motion.add(timeMs, x, y, z)
        if (!moved && timeMs < alarmMs - LAST_CHANCE_MS) return false
        done = true
        return true
    }

    companion object {
        const val WINDOW_MS = 30 * 60_000L
        const val LAST_CHANCE_MS = 2 * 60_000L
    }
}
