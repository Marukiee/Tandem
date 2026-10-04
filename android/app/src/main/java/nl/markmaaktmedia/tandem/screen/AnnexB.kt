package nl.markmaaktmedia.tandem.screen

/**
 * Just enough of H.264 in Annex B to run a decoder: find the NAL units, tell a keyframe, take the parameter sets out
 * and read the size from the SPS. The decoder is configured from the sets of the keyframe that starts it, and
 * rebuilt when a later keyframe carries a different size.
 */
object AnnexB {
    const val NAL_SLICE = 1
    const val NAL_IDR = 5
    const val NAL_SPS = 7
    const val NAL_PPS = 8

    class ParameterSets(val sps: ByteArray, val pps: ByteArray) {
        override fun equals(other: Any?) = other is ParameterSets && sps.contentEquals(other.sps) && pps.contentEquals(other.pps)
        override fun hashCode() = 31 * sps.contentHashCode() + pps.contentHashCode()
    }

    /** Where each NAL unit is, start codes left out. */
    fun nalRanges(data: ByteArray): List<IntRange> {
        val codes = ArrayList<IntArray>()
        var i = 0
        while (i + 3 <= data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                if (data[i + 2].toInt() == 1) {
                    codes += intArrayOf(i, i + 3)
                    i += 3
                    continue
                }
                if (i + 4 <= data.size && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) {
                    codes += intArrayOf(i, i + 4)
                    i += 4
                    continue
                }
            }
            i++
        }
        val out = ArrayList<IntRange>(codes.size)
        for (n in codes.indices) {
            val start = codes[n][1]
            val end = if (n + 1 < codes.size) codes[n + 1][0] else data.size
            if (start < end) out += start until end
        }
        return out
    }

    fun nalType(data: ByteArray, range: IntRange): Int = data[range.first].toInt() and 0x1F

    fun isKeyframe(data: ByteArray): Boolean = nalRanges(data).any { nalType(data, it) == NAL_IDR }

    /** The first SPS and PPS, or null when the buffer has not got both. */
    fun parameterSets(data: ByteArray): ParameterSets? {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        for (range in nalRanges(data)) {
            when (nalType(data, range)) {
                NAL_SPS -> if (sps == null) sps = data.copyOfRange(range.first, range.last + 1)
                NAL_PPS -> if (pps == null) pps = data.copyOfRange(range.first, range.last + 1)
            }
        }
        return if (sps != null && pps != null) ParameterSets(sps, pps) else null
    }

    /** A NAL unit with its start code back on, which is the shape a decoder wants its codec specific data in. */
    fun withStartCode(nal: ByteArray): ByteArray = byteArrayOf(0, 0, 0, 1) + nal

    /** Width and height of the picture an SPS describes, after cropping. Null when it cannot be read. */
    fun dimensions(sps: ByteArray): Pair<Int, Int>? {
        if (sps.size < 4) return null
        val reader = BitReader(unescape(sps.copyOfRange(1, sps.size)))
        return runCatching {
            val profile = reader.bits(8)
            reader.bits(16)
            reader.ue()
            var chroma = 1
            if (profile in HIGH_PROFILES) {
                chroma = reader.ue()
                if (chroma == 3) reader.bits(1)
                reader.ue()
                reader.ue()
                reader.bits(1)
                if (reader.bits(1) == 1) {
                    for (index in 0 until if (chroma != 3) 8 else 12) {
                        if (reader.bits(1) == 1) {
                            var last = 8
                            var next = 8
                            for (n in 0 until if (index < 6) 16 else 64) {
                                if (next != 0) {
                                    next = (last + reader.se() + 256) % 256
                                    if (next != 0) last = next
                                }
                            }
                        }
                    }
                }
            }
            reader.ue()
            when (reader.ue()) {
                0 -> reader.ue()
                1 -> {
                    reader.bits(1)
                    reader.se()
                    reader.se()
                    repeat(reader.ue()) { reader.se() }
                }
            }
            reader.ue()
            reader.bits(1)
            val widthMbs = reader.ue()
            val heightUnits = reader.ue()
            val frameMbsOnly = reader.bits(1)
            if (frameMbsOnly == 0) reader.bits(1)
            reader.bits(1)
            var left = 0
            var right = 0
            var top = 0
            var bottom = 0
            if (reader.bits(1) == 1) {
                left = reader.ue()
                right = reader.ue()
                top = reader.ue()
                bottom = reader.ue()
            }
            val unitX = if (chroma == 0 || chroma == 3) 1 else 2
            val unitY = (if (chroma == 1) 2 else 1) * (2 - frameMbsOnly)
            val width = (widthMbs + 1) * 16 - unitX * (left + right)
            val height = (2 - frameMbsOnly) * (heightUnits + 1) * 16 - unitY * (top + bottom)
            width to height
        }.getOrNull()
    }

    private val HIGH_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)

    /** The emulation prevention bytes (00 00 03) taken out, so the bits read as they were written. */
    private fun unescape(bytes: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(bytes.size)
        var zeros = 0
        for (b in bytes) {
            if (zeros >= 2 && b.toInt() == 3) {
                zeros = 0
                continue
            }
            out.write(b.toInt())
            zeros = if (b.toInt() == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private class BitReader(private val bytes: ByteArray) {
        private var position = 0

        fun bits(count: Int): Int {
            var value = 0
            repeat(count) {
                check(position / 8 < bytes.size) { "out of bits" }
                value = (value shl 1) or ((bytes[position / 8].toInt() shr (7 - position % 8)) and 1)
                position++
            }
            return value
        }

        fun ue(): Int {
            var zeros = 0
            while (bits(1) == 0) {
                zeros++
                check(zeros <= 31) { "bad exp-golomb" }
            }
            if (zeros == 0) return 0
            return (1 shl zeros) - 1 + bits(zeros)
        }

        fun se(): Int {
            val value = ue()
            return if (value % 2 == 1) (value + 1) / 2 else -(value / 2)
        }
    }
}
