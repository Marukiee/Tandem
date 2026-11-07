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

/**
 * Hears a double pinch or double tap of the fingers of the hand the watch is on, in the motion sensor. Fingers meeting
 * send a short knock through the wrist that is much faster than anything the arm does, so a high-pass filter keeps
 * the knock and drops the arm. It asks for two knocks because one is too easily caused by anything else.
 *
 * Not tuned against real wrists yet: the numbers are a start, and raw recordings will set them.
 */
class PinchDetector(private val minPeak: Float = 1.2f, private val peakOverNoise: Float = 4f) {
    /**
     * Until this sensor time nothing counts: the watch buzzes for a pinch, the buzz is a knock in the same sensor, and the next pinch
     * it seemed to be buzzed again. That loop was the pointer that clicked by itself, every 0.4 s.
     */
    @Volatile var quietUntilNs = 0L
    private var lastNs = 0L
    private val slow = FloatArray(3)
    private var noise = 0f
    private var lastPeakNs = 0L
    private var previousPeakNs = 0L
    private var peaksInBurst = 0

    /** Acceleration in metres per second squared. True at the moment a double pinch is certain. */
    fun update(timestampNs: Long, x: Float, y: Float, z: Float): Boolean {
        val previous = lastNs
        lastNs = timestampNs
        if (previous == 0L || timestampNs <= previous) {
            slow[0] = x
            slow[1] = y
            slow[2] = z
            return false
        }
        val seconds = ((timestampNs - previous) / 1e9f).coerceAtMost(0.05f)
        val follow = seconds / (seconds + FILTER_SECONDS)
        slow[0] += (x - slow[0]) * follow
        slow[1] += (y - slow[1]) * follow
        slow[2] += (z - slow[2]) * follow
        val knock = hypot(hypot(x - slow[0], y - slow[1]), z - slow[2])

        if (timestampNs < quietUntilNs) {
            peaksInBurst = 0
            return false
        }
        val threshold = maxOf(minPeak, noise * peakOverNoise)
        val isPeak = knock > threshold && timestampNs - lastPeakNs > REFRACTORY_NS
        // A knock must not teach the filter that this much movement is normal.
        if (knock <= threshold) noise += (knock - noise) * NOISE_FOLLOW
        if (isPeak) {
            previousPeakNs = lastPeakNs
            lastPeakNs = timestampNs
            peaksInBurst = if (timestampNs - previousPeakNs < MAX_GAP_NS) peaksInBurst + 1 else 1
        }
        // Wait to see that no third knock follows: shaking a hand is a row of knocks, not two.
        if (peaksInBurst == 2 && timestampNs - lastPeakNs > CONFIRM_NS) {
            val gap = lastPeakNs - previousPeakNs
            peaksInBurst = 0
            return gap > MIN_GAP_NS
        }
        if (peaksInBurst > 2 && timestampNs - lastPeakNs > CONFIRM_NS) peaksInBurst = 0
        return false
    }

    fun reset() {
        lastNs = 0L
        noise = 0f
        lastPeakNs = 0L
        previousPeakNs = 0L
        peaksInBurst = 0
    }

    private companion object {
        /** About 16 Hz: an arm moves slower than this, a knock is faster. */
        const val FILTER_SECONDS = 0.01f
        const val NOISE_FOLLOW = 0.01f
        const val REFRACTORY_NS = 70_000_000L
        const val MIN_GAP_NS = 90_000_000L
        const val MAX_GAP_NS = 450_000_000L
        const val CONFIRM_NS = 200_000_000L
    }
}
