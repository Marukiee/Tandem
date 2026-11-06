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
data class DailySteps(val day: String = "", val steps: Long = 0, val lastTotal: Long = -1) {
    fun update(today: String, total: Long): DailySteps {
        val delta = when {
            lastTotal < 0 -> 0
            total >= lastTotal -> total - lastTotal
            else -> total
        }
        val base = if (today == day) steps else 0
        return DailySteps(today, base + delta, total)
    }
}
