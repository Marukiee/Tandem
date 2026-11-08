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

/**
 * One buzz without a break over the minutes before the alarm, in the shape Mark drew: from nothing up to a soft hill, softer again, then
 * up to its highest and easing off as the alarm takes over. Never to full strength while someone may be waking by themselves: that is
 * only for after the alarm, for someone who still has not moved. Between the points it eases in and out, so nowhere does it jolt. The
 * pulses that went to full strength from the first one woke with a start, which is what this is not for.
 */
object WakeSwell {
    /** Short enough that the steps of strength are not felt. */
    const val STEP_MS = 200L

    /** After the alarm, for someone who still has not moved: up to full strength in this time, and held. */
    const val RISE_AFTER_MS = 2 * 60_000L

    /** And no longer than this after the alarm: a watch on a nightstand should not buzz all morning. */
    const val MAX_AFTER_MS = 10 * 60_000L

    /** Too soft to feel on most wrists: it begins from nothing. */
    private const val SOFTEST = 6
    private const val STRONGEST = 255

    /** A motor's first part of its range is felt as much more than its share, so the drawing is bent down before it is a strength. */
    private const val FEEL = 1.5

    /** The highest it goes before the alarm: about half of what the motor can, so the second hill is not the strongest. */
    private const val BEFORE_ALARM = 0.62

    /** The drawing: how far along the window, and how strong, from 0 to 1 of its highest. */
    private val POINTS = listOf(0.0 to 0.0, 0.06 to 0.0, 0.31 to 0.44, 0.5 to 0.15, 0.83 to 1.0, 1.0 to 0.38)

    /** How strong at [share] of the window, from 0 to 1 of the motor, eased between the points of the drawing. */
    fun strength(share: Double): Double {
        val at = share.coerceIn(0.0, 1.0)
        val after = POINTS.indexOfFirst { it.first >= at }.coerceAtLeast(1)
        val (x0, y0) = POINTS[after - 1]
        val (x1, y1) = POINTS[after]
        return BEFORE_ALARM * ease(y0, y1, if (x1 > x0) (at - x0) / (x1 - x0) else 1.0)
    }

    /** How strong [msAfter] the alarm, for someone who has not moved: from where the drawing ended, eased up to full and held there. */
    fun strengthAfter(msAfter: Long): Double = ease(strength(1.0), 1.0, msAfter.toDouble() / RISE_AFTER_MS)

    /** The motor's strength for a strength from 0 to 1: 1 to 255, never 0, which would be a break. */
    fun amplitude(strength: Double): Int =
        Math.round(SOFTEST + (STRONGEST - SOFTEST) * Math.pow(strength.coerceIn(0.0, 1.0), FEEL)).toInt().coerceIn(1, STRONGEST)

    /**
     * The rest of a window of [windowMs] from [fromMs] into it, and then [afterMs] past the alarm, as the timings and strengths of one
     * waveform: one buzz, so there is no seam at the alarm. A window that began before the watch got to it picks up where it is by then.
     */
    fun waveform(fromMs: Long, windowMs: Long, afterMs: Long = 0): Pair<LongArray, IntArray> {
        val start = fromMs.coerceIn(0, windowMs)
        val before = ((windowMs - start) / STEP_MS).toInt()
        val steps = (before + (afterMs / STEP_MS).toInt()).coerceAtLeast(1)
        val timings = LongArray(steps) { STEP_MS }
        val amplitudes = IntArray(steps) { step ->
            val at = start + step * STEP_MS + STEP_MS / 2
            amplitude(if (at < windowMs) strength(at.toDouble() / windowMs) else strengthAfter(at - windowMs))
        }
        return timings to amplitudes
    }

    private fun ease(from: Double, to: Double, share: Double): Double =
        from + (to - from) * (1 - kotlin.math.cos(Math.PI * share.coerceIn(0.0, 1.0))) / 2
}

/**
 * Says whether the wrist moved after the alarm went off: someone who reaches for the phone is awake, and the watch need not go on. Before
 * the alarm a sleeper turning over is no sign of anything.
 */
class AwakeAfterAlarm(private val alarmMs: Long) {
    private val motion = WristMotion()

    var awake = false
        private set

    /** A sample of the accelerometer in g. True from the first moment it moved after the alarm. */
    fun add(timeMs: Long, x: Float, y: Float, z: Float): Boolean {
        if (awake || timeMs < alarmMs) return awake
        if (motion.add(timeMs, x, y, z)) awake = true
        return awake
    }
}
