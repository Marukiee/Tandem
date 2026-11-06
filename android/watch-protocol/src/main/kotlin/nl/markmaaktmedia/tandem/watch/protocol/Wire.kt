package nl.markmaaktmedia.tandem.watch.protocol

import java.io.ByteArrayOutputStream

class ProtocolException(message: String) : Exception(message)

/** Compact binary writer. Strings are UTF-8 with a varint length prefix. */
class ByteWriter {
    private val out = ByteArrayOutputStream()

    fun u8(value: Int) = apply { out.write(value and 0xFF) }

    fun bool(value: Boolean) = u8(if (value) 1 else 0)

    fun varint(value: Long) = apply {
        require(value >= 0) { "varint must not be negative" }
        var v = value
        while (v >= 0x80) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    /** Zigzag, so that a small movement to the left costs as little as one to the right. */
    fun svarint(value: Int) = varint(((value shl 1) xor (value shr 31)).toLong() and 0xFFFFFFFFL)

    fun bytes(value: ByteArray) = apply {
        varint(value.size.toLong())
        out.write(value)
    }

    fun raw(value: ByteArray) = apply { out.write(value) }

    fun str(value: String) = bytes(value.encodeToByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()
}

/** Bounds-checked reader. Every read past the end throws, so malformed input can never crash with an index error. */
class ByteReader(private val data: ByteArray, private var pos: Int = 0) {
    val remaining: Int get() = data.size - pos

    fun u8(): Int {
        if (remaining < 1) throw ProtocolException("truncated u8")
        return data[pos++].toInt() and 0xFF
    }

    fun bool(): Boolean = u8() != 0

    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (shift < 63) {
            val b = u8()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
        throw ProtocolException("varint too long")
    }

    fun svarint(): Int {
        val v = varint()
        return ((v ushr 1) xor -(v and 1)).toInt()
    }

    fun bytes(): ByteArray {
        val length = varint()
        if (length > remaining) throw ProtocolException("truncated bytes")
        return raw(length.toInt())
    }

    fun raw(length: Int): ByteArray {
        if (length < 0 || length > remaining) throw ProtocolException("truncated raw")
        val result = data.copyOfRange(pos, pos + length)
        pos += length
        return result
    }

    fun str(): String = bytes().decodeToString()

    fun rest(): ByteArray = raw(remaining)
}
