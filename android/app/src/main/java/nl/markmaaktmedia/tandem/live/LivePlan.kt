package nl.markmaaktmedia.tandem.live

import kotlin.math.max
import kotlin.math.min

/**
 * What the phone sends, worked out from what the screen or camera has and what the Mac asked for. Plain numbers in,
 * plain numbers out, so the rules can be tested without a phone.
 */
object LivePlan {
    data class Size(val width: Int, val height: Int)

    /** A request's limits. Zero means the Mac has no preference. */
    data class Limits(val maxWidth: Int, val maxHeight: Int, val maxFps: Int, val maxBitrate: Int)

    data class Plan(val width: Int, val height: Int, val fps: Int, val bitrate: Int)

    /** What is used when the Mac says nothing about the frame rate. 30 is smooth and does not drain the battery. */
    const val DEFAULT_FPS = 30
    const val MAX_FPS = 60

    /** Encoders want sizes they can cut into blocks. A multiple of 8 loses at most 7 pixels and is accepted everywhere. */
    private const val ALIGN = 8
    private const val MIN_SIDE = 16

    /**
     * The source fitted in a box of the shape the Mac gave, in whichever orientation the source has: the Mac asks for
     * "1920 by 1080" and a portrait phone gets 1080 by 1920 at most. Never larger than the source.
     */
    fun fit(sourceWidth: Int, sourceHeight: Int, maxWidth: Int, maxHeight: Int): Size {
        if (sourceWidth <= 0 || sourceHeight <= 0) return Size(MIN_SIDE, MIN_SIDE)
        var scale = 1.0
        if (maxWidth > 0 && maxHeight > 0) {
            val long = max(maxWidth, maxHeight)
            val short = min(maxWidth, maxHeight)
            val boxWidth = if (sourceWidth >= sourceHeight) long else short
            val boxHeight = if (sourceWidth >= sourceHeight) short else long
            scale = min(1.0, min(boxWidth.toDouble() / sourceWidth, boxHeight.toDouble() / sourceHeight))
        } else if (maxWidth > 0 || maxHeight > 0) {
            val limit = max(maxWidth, maxHeight)
            scale = min(1.0, limit.toDouble() / max(sourceWidth, sourceHeight))
        }
        return Size(align(sourceWidth * scale), align(sourceHeight * scale))
    }

    private fun align(value: Double): Int = max(MIN_SIDE, (value.toInt() / ALIGN) * ALIGN)

    fun fps(requested: Int): Int = if (requested <= 0) DEFAULT_FPS else requested.coerceIn(10, MAX_FPS)

    /**
     * About 0.07 bits for every pixel of every frame, which is clean for a screen and a camera at 30 frames, with a
     * floor so a small picture is not starved and a ceiling a phone's radio can carry. The Mac's own maximum wins.
     */
    fun bitrate(width: Int, height: Int, fps: Int, requestedMax: Int): Int {
        val wanted = (width.toLong() * height * fps * 0.07).toLong().coerceIn(1_500_000L, 16_000_000L).toInt()
        return if (requestedMax > 0) min(wanted, max(requestedMax, 500_000)) else wanted
    }

    fun plan(sourceWidth: Int, sourceHeight: Int, limits: Limits): Plan {
        val size = fit(sourceWidth, sourceHeight, limits.maxWidth, limits.maxHeight)
        val fps = fps(limits.maxFps)
        return Plan(size.width, size.height, fps, bitrate(size.width, size.height, fps, limits.maxBitrate))
    }

    /** `Surface.ROTATION_*` for the angle an orientation sensor reports (0 to 359, or -1 when it does not know). */
    fun surfaceRotation(sensorDegrees: Int): Int? = when (sensorDegrees) {
        in 0..359 -> when (sensorDegrees) {
            in 45..134 -> 3 // Surface.ROTATION_270
            in 135..224 -> 2 // Surface.ROTATION_180
            in 225..314 -> 1 // Surface.ROTATION_90
            else -> 0
        }
        else -> null
    }

    /** The picture of a bitrate for a person: "5.8 Mbit/s". */
    fun megabits(bitsPerSecond: Int): String = String.format(java.util.Locale.US, "%.1f Mbit/s", bitsPerSecond / 1_000_000.0)
}

/** The bits of Annex B the host needs to look at. */
object AnnexBScan {
    /** The NAL unit types in an access unit, in order. */
    fun nalTypes(data: ByteArray): List<Int> {
        val types = ArrayList<Int>()
        var i = 0
        while (i + 3 < data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                types += data[i + 3].toInt() and 0x1F
                i += 3
            } else {
                i++
            }
        }
        return types
    }

    fun startsWithStartCode(data: ByteArray): Boolean =
        data.size > 4 && data[0].toInt() == 0 && data[1].toInt() == 0 &&
            (data[2].toInt() == 1 || (data[2].toInt() == 0 && data[3].toInt() == 1))

    fun hasParameterSets(data: ByteArray): Boolean = nalTypes(data).let { 7 in it && 8 in it }

    fun hasKeyframe(data: ByteArray): Boolean = 5 in nalTypes(data)
}
