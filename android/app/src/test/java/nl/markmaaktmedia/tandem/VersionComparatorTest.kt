package nl.markmaaktmedia.tandem

import nl.markmaaktmedia.tandem.update.VersionComparator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionComparatorTest {
    @Test fun tenBeatsNine() = assertTrue(VersionComparator.isNewer("1.10.0", "1.9.0"))

    @Test fun equalIsNotNewer() = assertFalse(VersionComparator.isNewer("1.2.3", "1.2.3"))

    @Test fun leadingVIsIgnored() = assertEquals("1.2.3", VersionComparator.normalise("v1.2.3"))

    @Test fun releaseBeatsItsPrerelease() = assertTrue(VersionComparator.isNewer("1.0.0", "1.0.0-rc1"))

    @Test fun missingPartsCountAsZero() = assertFalse(VersionComparator.isNewer("1.0", "1.0.0"))
}
