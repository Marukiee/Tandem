package nl.markmaaktmedia.tandem.watch.pointer

import kotlin.math.hypot

data class Touch(val id: Long, val x: Float, val y: Float)

/** [SWIPE_RIGHT] leaves the screen, as everywhere on a watch; any other slide pauses the pointer. */
enum class ScreenGesture { TAP, TWO_FINGER_TAP, HOLD, HOLD_END, SLIDE, SWIPE_RIGHT }

/**
 * What a screen this small can tell apart, from raw fingers. No Android in here, so the rules are unit tested. A tap and a hold part at
 * the same moment, so there is no length of touch that does nothing. Once a hold has started, a finger that slides keeps holding: only
 * lifting it lets go, because that is what a drag is.
 */
class ScreenGestures(private val slop: Float = 24f, private val holdMs: Long = 300, private val swipe: Float = 90f) {
    private var startMs = -1L
    private var lastX = 0f
    private var lastY = 0f
    private var firstX = 0f
    private var firstY = 0f
    private var most = 0
    private var moved = false
    private var fired = false
    private val origins = HashMap<Long, Pair<Float, Float>>()

    /** When to call [onTimer], if a finger is resting on the screen. */
    val deadline: Long?
        get() = if (startMs >= 0 && most == 1 && !moved && !fired) startMs + holdMs else null

    /** Whether a finger is on the screen and it is not yet known what for. */
    val undecided: Boolean get() = startMs >= 0 && !fired && !moved

    fun onFrame(nowMs: Long, touches: List<Touch>): ScreenGesture? {
        if (touches.isEmpty()) {
            val result = when {
                startMs < 0 -> null
                fired -> ScreenGesture.HOLD_END
                moved -> when {
                    most != 1 -> null
                    lastX - firstX > swipe && lastX - firstX > 2 * kotlin.math.abs(lastY - firstY) -> ScreenGesture.SWIPE_RIGHT
                    else -> ScreenGesture.SLIDE
                }
                nowMs - startMs > holdMs -> null
                most >= 2 -> ScreenGesture.TWO_FINGER_TAP
                else -> ScreenGesture.TAP
            }
            startMs = -1
            most = 0
            moved = false
            fired = false
            origins.clear()
            return result
        }
        if (startMs < 0) {
            startMs = nowMs
            firstX = touches[0].x
            firstY = touches[0].y
        }
        lastX = touches[0].x
        lastY = touches[0].y
        most = maxOf(most, touches.size)
        if (!fired) {
            for (touch in touches) {
                val origin = origins.getOrPut(touch.id) { touch.x to touch.y }
                if (hypot(touch.x - origin.first, touch.y - origin.second) > slop) moved = true
            }
        }
        return null
    }

    fun onTimer(nowMs: Long): ScreenGesture? {
        val due = deadline ?: return null
        if (nowMs < due) return null
        fired = true
        return ScreenGesture.HOLD
    }
}

/** One pinch, or two in a row. */
enum class Pinch { SINGLE, DOUBLE }

/**
 * Hears pinches of the fingers of the hand the watch is on. Thumb and finger meeting send a short knock through the wrist, much faster
 * than an arm moves, while the wrist itself hardly turns. Set against a recording of 24 pinches on a real wrist, each when the screen
 * asked for one, half of them while moving the arm: 21 heard, none while the hand was still, one while moving it. The numbers:
 * - a knock of at least [minKnock] m/s2 above 16 Hz (pinches were 2.4 to 5.8, moving the arm mostly under 2.6),
 * - at most [maxKnock] (a tap on a table is 13 and more),
 * - while the wrist turns slower than [maxTurn] rad/s (2 at most during a pinch, 3 to 6 when the arm moves).
 * Two within [doubleMs] are a double pinch, so a single one is only certain once that time has gone by. One wrist and two sessions:
 * a first fit, not proof.
 */
class PinchDetector(
    private val minKnock: Float = 2.0f,
    private val maxKnock: Float = 9.0f,
    private val maxTurn: Float = 2.4f,
    private val overNoise: Float = 4f,
    private val doubleMs: Long = 300,
) {
    /**
     * Until this sensor time nothing counts: the watch buzzes for a pinch, the buzz is a knock in the same sensor, and the next pinch
     * it seemed to be buzzed again. That loop was the pointer that clicked by itself, every 0.4 s.
     */
    @Volatile var quietUntilNs = 0L

    private var lastNs = 0L
    private val slow = FloatArray(3)
    private var noise = 0f
    private var before = 0f
    private var previous = 0f
    private var previousNs = 0L
    private var lastPeakNs = 0L
    /** A knock that waits until the turn of the wrist around it is known. */
    private var candidateNs = -1L
    /** An accepted pinch that waits to see whether a second one follows. */
    private var firstNs = -1L
    private val turns = LongArray(TURN_SAMPLES)
    private val turnValues = FloatArray(TURN_SAMPLES)
    private var turnAt = 0

    /** Rotation in radians per second, from the gyroscope. */
    fun gyro(timestampNs: Long, x: Float, y: Float, z: Float) {
        turns[turnAt] = timestampNs
        turnValues[turnAt] = hypot(hypot(x, y), z)
        turnAt = (turnAt + 1) % TURN_SAMPLES
    }

    /** Acceleration in metres per second squared. A pinch at the moment it is certain. */
    fun update(timestampNs: Long, x: Float, y: Float, z: Float): Pinch? {
        val last = lastNs
        lastNs = timestampNs
        if (last == 0L || timestampNs <= last) {
            slow[0] = x
            slow[1] = y
            slow[2] = z
            return null
        }
        val seconds = ((timestampNs - last) / 1e9f).coerceAtMost(0.05f)
        val follow = seconds / (seconds + FILTER_SECONDS)
        slow[0] += (x - slow[0]) * follow
        slow[1] += (y - slow[1]) * follow
        slow[2] += (z - slow[2]) * follow
        val knock = hypot(hypot(x - slow[0], y - slow[1]), z - slow[2])

        if (timestampNs < quietUntilNs) {
            candidateNs = -1
            firstNs = -1
            before = knock
            previous = knock
            previousNs = timestampNs
            return null
        }

        // The sample before this one was the top of a knock.
        val threshold = maxOf(minKnock, noise * overNoise)
        if (previous > threshold && previous >= before && previous >= knock && previousNs - lastPeakNs > REFRACTORY_NS) {
            lastPeakNs = previousNs
            if (previous <= maxKnock) candidateNs = previousNs
        }
        // A knock must not teach the filter that this much movement is normal.
        if (knock <= threshold) noise += (knock - noise) * NOISE_FOLLOW
        before = previous
        previous = knock
        previousNs = timestampNs

        var result: Pinch? = null
        val candidate = candidateNs
        if (candidate >= 0 && timestampNs - candidate >= TURN_WINDOW_NS) {
            candidateNs = -1
            if (turnAround(candidate) <= maxTurn) {
                if (firstNs >= 0 && candidate - firstNs <= doubleMs * 1_000_000) {
                    firstNs = -1
                    result = Pinch.DOUBLE
                } else {
                    firstNs = candidate
                }
            }
        }
        if (result == null && firstNs >= 0 && timestampNs - firstNs > doubleMs * 1_000_000 + TURN_WINDOW_NS) {
            firstNs = -1
            result = Pinch.SINGLE
        }
        return result
    }

    /** How fast the wrist turned around [atNs]: an arm that moves turns it, fingers that pinch hardly do. */
    private fun turnAround(atNs: Long): Float {
        var most = 0f
        for (i in 0 until TURN_SAMPLES) {
            if (kotlin.math.abs(turns[i] - atNs) <= TURN_WINDOW_NS) most = maxOf(most, turnValues[i])
        }
        return most
    }

    fun reset() {
        lastNs = 0L
        noise = 0f
        before = 0f
        previous = 0f
        previousNs = 0L
        lastPeakNs = 0L
        candidateNs = -1
        firstNs = -1
        turns.fill(0)
        turnValues.fill(0f)
    }

    private companion object {
        /** About 16 Hz: an arm moves slower than this, a knock is faster. */
        const val FILTER_SECONDS = 0.01f
        const val NOISE_FOLLOW = 0.01f
        const val REFRACTORY_NS = 120_000_000L
        const val TURN_WINDOW_NS = 120_000_000L
        /** A little over two windows of gyroscope at 200 Hz. */
        const val TURN_SAMPLES = 64
    }
}
