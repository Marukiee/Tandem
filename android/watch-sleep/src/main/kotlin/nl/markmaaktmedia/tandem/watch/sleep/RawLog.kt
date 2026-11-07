package nl.markmaaktmedia.tandem.watch.sleep

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Append-only sensor log. Raw samples are kept so a better sleep algorithm can re-analyse old nights.
 *
 * File: magic "TWS1", then records. Each record is one type byte, a zigzag varint timestamp delta in
 * milliseconds against the previous record of the same type, then the payload. A file cut off by a
 * crash or a dead battery still reads up to the last complete record.
 */
sealed interface RawRecord {
    val timeMs: Long

    /** Acceleration in units of 1/2048 g, which covers the +-16 g range of a watch sensor. */
    data class Accel(override val timeMs: Long, val x: Short, val y: Short, val z: Short) : RawRecord
    data class HeartRate(override val timeMs: Long, val bpm: Int, val accuracy: Int) : RawRecord
    /** Cumulative count since the sensor last reset, usually at boot. */
    data class Steps(override val timeMs: Long, val total: Long) : RawRecord
    /** In hundredths of a degree Celsius. Not used yet: the open sleep data has none to learn from, and old nights can not be measured again. */
    data class SkinTemperature(override val timeMs: Long, val centiDegrees: Int) : RawRecord
    data class Mark(override val timeMs: Long, val label: String) : RawRecord
    /** Rotation in thousandths of a radian per second. Only in recordings for tuning the mouse: a night does not need it. */
    data class Gyro(override val timeMs: Long, val x: Short, val y: Short, val z: Short) : RawRecord
}

const val ACCEL_UNITS_PER_G = 2048f
const val MARK_WORN = "worn"
const val MARK_OFF = "off"

private val MAGIC = "TWS1".encodeToByteArray()
private const val TYPE_ACCEL = 1
private const val TYPE_HEART_RATE = 2
private const val TYPE_STEPS = 3
private const val TYPE_MARK = 4
private const val TYPE_SKIN = 5
private const val TYPE_GYRO = 6

class RawLogWriter(private val out: OutputStream, writeHeader: Boolean) {
    private val last = LongArray(TYPE_GYRO + 1)

    init {
        if (writeHeader) out.write(MAGIC)
    }

    /** Stored as differences that start at zero for every writer, so one recording needs one file and one writer. */
    fun write(record: RawRecord) {
        when (record) {
            is RawRecord.Accel -> {
                header(TYPE_ACCEL, record.timeMs)
                short(record.x)
                short(record.y)
                short(record.z)
            }
            is RawRecord.HeartRate -> {
                header(TYPE_HEART_RATE, record.timeMs)
                out.write(record.bpm.coerceIn(0, 255))
                out.write(record.accuracy.coerceIn(0, 255))
            }
            is RawRecord.Steps -> {
                header(TYPE_STEPS, record.timeMs)
                varint(record.total)
            }
            is RawRecord.SkinTemperature -> {
                header(TYPE_SKIN, record.timeMs)
                varint(record.centiDegrees.coerceAtLeast(0).toLong())
            }
            is RawRecord.Gyro -> {
                header(TYPE_GYRO, record.timeMs)
                short(record.x)
                short(record.y)
                short(record.z)
            }
            is RawRecord.Mark -> {
                header(TYPE_MARK, record.timeMs)
                val bytes = record.label.encodeToByteArray()
                varint(bytes.size.toLong())
                out.write(bytes)
            }
        }
    }

    fun flush() = out.flush()

    private fun header(type: Int, timeMs: Long) {
        out.write(type)
        val delta = timeMs - last[type]
        last[type] = timeMs
        varint((delta shl 1) xor (delta shr 63))
    }

    private fun short(value: Short) {
        out.write(value.toInt() and 0xFF)
        out.write((value.toInt() shr 8) and 0xFF)
    }

    private fun varint(value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }
}

/**
 * Reads every complete record. Writers restart their delta state, so the recorder opens a new file for
 * each session instead of appending to an old one; a torn final record ends the read quietly.
 */
fun readRawLog(input: InputStream): List<RawRecord> {
    val records = ArrayList<RawRecord>()
    forEachRawRecord(input) { records += it }
    return records
}

/** A night is close to a million samples, too many to hold as objects on a watch. */
fun forEachRawRecord(input: InputStream, visit: (RawRecord) -> Unit) {
    val data = DataInputStream(input.buffered())
    val magic = ByteArray(MAGIC.size)
    try {
        data.readFully(magic)
    } catch (_: EOFException) {
        return
    }
    require(magic.contentEquals(MAGIC)) { "not a TWS1 log" }
    val last = LongArray(TYPE_GYRO + 1)
    try {
        while (true) {
            val type = data.read()
            if (type < 0) break
            if (type !in TYPE_ACCEL..TYPE_GYRO) break
            val raw = readVarint(data)
            val delta = (raw ushr 1) xor -(raw and 1)
            val time = last[type] + delta
            last[type] = time
            visit(
                when (type) {
                    TYPE_ACCEL -> RawRecord.Accel(time, readShort(data), readShort(data), readShort(data))
                    TYPE_HEART_RATE -> RawRecord.HeartRate(time, data.readUnsignedByte(), data.readUnsignedByte())
                    TYPE_STEPS -> RawRecord.Steps(time, readVarint(data))
                    TYPE_SKIN -> RawRecord.SkinTemperature(time, readVarint(data).toInt())
                    TYPE_GYRO -> RawRecord.Gyro(time, readShort(data), readShort(data), readShort(data))
                    else -> {
                        val bytes = ByteArray(readVarint(data).toInt().coerceIn(0, 4096))
                        data.readFully(bytes)
                        RawRecord.Mark(time, bytes.decodeToString())
                    }
                },
            )
        }
    } catch (_: EOFException) {
        // Torn tail after a crash: keep what is complete.
    }
}

private fun readShort(data: DataInputStream): Short {
    val low = data.readUnsignedByte()
    val high = data.readUnsignedByte()
    return ((high shl 8) or low).toShort()
}

private fun readVarint(data: DataInputStream): Long {
    var result = 0L
    var shift = 0
    while (shift < 64) {
        val b = data.readUnsignedByte()
        result = result or ((b and 0x7F).toLong() shl shift)
        if (b and 0x80 == 0) return result
        shift += 7
    }
    throw EOFException("varint too long")
}
