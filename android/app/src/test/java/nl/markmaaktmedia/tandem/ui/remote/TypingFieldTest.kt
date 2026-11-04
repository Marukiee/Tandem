package nl.markmaaktmedia.tandem.ui.remote

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TypingFieldTest {
    private fun typed(length: Int) = "x".repeat(length).let { TextFieldValue(it, TextRange(it.length)) }

    /** It used to empty at 41 letters, which looks as if all of it was gone. */
    @Test fun whatIsTypedStaysInTheField() {
        val field = typed(500)
        assertSame(field, keptTail(field))
    }

    @Test fun onlyWhatScrolledFarOutOfViewIsCutAndTheCursorStaysAtTheEnd() {
        val kept = keptTail(typed(2001))
        assertEquals(1000, kept.text.length)
        assertEquals(TextRange(1000), kept.selection)
    }

    @Test fun aWordBeingComposedIsNotCut() {
        val field = TextFieldValue("x".repeat(2001), TextRange(2001), TextRange(1990, 2001))
        assertSame(field, keptTail(field))
    }
}
