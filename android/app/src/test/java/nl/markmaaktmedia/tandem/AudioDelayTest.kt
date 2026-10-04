package nl.markmaaktmedia.tandem

import nl.markmaaktmedia.tandem.audio.AudioDelay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDelayTest {
    @Test
    fun theLevelsGrowFromCloseToLiveToSteady() {
        val all = AudioDelay.entries
        for ((a, b) in all.zip(all.drop(1))) {
            assertTrue(a.prebufferMs < b.prebufferMs)
            assertTrue(a.trackMs < b.trackMs)
            assertTrue(a.maxMs < b.maxMs)
        }
    }

    @Test
    fun eachLevelStartsBeforeItsSpeakerIsFullAndKeepsLessThanItDrops() {
        for (level in AudioDelay.entries) {
            assertTrue("${level.name} starts within its speaker buffer", level.prebufferMs < level.trackMs)
            assertTrue("${level.name} keeps less than it allows", level.keepMs < level.maxMs)
            assertTrue("${level.name} keeps more than it starts with", level.keepMs >= level.prebufferMs)
        }
    }

    @Test
    fun anUnknownIndexIsTheNormalLevel() {
        assertEquals(AudioDelay.NORMAL, AudioDelay.fromIndex(7))
        assertEquals(AudioDelay.NORMAL, AudioDelay.fromIndex(-1))
        assertEquals(AudioDelay.LOW, AudioDelay.fromIndex(0))
    }
}
