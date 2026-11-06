package nl.markmaaktmedia.tandem.watch.sleep

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Turns a night of samples into one row of numbers per 30 seconds, the input of [SleepModel]. Holds no sample longer than it needs,
 * because a night is close to a million of them. Has to stay equal to watch-sleep/training/features.py, the code the model was
 * trained with: a golden test compares the two.
 */
class NightFeatures {
    private var startMs = -1L
    private var lastAccelMs = -1L
    private var subs = 0
    private var count = IntArray(INITIAL_SUBS)
    private var sum = DoubleArray(INITIAL_SUBS * 3)
    private var squares = DoubleArray(INITIAL_SUBS)

    private var heartTimes = LongArray(4096)
    private var heartBpm = IntArray(4096)
    private var hearts = 0

    private var stillSinceMs: Long? = null
    private val offWrist = ArrayList<LongRange>()
    private var offSince = -1L

    fun add(record: RawRecord) {
        when (record) {
            is RawRecord.Accel -> addAccel(record)
            is RawRecord.HeartRate -> addHeart(record)
            is RawRecord.Mark -> when (record.label) {
                SleepAutoDetector.STILL_SINCE -> stillSinceMs = record.timeMs
                MARK_OFF -> if (offSince < 0) offSince = record.timeMs
                MARK_WORN -> {
                    if (offSince >= 0) offWrist += offSince..record.timeMs
                    offSince = -1
                }
            }
            is RawRecord.Steps, is RawRecord.SkinTemperature -> Unit
        }
    }

    private fun addAccel(sample: RawRecord.Accel) {
        if (startMs < 0) startMs = sample.timeMs
        val offset = sample.timeMs - startMs
        if (offset < 0) return
        lastAccelMs = max(lastAccelMs, sample.timeMs)
        val index = (offset / SUB_MS).toInt()
        if (index >= count.size) {
            val size = max(index + 1, count.size * 2)
            count = count.copyOf(size)
            sum = sum.copyOf(size * 3)
            squares = squares.copyOf(size)
        }
        subs = max(subs, index + 1)
        val x = sample.x / UNITS
        val y = sample.y / UNITS
        val z = sample.z / UNITS
        count[index]++
        sum[index * 3] += x
        sum[index * 3 + 1] += y
        sum[index * 3 + 2] += z
        squares[index] += x * x + y * y + z * z
    }

    private fun addHeart(rate: RawRecord.HeartRate) {
        // Accuracy zero means no contact or no use. The first value of a sensor can also be an old one, far before the start.
        if (rate.accuracy == 0 || rate.bpm !in MIN_BPM..MAX_BPM) return
        if (hearts == heartTimes.size) {
            heartTimes = heartTimes.copyOf(hearts * 2)
            heartBpm = heartBpm.copyOf(hearts * 2)
        }
        heartTimes[hearts] = rate.timeMs
        heartBpm[hearts] = rate.bpm
        hearts++
    }

    /** Null when nothing was measured. */
    fun finish(): Night? {
        if (startMs < 0 || subs < 2) return null
        val epochs = ((lastAccelMs - startMs) / EPOCH_MS).toInt() + 1
        val motion = motion(epochs)
        val grid = heartGrid(epochs)
        val lead = stillSinceMs?.let { ((startMs - it) / EPOCH_MS).toInt().coerceAtLeast(0) } ?: 0
        val rows = rows(motion, grid, epochs, lead)
        val worn = BooleanArray(epochs) { true }
        val ranges = if (offSince >= 0) offWrist + listOf(offSince..lastAccelMs) else offWrist
        for (range in ranges) {
            val first = ((range.first - startMs) / EPOCH_MS).toInt().coerceAtLeast(0)
            val last = ((range.last - startMs) / EPOCH_MS).toInt().coerceAtMost(epochs - 1)
            for (i in first..last) worn[i] = false
        }
        val heart = Array(epochs) { i ->
            val values = (0 until PER).map { grid[i * PER + it] }.filter { !it.isNaN() }
            if (values.isEmpty()) null else Math.round(values.average()).toInt()
        }
        return Night(startMs, rows, motion.ok, motion.act.map { !it.isNaN() && it > MOVED_LOG }.toBooleanArray(), worn, heart, lead)
    }

    private class Motion(val act: DoubleArray, val peak: DoubleArray, val turn: DoubleArray, val angle: DoubleArray, val ok: DoubleArray)

    private fun motion(epochs: Int): Motion {
        val total = epochs * SUBS_PER_EPOCH
        val std = DoubleArray(total)
        val angle = DoubleArray(total)
        val usable = BooleanArray(total)
        for (s in 0 until min(total, subs)) {
            val c = count[s]
            if (c < 2) continue
            usable[s] = true
            val mx = sum[s * 3] / c
            val my = sum[s * 3 + 1] / c
            val mz = sum[s * 3 + 2] / c
            std[s] = sqrt(max(squares[s] / c - (mx * mx + my * my + mz * mz), 0.0))
            angle[s] = Math.toDegrees(atan2(mz, hypot(mx, my)))
        }
        val act = DoubleArray(epochs) { Double.NaN }
        val peak = DoubleArray(epochs) { Double.NaN }
        val turn = DoubleArray(epochs)
        val meanAngle = DoubleArray(epochs) { Double.NaN }
        val ok = DoubleArray(epochs)
        for (i in 0 until epochs) {
            var n = 0
            var stdSum = 0.0
            var stdMax = 0.0
            var angleSum = 0.0
            var change = 0.0
            for (s in i * SUBS_PER_EPOCH until (i + 1) * SUBS_PER_EPOCH) {
                if (!usable[s]) continue
                n++
                stdSum += std[s]
                stdMax = max(stdMax, std[s])
                angleSum += angle[s]
                // Across a gap the wrist may have moved at any time, so only neighbours compare.
                if (s > 0 && usable[s - 1]) change += abs(angle[s] - angle[s - 1])
            }
            if (n == 0) continue
            act[i] = log10(max(stdSum / n, FLOOR_G))
            peak[i] = log10(max(stdMax, FLOOR_G))
            turn[i] = log10(1 + change)
            meanAngle[i] = angleSum / n / 90
            ok[i] = n.toDouble() / SUBS_PER_EPOCH
        }
        return Motion(act, peak, turn, meanAngle, ok)
    }

    private fun heartGrid(epochs: Int): DoubleArray {
        val steps = epochs * PER
        val order = (0 until hearts).filter { heartTimes[it] >= startMs && heartTimes[it] - startMs < epochs * EPOCH_MS }.sortedBy { heartTimes[it] }
        val bpm = DoubleArray(order.size) { heartBpm[order[it]].toDouble() }
        val cleaned = if (bpm.size >= 3) {
            DoubleArray(bpm.size) { i ->
                val around = doubleArrayOf(bpm[max(i - 1, 0)], bpm[i], bpm[min(i + 1, bpm.size - 1)]).also { it.sort() }
                // A single wild value is a sensor error, not a heart.
                if (abs(bpm[i] - around[1]) > MAX_JUMP) around[1] else bpm[i]
            }
        } else {
            bpm
        }
        val counts = IntArray(steps)
        val sums = DoubleArray(steps)
        order.forEachIndexed { k, i ->
            val step = ((heartTimes[i] - startMs) / GRID_MS).toInt()
            counts[step]++
            sums[step] += cleaned[k]
        }
        return DoubleArray(steps) { if (counts[it] == 0) Double.NaN else sums[it] / counts[it] }
    }

    private fun rows(motion: Motion, grid: DoubleArray, n: Int, lead: Int): Array<FloatArray> {
        val ok = DoubleArray(n)
        val mean = DoubleArray(n) { Double.NaN }
        for (i in 0 until n) {
            var c = 0
            var s = 0.0
            for (k in i * PER until (i + 1) * PER) {
                if (!grid[k].isNaN()) {
                    c++
                    s += grid[k]
                }
            }
            ok[i] = c.toDouble() / PER
            if (c > 0) mean[i] = s / c
        }
        val have = mean.filter { !it.isNaN() }.sorted()
        val center: Double
        val scale: Double
        if (have.size >= 20) {
            center = percentile(have, 50.0)
            scale = max(percentile(have, 75.0) - percentile(have, 25.0), 3.0)
        } else {
            center = 60.0
            scale = 10.0
        }
        val z = DoubleArray(n) { (mean[it] - center) / scale }
        val zf = fill(z)
        val step = DoubleArray(n) { if (it == 0) 0.0 else abs(zf[it] - zf[it - 1]) }
        val local2 = rolling(z, 2, ::std)
        val local10 = rolling(z, 10, ::std)
        val zfMean = rolling(zf, 30, ::average)
        val slow = DoubleArray(n) { zf[it] - zfMean[it] }

        val diffs = DoubleArray(grid.size - 1) { abs(grid[it + 1] - grid[it]) / scale }
        val jitter = DoubleArray(n) { i ->
            var c = 0
            var s = 0.0
            for (k in max(0, (i - 1) * PER) until min(diffs.size, (i + 2) * PER)) {
                if (!diffs[k].isNaN()) {
                    c++
                    s += diffs[k]
                }
            }
            if (c == 0) Double.NaN else s / c
        }

        val floorLog = log10(FLOOR_G)
        val a = DoubleArray(n) { if (motion.act[it].isNaN()) floorLog else motion.act[it] }
        val act2 = rolling(a, 2, ::average)
        val act10 = rolling(a, 10, ::average)
        val runForward = DoubleArray(n)
        var run = 0
        for (i in 0 until n) {
            run = if (a[i] > MOVED_LOG) 0 else run + 1
            runForward[i] = min(run, MAX_RUN).toDouble() / MAX_RUN
        }
        run = 0
        val still = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            run = if (a[i] > MOVED_LOG) 0 else run + 1
            still[i] = min(min(run, MAX_RUN).toDouble() / MAX_RUN, runForward[i])
        }

        val gridScaled = fill(grid).also { for (i in it.indices) it[i] /= scale }
        val m3 = movingAverage(gridScaled, 3)
        val m12 = movingAverage(gridScaled, 12)
        val m48 = movingAverage(gridScaled, 48)
        val bands = arrayOf(
            band(DoubleArray(grid.size) { gridScaled[it] - m3[it] }, n),
            band(DoubleArray(grid.size) { m3[it] - m12[it] }, n),
            band(DoubleArray(grid.size) { m12[it] - m48[it] }, n),
        )

        // What matters is high or low for this night: it also takes away most of the difference between sensors.
        for (values in listOf(step, local2, local10, jitter) + bands) center(values)

        return Array(n) { i ->
            floatArrayOf(
                (a[i] / 3 + 1).toFloat(),
                ((if (motion.peak[i].isNaN()) floorLog else motion.peak[i]) / 3 + 1).toFloat(),
                (motion.turn[i] / 2).toFloat(),
                (if (motion.angle[i].isNaN()) 0.0 else motion.angle[i]).toFloat(),
                motion.ok[i].toFloat(),
                (if (z[i].isNaN()) 0.0 else z[i]).toFloat(),
                ok[i].toFloat(),
                step[i].coerceIn(-5.0, 5.0).toFloat(),
                orZero(local2[i]),
                orZero(local10[i]),
                slow[i].coerceIn(-5.0, 5.0).toFloat(),
                orZero(jitter[i]),
                (act2[i] / 3 + 1).toFloat(),
                (act10[i] / 3 + 1).toFloat(),
                still[i].toFloat(),
                ((i + lead) * EPOCH_MS / 36_000_000.0).toFloat(),
                bands[0][i].toFloat(),
                bands[1][i].toFloat(),
                bands[2][i].toFloat(),
            )
        }
    }

    /** How much heart rate moves in one time scale around each epoch, over five minutes. */
    private fun band(signal: DoubleArray, epochs: Int): DoubleArray = DoubleArray(epochs) { i ->
        val center = min(i * PER + PER / 2, signal.size - 1)
        var total = 0.0
        for (k in max(center - BAND_HALF, 0) until min(center + BAND_HALF, signal.size)) total += signal[k] * signal[k]
        log10(total / (2 * BAND_HALF) + 1e-4) / 4 + 1
    }

    private fun movingAverage(a: DoubleArray, window: Int): DoubleArray {
        val left = window / 2
        val right = window - 1 - left
        return DoubleArray(a.size) { i ->
            var s = 0.0
            for (k in i - left..i + right) s += a[k.coerceIn(0, a.size - 1)]
            s / window
        }
    }

    private fun rolling(a: DoubleArray, half: Int, statistic: (List<Double>) -> Double): DoubleArray =
        DoubleArray(a.size) { i ->
            val window = (max(0, i - half)..min(a.size - 1, i + half)).map { a[it] }.filter { !it.isNaN() }
            if (window.isEmpty()) Double.NaN else statistic(window)
        }

    /** Straight lines between what is known, flat at both ends. */
    private fun fill(a: DoubleArray): DoubleArray {
        val known = a.indices.filter { !a[it].isNaN() }
        if (known.isEmpty()) return DoubleArray(a.size)
        val out = a.copyOf()
        for (i in 0 until known.first()) out[i] = a[known.first()]
        for (i in known.last() + 1 until a.size) out[i] = a[known.last()]
        for (k in 0 until known.size - 1) {
            val from = known[k]
            val to = known[k + 1]
            for (i in from + 1 until to) out[i] = a[from] + (a[to] - a[from]) * (i - from) / (to - from)
        }
        return out
    }

    private fun center(values: DoubleArray) {
        val have = values.filter { !it.isNaN() }.sorted()
        if (have.isEmpty()) return
        val middle = percentile(have, 50.0)
        for (i in values.indices) values[i] -= middle
    }

    private fun orZero(value: Double) = (if (value.isNaN()) 0.0 else value).toFloat()

    private fun percentile(sorted: List<Double>, percent: Double): Double {
        val position = percent / 100 * (sorted.size - 1)
        val low = floor(position).toInt()
        val high = min(low + 1, sorted.size - 1)
        return sorted[low] + (sorted[high] - sorted[low]) * (position - low)
    }

    private fun average(values: List<Double>) = values.sum() / values.size

    private fun std(values: List<Double>): Double {
        val mean = average(values)
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }

    companion object {
        const val EPOCH_MS = 30_000L
        private const val SUB_MS = 5_000L
        private const val SUBS_PER_EPOCH = 6
        private const val GRID_MS = 5_000L
        private const val PER = 6
        private const val UNITS = ACCEL_UNITS_PER_G.toDouble()
        private const val INITIAL_SUBS = 8 * 60 * 12

        /** Below this a wrist is not distinguishable from sensor noise, and sensors differ there. */
        private const val FLOOR_G = 0.002
        private val MOVED_LOG = log10(0.02)
        private const val MAX_RUN = 40
        private const val MIN_BPM = 30
        private const val MAX_BPM = 220
        private const val MAX_JUMP = 15.0
        private const val BAND_HALF = 30
    }
}

/** One row per 30 seconds. Everything per epoch has the same length. */
class Night(
    val startMs: Long,
    val rows: Array<FloatArray>,
    /** How much of the epoch has motion data, from 0 to 1. */
    val motionShare: DoubleArray,
    val moved: BooleanArray,
    val worn: BooleanArray,
    val heartRates: Array<Int?>,
    /** Epochs the wrist had already been still before the recording started by itself. */
    val leadEpochs: Int,
)
