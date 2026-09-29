package nl.markmaaktmedia.tandem

import nl.markmaaktmedia.tandem.ui.remote.MacKeys
import nl.markmaaktmedia.tandem.ui.remote.Mods
import nl.markmaaktmedia.tandem.ui.remote.TextDiff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MacKeysTest {
    @Test fun lettersMapToTheirPositionsNotTheirAlphabetOrder() {
        assertEquals(MacKeys.Stroke(0), MacKeys.strokeFor('a'))
        assertEquals(MacKeys.Stroke(8), MacKeys.strokeFor('c'))
        assertEquals(MacKeys.Stroke(9), MacKeys.strokeFor('v'))
        assertEquals(MacKeys.Stroke(6), MacKeys.strokeFor('z'))
    }

    @Test fun capitalsAddShift() {
        assertEquals(MacKeys.Stroke(0, Mods.SHIFT), MacKeys.strokeFor('A'))
    }

    @Test fun symbolsOnTopOfAKeyAddShiftToThatKey() {
        assertEquals(MacKeys.Stroke(18, Mods.SHIFT), MacKeys.strokeFor('!'))
        assertEquals(MacKeys.Stroke(44, Mods.SHIFT), MacKeys.strokeFor('?'))
        assertEquals(MacKeys.Stroke(50, Mods.SHIFT), MacKeys.strokeFor('~'))
        assertEquals(MacKeys.Stroke(33, Mods.SHIFT), MacKeys.strokeFor('{'))
    }

    @Test fun spaceNewlineAndTab() {
        assertEquals(MacKeys.Stroke(49), MacKeys.strokeFor(' '))
        assertEquals(MacKeys.Stroke(MacKeys.RETURN), MacKeys.strokeFor('\n'))
        assertEquals(MacKeys.Stroke(MacKeys.TAB), MacKeys.strokeFor('\t'))
    }

    @Test fun charactersWithoutAKeyAreLeftAlone() {
        assertNull(MacKeys.strokeFor('é'))
        assertNull(MacKeys.strokeFor('Ж'))
    }

    @Test fun typingIsAnInsertion() {
        assertEquals(TextDiff.Edit(0, "b"), TextDiff.between("a", "ab"))
    }

    @Test fun backspaceIsADeletion() {
        assertEquals(TextDiff.Edit(1, ""), TextDiff.between("ab", "a"))
    }

    @Test fun anAutocorrectThatReplacesAWordDeletesThenTypes() {
        assertEquals(TextDiff.Edit(2, "he"), TextDiff.between("teh", "the"))
    }

    @Test fun nothingChangedIsNothing() {
        assertEquals(TextDiff.Edit(0, ""), TextDiff.between("same", "same"))
    }

    @Test fun anEmojiIsOneDeletionNotTwo() {
        assertEquals(TextDiff.Edit(1, ""), TextDiff.between("a😀", "a"))
    }
}
