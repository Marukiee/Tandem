package nl.markmaaktmedia.tandem

import nl.markmaaktmedia.tandem.screen.AnnexB
import nl.markmaaktmedia.tandem.screen.FrameQueue
import nl.markmaaktmedia.tandem.screen.Keys
import nl.markmaaktmedia.tandem.screen.Outcome
import nl.markmaaktmedia.tandem.screen.PointerMapper
import nl.markmaaktmedia.tandem.screen.SessionPolicy
import nl.markmaaktmedia.tandem.screen.VideoFrame
import nl.markmaaktmedia.tandem.screen.ViewTransform
import nl.markmaaktmedia.tandem.screen.ViewerConfig
import nl.markmaaktmedia.tandem.screen.ViewerFeedback
import nl.markmaaktmedia.tandem.screen.ViewerGestures
import nl.markmaaktmedia.tandem.screen.ViewerOutput
import nl.markmaaktmedia.tandem.ui.remote.Touch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.tandem_core.TandemMediaEnd

class ScreenViewerTest {
    // ---- Gestures ---------------------------------------------------------------------------

    private class Recorder : ViewerOutput {
        val log = mutableListOf<String>()
        var moved = 0f
        var scrolled = 0f
        var zoomed = 1f
        override fun pointerMove(dx: Float, dy: Float) { moved += dx; log += "move" }
        override fun pointerTo(x: Float, y: Float) { log += "to ${x.toInt()},${y.toInt()}" }
        override fun button(button: Int, down: Boolean, clicks: Int) { log += "button $button ${if (down) "down" else "up"} $clicks" }
        override fun scroll(dx: Float, dy: Float) { scrolled += dy; log += "scroll" }
        override fun zoom(factor: Float, focalX: Float, focalY: Float) { zoomed *= factor; log += "zoom" }
        override fun pan(dx: Float, dy: Float) { log += "pan" }
        override fun feedback(kind: ViewerFeedback) { log += "feedback $kind" }
    }

    private fun gestures(out: Recorder, direct: Boolean = false) =
        ViewerGestures(ViewerConfig(slop = 8f), out).also { it.direct = direct }

    private fun ViewerGestures.touch(now: Long, vararg t: Touch) = onFrame(now, t.toList())
    private fun ViewerGestures.lift(now: Long) = onFrame(now, emptyList())
    private fun clicks(out: Recorder) = out.log.filter { it.startsWith("button") }

    @Test fun aTapIsAClickAndTwoTapsAreADoubleClick() {
        val out = Recorder()
        val g = gestures(out)
        g.touch(0, Touch(1, 100f, 100f)); g.lift(60)
        assertEquals(listOf("button 0 down 1", "button 0 up 1"), clicks(out))
        out.log.clear()
        g.touch(200, Touch(2, 100f, 100f)); g.lift(250)
        assertEquals(listOf("button 0 down 2", "button 0 up 2"), clicks(out))
    }

    @Test fun oneFingerMovesThePointerWithoutClicking() {
        val out = Recorder()
        val g = gestures(out)
        g.touch(0, Touch(1, 100f, 100f))
        g.touch(10, Touch(1, 120f, 100f))
        g.touch(20, Touch(1, 150f, 100f))
        g.lift(30)
        assertEquals(50f, out.moved, 0.01f)
        assertTrue(clicks(out).isEmpty())
    }

    @Test fun holdingAndLiftingIsARightClick() {
        val out = Recorder()
        val g = gestures(out)
        g.touch(0, Touch(1, 100f, 100f))
        g.onTimer(400)
        g.lift(500)
        assertEquals(listOf("button 1 down 1", "button 1 up 1"), clicks(out))
    }

    @Test fun holdingThenMovingDragsWithTheLeftButton() {
        val out = Recorder()
        val g = gestures(out)
        g.touch(0, Touch(1, 100f, 100f))
        g.onTimer(400)
        g.touch(450, Touch(1, 130f, 100f))
        g.touch(460, Touch(1, 160f, 100f))
        g.lift(470)
        assertEquals(listOf("button 0 down 1", "button 0 up 1"), clicks(out))
        assertEquals(60f, out.moved, 0.01f)
    }

    @Test fun cancelLetsGoOfAHeldButton() {
        val out = Recorder()
        val g = gestures(out)
        g.touch(0, Touch(1, 100f, 100f))
        g.onTimer(400)
        g.touch(450, Touch(1, 140f, 100f))
        g.cancel()
        assertEquals(listOf("button 0 down 1", "button 0 up 1"), clicks(out))
    }

    @Test fun twoFingersMovingTogetherScroll() {
        val out = Recorder()
        val g = gestures(out)
        g.touch(0, Touch(1, 100f, 100f), Touch(2, 200f, 100f))
        g.touch(10, Touch(1, 100f, 140f), Touch(2, 200f, 140f))
        g.touch(20, Touch(1, 100f, 180f), Touch(2, 200f, 180f))
        g.lift(30)
        assertEquals(80f, out.scrolled, 0.01f)
        assertEquals(1f, out.zoomed, 0.001f)
    }

    @Test fun twoFingersMovingApartZoom() {
        val out = Recorder()
        val g = gestures(out)
        g.touch(0, Touch(1, 200f, 200f), Touch(2, 260f, 200f))
        g.touch(10, Touch(1, 170f, 200f), Touch(2, 290f, 200f))
        g.touch(20, Touch(1, 140f, 200f), Touch(2, 320f, 200f))
        g.lift(30)
        assertTrue(out.zoomed > 2.5f)
        assertEquals(0f, out.scrolled, 0.001f)
    }

    @Test fun aQuickTapOfTwoFingersIsARightClick() {
        val out = Recorder()
        val g = gestures(out)
        g.touch(0, Touch(1, 100f, 100f), Touch(2, 200f, 100f))
        g.lift(80)
        assertEquals(listOf("button 1 down 1", "button 1 up 1"), clicks(out))
    }

    @Test fun touchModeClicksWhereItIsTouched() {
        val out = Recorder()
        val g = gestures(out, direct = true)
        g.touch(0, Touch(1, 300f, 400f)); g.lift(50)
        assertEquals(listOf("to 300,400", "button 0 down 1", "button 0 up 1"), out.log.filter { !it.startsWith("feedback") })
    }

    @Test fun touchModeDragsFromWhereItLanded() {
        val out = Recorder()
        val g = gestures(out, direct = true)
        g.touch(0, Touch(1, 300f, 400f))
        g.touch(10, Touch(1, 340f, 400f))
        g.touch(20, Touch(1, 380f, 420f))
        g.lift(30)
        val seen = out.log.filter { !it.startsWith("feedback") }
        assertEquals("to 300,400", seen.first())
        assertEquals("button 0 down 1", seen[1])
        assertTrue(seen.contains("to 380,420"))
        assertEquals("button 0 up 1", seen.last())
    }

    @Test fun threeFingersPanThePicture() {
        val out = Recorder()
        val g = gestures(out)
        g.touch(0, Touch(1, 100f, 100f), Touch(2, 200f, 100f), Touch(3, 300f, 100f))
        g.touch(10, Touch(1, 100f, 150f), Touch(2, 200f, 150f), Touch(3, 300f, 150f))
        g.lift(20)
        assertTrue(out.log.contains("pan"))
        assertTrue(clicks(out).isEmpty())
    }

    // ---- The picture on the screen ---------------------------------------------------------------

    private fun transform() = ViewTransform().apply {
        setView(1000f, 500f)
        setVideo(2000f, 1000f)
    }

    @Test fun thePictureFitsAndKeepsItsShape() {
        val t = transform()
        assertEquals(0.5f, t.fitScale, 0.0001f)
        assertEquals(1000f, t.pictureWidth, 0.01f)
        assertEquals(0f, t.left, 0.01f)
        val tall = ViewTransform().apply { setView(500f, 1000f); setVideo(2000f, 1000f) }
        assertEquals(250f, tall.pictureHeight, 0.01f)
        assertEquals(375f, tall.top, 0.01f)
    }

    @Test fun zoomingKeepsThePlaceUnderTheFingers() {
        val t = transform()
        val (fx, fy) = t.toPicture(700f, 100f)
        t.zoomBy(2f, 700f, 100f)
        val (gx, gy) = t.toPicture(700f, 100f)
        assertEquals(fx, gx, 0.001f)
        assertEquals(fy, gy, 0.001f)
        assertEquals(2f, t.zoom, 0.001f)
    }

    @Test fun theZoomedPictureNeverLeavesTheScreen() {
        val t = transform()
        t.zoomBy(3f, 500f, 250f)
        t.panBy(100000f, 100000f)
        assertTrue(t.left <= 0.001f && t.left + t.pictureWidth >= 999.99f)
        assertTrue(t.top <= 0.001f && t.top + t.pictureHeight >= 499.99f)
        t.reset()
        assertEquals(0f, t.left, 0.01f)
    }

    @Test fun aPositionTurnsBackIntoAPlaceOnTheMac() {
        val t = transform()
        assertEquals(0.5f to 0.5f, t.toPicture(500f, 250f))
        assertEquals(1f to 0f, t.toPicture(5000f, -50f))
    }

    @Test fun aPointerAtTheEdgeOfAZoomedPictureIsBroughtIntoView() {
        val t = transform()
        t.zoomBy(4f, 500f, 250f)
        t.keepVisible(0.9f, 0.9f, 40f)
        val (x, y) = t.toView(0.9f, 0.9f)
        assertTrue(x <= 1000f - 40f + 0.01f && y <= 500f - 40f + 0.01f)
    }

    // ---- Pointer, keys, frames --------------------------------------------------------------------

    @Test fun smallMovesAddUpAndAFlickGoesFurther() {
        val slow = PointerMapper(1f)
        var total = 0
        repeat(10) { total += slow.move(0.4f, 0f, 1f).first }
        assertEquals(4, total)
        val fast = PointerMapper(1f).move(60f, 0f, 1f).first
        assertTrue(fast in 100..160)
        // A picture shown at half size: the finger covers twice as many pixels of it.
        assertEquals(20, PointerMapper(1f).scroll(10f, 0f, 0.5f).first)
    }

    @Test fun keysMapBetweenMacAndHid() {
        assertEquals(0x04, Keys.hidForMac(0))
        assertEquals(0x28, Keys.hidForMac(36))
        assertEquals(0x52, Keys.hidForMac(126))
        assertEquals(0x04, Keys.hidForAndroid(29))
        assertEquals(0x27, Keys.hidForAndroid(7))
        assertEquals(0x1E, Keys.hidForAndroid(8))
        assertEquals(0x3A, Keys.hidForAndroid(131))
        assertNull(Keys.hidForAndroid(59))
        assertEquals(36, Keys.macForHid(0x28))
    }

    private fun frame(key: Boolean, n: Long = 0) = VideoFrame(n, key, false, byteArrayOf(1), 0)

    @Test fun nothingButAKeyframeIsFedAfterALoss() {
        val q = FrameQueue(capacity = 3)
        assertEquals(FrameQueue.Offer.Queued, q.offer(frame(false, 1)))
        q.offer(frame(true, 2))
        q.offer(frame(false, 3))
        // The P frame before the keyframe is gone; the keyframe goes first.
        assertEquals(2L, q.peek()!!.ptsUs)
        q.consume()
        assertEquals(3L, q.peek()!!.ptsUs)
        q.consume()
        q.markBroken()
        q.offer(frame(false, 4))
        assertNull(q.peek())
        q.offer(frame(true, 5))
        assertEquals(5L, q.peek()!!.ptsUs)
    }

    @Test fun aQueueThatOverflowsIsEmptiedAndAKeyframeIsAskedFor() {
        val q = FrameQueue(capacity = 2)
        q.consume() // nothing there, nothing happens
        q.offer(frame(true, 1)); q.consume()
        q.offer(frame(false, 2)); q.offer(frame(false, 3))
        assertEquals(FrameQueue.Offer.OverflowNeedKeyframe, q.offer(frame(false, 4)))
        assertTrue(q.waitingForKeyframe)
        assertEquals(0, q.size)
    }

    @Test fun parameterSetsAndSizeAreReadFromAKeyframe() {
        // The SPS and PPS the Mac's encoder wrote for a 1280x720 picture (taken from its harness).
        val sps = "2764001fac13146014016e9a80808083c20109a0".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val pps = "28ee3cb0".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val idr = byteArrayOf(0x65, 0x88.toByte(), 0x84.toByte(), 0x00)
        val code = byteArrayOf(0, 0, 0, 1)
        val data = code + sps + code + pps + code + idr
        assertTrue(AnnexB.isKeyframe(data))
        assertFalse(AnnexB.isKeyframe(code + byteArrayOf(0x41, 0x9a.toByte(), 1)))
        val sets = AnnexB.parameterSets(data)
        assertNotNull(sets)
        assertTrue(sets!!.sps.contentEquals(sps))
        assertEquals(1280 to 720, AnnexB.dimensions(sps))
    }

    // ---- Session policy ---------------------------------------------------------------------------

    @Test fun onlyALostLinkIsRetried() {
        assertEquals(Outcome.Retry, SessionPolicy.outcome(TandemMediaEnd.PEER_GONE))
        assertEquals(Outcome.Retry, SessionPolicy.outcome(TandemMediaEnd.ERROR))
        assertEquals(Outcome.StoppedByMac, SessionPolicy.outcome(TandemMediaEnd.ENDED))
        assertEquals(Outcome.Denied, SessionPolicy.outcome(TandemMediaEnd.DECLINED))
        assertEquals(Outcome.Denied, SessionPolicy.outcome(TandemMediaEnd.POLICY))
        assertEquals(Outcome.Busy, SessionPolicy.outcome(TandemMediaEnd.BUSY))
        assertEquals(Outcome.Unavailable, SessionPolicy.outcome(TandemMediaEnd.UNAVAILABLE))
        assertEquals(Outcome.NoAnswer, SessionPolicy.outcome(TandemMediaEnd.TIMEOUT))
    }

    @Test fun retriesSlowDownAndAskForLess() {
        assertTrue(SessionPolicy.retryDelayMs(1) < SessionPolicy.retryDelayMs(3))
        assertTrue(SessionPolicy.retryDelayMs(8) <= 10_000)
        assertEquals(12_000_000, SessionPolicy.maxBitrate(12_000_000, 1))
        assertTrue(SessionPolicy.maxBitrate(12_000_000, 3) < 7_000_000)
        assertEquals(SessionPolicy.MIN_BITRATE, SessionPolicy.maxBitrate(2_000_000, 8))
    }
}
