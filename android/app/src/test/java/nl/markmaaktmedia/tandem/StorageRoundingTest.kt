package nl.markmaaktmedia.tandem

import nl.markmaaktmedia.tandem.engine.StatusReporter
import org.junit.Assert.assertEquals
import org.junit.Test

class StorageRoundingTest {
    @Test
    fun plentyOfSpaceRoundsToWholeGigabytes() {
        assertEquals(12_000_000_000uL, StatusReporter.roundStorage(12_345_678_901))
        assertEquals(12_000_000_000uL, StatusReporter.roundStorage(12_000_000_017))
    }

    @Test
    fun littleSpaceRoundsToHundredMegabytes() {
        assertEquals(3_200_000_000uL, StatusReporter.roundStorage(3_281_000_000))
        assertEquals(0uL, StatusReporter.roundStorage(40_000_000))
    }
}
