package nl.markmaaktmedia.tandem

import nl.markmaaktmedia.tandem.capture.CaptureFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Calendar
import java.util.Date

class CaptureFilesTest {
    @Test
    fun a_name_has_no_colons_and_keeps_the_extension() {
        val at = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 4, 14, 31, 22) }.time
        assertEquals("Photo 2026-10-04 14.31.22.jpg", CaptureFiles.fileName("Photo", "jpg", at))
    }

    @Test
    fun grey_paper_is_stretched_to_white_and_ink_to_black() {
        // Light grey paper with a few dark marks, the way a photographed page looks.
        val paper = 0xFFB4B4B4.toInt()
        val ink = 0xFF3C3C3C.toInt()
        val pixels = IntArray(1000) { if (it % 10 == 0) ink else paper }
        val out = CaptureFiles.stretchLevels(pixels)
        assertEquals(0xFF, (out[1] shr 16) and 0xFF)
        assertEquals(0x00, (out[0] shr 16) and 0xFF)
    }

    @Test
    fun a_flat_picture_is_left_alone() {
        val pixels = IntArray(100) { 0xFF808080.toInt() }
        assertTrue(CaptureFiles.stretchLevels(pixels).contentEquals(pixels))
    }

    @Test
    fun only_old_files_are_swept() {
        val folder = File.createTempFile("captures", "").apply { delete(); mkdirs() }
        val old = File(folder, "old.jpg").apply { writeText("x"); setLastModified(1_000) }
        val fresh = File(folder, "fresh.jpg").apply { writeText("x") }
        assertEquals(1, CaptureFiles.sweep(folder, keepMillis = 60_000, now = Date().time))
        assertTrue(!old.exists())
        assertTrue(fresh.exists())
        folder.deleteRecursively()
    }
}
