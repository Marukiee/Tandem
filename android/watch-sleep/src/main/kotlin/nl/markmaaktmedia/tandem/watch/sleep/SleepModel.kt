package nl.markmaaktmedia.tandem.watch.sleep

import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.tanh

enum class SleepStage { WAKE, LIGHT, DEEP, REM }

/**
 * A small recurrent network that reads the whole night in both directions, written out by hand so the app needs no machine learning
 * library. See watch-sleep/training for how it was made and how well it does. Sleep staged from a wrist stays an estimate: without
 * brain waves nobody can do this exactly.
 */
class SleepModel private constructor(
    private val nets: List<Net>,
    private val transitions: Array<FloatArray>,
    private val prior: FloatArray,
) {
    val inputs: Int get() = nets.first().inputs

    /** Log probability per epoch and stage, the mean of all networks. */
    fun logProbabilities(rows: Array<FloatArray>): Array<FloatArray> {
        val all = nets.map { it.logits(rows) }
        return Array(rows.size) { t ->
            val mean = FloatArray(STAGES)
            for (logits in all) {
                val p = logSoftmax(logits[t])
                for (c in 0 until STAGES) mean[c] += p[c] / all.size
            }
            mean
        }
    }

    /** The most likely course of the night: stages last for minutes, and some never follow each other. */
    fun stages(rows: Array<FloatArray>): Array<SleepStage> = decode(logProbabilities(rows))

    /** The same with other weights for reading the night, to hold them against another app's nights (see `AnalyzeCli`). */
    fun stages(rows: Array<FloatArray>, wakeBoost: Float, transitionWeight: Float): Array<SleepStage> =
        decode(logProbabilities(rows), wakeBoost, transitionWeight)

    /**
     * Taken as learned, the transitions smooth away nearly every short time awake: 64% of the nights ended up without a minute awake,
     * where in truth that is 2%. So they weigh less and awake weighs more, with the numbers measured in training/README.md.
     */
    internal fun decode(logs: Array<FloatArray>, wakeBoost: Float = WAKE_BOOST, transitionWeight: Float = TRANSITION_WEIGHT): Array<SleepStage> {
        if (logs.isEmpty()) return emptyArray()
        val back = Array(logs.size) { IntArray(STAGES) }
        var best = FloatArray(STAGES) { weighed(logs[0], it, wakeBoost) + prior[it] }
        for (t in 1 until logs.size) {
            val next = FloatArray(STAGES)
            for (to in 0 until STAGES) {
                var top = Float.NEGATIVE_INFINITY
                var from = 0
                for (c in 0 until STAGES) {
                    val score = best[c] + transitions[c][to] * transitionWeight
                    if (score > top) {
                        top = score
                        from = c
                    }
                }
                back[t][to] = from
                next[to] = top + weighed(logs[t], to, wakeBoost)
            }
            best = next
        }
        var state = best.indices.maxByOrNull { best[it] } ?: 0
        val path = Array(logs.size) { SleepStage.WAKE }
        for (t in logs.size - 1 downTo 0) {
            path[t] = SleepStage.entries[state]
            state = back[t][state]
        }
        return path
    }

    private fun weighed(log: FloatArray, stage: Int, boost: Float): Float = if (stage == SleepStage.WAKE.ordinal) log[stage] + boost else log[stage]

    private fun logSoftmax(values: FloatArray): FloatArray {
        val top = values.max()
        var sum = 0f
        for (v in values) sum += exp(v - top)
        val log = ln(sum)
        return FloatArray(values.size) { values[it] - top - log }
    }

    internal class Gru(private val inputs: Int, private val hidden: Int, reader: Reader) {
        private val ih = reader.floats(3 * hidden * inputs)
        private val hh = reader.floats(3 * hidden * hidden)
        private val bi = reader.floats(3 * hidden)
        private val bh = reader.floats(3 * hidden)

        /** Same sums as PyTorch's GRU, in the same order. */
        fun run(sequence: Array<FloatArray>, backward: Boolean): Array<FloatArray> {
            val out = Array(sequence.size) { FloatArray(hidden) }
            var h = FloatArray(hidden)
            val gate = FloatArray(3 * hidden)
            val fromHidden = FloatArray(3 * hidden)
            val indices = if (backward) sequence.indices.reversed() else sequence.indices
            for (t in indices) {
                val x = sequence[t]
                for (g in 0 until 3 * hidden) {
                    var a = bi[g]
                    val rowI = g * inputs
                    for (i in 0 until inputs) a += ih[rowI + i] * x[i]
                    gate[g] = a
                    var b = bh[g]
                    val rowH = g * hidden
                    for (i in 0 until hidden) b += hh[rowH + i] * h[i]
                    fromHidden[g] = b
                }
                val next = out[t]
                for (i in 0 until hidden) {
                    val reset = sigmoid(gate[i] + fromHidden[i])
                    val update = sigmoid(gate[hidden + i] + fromHidden[hidden + i])
                    val new = tanh(gate[2 * hidden + i] + reset * fromHidden[2 * hidden + i])
                    next[i] = new + update * (h[i] - new)
                }
                h = next
            }
            return out
        }

        private fun sigmoid(v: Float) = 1f / (1f + exp(-v))
    }

    internal class Net(val inputs: Int, private val hidden: Int, layers: Int, reader: Reader) {
        private val inWeight = reader.floats(hidden * inputs)
        private val inBias = reader.floats(hidden)
        private val grus = List(layers) { layer ->
            val size = if (layer == 0) hidden else 2 * hidden
            Gru(size, hidden, reader) to Gru(size, hidden, reader)
        }
        private val outWeight = reader.floats(STAGES * 2 * hidden)
        private val outBias = reader.floats(STAGES)

        fun logits(rows: Array<FloatArray>): Array<FloatArray> {
            var sequence = Array(rows.size) { t ->
                FloatArray(hidden) { h ->
                    var v = inBias[h]
                    for (i in 0 until inputs) v += inWeight[h * inputs + i] * rows[t][i]
                    max(v, 0f)
                }
            }
            for ((forward, backward) in grus) {
                val a = forward.run(sequence, backward = false)
                val b = backward.run(sequence, backward = true)
                sequence = Array(rows.size) { t -> a[t] + b[t] }
            }
            return Array(rows.size) { t ->
                FloatArray(STAGES) { c ->
                    var v = outBias[c]
                    for (i in 0 until 2 * hidden) v += outWeight[c * 2 * hidden + i] * sequence[t][i]
                    v
                }
            }
        }
    }

    internal class Reader(input: InputStream) {
        private val data = DataInputStream(input.buffered())

        fun int(): Int = ByteBuffer.wrap(ByteArray(4).also { data.readFully(it) }).order(ByteOrder.LITTLE_ENDIAN).int

        fun floats(count: Int): FloatArray {
            val bytes = ByteArray(count * 4)
            data.readFully(bytes)
            val out = FloatArray(count)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
            return out
        }
    }

    companion object {
        private const val STAGES = 4
        private const val MAGIC = 0x314D5354 // "TSM1"
        internal const val TRANSITION_WEIGHT = 0.4f
        internal const val WAKE_BOOST = 0.6f

        /** The weights take a moment to read and a few hundred kilobytes, so nothing keeps them after a night is analysed. */
        fun load(): SleepModel = requireNotNull(SleepModel::class.java.getResourceAsStream("/sleep_model.bin")) { "sleep model missing" }
            .use { read(it) }

        internal fun read(input: InputStream): SleepModel {
            val reader = Reader(input)
            require(reader.int() == MAGIC) { "not a sleep model" }
            val count = reader.int()
            val inputs = reader.int()
            val hidden = reader.int()
            val layers = reader.int()
            val transitions = Array(STAGES) { reader.floats(STAGES) }
            val prior = reader.floats(STAGES)
            return SleepModel(List(count) { Net(inputs, hidden, layers, reader) }, transitions, prior)
        }
    }
}
