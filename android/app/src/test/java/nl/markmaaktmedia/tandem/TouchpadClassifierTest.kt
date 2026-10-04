package nl.markmaaktmedia.tandem

import nl.markmaaktmedia.tandem.ui.remote.PadFeedback
import nl.markmaaktmedia.tandem.ui.remote.SwipeDirection
import nl.markmaaktmedia.tandem.ui.remote.Touch
import nl.markmaaktmedia.tandem.ui.remote.TouchpadClassifier
import nl.markmaaktmedia.tandem.ui.remote.TouchpadConfig
import nl.markmaaktmedia.tandem.ui.remote.TouchpadOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchpadClassifierTest {
    private val log = mutableListOf<String>()

    // What the screen is told for the haptics and for the cue that the button is held, apart from the wire log.
    private val feedbacks = mutableListOf<PadFeedback>()
    private val dragging = mutableListOf<Boolean>()
    private val out = object : TouchpadOutput {
        override fun pointer(dx: Int, dy: Int) { log += "pointer" }
        override fun scroll(dx: Int, dy: Int) { log += "scroll" }
        override fun click(button: Int, count: Int) { log += "click($button,$count)" }
        override fun button(button: Int, down: Boolean) { log += "button($button,${if (down) "down" else "up"})" }
        override fun swipe(direction: SwipeDirection) { log += "swipe($direction)" }
        override fun feedback(kind: PadFeedback) { feedbacks += kind }
        override fun dragging(active: Boolean) { dragging += active }
    }

    // Sums as well, because what matters for moving is that nothing is lost on the way.
    private var pointerX = 0
    private var pointerY = 0
    private var scrollX = 0
    private var scrollY = 0
    private val summing = object : TouchpadOutput by out {
        override fun pointer(dx: Int, dy: Int) { pointerX += dx; pointerY += dy; out.pointer(dx, dy) }
        override fun scroll(dx: Int, dy: Int) { scrollX += dx; scrollY += dy; out.scroll(dx, dy) }
    }

    private val pad = TouchpadClassifier(TouchpadConfig(slop = 20f, swipeDistance = 150f), summing)

    private fun frame(t: Long, vararg fingers: Touch) = pad.onFrame(t, fingers.toList())
    private fun up(t: Long) = pad.onFrame(t, emptyList())
    private fun f(id: Long, x: Float, y: Float) = Touch(id, x, y)

    private fun assertLog(vararg expected: String) = assertEquals(expected.toList(), log)

    // One finger

    @Test fun tapClicks() {
        frame(0, f(1, 100f, 100f))
        up(90)
        assertLog("click(0,1)")
    }

    @Test fun tapWithASmallWobbleStillClicksAndNeverMovesThePointer() {
        frame(0, f(1, 100f, 100f))
        frame(30, f(1, 106f, 103f))
        frame(60, f(1, 104f, 101f))
        up(90)
        assertLog("click(0,1)")
    }

    @Test fun slowPressWithoutMovingDoesNotClickAndDoesNotDragOnItsOwn() {
        frame(0, f(1, 100f, 100f))
        up(600)
        // The timer was never asked, so no drag began, and 600 ms is too slow for a tap.
        assertLog()
    }

    @Test fun movingMovesThePointerByTheWholeDistanceIncludingTheSlopCircle() {
        frame(0, f(1, 100f, 100f))
        frame(16, f(1, 110f, 100f))
        frame(32, f(1, 130f, 100f))
        frame(48, f(1, 160f, 100f))
        up(64)
        assertEquals(60, pointerX)
        assertEquals(0, pointerY)
        assertTrue(log.none { it.startsWith("click") })
    }

    @Test fun subPixelMovementAddsUpInsteadOfVanishing() {
        frame(0, f(1, 0f, 0f))
        frame(10, f(1, 30f, 0f))
        for (i in 1..40) frame(10L + i * 8, f(1, 30f + i * 0.25f, 0f))
        up(400)
        assertEquals(40, pointerX)
    }

    @Test fun twoTapsInARowDoubleClick() {
        frame(0, f(1, 100f, 100f)); up(60)
        frame(150, f(2, 100f, 100f)); up(200)
        assertLog("click(0,1)", "click(0,2)")
    }

    @Test fun tapsFarApartInTimeStaySingleClicks() {
        frame(0, f(1, 100f, 100f)); up(60)
        frame(700, f(2, 100f, 100f)); up(760)
        assertLog("click(0,1)", "click(0,1)")
    }

    @Test fun aThirdTapAfterADoubleClickStartsOver() {
        frame(0, f(1, 0f, 0f)); up(50)
        frame(120, f(2, 0f, 0f)); up(170)
        frame(240, f(3, 0f, 0f)); up(290)
        assertLog("click(0,1)", "click(0,2)", "click(0,1)")
    }

    // Drag

    @Test fun holdingStillThenTheTimerPicksUpTheButtonAndMovingDrags() {
        frame(0, f(1, 100f, 100f))
        assertEquals(280L, pad.deadline())
        pad.onTimer(280)
        frame(400, f(1, 130f, 100f))
        up(500)
        assertLog("button(0,down)", "pointer", "button(0,up)")
        assertEquals(30, pointerX)
    }

    @Test fun timerBeforeTheDeadlineDoesNothing() {
        frame(0, f(1, 100f, 100f))
        pad.onTimer(200)
        assertLog()
    }

    @Test fun holdWithoutMovingReleasesAsPlainDownUp() {
        frame(0, f(1, 100f, 100f))
        pad.onTimer(280)
        up(700)
        assertLog("button(0,down)", "button(0,up)")
    }

    @Test fun doubleTapAndDragHoldsTheButtonWhileMoving() {
        frame(0, f(1, 100f, 100f)); up(60)
        frame(150, f(2, 100f, 100f))
        // Nothing yet: it could still turn out to be a double click.
        assertLog("click(0,1)")
        frame(170, f(2, 125f, 100f))
        frame(190, f(2, 175f, 100f))
        up(220)
        assertLog("click(0,1)", "button(0,down)", "pointer", "pointer", "button(0,up)")
        assertEquals(75, pointerX)
    }

    @Test fun doubleTapAndDragDoesNotAlsoDoubleClick() {
        frame(0, f(1, 100f, 100f)); up(60)
        frame(150, f(2, 100f, 100f))
        frame(170, f(2, 160f, 100f))
        up(220)
        assertTrue(log.none { it == "click(0,2)" })
    }

    @Test fun doubleTapThenHoldStillDragsOnTheTimer() {
        frame(0, f(1, 100f, 100f)); up(60)
        frame(150, f(2, 100f, 100f))
        assertEquals(430L, pad.deadline())
        pad.onTimer(430)
        up(600)
        assertLog("click(0,1)", "button(0,down)", "button(0,up)")
    }

    @Test fun cancelReleasesAHeldButton() {
        frame(0, f(1, 100f, 100f))
        pad.onTimer(280)
        pad.cancel()
        assertLog("button(0,down)", "button(0,up)")
        // and it is quiet afterwards
        up(500)
        assertLog("button(0,down)", "button(0,up)")
    }

    @Test fun cancelAfterAFinishedTapKeepsTheDoubleTap() {
        frame(0, f(1, 100f, 100f)); up(60)
        pad.cancel()
        frame(150, f(2, 100f, 100f)); up(200)
        assertLog("click(0,1)", "click(0,2)")
    }

    // Two fingers

    @Test fun twoFingerTapRightClicks() {
        frame(0, f(1, 100f, 100f))
        frame(20, f(1, 100f, 100f), f(2, 200f, 100f))
        up(90)
        assertLog("click(1,1)")
    }

    @Test fun twoFingerTapWithStaggeredLiftRightClicks() {
        frame(0, f(1, 100f, 100f), f(2, 200f, 100f))
        frame(70, f(1, 100f, 100f))
        up(95)
        assertLog("click(1,1)")
    }

    @Test fun twoFingerTapDoesNotBecomeADoubleClickWithTheTapBefore() {
        frame(0, f(1, 100f, 100f)); up(60)
        frame(120, f(2, 100f, 100f), f(3, 200f, 100f)); up(180)
        assertLog("click(0,1)", "click(1,1)")
    }

    @Test fun twoFingersMovingScrollAndNeverMoveThePointer() {
        frame(0, f(1, 100f, 100f), f(2, 200f, 100f))
        frame(16, f(1, 100f, 120f), f(2, 200f, 120f))
        frame(32, f(1, 100f, 160f), f(2, 200f, 160f))
        frame(48, f(1, 100f, 200f), f(2, 200f, 200f))
        up(64)
        assertEquals(100, scrollY)
        assertEquals(0, scrollX)
        assertEquals(0, pointerX + pointerY)
        assertTrue(log.none { it.startsWith("click") })
    }

    @Test fun scrollWorksWhenTheSecondFingerLandsLate() {
        frame(0, f(1, 100f, 100f))
        frame(60, f(1, 100f, 100f), f(2, 200f, 100f))
        frame(76, f(1, 100f, 140f), f(2, 200f, 140f))
        frame(92, f(1, 100f, 180f), f(2, 200f, 180f))
        up(120)
        assertEquals(80, scrollY)
        assertEquals(0, pointerY)
    }

    @Test fun aFingerLandingMidMoveTurnsItIntoAScroll() {
        frame(0, f(1, 100f, 100f))
        frame(16, f(1, 100f, 150f))
        val moved = pointerY
        frame(400, f(1, 100f, 150f), f(2, 200f, 150f))
        frame(416, f(1, 100f, 190f), f(2, 200f, 190f))
        up(450)
        assertEquals(moved, pointerY)
        assertEquals(40, scrollY)
    }

    @Test fun liftingOneFingerOfAScrollDoesNotSendThePointerOff() {
        frame(0, f(1, 100f, 100f), f(2, 200f, 100f))
        frame(16, f(1, 100f, 160f), f(2, 200f, 160f))
        frame(32, f(1, 100f, 200f))
        frame(48, f(1, 100f, 300f))
        up(64)
        assertEquals(0, pointerX + pointerY)
    }

    // Three fingers

    private fun threeFingerSwipe(dx: Float, dy: Float) {
        val a = f(1, 100f, 300f)
        val b = f(2, 200f, 300f)
        val c = f(3, 300f, 300f)
        frame(0, a)
        frame(15, a, b)
        frame(30, a, b, c)
        for (step in 1..10) {
            val s = step / 10f
            frame(30L + step * 16L, f(1, a.x + dx * s, a.y + dy * s), f(2, b.x + dx * s, b.y + dy * s), f(3, c.x + dx * s, c.y + dy * s))
        }
        up(300)
    }

    @Test fun threeFingersUpIsMissionControl() {
        threeFingerSwipe(0f, -260f)
        assertLog("swipe(Up)")
    }

    @Test fun threeFingersDownIsAppExpose() {
        threeFingerSwipe(0f, 260f)
        assertLog("swipe(Down)")
    }

    @Test fun threeFingersLeftAndRightSwitchSpace() {
        threeFingerSwipe(-260f, 0f)
        assertLog("swipe(Left)")
        log.clear()
        threeFingerSwipe(260f, 10f)
        assertLog("swipe(Right)")
    }

    @Test fun aSwipeFiresOnceEvenIfTheFingersKeepGoing() {
        threeFingerSwipe(0f, -600f)
        assertEquals(1, log.count { it.startsWith("swipe") })
    }

    @Test fun threeFingersThatBarelyMoveDoNothing() {
        threeFingerSwipe(20f, -30f)
        assertLog()
    }

    @Test fun aDiagonalWaitsUntilItIsOneOrTheOther() {
        threeFingerSwipe(200f, -200f)
        assertLog()
    }

    @Test fun threeFingersDoNotLeakScrollOrPointerMoves() {
        threeFingerSwipe(0f, -260f)
        assertEquals(0, pointerX + pointerY)
        assertEquals(0, scrollX + scrollY)
    }

    @Test fun aThirdFingerThatArrivesMidScrollIsIgnored() {
        frame(0, f(1, 100f, 100f), f(2, 200f, 100f))
        frame(16, f(1, 100f, 150f), f(2, 200f, 150f))
        frame(500, f(1, 100f, 150f), f(2, 200f, 150f), f(3, 300f, 150f))
        for (step in 1..10) {
            val y = 150f + step * 40f
            frame(500L + step * 16L, f(1, 100f, y), f(2, 200f, y), f(3, 300f, y))
        }
        up(800)
        assertTrue(log.none { it.startsWith("swipe") })
        assertTrue(scrollY > 300)
    }

    @Test fun aRestingThirdFingerLongAfterTheFirstIsNotASwipe() {
        frame(0, f(1, 100f, 300f), f(2, 200f, 300f))
        frame(600, f(1, 100f, 300f), f(2, 200f, 300f), f(3, 300f, 300f))
        for (step in 1..10) {
            val y = 300f - step * 40f
            frame(600L + step * 16L, f(1, 100f, y), f(2, 200f, y), f(3, 300f, y))
        }
        up(900)
        assertLog()
    }

    @Test fun swipeStillCountsWhenTheFirstTwoFingersCreptPastTheSlopBeforeTheThirdLanded() {
        frame(0, f(1, 100f, 300f))
        frame(20, f(1, 100f, 280f), f(2, 200f, 280f))
        frame(40, f(1, 100f, 250f), f(2, 200f, 250f), f(3, 300f, 250f))
        for (step in 1..10) {
            val y = 250f - step * 30f
            frame(40L + step * 16L, f(1, 100f, y), f(2, 200f, y), f(3, 300f, y))
        }
        up(300)
        assertEquals(1, log.count { it == "swipe(Up)" })
    }

    @Test fun deadlineIsOnlySetWhileAHoldCouldStillHappen() {
        assertNull(pad.deadline())
        frame(0, f(1, 100f, 100f))
        assertEquals(280L, pad.deadline())
        frame(16, f(1, 100f, 100f), f(2, 200f, 100f))
        assertNull(pad.deadline())
    }

    // Press and hold to drag, like pressing a mouse button

    @Test fun theHoldTakesTwoHundredAndEightyMilliseconds() {
        frame(0, f(1, 100f, 100f))
        assertEquals(280L, pad.deadline())
        // Woken a little early (the clock of the loop is not exact): nothing yet.
        pad.onTimer(279)
        assertLog()
        pad.onTimer(280)
        assertLog("button(0,down)")
    }

    @Test fun aTapJustUnderTheHoldTimeStillClicks() {
        frame(0, f(1, 100f, 100f))
        up(270)
        assertLog("click(0,1)")
    }

    @Test fun aStillFingerTicksAndTheButtonGoesDownTogether() {
        frame(0, f(1, 100f, 100f))
        pad.onTimer(280)
        assertEquals(listOf(PadFeedback.DragStart), feedbacks)
        assertEquals(listOf(true), dragging)
        assertLog("button(0,down)")
    }

    @Test fun movingTheSameFingerDragsAndLiftingLetsGo() {
        frame(0, f(1, 100f, 100f))
        pad.onTimer(280)
        frame(300, f(1, 120f, 100f))
        frame(316, f(1, 160f, 130f))
        frame(332, f(1, 220f, 130f))
        up(348)
        assertLog("button(0,down)", "pointer", "pointer", "pointer", "button(0,up)")
        assertEquals(120, pointerX)
        assertEquals(30, pointerY)
        assertEquals(listOf(true, false), dragging)
    }

    @Test fun aFingerThatWobblesInsideTheSlopStillHolds() {
        frame(0, f(1, 100f, 100f))
        frame(60, f(1, 108f, 104f))
        frame(140, f(1, 103f, 109f))
        frame(220, f(1, 111f, 98f))
        pad.onTimer(280)
        assertLog("button(0,down)")
        // The wobble did not move the pointer and the drag starts from where the finger is now.
        assertEquals(0, pointerX + pointerY)
        frame(300, f(1, 141f, 98f))
        assertEquals(30, pointerX)
    }

    @Test fun aQuickMoveMovesThePointerAndNeverHolds() {
        frame(0, f(1, 100f, 100f))
        frame(16, f(1, 140f, 100f))
        frame(32, f(1, 200f, 100f))
        // The timer has nothing left to wait for once the finger is moving.
        assertNull(pad.deadline())
        pad.onTimer(280)
        frame(400, f(1, 260f, 100f))
        up(416)
        assertEquals(160, pointerX)
        assertTrue(log.none { it.startsWith("button") })
        assertEquals(emptyList<Boolean>(), dragging)
    }

    @Test fun leavingTheSlopCircleBeforeTheHoldMovesThePointerEvenWhenTheFingerThenRests() {
        frame(0, f(1, 100f, 100f))
        frame(200, f(1, 130f, 100f))
        assertNull(pad.deadline())
        frame(300, f(1, 130f, 100f))
        pad.onTimer(400)
        up(500)
        assertEquals(30, pointerX)
        assertTrue(log.none { it.startsWith("button") })
    }

    @Test fun aTapAfterAHoldDragIsNotADoubleClick() {
        frame(0, f(1, 100f, 100f))
        pad.onTimer(280)
        frame(300, f(1, 140f, 100f))
        up(320)
        frame(400, f(2, 100f, 100f)); up(440)
        assertLog("button(0,down)", "pointer", "button(0,up)", "click(0,1)")
    }

    @Test fun anInterruptedHoldDragLetsGoAndTellsTheScreen() {
        frame(0, f(1, 100f, 100f))
        pad.onTimer(280)
        frame(300, f(1, 140f, 100f))
        pad.cancel()
        assertLog("button(0,down)", "pointer", "button(0,up)")
        assertEquals(listOf(true, false), dragging)
        // Nothing is sent a second time when the finger finally lifts.
        up(400)
        assertLog("button(0,down)", "pointer", "button(0,up)")
        assertEquals(listOf(true, false), dragging)
    }

    @Test fun aTapAndDragShowsTheCueToo() {
        frame(0, f(1, 100f, 100f)); up(60)
        frame(150, f(2, 100f, 100f))
        frame(170, f(2, 160f, 100f))
        up(220)
        assertEquals(listOf(true, false), dragging)
        assertEquals(listOf(PadFeedback.Click, PadFeedback.DragStart), feedbacks)
    }

    @Test fun twoFingersAfterAHoldTouchStartedDoNotDragButScroll() {
        frame(0, f(1, 100f, 100f))
        frame(60, f(1, 100f, 100f), f(2, 200f, 100f))
        assertNull(pad.deadline())
        frame(76, f(1, 100f, 140f), f(2, 200f, 140f))
        frame(92, f(1, 100f, 180f), f(2, 200f, 180f))
        up(120)
        assertEquals(80, scrollY)
        assertTrue(log.none { it.startsWith("button") })
    }

    @Test fun aHoldDragKeepsDraggingWhenAnotherFingerJoinsAndLeaves() {
        frame(0, f(1, 100f, 100f))
        pad.onTimer(280)
        frame(300, f(1, 140f, 100f))
        // A resting second finger must not turn the drag into something else or let go early.
        frame(340, f(1, 140f, 100f), f(2, 300f, 300f))
        frame(360, f(1, 170f, 100f), f(2, 300f, 300f))
        frame(380, f(1, 200f, 100f))
        up(400)
        assertEquals(1, log.count { it == "button(0,down)" })
        assertEquals(1, log.count { it == "button(0,up)" })
        assertEquals("button(0,up)", log.last())
    }
}
