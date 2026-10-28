package nl.markmaaktmedia.tandem

import nl.markmaaktmedia.tandem.audio.PcmSmoothing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PcmSmoothingTest {
    /** Stereo 16 bit: a ramp, so a jump anywhere would show as a big difference between two neighbours. */
    private fun ramp(frames: Int): ByteArray {
        val pcm = ByteArray(frames * 4)
        for (frame in 0 until frames) for (channel in 0..1) {
            val v = frame * 10
            pcm[(frame * 2 + channel) * 2] = (v and 0xFF).toByte()
            pcm[(frame * 2 + channel) * 2 + 1] = (v shr 8).toByte()
        }
        return pcm
    }

    private fun sample(pcm: ByteArray, frame: Int) = (pcm[frame * 4].toInt() and 0xFF) or (pcm[frame * 4 + 1].toInt() shl 8)

    @Test
    fun aSqueezedPacketKeepsItsEndsAndHasNoJump() {
        val pcm = ramp(288)
        val shorter = PcmSmoothing.squeeze(pcm, 6, 4)
        assertEquals(282 * 4, shorter.size)
        assertEquals(sample(pcm, 0), sample(shorter, 0))
        assertEquals(sample(pcm, 287), sample(shorter, 281))
        for (frame in 1 until 282) assertEquals(true, sample(shorter, frame) - sample(shorter, frame - 1) in 0..12)
    }

    @Test
    fun aStretchedPacketIsLongerWithTheSameEnds() {
        val pcm = ramp(288)
        val longer = PcmSmoothing.squeeze(pcm, -6, 4)
        assertEquals(294 * 4, longer.size)
        assertEquals(sample(pcm, 287), sample(longer, 293))
    }

    @Test
    fun nothingToDoLeavesThePacketAlone() {
        val pcm = ramp(10)
        assertSame(pcm, PcmSmoothing.squeeze(pcm, 0, 4))
    }

    @Test
    fun aFadeInStartsQuietAndEndsAsItWas() {
        val pcm = ByteArray(100 * 4) { if (it % 2 == 0) 0 else 0x10 }
        PcmSmoothing.fadeIn(pcm, 50, 4)
        assertEquals(true, sample(pcm, 0) < 0x1000 / 10)
        assertEquals(0x1000, sample(pcm, 60))
    }

    @Test
    fun concealingWithoutAnythingBeforeIsSilence() {
        val filled = PcmSmoothing.conceal(ByteArray(0), 400, 4)
        assertEquals(400, filled.size)
        assertEquals(0, filled.count { it.toInt() != 0 })
    }
}
