package nl.markmaaktmedia.tandem.watch.sleep

import java.nio.ByteBuffer
import kotlin.math.roundToInt

/** What a short measurement says. Null when the sensor found nothing it can stand behind. */
fun burstHeartRate(samples: List<HeartSample>): Int? {
    // The first value of a sensor can be an old one, from before it looked again.
    val usable = samples.drop(1).filter { it.accuracy > 0 && it.bpm in DayHeart.MIN_BPM..DayHeart.MAX_BPM }.map { it.bpm }.sorted()
    if (usable.size < DayHeart.MIN_BURST_SAMPLES) return null
    return usable[usable.size / 2]
}

data class HeartSample(val bpm: Int, val accuracy: Int)

/**
 * One day of heart rate, as a value per five minutes. Fine enough to see a walk and a night, and small enough to send over Bluetooth
 * and keep for years. A recording adds everything it measures; between recordings a short measurement fills the slot.
 */
class DayHeart private constructor(val day: String, private val sums: IntArray, private val counts: IntArray) {
    constructor(day: String) : this(day, IntArray(SLOTS), IntArray(SLOTS))

    /** Slots by the clock, so a day with a clock change folds its extra hour into the one it repeats. */
    fun add(minuteOfDay: Int, bpm: Int) {
        if (bpm !in MIN_BPM..MAX_BPM || minuteOfDay !in 0 until MINUTES_PER_DAY) return
        val slot = minuteOfDay / MINUTES_PER_SLOT
        sums[slot] += bpm
        counts[slot]++
    }

    /** A value per slot from midnight on, 0 where nothing was measured. */
    fun curve(): List<Int> = List(SLOTS) { if (counts[it] == 0) 0 else (sums[it].toDouble() / counts[it]).roundToInt() }

    val low: Int? get() = curve().filter { it > 0 }.minOrNull()
    val high: Int? get() = curve().filter { it > 0 }.maxOrNull()
    val mean: Int? get() = curve().filter { it > 0 }.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
    val latest: Int? get() = curve().lastOrNull { it > 0 }

    fun toBytes(): ByteArray {
        val buffer = ByteBuffer.allocate(SLOTS * 8)
        for (i in 0 until SLOTS) buffer.putInt(sums[i]).putInt(counts[i])
        return buffer.array()
    }

    companion object {
        const val SLOTS = 288
        const val MINUTES_PER_SLOT = 5
        const val MIN_BPM = 30
        const val MAX_BPM = 230
        const val MIN_BURST_SAMPLES = 3
        private const val MINUTES_PER_DAY = 24 * 60

        /** A fresh day when the file is from something else: a day without its heart rate beats a day with a wrong one. */
        fun fromBytes(day: String, bytes: ByteArray?): DayHeart {
            if (bytes == null || bytes.size != SLOTS * 8) return DayHeart(day)
            val buffer = ByteBuffer.wrap(bytes)
            val sums = IntArray(SLOTS)
            val counts = IntArray(SLOTS)
            for (i in 0 until SLOTS) {
                sums[i] = buffer.getInt()
                counts[i] = buffer.getInt()
            }
            return DayHeart(day, sums, counts)
        }
    }
}
