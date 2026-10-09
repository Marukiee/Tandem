package nl.markmaaktmedia.tandem.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals as eq
import org.junit.Test
import uniffi.tandem_core.TandemEdge

class PointerTrackTest {
    @Test fun thePointerArrivesAtTheOppositeSideAtTheSameHeight() {
        // It left a Mac by its right edge, half way down: here it comes in at the left, half way down.
        val t = PointerTrack.enter(1000f, 2000f, TandemEdge.RIGHT, 0.5f)
        assertEquals(TandemEdge.LEFT, t.cameInBy)
        assertEquals(0f, t.x, 0.01f)
        assertEquals(999.5f, t.y, 0.01f)
        val top = PointerTrack.enter(1000f, 2000f, TandemEdge.BOTTOM, 1f)
        assertEquals(TandemEdge.TOP, top.cameInBy)
        assertEquals(999f, top.x, 0.01f)
        assertEquals(0f, top.y, 0.01f)
    }

    @Test fun movingInsideStaysAndMovingBackOutLeavesAtTheHeightItWas() {
        val t = PointerTrack.enter(1000f, 2000f, TandemEdge.RIGHT, 0.25f)
        assertNull(t.move(300f, 0f, hold = false))
        assertEquals(300f, t.x, 0.01f)
        val along = t.move(-400f, 0f, hold = false)
        assertNotNull(along)
        assertEquals(0.25f, along!!, 0.01f)
    }

    @Test fun aDragPushedAgainstTheEdgeStaysUntilTheButtonIsUp() {
        val t = PointerTrack.enter(1000f, 2000f, TandemEdge.RIGHT, 0.5f)
        assertNull(t.move(-50f, 0f, hold = true))
        assertEquals(0f, t.x, 0.01f)
        assertNotNull(t.move(-1f, 0f, hold = false))
    }

    @Test fun theOtherEdgesOnlyStopThePointer() {
        val t = PointerTrack.enter(1000f, 2000f, TandemEdge.RIGHT, 0.5f)
        assertNull(t.move(0f, 9000f, hold = false))
        assertEquals(1999f, t.y, 0.01f)
        assertNull(t.move(9000f, 0f, hold = false))
        assertEquals(999f, t.x, 0.01f)
    }
}
