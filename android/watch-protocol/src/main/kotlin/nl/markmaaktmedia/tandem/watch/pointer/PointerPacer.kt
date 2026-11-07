package nl.markmaaktmedia.tandem.watch.pointer

import kotlin.math.roundToInt

/**
 * Evens out the movement of a watch pointer on the phone. Bluetooth brings it in bursts, a few dozen times a second and never evenly,
 * and a computer moves its pointer at once for each piece: so the pointer stepped, and its acceleration, which grows with the size of a
 * piece, turned an even turn of the arm into an uneven speed. Spread over the time until the next burst, each piece becomes many small
 * even steps, and the speed follows the arm again. It costs about one gap between bursts of delay.
 */
class PointerPacer {
    private var leftX = 0f
    private var leftY = 0f
    private var speedX = 0f
    private var speedY = 0f
    private var carryX = 0f
    private var carryY = 0f
    private var lastArrivalMs = -1L
    private var lastTickMs = -1L
    private var gapMs = START_GAP_MS

    /** Whether anything is still to be sent, so the timer can stop when the arm is still. */
    val busy: Boolean get() = leftX != 0f || leftY != 0f || carryX.toInt() != 0 || carryY.toInt() != 0

    /** A burst from the watch at [nowMs]. */
    fun add(nowMs: Long, dx: Int, dy: Int) {
        if (lastArrivalMs >= 0) {
            val gap = nowMs - lastArrivalMs
            // A long pause is the arm resting, not the link: it would make the next movement crawl.
            if (gap in 1..MAX_GAP_MS) gapMs += (gap.coerceAtLeast(MIN_GAP_MS) - gapMs) * GAP_FOLLOW
        }
        lastArrivalMs = nowMs
        if (lastTickMs < 0 || !busy) lastTickMs = nowMs
        leftX += dx
        leftY += dy
        // What is left is spread over the time until the next burst is due.
        speedX = leftX / gapMs
        speedY = leftY / gapMs
    }

    /** The step to send at [nowMs], or null when there is none. Goes by the clock, so a late timer only makes a bigger step. */
    fun tick(nowMs: Long): PointerDelta? {
        if (lastTickMs < 0) return null
        val elapsed = (nowMs - lastTickMs).coerceAtLeast(0)
        lastTickMs = nowMs
        val stepX = take(speedX * elapsed, leftX)
        val stepY = take(speedY * elapsed, leftY)
        leftX -= stepX
        leftY -= stepY
        return whole(stepX, stepY)
    }

    /** Everything that is left, at once: a click has to land where the pointer was going. */
    fun drain(): PointerDelta? {
        val stepX = leftX
        val stepY = leftY
        leftX = 0f
        leftY = 0f
        return whole(stepX, stepY)
    }

    fun reset() {
        leftX = 0f
        leftY = 0f
        speedX = 0f
        speedY = 0f
        carryX = 0f
        carryY = 0f
        lastArrivalMs = -1
        lastTickMs = -1
        gapMs = START_GAP_MS
    }

    private fun take(wanted: Float, left: Float): Float =
        if (left >= 0) wanted.coerceIn(0f, left) else wanted.coerceIn(left, 0f)

    private fun whole(stepX: Float, stepY: Float): PointerDelta? {
        carryX += stepX
        carryY += stepY
        val dx = carryX.roundToInt()
        val dy = carryY.roundToInt()
        if (dx == 0 && dy == 0) return null
        carryX -= dx
        carryY -= dy
        return PointerDelta(dx, dy)
    }

    companion object {
        /** About 120 times a second: smooth on any screen, and each piece small enough that acceleration follows the speed. */
        const val TICK_MS = 8L
        private const val START_GAP_MS = 30f
        private const val MIN_GAP_MS = 8L
        private const val MAX_GAP_MS = 150L
        private const val GAP_FOLLOW = 0.2f
    }
}

/** How often and how evenly something arrived, for one line in the log when a pointer session ends. */
class ArrivalStats {
    private val gaps = IntArray(GAP_BUCKETS)
    private var last = -1L
    private var count = 0
    private var biggest = 0

    fun reset() {
        gaps.fill(0)
        last = -1
        count = 0
        biggest = 0
    }

    fun note(nowMs: Long, size: Int) {
        count++
        biggest = maxOf(biggest, size)
        if (last >= 0) gaps[(nowMs - last).coerceIn(0, GAP_BUCKETS - 1L).toInt()]++
        last = nowMs
    }

    /** Pauses of the arm count as one long gap and are left out of the share that was late. */
    fun summary(): String {
        val counted = gaps.sum()
        if (counted == 0) return "$count pieces"
        fun percentile(share: Double): Int {
            var seen = 0
            for (gap in gaps.indices) {
                seen += gaps[gap]
                if (seen >= counted * share) return gap
            }
            return GAP_BUCKETS - 1
        }
        val moving = counted - gaps[GAP_BUCKETS - 1]
        val late = (LATE_MS until GAP_BUCKETS - 1).sumOf { gaps[it] }
        return "$count pieces, gap median ${percentile(0.5)} ms, 90% under ${percentile(0.9)} ms, " +
            "${if (moving > 0) late * 100 / moving else 0}% over $LATE_MS ms, biggest $biggest"
    }

    private companion object {
        const val GAP_BUCKETS = 251
        const val LATE_MS = 60
    }
}
