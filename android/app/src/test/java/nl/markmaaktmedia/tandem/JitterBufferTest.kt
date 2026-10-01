package nl.markmaaktmedia.tandem

import nl.markmaaktmedia.tandem.audio.JitterBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class JitterBufferTest {
    // 48 kHz stereo, 16 bit: 4 bytes per frame and 192 bytes per millisecond.
    private fun buffer(max: Int = 320, keep: Int = 120) = JitterBuffer(frameBytes = 4, bytesPerMs = 192, maxMs = max, keepMs = keep).also { it.reset(1) }

    /** A packet of [ms] milliseconds, every byte set to [fill]. */
    private fun packet(ms: Int, fill: Int) = ByteArray(ms * 192) { fill.toByte() }

    @Test
    fun packetsComeOutInOrder() {
        val b = buffer()
        b.push(1, 0, packet(5, 1))
        b.push(1, 1, packet(5, 2))
        assertEquals(1, b.take(10)!![0].toInt())
        assertEquals(2, b.take(10)!![0].toInt())
        assertNull(b.take(5))
    }

    @Test
    fun aPacketFromAnotherStreamIsIgnored() {
        val b = buffer()
        b.push(2, 0, packet(5, 9))
        assertNull(b.take(5))
        assertEquals(0, b.queuedMs)
    }

    @Test
    fun aLatePacketAndADuplicateAreDropped() {
        val b = buffer()
        b.push(1, 5, packet(5, 1))
        b.push(1, 4, packet(5, 2))
        b.push(1, 5, packet(5, 3))
        assertEquals(1, b.take(10)!![0].toInt())
        assertNull(b.take(5))
        assertEquals(2, b.dropped)
    }

    @Test
    fun aLostPacketLeavesAMomentOfSilenceSoTheRestDoesNotSlideForward() {
        val b = buffer()
        b.push(1, 0, packet(5, 1))
        // Packets 1 and 2 never arrived.
        b.push(1, 3, packet(5, 4))
        assertEquals(1, b.take(10)!![0].toInt())
        val silence = b.take(10)!!
        assertEquals(10 * 192, silence.size)
        assertEquals(0, silence.count { it.toInt() != 0 })
        assertEquals(4, b.take(10)!![0].toInt())
    }

    @Test
    fun aLongStallIsNotFilledWithSilence() {
        val b = buffer()
        b.push(1, 0, packet(5, 1))
        b.push(1, 1000, packet(5, 2))
        b.take(10)
        // At most the cap (40 ms), and always a whole number of frames.
        val silence = b.take(10)!!
        assertEquals(40 * 192, silence.size)
        assertEquals(0, silence.size % 4)
    }

    @Test
    fun anOverfullBufferThrowsTheOldestAwayDownToTheCushion() {
        val b = buffer(max = 100, keep = 40)
        for (seq in 0 until 30L) b.push(1, seq, packet(5, seq.toInt()))
        // 150 ms came in, 100 is the most that may wait: it was cut down to 40 and filled again.
        assertNotNull(b.take(10))
        assertEquals(true, b.queuedMs <= 100)
        assertEquals(true, b.dropped > 0)
        // What is left is the newest sound, not the oldest.
        var last = -1
        while (true) last = b.take(1)?.get(0)?.toInt() ?: break
        assertEquals(29, last)
    }

    @Test
    fun aNewStreamStartsOverAndForgetsTheOldOne() {
        val b = buffer()
        b.push(1, 7, packet(5, 1))
        b.reset(2)
        assertNull(b.take(5))
        b.push(2, 0, packet(5, 2))
        assertEquals(2, b.take(10)!![0].toInt())
        // The counter starts again too: 0 is not "older than 7" any more.
        b.push(2, 1, packet(5, 3))
        assertEquals(3, b.take(10)!![0].toInt())
    }
}
