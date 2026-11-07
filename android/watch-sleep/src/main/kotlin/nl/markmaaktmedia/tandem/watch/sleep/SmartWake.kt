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
 * Starts waking someone gently a few minutes before their alarm. It only adds something: the alarm itself still goes off on the phone.
 */
class SmartWake(val alarmMs: Long) {
    private var done = false

    val windowStartMs: Long get() = alarmMs - WINDOW_MS

    /** True once: now is the time. */
    fun isTime(timeMs: Long): Boolean {
        if (done || timeMs < windowStartMs || timeMs >= alarmMs) return false
        done = true
        return true
    }

    companion object {
        const val WINDOW_MS = 5 * 60_000L
    }
}

/** A long buzz that grows from barely there to full strength, so it reaches someone before it startles them. */
object WakePulse {
    const val LENGTH_MS = 7_000L
    private const val STEP_MS = 200L
    private const val SOFTEST = 25
    private const val STRONGEST = 255
    private val steps = (LENGTH_MS / STEP_MS).toInt()

    val timings: LongArray get() = LongArray(steps) { STEP_MS }

    /** Slow at first: the first part of a motor's range is what is felt as soft. */
    val amplitudes: IntArray
        get() = IntArray(steps) { step ->
            val done = step.toFloat() / (steps - 1)
            (SOFTEST + (STRONGEST - SOFTEST) * done * done).toInt()
        }
}
