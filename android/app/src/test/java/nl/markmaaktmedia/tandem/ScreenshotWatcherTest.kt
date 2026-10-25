package nl.markmaaktmedia.tandem

import nl.markmaaktmedia.tandem.share.ScreenshotWatcher
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotWatcherTest {
    @Test
    fun aScreenshotIsInAScreenshotsFolderOrNamedLikeOne() {
        assertTrue(ScreenshotWatcher.isScreenshot("Pictures/Screenshots/", "Screenshot_20261025-101500.png"))
        assertTrue(ScreenshotWatcher.isScreenshot("DCIM/Screenshots/", "Schermafbeelding_20261025.png"))
        assertTrue(ScreenshotWatcher.isScreenshot("Pictures/", "Screenshot_20261025.png"))
        assertFalse(ScreenshotWatcher.isScreenshot("DCIM/Camera/", "PXL_20261025.jpg"))
    }

    /** One that another device sent lands in Downloads; taking it for a new one would send it straight back. */
    @Test
    fun whatCameInIsNoScreenshot() {
        assertFalse(ScreenshotWatcher.isScreenshot("Download/", "Screenshot 2026-10-25 at 10.15.00.png"))
        assertFalse(ScreenshotWatcher.isScreenshot("Download/Screenshots/", "Screenshot.png"))
    }
}
