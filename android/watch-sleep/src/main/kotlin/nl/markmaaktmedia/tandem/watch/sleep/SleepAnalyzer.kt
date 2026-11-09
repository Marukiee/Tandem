package nl.markmaaktmedia.tandem.watch.sleep

import java.io.InputStream
import kotlin.math.roundToInt

data class SleepEpoch(val startMs: Long, val stage: SleepStage, val heartRate: Int?) {
    val asleep: Boolean get() = stage != SleepStage.WAKE
}

data class StageMinutes(val light: Int, val deep: Int, val rem: Int)

data class SleepSummary(
    val onsetMs: Long,
    val wakeMs: Long,
    val sleepMinutes: Int,
    val wakeAfterOnsetMinutes: Int,
    val awakenings: Int,
    /** Sleep time as a percentage of the period between falling asleep and waking up. */
    val efficiencyPercent: Int,
    /** Per cent of the sleep period with clear movement of the wrist. Compare nights, not people. */
    val restlessnessPercent: Int,
    val meanHeartRate: Int?,
    val lowestHeartRate: Int?,
    /** Null when too little heart rate came in: from movement alone these would only be guesses. */
    val stages: StageMinutes?,
    val epochs: List<SleepEpoch>,
) {
    /** The night as stretches of one stage with their length in seconds: a few dozen of these say what hundreds of epochs say. */
    fun stageRuns(): List<Pair<SleepStage, Int>> {
        val seconds = (NightFeatures.EPOCH_MS / 1000).toInt()
        val runs = mutableListOf<Pair<SleepStage, Int>>()
        for (epoch in epochs) {
            val last = runs.lastOrNull()
            if (last?.first == epoch.stage) runs[runs.lastIndex] = epoch.stage to last.second + seconds
            else runs += epoch.stage to seconds
        }
        return runs
    }
}

/**
 * Finds the night in a recording. [SleepModel] says per 30 seconds what the person was doing; this finds where the main sleep starts
 * and ends, and leaves out what can not be sleep. Raw samples stay on disk, so a better model can analyse old nights again.
 */
object SleepAnalyzer {
    private const val EPOCHS_PER_MINUTE = 2
    /** A stretch has to last this long before it can start or end a night, so a false moment does not move the night's edges. */
    private const val MIN_RUN_EPOCHS = 5 * EPOCHS_PER_MINUTE
    /** Awake for up to this long does not split the night. */
    private const val MAX_GAP_EPOCHS = 60 * EPOCHS_PER_MINUTE
    /** Shorter sleep periods are ignored, so sitting still on the couch does not become the night. */
    private const val MIN_PERIOD_EPOCHS = 60 * EPOCHS_PER_MINUTE
    /** The lead-in only counts when the sleep found in the recording starts about where the recording does. */
    private const val MAX_LEAD_GAP_EPOCHS = 15 * EPOCHS_PER_MINUTE
    /** Without a heart rate for this long and without moving, the watch lies somewhere. For recordings without the off-wrist sensor. */
    private const val NO_HEART_EPOCHS = 20 * EPOCHS_PER_MINUTE
    private const val MIN_HEART_SHARE = 0.5

    fun analyze(input: InputStream): SleepSummary? {
        val features = NightFeatures()
        forEachRawRecord(input, features::add)
        return analyze(features.finish())
    }

    fun analyze(records: List<RawRecord>): SleepSummary? {
        val features = NightFeatures()
        records.forEach(features::add)
        return analyze(features.finish())
    }

    private fun analyze(night: Night?): SleepSummary? {
        if (night == null || night.rows.size + night.leadEpochs < MIN_PERIOD_EPOCHS) return null
        return summarize(night, SleepModel.load().stages(night.rows))
    }

    internal fun summarize(night: Night, stages: Array<SleepStage>): SleepSummary? {
        val count = stages.size
        val lying = lyingSomewhere(night)
        val asleep = BooleanArray(count) { i ->
            stages[i] != SleepStage.WAKE && night.worn[i] && night.motionShare[i] > 0 && !lying[i]
        }
        val period = mainPeriod(asleep, night.leadEpochs) ?: return null
        val first = period.first
        val last = period.last
        val shown = Array(count) { if (asleep[it]) stages[it] else SleepStage.WAKE }

        val range = first..last
        // A recording that started by itself began after the person lay still. That time is falling asleep, not sleep: counted as
        // sleep it made every night 99% asleep with no time to fall asleep at all, where Google's Sleep Score on the same wrist
        // counted half an hour. So the night starts where the person lay down, and that time is awake, in bed.
        val lead = if (first <= MAX_LEAD_GAP_EPOCHS) night.leadEpochs + first else 0
        val leadFromBefore = if (lead > 0) night.leadEpochs else 0
        // The minutes between the start of the recording and the first sleep are falling asleep too, when they are few: left out, the
        // time to fall asleep came out as nothing.
        val settling = if (first <= MAX_LEAD_GAP_EPOCHS) first else 0
        val bedtime = night.startMs + (first - settling) * NightFeatures.EPOCH_MS

        val sleepEpochs = range.count { asleep[it] }
        val total = range.count() + leadFromBefore + settling
        var awakenings = 0
        for (i in first + 1..last) if (!asleep[i] && asleep[i - 1]) awakenings++
        val rates = range.filter { asleep[it] }.mapNotNull { night.heartRates[it] }
        val minutes = { stage: SleepStage -> range.count { shown[it] == stage } / EPOCHS_PER_MINUTE }
        val hasHeart = range.count { asleep[it] } > 0 && rates.size >= MIN_HEART_SHARE * range.count { asleep[it] }

        return SleepSummary(
            onsetMs = bedtime - leadFromBefore * NightFeatures.EPOCH_MS,
            wakeMs = night.startMs + (last + 1) * NightFeatures.EPOCH_MS,
            sleepMinutes = sleepEpochs / EPOCHS_PER_MINUTE,
            // Awake once asleep: the time falling asleep is its own part of the night.
            wakeAfterOnsetMinutes = (range.count() - sleepEpochs) / EPOCHS_PER_MINUTE,
            awakenings = awakenings,
            efficiencyPercent = (100.0 * sleepEpochs / total).roundToInt(),
            restlessnessPercent = (100.0 * range.count { night.moved[it] } / total).roundToInt(),
            meanHeartRate = rates.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
            lowestHeartRate = rates.minOrNull(),
            stages = if (hasHeart) {
                StageMinutes(
                    light = minutes(SleepStage.LIGHT),
                    deep = minutes(SleepStage.DEEP),
                    rem = minutes(SleepStage.REM),
                )
            } else {
                null
            },
            epochs = (0 until leadFromBefore).map { SleepEpoch(bedtime - (leadFromBefore - it) * NightFeatures.EPOCH_MS, SleepStage.WAKE, null) } +
                (first - settling until first).map { SleepEpoch(night.startMs + it * NightFeatures.EPOCH_MS, SleepStage.WAKE, night.heartRates[it]) } +
                range.map { SleepEpoch(night.startMs + it * NightFeatures.EPOCH_MS, shown[it], night.heartRates[it]) },
        )
    }

    private fun lyingSomewhere(night: Night): BooleanArray {
        val count = night.heartRates.size
        val lying = BooleanArray(count)
        // A night without any heart rate has no permission or a broken sensor, and that says nothing about the wrist.
        if (night.heartRates.count { it != null } < count / 10) return lying
        var runStart = -1
        for (i in 0..count) {
            val quiet = i < count && night.heartRates[i] == null && !night.moved[i]
            if (quiet && runStart < 0) runStart = i
            if (!quiet && runStart >= 0) {
                if (i - runStart >= NO_HEART_EPOCHS) for (j in runStart until i) lying[j] = true
                runStart = -1
            }
        }
        return lying
    }

    /** Joins sleep separated by short times awake and returns the period with the most sleep. */
    private fun mainPeriod(asleep: BooleanArray, lead: Int): IntRange? {
        val runs = BooleanArray(asleep.size)
        var runStart = -1
        for (i in 0..asleep.size) {
            val on = i < asleep.size && asleep[i]
            if (on && runStart < 0) runStart = i
            if (!on && runStart >= 0) {
                if (i - runStart >= MIN_RUN_EPOCHS) for (j in runStart until i) runs[j] = true
                runStart = -1
            }
        }
        var best: IntRange? = null
        var bestSleep = 0
        var currentStart = -1
        var lastOn = -1
        var currentSleep = 0

        fun close() {
            if (currentStart < 0) return
            val withLead = if (currentStart <= MAX_LEAD_GAP_EPOCHS) lead else 0
            if (lastOn - currentStart + 1 + withLead >= MIN_PERIOD_EPOCHS && currentSleep > bestSleep) {
                best = currentStart..lastOn
                bestSleep = currentSleep
            }
        }

        for (i in runs.indices) {
            if (!runs[i]) continue
            if (currentStart >= 0 && i - lastOn > MAX_GAP_EPOCHS) {
                close()
                currentStart = -1
                currentSleep = 0
            }
            if (currentStart < 0) currentStart = i
            lastOn = i
            currentSleep++
        }
        close()
        return best
    }
}
