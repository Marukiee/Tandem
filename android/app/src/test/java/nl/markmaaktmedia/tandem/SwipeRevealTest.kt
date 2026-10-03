package nl.markmaaktmedia.tandem

import nl.markmaaktmedia.tandem.ui.components.revealShown
import org.junit.Assert.assertEquals
import org.junit.Test

class SwipeRevealTest {
    @Test fun thePanelGrowsOnTheSideTheRowWasPulledTo() {
        assertEquals(30f, revealShown(-30f, side = -1), 0f)
        assertEquals(30f, revealShown(30f, side = 1), 0f)
    }

    @Test fun overshootingRestShowsNothingOnTheOtherSide() {
        // Pulled left, the bounce on letting go swings a few points to the right of rest.
        assertEquals(0f, revealShown(4f, side = -1), 0f)
        // And the same the other way round.
        assertEquals(0f, revealShown(-4f, side = 1), 0f)
    }

    @Test fun beforeTheSideIsKnownTheOffsetDecides() {
        assertEquals(5f, revealShown(-5f, side = 0), 0f)
        assertEquals(5f, revealShown(5f, side = 0), 0f)
        assertEquals(0f, revealShown(0f, side = 0), 0f)
    }
}
