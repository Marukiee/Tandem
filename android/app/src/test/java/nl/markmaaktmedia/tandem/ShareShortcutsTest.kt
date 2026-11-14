package nl.markmaaktmedia.tandem

import nl.markmaaktmedia.tandem.share.ShareShortcuts
import nl.markmaaktmedia.tandem.share.ShareShortcuts.Picked
import nl.markmaaktmedia.tandem.share.ShareShortcuts.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareShortcutsTest {
    private val secret = "0123456789abcdef0123456789abcdef"

    @Test
    fun onlyAnIdWithTheSecretSendsWithoutATap() {
        assertEquals(Picked(Target.All, proven = true), ShareShortcuts.parse("all:$secret", secret))
        assertEquals(Picked(Target.Device("abc123"), proven = true), ShareShortcuts.parse("device:$secret:abc123", secret))
    }

    @Test
    fun anyOtherAppGetsTheSheet() {
        // What another app can send: the old forms, a guess, or nothing after the colon.
        assertEquals(Picked(Target.All, proven = false), ShareShortcuts.parse("all", secret))
        assertEquals(Picked(Target.All, proven = false), ShareShortcuts.parse("all:guess", secret))
        assertEquals(Picked(Target.All, proven = false), ShareShortcuts.parse("all:", secret))
        assertEquals(Picked(Target.Device("abc123"), proven = false), ShareShortcuts.parse("device:abc123", secret))
        assertEquals(Picked(Target.Device("abc123"), proven = false), ShareShortcuts.parse("device:guess:abc123", secret))
        assertEquals(Picked(Target.Device("abc123"), proven = false), ShareShortcuts.parse("device::abc123", secret))
        assertNull(ShareShortcuts.parse("device:$secret:", secret))
        assertNull(ShareShortcuts.parse("something", secret))
    }
}
