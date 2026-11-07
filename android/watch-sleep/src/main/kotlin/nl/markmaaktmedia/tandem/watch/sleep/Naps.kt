package nl.markmaaktmedia.tandem.watch.sleep

import java.io.InputStream
import kotlin.math.roundToInt

/** A stretch of the day that could have been a nap. Only the person can tell, see [NapDetector]. */
data class NapGuess(
    val startMs: Long,
    val endMs: Long,
    val sleepMinutes: Int,
    val awakeMinutes: Int,
    val meanHeartRate: Int?,
    val lowestHeartRate: Int?,
)

/**
 * Finds the stretches of a day that could have been a nap, from the same slow stream of wrist movement as [SleepAutoDetector].
 *
 * It can only ask. On the nights of two sleep-lab data sets, the heart rate of the first 20 minutes of sleep was the same as lying still
 * awake: a limit that kept 81% of the real sleep also kept 79% of the lying awake. So movement and heart rate here only rule out what
 * clearly was no nap, and the person says yes or no to the rest.
 */
class NapDetector {
    private var allowed = false
    private var sleepingHeart: Int? = null

    private val minutes = ArrayDeque<Minute>()
    private var candidate = false
    private var stepsAtStart = 0L
    private val hearts = ArrayList<Pair<Long, Int>>()

    private class Minute(val startMs: Long, val moved: Boolean)

    /** Whether a nap can start now: the time of day, and no recording running. Going off mid-stretch forgets it: nothing to ask about. */
    fun setAllowed(on: Boolean) {
        if (allowed && !on) reset()
        allowed = on
    }

    /** The usual heart rate of a sleeping night, so an afternoon far above it can not be sleep. Null until a night is known. */
    fun setSleepingHeart(bpm: Int?) {
        sleepingHeart = bpm
    }

    fun onHeart(timeMs: Long, bpm: Int) {
        if (allowed) hearts += timeMs to bpm
    }

    fun reset() {
        minutes.clear()
        hearts.clear()
        candidate = false
    }

    /** One minute of wrist. Not usable means off the wrist or on the charger. Returns a stretch when one just ended. */
    fun onMinute(startMs: Long, moved: Boolean, usable: Boolean, stepsToday: Long): NapGuess? {
        if (!allowed) return null
        // Nothing is known about the time without samples, so a stretch ends where they stopped.
        val gap = minutes.isNotEmpty() && startMs - minutes.last().startMs > MAX_GAP_MS
        if (!usable || gap) {
            val guess = close(stepsToday)
            reset()
            if (guess != null || !usable) return guess
        }
        minutes.addLast(Minute(startMs, moved))
        if (!candidate) {
            while (minutes.size > START_STILL_MINUTES) minutes.removeFirst()
            hearts.removeAll { it.first < minutes.first().startMs }
            if (minutes.size == START_STILL_MINUTES && minutes.count { it.moved } <= START_MAX_MOVED_MINUTES) {
                candidate = true
                stepsAtStart = stepsToday
            }
            return null
        }
        if (minutes.takeLast(STOP_WINDOW_MINUTES).count { it.moved } < STOP_MOVED_MINUTES) return null
        return close(stepsToday).also { reset() }
    }

    private fun close(stepsToday: Long): NapGuess? {
        if (!candidate) return null
        val lastStill = minutes.indexOfLast { !it.moved }
        if (lastStill < 0) return null
        // The twenty minutes may begin with the one movement they allow, and that is not yet part of it.
        val inside = minutes.subList(minutes.indexOfFirst { !it.moved }, lastStill + 1)
        val sleep = inside.count { !it.moved }
        val start = inside.first().startMs
        val end = inside.last().startMs + MINUTE_MS
        if (sleep < START_STILL_MINUTES - START_MAX_MOVED_MINUTES || sleep >= MAX_NAP_MINUTES) return null
        // Someone who walks with their arm still, or a watch in a bag on a train.
        if (stepsToday - stepsAtStart > MAX_STEPS) return null
        val rates = hearts.filter { it.first in start until end }.map { it.second }.sorted()
        // Nothing to judge by: lying on a table does not give a heart rate either.
        if (rates.size < MIN_HEART_SAMPLES) return null
        val typical = rates[rates.size / 2]
        val usual = sleepingHeart
        if (usual != null && typical > usual * MAX_ABOVE_SLEEPING) return null
        return NapGuess(start, end, sleep, inside.size - sleep, rates.average().roundToInt(), rates.first())
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val MAX_GAP_MS = 12 * MINUTE_MS
        const val START_STILL_MINUTES = 20
        const val START_MAX_MOVED_MINUTES = 1
        const val STOP_WINDOW_MINUTES = 10
        const val STOP_MOVED_MINUTES = 6
        /** Three hours is where the health pages start calling it a night. */
        const val MAX_NAP_MINUTES = 180
        const val MAX_STEPS = 30
        const val MIN_HEART_SAMPLES = 2
        /** Of real sleep on those 284 nights, 94% stayed under this. It only rules out a clearly awake afternoon. */
        const val MAX_ABOVE_SLEEPING = 1.15
    }
}

/**
 * A nap someone started themselves. They said they were going to sleep, so no heart rate has to prove it. No stages: the sleep model
 * compares a night with itself, and a nap is too short for that.
 */
object NapAnalyzer {
    private const val EPOCHS_PER_MINUTE = 2
    private const val MIN_RUN_EPOCHS = 5 * EPOCHS_PER_MINUTE
    private const val MAX_GAP_EPOCHS = 10 * EPOCHS_PER_MINUTE
    private const val MIN_NAP_EPOCHS = 10 * EPOCHS_PER_MINUTE

    fun analyze(input: InputStream): SleepSummary? {
        val features = NightFeatures()
        forEachRawRecord(input, features::add)
        return features.finish()?.let(::analyze)
    }

    fun analyze(records: List<RawRecord>): SleepSummary? {
        val features = NightFeatures()
        records.forEach(features::add)
        return features.finish()?.let(::analyze)
    }

    internal fun analyze(night: Night): SleepSummary? {
        val count = night.moved.size
        val still = BooleanArray(count) { night.worn[it] && !night.moved[it] && night.motionShare[it] > 0 }
        val period = longestStretch(still) ?: return null
        val slept = period.count { still[it] }
        if (slept < MIN_NAP_EPOCHS) return null
        val rates = period.filter { still[it] }.mapNotNull { night.heartRates[it] }
        val total = period.count()
        var awakenings = 0
        for (i in period.first + 1..period.last) if (!still[i] && still[i - 1]) awakenings++
        return SleepSummary(
            onsetMs = night.startMs + period.first * NightFeatures.EPOCH_MS,
            wakeMs = night.startMs + (period.last + 1) * NightFeatures.EPOCH_MS,
            sleepMinutes = slept / EPOCHS_PER_MINUTE,
            wakeAfterOnsetMinutes = (total - slept) / EPOCHS_PER_MINUTE,
            awakenings = awakenings,
            efficiencyPercent = (100.0 * slept / total).roundToInt(),
            restlessnessPercent = (100.0 * period.count { night.moved[it] } / total).roundToInt(),
            meanHeartRate = rates.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
            lowestHeartRate = rates.minOrNull(),
            stages = null,
            epochs = emptyList(),
        )
    }

    /** Joins still stretches of at least five minutes across short movements, and returns the one with the most stillness. */
    private fun longestStretch(still: BooleanArray): IntRange? {
        var best: IntRange? = null
        var bestStill = 0
        var start = -1
        var lastEnd = -1
        var stillInside = 0
        fun close() {
            if (start >= 0 && stillInside > bestStill) {
                best = start..lastEnd
                bestStill = stillInside
            }
        }
        var i = 0
        while (i < still.size) {
            if (!still[i]) {
                i++
                continue
            }
            var end = i
            while (end + 1 < still.size && still[end + 1]) end++
            if (end - i + 1 >= MIN_RUN_EPOCHS) {
                if (start >= 0 && i - lastEnd > MAX_GAP_EPOCHS) {
                    close()
                    start = -1
                    stillInside = 0
                }
                if (start < 0) start = i
                lastEnd = end
                stillInside += end - i + 1
            }
            i = end + 1
        }
        close()
        return best
    }
}
