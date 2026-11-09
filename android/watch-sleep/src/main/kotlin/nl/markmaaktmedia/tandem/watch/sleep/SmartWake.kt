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
 * One buzz without a break over the minutes before the alarm that rises and falls a few times, calmly, each time a little more. It never
 * gets strong: it only wakes someone a little, ahead of the alarm, which wakes them the rest of the way. Between its points it eases in
 * and out, so nowhere does it jolt. The pulses that went to full strength from the first one woke with a start, and so did a swell up to
 * half the motor, which is what this is not for.
 */
object WakeSwell {
    /** Short enough that the steps of strength are not felt. */
    const val STEP_MS = 200L

    /** Too soft to feel on most wrists: it begins from nothing. */
    private const val SOFTEST = 6
    private const val STRONGEST = 255

    /** A motor's first part of its range is felt as much more than its share, so a strength is bent down before it drives the motor. */
    private const val FEEL = 1.5

    /**
     * Four slow waves, each a little higher than the one before and never above two fifths of the motor's strength; between them it
     * stays on, softly, so it is one buzz. How far along the window, and how strong, from 0 to 1.
     */
    private val POINTS = listOf(
        0.0 to 0.0,
        0.125 to 0.16, 0.25 to 0.05,
        0.375 to 0.24, 0.5 to 0.07,
        0.625 to 0.32, 0.75 to 0.09,
        0.875 to 0.40, 1.0 to 0.2,
    )

    /** How strong at [share] of the window, from 0 to 1 of the motor, eased between the points. */
    fun strength(share: Double): Double {
        val at = share.coerceIn(0.0, 1.0)
        val after = POINTS.indexOfFirst { it.first >= at }.coerceAtLeast(1)
        val (x0, y0) = POINTS[after - 1]
        val (x1, y1) = POINTS[after]
        val u = if (x1 > x0) (at - x0) / (x1 - x0) else 1.0
        return y0 + (y1 - y0) * (1 - kotlin.math.cos(Math.PI * u.coerceIn(0.0, 1.0))) / 2
    }

    /** The motor's strength for a strength from 0 to 1: 1 to 255, never 0, which would be a break. */
    fun amplitude(strength: Double): Int =
        Math.round(SOFTEST + (STRONGEST - SOFTEST) * Math.pow(strength.coerceIn(0.0, 1.0), FEEL)).toInt().coerceIn(1, STRONGEST)

    /** The top of the swell as drawn, two fifths of the motor; a person's own choice scales the whole swell to their top instead. */
    const val DRAWN_TOP_PERCENT = 40

    /**
     * The rest of a window of [windowMs] from [fromMs] into it, as the timings and strengths of one waveform: one buzz, without seams. A
     * window that began before the watch got to it picks up where it is by then. [topPercent] is how strong its highest wave is, in per
     * cent of the motor: the same shape, softer or stronger, as the person chose.
     */
    fun waveform(fromMs: Long, windowMs: Long, topPercent: Int = DRAWN_TOP_PERCENT): Pair<LongArray, IntArray> {
        val start = fromMs.coerceIn(0, windowMs)
        val steps = ((windowMs - start) / STEP_MS).toInt().coerceAtLeast(1)
        val scale = topPercent.coerceIn(1, 100).toDouble() / DRAWN_TOP_PERCENT
        val timings = LongArray(steps) { STEP_MS }
        val amplitudes = IntArray(steps) { step -> amplitude(scale * strength((start + step * STEP_MS + STEP_MS / 2).toDouble() / windowMs)) }
        return timings to amplitudes
    }
}
