package nl.markmaaktmedia.tandem.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MacKeyTextTest {
    @Test fun aKeyTypesWhatIsPrintedOnItAndShiftChangesIt() {
        assertEquals("a", MacKeyText.text(0, shift = false))
        assertEquals("A", MacKeyText.text(0, shift = true))
        assertEquals("1", MacKeyText.text(18, shift = false))
        assertEquals("!", MacKeyText.text(18, shift = true))
        assertEquals(" ", MacKeyText.text(49, shift = false))
        assertEquals("\n", MacKeyText.text(36, shift = false))
    }

    @Test fun keysThatTypeNothingGiveNothing() {
        assertNull(MacKeyText.text(MacKeyText.ESCAPE, shift = false))
        assertNull(MacKeyText.text(123, shift = false))
    }
}
