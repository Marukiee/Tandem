package nl.markmaaktmedia.tandem.watch.pointer

import nl.markmaaktmedia.tandem.watch.protocol.DeviceBattery
import nl.markmaaktmedia.tandem.watch.protocol.Message
import nl.markmaaktmedia.tandem.watch.protocol.MessageCodec
import nl.markmaaktmedia.tandem.watch.protocol.PointerTarget
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val STEP_NS = 5_000_000L // 200 Hz

class GyroPointerTest {
    private fun run(pointer: GyroPointer, seconds: Float, across: Float, out: Float): Pair<Int, Int> {
        var x = 0
        var y = 0
        repeat((seconds * 200).toInt()) {
            pointer.update(time, across, out)?.let {
                x += it.dx
                y += it.dy
            }
            time += STEP_NS
        }
        return x to y
    }

    private var time = 1_000_000_000L

    @Test
    fun `an arm at rest does not move the pointer`() {
        val pointer = GyroPointer()
        val random = Random(1)
        repeat(2000) {
            assertNull(pointer.update(time, random.nextFloat() * 0.03f - 0.015f, random.nextFloat() * 0.03f - 0.015f))
            time += STEP_NS
        }
    }

    @Test
    fun `turning left moves left and lifting moves up`() {
        val (left, _) = run(GyroPointer(), 0.5f, 0f, 1f)
        assertTrue(left < 0)
        val (_, up) = run(GyroPointer(), 0.5f, -1f, 0f)
        assertTrue(up < 0)
    }

    @Test
    fun `the other way of wearing it flips only up and down`() {
        val (x, y) = run(GyroPointer(invertVertical = true), 0.5f, -1f, 1f)
        assertTrue(x < 0)
        assertTrue(y > 0)
    }

    @Test
    fun `a slow movement is not lost to rounding`() {
        val (x, _) = run(GyroPointer(), 2f, 0f, -0.1f)
        assertTrue(x > 50, "moved $x")
    }

    @Test
    fun `the same turn goes further when made fast`() {
        val (slow, _) = run(GyroPointer(), 2f, 0f, -0.25f)
        val (fast, _) = run(GyroPointer(), 0.25f, 0f, -2f)
        assertTrue(fast > slow * 1.5, "fast $fast, slow $slow")
    }

    @Test
    fun `a gap in the samples does not make it jump`() {
        val pointer = GyroPointer()
        run(pointer, 0.2f, 0f, 1f)
        time += 5_000_000_000L
        val jump = pointer.update(time, 0f, 1f)
        assertTrue(abs(jump?.dx ?: 0) < 200)
    }
}

class CrownStepsTest {
    @Test
    fun `counts whole steps and keeps the rest`() {
        val crown = CrownSteps(100f)
        assertEquals(0, crown.update(60f))
        assertEquals(1, crown.update(60f))
        assertEquals(-1, crown.update(-120f))
    }
}

class ScreenGesturesTest {
    private fun finger(id: Long = 0, x: Float = 100f) = Touch(id, x, 100f)

    @Test
    fun `two fingers landing a little apart are one two finger tap`() {
        val g = ScreenGestures()
        assertNull(g.onFrame(0, listOf(finger(0))))
        assertNull(g.onFrame(40, listOf(finger(0), finger(1, 200f))))
        assertNull(g.onFrame(120, listOf(finger(1, 200f))))
        assertEquals(ScreenGesture.TWO_FINGER_TAP, g.onFrame(150, emptyList()))
    }

    @Test
    fun `one finger is a tap, and a slide or long touch is not`() {
        val g = ScreenGestures()
        g.onFrame(0, listOf(finger()))
        assertEquals(ScreenGesture.TAP, g.onFrame(100, emptyList()))
        g.onFrame(200, listOf(finger()))
        g.onFrame(250, listOf(finger(x = 180f)))
        assertNull(g.onFrame(300, emptyList()))
    }

    @Test
    fun `a resting finger fires once when the time is up`() {
        val g = ScreenGestures()
        g.onFrame(0, listOf(finger()))
        assertEquals(500L, g.deadline)
        assertNull(g.onTimer(499))
        assertEquals(ScreenGesture.LONG_PRESS, g.onTimer(500))
        assertNull(g.deadline)
        assertNull(g.onFrame(900, emptyList()))
    }
}

class PinchDetectorTest {
    private val gravity = 9.81f

    /** [knocks] are times in ms; each is a sharp bump over 10 ms. Also adds a little sensor noise. */
    private fun count(ms: Int, knocks: List<Int>, arm: (Float) -> Float = { 0f }): Int {
        val detector = PinchDetector()
        val random = Random(2)
        var detected = 0
        var t = 1_000_000_000L
        for (i in 0 until ms / 5) {
            val now = i * 5
            val knock = if (knocks.any { now in it until it + 10 }) 6f else 0f
            val noise = random.nextFloat() * 0.1f - 0.05f
            if (detector.update(t, arm(now / 1000f) + knock + noise, noise, gravity + noise)) detected++
            t += STEP_NS
        }
        return detected
    }

    @Test
    fun `two knocks are one double pinch`() = assertEquals(1, count(2000, listOf(800, 1050)))

    @Test
    fun `one knock is nothing`() = assertEquals(0, count(2000, listOf(800)))

    @Test
    fun `two knocks too far apart are nothing`() = assertEquals(0, count(3000, listOf(800, 1800)))

    @Test
    fun `shaking is nothing`() = assertEquals(0, count(3000, listOf(800, 950, 1100, 1250, 1400)))

    @Test
    fun `moving the arm is nothing`() {
        assertEquals(0, count(5000, emptyList()) { 4f * sin(2 * PI.toFloat() * 1.5f * it) })
    }

    @Test
    fun `a double pinch is still heard while the arm moves`() {
        assertEquals(1, count(4000, listOf(1500, 1750)) { 4f * sin(2 * PI.toFloat() * 1.5f * it) })
    }
}

class PointerMessagesTest {
    private fun roundTrip(message: Message) = assertEquals(message, MessageCodec.decode(MessageCodec.encode(message)))

    @Test
    fun `pointer messages survive the wire, left and up included`() {
        roundTrip(Message.PointerMove(-3, 1200))
        roundTrip(Message.PointerMove(-32768, 32767))
        roundTrip(Message.PointerScroll(0, -40))
        roundTrip(Message.PointerClick(1, 2))
        roundTrip(Message.PointerKey(124, 0))
        roundTrip(Message.FindDevice(true))
        roundTrip(Message.Ringing(false))
        roundTrip(Message.DeviceBatteries(listOf(DeviceBattery("id", "Mac", 87, charging = true))))
        roundTrip(Message.WatchStatus(61, charging = false))
        roundTrip(Message.SleepMode(true))
        roundTrip(Message.TryWakeBuzz)
        roundTrip(Message.PointerSession("abc"))
        roundTrip(Message.PointerSession(null))
        roundTrip(Message.PointerTargets(true, listOf(PointerTarget("id", "Mac", online = true, allowsInput = false))))
    }

    @Test
    fun `a small move stays small`() {
        assertTrue(MessageCodec.encode(Message.PointerMove(-3, 5)).size <= 3)
    }
}
