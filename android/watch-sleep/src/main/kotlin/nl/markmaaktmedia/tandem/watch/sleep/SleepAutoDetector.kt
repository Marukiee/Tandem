package nl.markmaaktmedia.tandem.watch.sleep


sealed interface AutoSleepDecision {
    /** Went to sleep around [stillSinceMs]: the detector only knows that some time after it happened. */
    data class Start(val stillSinceMs: Long) : AutoSleepDecision
    data object Stop : AutoSleepDecision
}

/**
 * Decides from a slow stream of wrist movement when a night starts and ends, so nobody has to press a button in bed. Pure logic, so
 * a whole night can be tested without a watch. The thresholds are a first guess until they have been checked against real nights.
 */
class SleepAutoDetector {
    private var recording = false
    private var worn = true
    private var charging = false
    private var allowed = true
    private var quietUntilMs = 0L
    private var interacted = false

    private var minuteStart = -1L
    private var minuteMoved = false
    private var minuteUsable = true

    private val motion = WristMotion()

    private val minutes = ArrayDeque<Boolean>()
    private var offWristMinutes = 0
    private var chargingMinutes = 0

    val isRecording: Boolean get() = recording

    fun setWorn(on: Boolean) {
        worn = on
    }

    fun setCharging(on: Boolean) {
        charging = on
    }

    /** Whether the time of day allows a new night to start. A night that started goes on as long as it lasts. */
    fun setAllowed(on: Boolean) {
        allowed = on
    }

    /** The screen came on. Nobody does that in their sleep for long, and a wrist on a desk does not show it by moving. */
    fun onInteraction() {
        interacted = true
    }

    /** Someone stopped the recording by hand: they know they are awake, whatever their wrist does for a while. */
    fun onStoppedByHand(nowMs: Long) {
        recording = false
        minutes.clear()
        quietUntilMs = nowMs + AFTER_MANUAL_STOP_MS
    }

    /** Forgets everything except what the watch is doing right now: nobody is listening for a while. */
    fun reset() {
        recording = false
        resetAfterGap()
    }

    /** Samples in g. May come in batches, but in order. */
    fun onSample(timeMs: Long, x: Float, y: Float, z: Float): AutoSleepDecision? {
        if (minuteStart >= 0 && timeMs - minuteStart > MAX_GAP_MS) resetAfterGap()
        if (minuteStart < 0) minuteStart = timeMs
        if (motion.add(timeMs, x, y, z)) minuteMoved = true
        if (!worn || charging) minuteUsable = false
        if (timeMs - minuteStart < MINUTE_MS) return null
        val decision = closeMinute(minuteStart)
        minuteStart += MINUTE_MS
        return decision
    }

    private fun resetAfterGap() {
        // Nothing is known about the time in between, so the wrist cannot be called still for that time.
        minutes.clear()
        minuteStart = -1
        minuteMoved = false
        minuteUsable = true
        motion.clear()
    }

    private fun closeMinute(startMs: Long): AutoSleepDecision? {
        val moved = minuteMoved || interacted
        val usable = minuteUsable
        offWristMinutes = if (worn) 0 else offWristMinutes + 1
        chargingMinutes = if (charging) chargingMinutes + 1 else 0
        minuteMoved = false
        interacted = false
        minuteUsable = true

        if (recording) {
            minutes.addLast(moved)
            while (minutes.size > STOP_WINDOW_MINUTES) minutes.removeFirst()
            val awake = minutes.count { it } >= STOP_MOVED_MINUTES
            if (awake || offWristMinutes >= STOP_OFF_WRIST_MINUTES || chargingMinutes >= STOP_CHARGING_MINUTES) {
                recording = false
                minutes.clear()
                return AutoSleepDecision.Stop
            }
            return null
        }

        // A minute off the wrist or on the charger says nothing about sleep, so it breaks the run.
        if (!usable) {
            minutes.clear()
            return null
        }
        minutes.addLast(moved)
        while (minutes.size > START_STILL_MINUTES) minutes.removeFirst()
        val ready = minutes.size == START_STILL_MINUTES && minutes.count { it } <= START_MAX_MOVED_MINUTES
        if (!ready || !allowed || startMs < quietUntilMs) return null
        recording = true
        val since = startMs + MINUTE_MS - START_STILL_MINUTES * MINUTE_MS
        minutes.clear()
        return AutoSleepDecision.Start(since)
    }

    companion object {
        /** The label of the mark in the raw log that says the night began before the recording did. */
        const val STILL_SINCE = "still_since"

        private const val MINUTE_MS = 60_000L
        private const val MAX_GAP_MS = 12 * MINUTE_MS
        private const val START_STILL_MINUTES = 25
        private const val START_MAX_MOVED_MINUTES = 2
        private const val STOP_WINDOW_MINUTES = 12
        private const val STOP_MOVED_MINUTES = 8
        private const val STOP_OFF_WRIST_MINUTES = 10
        private const val STOP_CHARGING_MINUTES = 2
        private const val AFTER_MANUAL_STOP_MS = 3 * 60 * MINUTE_MS

        /** Evenings and mornings only: a long sit on a weekday afternoon is not a night. Naps stay a manual start. */
        fun inWindow(hourOfDay: Int): Boolean = hourOfDay >= 21 || hourOfDay < 11

        /** How long until [inWindow] changes. */
        fun minutesToWindowChange(hourOfDay: Int, minute: Int): Int {
            val hours = when {
                hourOfDay >= 21 -> 24 - hourOfDay + 11
                hourOfDay < 11 -> 11 - hourOfDay
                else -> 21 - hourOfDay
            }
            return hours * 60 - minute
        }
    }
}
