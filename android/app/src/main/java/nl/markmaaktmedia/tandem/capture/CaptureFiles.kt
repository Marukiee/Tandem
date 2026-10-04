package nl.markmaaktmedia.tandem.capture

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where the pictures for the Mac are made, what they are called, and the small amount of image
 * arithmetic that does not need a screen. Kept free of Android types where it can be, so the unit
 * tests can run it.
 */
object CaptureFiles {

    /** The name the Mac sees: "Photo 2026-10-04 14.31.22.jpg". No colons, which Finder turns into slashes. */
    fun fileName(prefix: String, extension: String, at: Date = Date()): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US).format(at)
        return "$prefix $stamp.$extension"
    }

    /** A cache folder: what is made here is sent once and never needs to stay. */
    fun folder(context: Context): File = File(context.cacheDir, "captures").apply { mkdirs() }

    /** Removes what is older than [keepMillis]. The Mac pulls a file soon after the offer, so hours are plenty. */
    fun sweep(folder: File, keepMillis: Long = 6 * 60 * 60 * 1000L, now: Long = System.currentTimeMillis()): Int {
        var removed = 0
        folder.listFiles()?.forEach { file ->
            if (file.isFile && now - file.lastModified() > keepMillis && file.delete()) removed++
        }
        return removed
    }

    /**
     * Makes a page of a photographed document look like a scan: the levels are stretched so the paper
     * goes to white and the ink to dark. The lightest [clip] of the pixels become white, the darkest
     * [clip] become black, and everything between is spread over the whole range.
     */
    fun stretchLevels(pixels: IntArray, clip: Double = 0.015): IntArray {
        if (pixels.isEmpty()) return pixels
        val histogram = IntArray(256)
        for (pixel in pixels) histogram[luma(pixel)]++
        val cut = (pixels.size * clip).toInt()
        var low = 0
        var seen = 0
        while (low < 255 && seen + histogram[low] <= cut) {
            seen += histogram[low]
            low++
        }
        var high = 255
        seen = 0
        while (high > low && seen + histogram[high] <= cut) {
            seen += histogram[high]
            high--
        }
        if (high - low < 8) return pixels
        val scale = 255.0 / (high - low)
        val lookup = IntArray(256) { value -> ((value - low) * scale).toInt().coerceIn(0, 255) }
        return IntArray(pixels.size) { index ->
            val pixel = pixels[index]
            val alpha = pixel ushr 24
            val r = lookup[(pixel shr 16) and 0xFF]
            val g = lookup[(pixel shr 8) and 0xFF]
            val b = lookup[pixel and 0xFF]
            (alpha shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private fun luma(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }
}
