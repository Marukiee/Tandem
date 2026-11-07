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
    fun `a trembling hand hardly moves a slow pointer backwards`() {
        val pointer = GyroPointer()
        var forward = 0
        var backward = 0
        repeat(400) { step ->
            val seconds = step * 0.005f
            // A slow turn with the trembling of a hand on top of it, ten times a second.
            val turn = -0.1f + 0.25f * sin(2 * PI.toFloat() * 10f * seconds)
            pointer.update(time, 0f, turn)?.let { if (it.dx > 0) forward += it.dx else backward -= it.dx }
            time += STEP_NS
        }
        // The old fixed smoothing let a twentieth of this go backwards: a pointer that shook while aiming.
        assertTrue(backward * 20 < forward, "forward $forward, backward $backward")
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
    fun `one finger is a tap and a slide is a slide`() {
        val g = ScreenGestures()
        g.onFrame(0, listOf(finger()))
        assertEquals(ScreenGesture.TAP, g.onFrame(100, emptyList()))
        g.onFrame(200, listOf(finger()))
        g.onFrame(250, listOf(finger(x = 180f)))
        assertEquals(ScreenGesture.SLIDE, g.onFrame(300, emptyList()))
    }

    @Test
    fun `a swipe to the right leaves, one to the left only pauses`() {
        val g = ScreenGestures()
        g.onFrame(0, listOf(finger(x = 60f)))
        g.onFrame(80, listOf(finger(x = 260f)))
        assertEquals(ScreenGesture.SWIPE_RIGHT, g.onFrame(120, emptyList()))
        g.onFrame(200, listOf(finger(x = 260f)))
        g.onFrame(280, listOf(finger(x = 60f)))
        assertEquals(ScreenGesture.SLIDE, g.onFrame(320, emptyList()))
    }

    @Test
    fun `a resting finger holds once and lets go when it is lifted`() {
        val g = ScreenGestures()
        g.onFrame(0, listOf(finger()))
        assertEquals(300L, g.deadline)
        assertNull(g.onTimer(299))
        assertEquals(ScreenGesture.HOLD, g.onTimer(300))
        assertNull(g.deadline)
        assertNull(g.onTimer(900))
        assertEquals(ScreenGesture.HOLD_END, g.onFrame(900, emptyList()))
    }

    @Test
    fun `a finger that slides while holding keeps holding`() {
        val g = ScreenGestures()
        g.onFrame(0, listOf(finger()))
        g.onTimer(300)
        assertNull(g.onFrame(400, listOf(finger(x = 260f))))
        assertEquals(ScreenGesture.HOLD_END, g.onFrame(500, emptyList()))
    }

    @Test
    fun `every length of touch does something`() {
        for (length in listOf(50L, 150L, 299L)) {
            val g = ScreenGestures()
            g.onFrame(0, listOf(finger()))
            assertEquals(ScreenGesture.TAP, g.onFrame(length, emptyList()), "touch of $length ms")
        }
        val g = ScreenGestures()
        g.onFrame(0, listOf(finger()))
        assertTrue(g.undecided)
        g.onTimer(300)
        assertTrue(!g.undecided)
    }
}

class PointerPacerTest {
    @Test
    fun `bursts come out as small even steps that add up to the same`() {
        val pacer = PointerPacer()
        val steps = ArrayList<Int>()
        var now = 0L
        var sent = 0
        repeat(10) { burst ->
            pacer.add(now, 30, 0)
            sent += 30
            val until = now + 30
            while (now < until) {
                now += PointerPacer.TICK_MS
                pacer.tick(now)?.let { steps += it.dx }
            }
        }
        pacer.drain()?.let { steps += it.dx }
        assertEquals(sent, steps.sum())
        // Once it has the rhythm of the bursts, no step is much bigger than another.
        val settled = steps.drop(8).dropLast(2)
        assertTrue(settled.max() - settled.min() <= 4, "steps $settled")
        assertTrue(steps.max() < 30, "a burst was not spread: ${steps.max()}")
    }

    @Test
    fun `a click gets everything that is left at once`() {
        val pacer = PointerPacer()
        pacer.add(0, 40, -20)
        assertEquals(PointerDelta(40, -20), pacer.drain())
        assertTrue(!pacer.busy)
        assertNull(pacer.tick(50))
    }

    @Test
    fun `a pause of the arm does not slow down the next movement`() {
        val pacer = PointerPacer()
        pacer.add(0, 30, 0)
        pacer.tick(30)
        pacer.add(5_000, 30, 0)
        val first = pacer.tick(5_016)
        assertTrue((first?.dx ?: 0) >= 10, "first step $first")
    }

    @Test
    fun `the stats say how even the bursts came`() {
        val stats = ArrivalStats()
        repeat(20) { stats.note(it * 30L, 12) }
        assertTrue(stats.summary().contains("median 30 ms"), stats.summary())
    }
}

class PointerHoldMessageTest {
    private fun roundTrip(message: Message) = assertEquals(message, MessageCodec.decode(MessageCodec.encode(message)))

    @Test
    fun `holding and letting go survive the wire`() {
        roundTrip(Message.PointerButton(0, true))
        roundTrip(Message.PointerButton(1, false))
        roundTrip(Message.PointerTargets(true, emptyList(), canHold = true))
    }

    @Test
    fun `an older phone that says nothing about holding cannot hold`() {
        val old = MessageCodec.encode(Message.PointerTargets(true, listOf(PointerTarget("id", "Mac", online = true, allowsInput = true))))
        // What an older phone sent ends right after the targets.
        val decoded = MessageCodec.decode(old.copyOf(old.size - 1)) as Message.PointerTargets
        assertTrue(!decoded.canHold)
    }
}

class PinchDetectorTest {
    private val gravity = 9.81f

    /**
     * [knocks] are times in ms; each is a sharp bump of [strength] over 10 ms, as fingers meeting are. [turn] is how fast the wrist turns,
     * in rad/s. Adds a little sensor noise. What the detector heard, in order.
     */
    private fun hear(
        ms: Int,
        knocks: List<Int>,
        strength: Float = 6f,
        quietUntilMs: Int = 0,
        turn: (Float) -> Float = { 0f },
        arm: (Float) -> Float = { 0f },
    ): List<Pinch> {
        val detector = PinchDetector()
        detector.quietUntilNs = 1_000_000_000L + quietUntilMs * 1_000_000L
        val random = Random(2)
        val heard = ArrayList<Pinch>()
        var t = 1_000_000_000L
        for (i in 0 until ms / 5) {
            val now = i * 5
            val knock = if (knocks.any { now in it until it + 10 }) strength else 0f
            val noise = random.nextFloat() * 0.1f - 0.05f
            detector.gyro(t, turn(now / 1000f), 0f, 0f)
            detector.update(t, arm(now / 1000f) + knock + noise, noise, gravity + noise)?.let { heard += it }
            t += STEP_NS
        }
        return heard
    }

    @Test
    fun `one pinch is one pinch`() = assertEquals(listOf(Pinch.SINGLE), hear(2000, listOf(800)))

    @Test
    fun `two quick pinches are a double pinch`() = assertEquals(listOf(Pinch.DOUBLE), hear(2000, listOf(800, 1000)))

    @Test
    fun `two pinches a second apart are two`() = assertEquals(listOf(Pinch.SINGLE, Pinch.SINGLE), hear(3000, listOf(800, 1800)))

    @Test
    fun `a tap on the table is nothing`() = assertEquals(emptyList(), hear(2000, listOf(800), strength = 30f))

    @Test
    fun `a knock while the wrist turns fast is the arm, not a pinch`() = assertEquals(emptyList(), hear(2000, listOf(800), turn = { 4f }))

    @Test
    fun `what comes while the watch buzzes itself is nothing`() = assertEquals(emptyList(), hear(2000, listOf(800, 1000), quietUntilMs = 1200))

    @Test
    fun `moving the arm is nothing`() {
        assertEquals(emptyList(), hear(5000, emptyList(), turn = { 1.5f }) { 4f * sin(2 * PI.toFloat() * 1.5f * it) })
    }

    @Test
    fun `a pinch is still heard while the arm moves slowly`() {
        assertEquals(listOf(Pinch.SINGLE), hear(4000, listOf(1500), turn = { 1f }) { 4f * sin(2 * PI.toFloat() * 1.5f * it) })
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

class ArmPointerTest {
    /** The quaternion of a forearm pointing at [yaw] radians around the vertical and lifted by [pitch], with the wrist turned by [roll]. */
    private fun arm(yaw: Double, pitch: Double, roll: Double = 0.0): FloatArray {
        // Z (yaw), then Y (minus pitch: lifting x up is a negative turn around y), then X (roll).
        val cy = kotlin.math.cos(yaw / 2); val sy = kotlin.math.sin(yaw / 2)
        val cp = kotlin.math.cos(-pitch / 2); val sp = kotlin.math.sin(-pitch / 2)
        val cr = kotlin.math.cos(roll / 2); val sr = kotlin.math.sin(roll / 2)
        val w = cr * cp * cy + sr * sp * sy
        val x = sr * cp * cy - cr * sp * sy
        val y = cr * sp * cy + sr * cp * sy
        val z = cr * cp * sy - sr * sp * cy
        return floatArrayOf(x.toFloat(), y.toFloat(), z.toFloat(), w.toFloat())
    }

    private fun ArmPointer.feed(q: FloatArray) = update(q[0], q[1], q[2], q[3])

    /** Moves in small steps, as the sensor does, and adds up what comes out. */
    private fun sweep(pointer: ArmPointer, from: Triple<Double, Double, Double>, to: Triple<Double, Double, Double>, steps: Int = 50): Pair<Int, Int> {
        var x = 0
        var y = 0
        for (i in 0..steps) {
            val f = i.toDouble() / steps
            val q = arm(from.first + (to.first - from.first) * f, from.second + (to.second - from.second) * f, from.third + (to.third - from.third) * f)
            pointer.feed(q)?.let { x += it.dx; y += it.dy }
        }
        return x to y
    }

    @Test
    fun `turning the forearm to the right moves right and lifting it moves up`() {
        val (right, _) = sweep(ArmPointer(), Triple(0.0, 0.0, 0.0), Triple(-0.3, 0.0, 0.0))
        assertTrue(right > 300, "right $right")
        val (_, up) = sweep(ArmPointer(), Triple(0.0, 0.0, 0.0), Triple(0.0, 0.3, 0.0))
        assertTrue(up < -300, "up $up")
    }

    @Test
    fun `a forearm turned with the wrist rolled over still moves sideways`() {
        val (x, y) = sweep(ArmPointer(), Triple(0.0, 0.0, 1.2), Triple(-0.3, 0.0, 1.2))
        assertTrue(x > 300 && abs(y) < x / 10, "x $x y $y")
    }

    @Test
    fun `turning only the wrist does not move the pointer`() {
        val (x, y) = sweep(ArmPointer(), Triple(0.0, 0.0, 0.0), Triple(0.0, 0.0, 1.0))
        assertEquals(0, x)
        assertEquals(0, y)
    }

    @Test
    fun `a trembling hand stands still`() {
        val pointer = ArmPointer()
        var moved = 0
        for (i in 0..400) {
            val q = arm(0.004 * kotlin.math.sin(i * 0.7), 0.004 * kotlin.math.cos(i * 0.9))
            pointer.feed(q)?.let { moved += abs(it.dx) + abs(it.dy) }
        }
        assertTrue(moved < 20, "moved $moved")
    }
}
