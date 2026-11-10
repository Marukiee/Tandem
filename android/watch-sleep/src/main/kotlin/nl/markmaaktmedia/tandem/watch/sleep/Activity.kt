package nl.markmaaktmedia.tandem.watch.sleep

import kotlin.math.roundToInt

data class WorkoutSummary(
    val startMs: Long,
    val endMs: Long,
    val minutes: Int,
    val meanHeartRate: Int?,
    val highestHeartRate: Int?,
    val steps: Long,
)

object WorkoutAnalyzer {
    fun analyze(records: List<RawRecord>): WorkoutSummary? {
        if (records.isEmpty()) return null
        val start = records.minOf { it.timeMs }
        val end = records.maxOf { it.timeMs }
        val rates = records.filterIsInstance<RawRecord.HeartRate>().map { it.bpm }.filter { it > 0 }
        val steps = records.filterIsInstance<RawRecord.Steps>().map { it.total }
        return WorkoutSummary(
            startMs = start,
            endMs = end,
            minutes = ((end - start) / 60_000).toInt(),
            meanHeartRate = rates.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
            highestHeartRate = rates.maxOrNull(),
            steps = if (steps.size >= 2) (steps.last() - steps.first()).coerceAtLeast(0) else 0,
        )
    }
}

/**
 * Turns the step sensor's running total into steps per day. The total only resets when the watch restarts, so a
 * total lower than the last one means a restart and counts from zero rather than going negative.
 */
data class DailySteps(
    val day: String = "",
    val steps: Long = 0,
    val lastTotal: Long = -1,
    val activeMinutes: Int = 0,
    val minute: Long = -1,
    val stepsInMinute: Long = 0,
    /** The steps of this day per hour from midnight, 24 of them once a step has come in. */
    val hourly: List<Int> = emptyList(),
) {
    /**
     * [atMinute] is when the steps were taken, in minutes on any clock that only goes up; [atHour] is the hour of the day they were taken
     * in, so a day can be shown hour by hour. A batch that spans an hour goes to the hour it was measured in.
     */
    fun update(today: String, total: Long, atMinute: Long = -1, atHour: Int = -1): DailySteps {
        val delta = when {
            lastTotal < 0 -> 0
            total >= lastTotal -> total - lastTotal
            else -> total
        }
        val sameDay = today == day
        val elapsed = if (minute < 0 || atMinute < 0) 0 else atMinute - minute
        val active = when {
            atMinute < 0 -> 0
            // One step at a time: the moment this minute gets there.
            elapsed <= 0 -> if (stepsInMinute < ACTIVE_STEPS_PER_MINUTE && stepsInMinute + delta >= ACTIVE_STEPS_PER_MINUTE) 1 else 0
            // A sensor that batches can report many minutes in one go, and those steps were not all taken in this minute.
            else -> minOf(elapsed, delta / ACTIVE_STEPS_PER_MINUTE).toInt()
        }
        return DailySteps(
            day = today,
            steps = (if (sameDay) steps else 0) + delta,
            lastTotal = total,
            activeMinutes = (if (sameDay) activeMinutes else 0) + active,
            minute = if (atMinute < 0) minute else atMinute,
            stepsInMinute = if (elapsed <= 0) stepsInMinute + delta else delta,
            hourly = (if (sameDay && hourly.size == HOURS) hourly else List(HOURS) { 0 }).let { hours ->
                if (atHour in 0 until HOURS && delta > 0) hours.mapIndexed { hour, count -> if (hour == atHour) count + delta.toInt() else count } else hours
            },
        )
    }

    companion object {
        /** About where walking becomes brisk: research on step rates puts moderate effort at 100 steps a minute. */
        const val ACTIVE_STEPS_PER_MINUTE = 100L
        const val HOURS = 24
    }
}
