package nl.markmaaktmedia.tandem

import nl.markmaaktmedia.tandem.screen.FingerGestures
import nl.markmaaktmedia.tandem.screen.ViewerOutput
import nl.markmaaktmedia.tandem.ui.remote.Touch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FingerGesturesTest {
    private class Recorder : ViewerOutput {
        val log = mutableListOf<String>()
        override fun pointerMove(dx: Float, dy: Float) { log += "move" }
        override fun pointerTo(x: Float, y: Float) { log += "to ${x.toInt()},${y.toInt()}" }
        override fun button(button: Int, down: Boolean, clicks: Int) { log += "button $button ${if (down) "down" else "up"}" }
        override fun scroll(dx: Float, dy: Float) { log += "scroll" }
        override fun zoom(factor: Float, focalX: Float, focalY: Float) { log += "zoom" }
        override fun pan(dx: Float, dy: Float) { log += "pan" }
    }

    /** The picture covers x 0 to 1000 and y 100 to 2000 of the view. */
    private fun gestures(out: Recorder) = FingerGestures(8f, out) { x, y -> x in 0f..1000f && y in 100f..2000f }

    private fun FingerGestures.touch(vararg t: Touch) = onFrame(t.toList())
    private fun FingerGestures.lift() = onFrame(emptyList())

    @Test fun theFingerGoesDownAtOnceWhereItLands() {
        val out = Recorder()
        val g = gestures(out)
        g.touch(Touch(1, 300f, 400f))
        assertEquals(listOf("to 300,400", "button 0 down"), out.log)
        g.lift()
        assertEquals(listOf("to 300,400", "button 0 down", "button 0 up"), out.log)
    }

    @Test fun aWobbleWithinTheSlopStaysATapOrALongPress() {
        val out = Recorder()
        val g = gestures(out)
        g.touch(Touch(1, 300f, 400f))
        g.touch(Touch(1, 303f, 404f))
        g.touch(Touch(1, 298f, 401f))
        g.lift()
        assertEquals(listOf("to 300,400", "button 0 down", "button 0 up"), out.log)
    }

    @Test fun aSwipeFollowsTheFingerWithTheButtonHeld() {
        val out = Recorder()
        val g = gestures(out)
        g.touch(Touch(1, 500f, 1500f))
        g.touch(Touch(1, 500f, 1400f))
        g.touch(Touch(1, 500f, 1000f))
        g.lift()
        assertEquals(listOf("to 500,1500", "button 0 down", "to 500,1400", "to 500,1000", "button 0 up"), out.log)
    }

    @Test fun aSecondFingerIsLeftAloneAndIsNeverBack() {
        val out = Recorder()
        val g = gestures(out)
        g.touch(Touch(1, 300f, 400f))
        g.touch(Touch(1, 300f, 400f), Touch(2, 600f, 900f))
        g.touch(Touch(1, 300f, 400f), Touch(2, 700f, 900f))
        g.lift()
        assertEquals(listOf("to 300,400", "button 0 down", "button 0 up"), out.log)
        assertTrue(out.log.none { it.startsWith("button 1") })
    }

    @Test fun theFirstFingerLiftingEndsTheTouchAndTheOtherWaitsUntilAllAreUp() {
        val out = Recorder()
        val g = gestures(out)
        g.touch(Touch(1, 300f, 400f))
        g.touch(Touch(1, 300f, 400f), Touch(2, 600f, 900f))
        g.touch(Touch(2, 600f, 900f))
        g.touch(Touch(2, 650f, 950f))
        g.lift()
        assertEquals(listOf("to 300,400", "button 0 down", "button 0 up"), out.log)
        g.touch(Touch(3, 100f, 200f))
        assertEquals(listOf("to 100,200", "button 0 down"), out.log.drop(3))
    }

    @Test fun aTouchBesideThePictureDoesNothing() {
        val out = Recorder()
        val g = gestures(out)
        g.touch(Touch(1, 500f, 50f))
        g.touch(Touch(1, 500f, 600f))
        g.lift()
        assertTrue(out.log.isEmpty())
        g.touch(Touch(2, 500f, 600f))
        assertEquals(listOf("to 500,600", "button 0 down"), out.log)
    }

    @Test fun cancellingLetsGoOfAFingerThatIsDown() {
        val out = Recorder()
        val g = gestures(out)
        g.touch(Touch(1, 300f, 400f))
        g.cancel()
        g.cancel()
        assertEquals(listOf("to 300,400", "button 0 down", "button 0 up"), out.log)
    }
}
