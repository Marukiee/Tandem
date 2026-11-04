package nl.markmaaktmedia.tandem

import nl.markmaaktmedia.tandem.update.VersionComparator
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionComparatorTest {
    @Test fun tenBeatsNine() = assertTrue(VersionComparator.isNewer("1.10.0", "1.9.0"))

    @Test fun equalIsNotNewer() = assertFalse(VersionComparator.isNewer("1.2.3", "1.2.3"))

    @Test fun leadingVIsIgnored() = assertEquals("1.2.3", VersionComparator.normalise("v1.2.3"))

    @Test fun releaseBeatsItsPrerelease() = assertTrue(VersionComparator.isNewer("1.0.0", "1.0.0-rc1"))

    @Test fun missingPartsCountAsZero() = assertFalse(VersionComparator.isNewer("1.0", "1.0.0"))

    private fun release(tag: String, vararg files: String, extra: String = "") =
        """{"tag_name":"$tag","assets":[${files.joinToString(",") { """{"name":"$it"}""" }}]$extra}"""

    /** v0.2.92 stood on GitHub as the newest with only app-debug.apk, and hid v0.2.91 from every phone. */
    @Test fun aReleaseWithoutTheFileDoesNotHideTheOneBefore() {
        val feed = JSONArray(
            "[" + listOf(
                release("v0.2.93"),
                release("v0.2.92", "app-debug.apk", "Tandem"),
                release("linux-preview", "Tandem.apk", extra = ""","prerelease":true"""),
                release("v0.2.95", "Tandem.apk", extra = ""","draft":true"""),
                release("v0.2.90", "Tandem.apk"),
                release("v0.2.91", "Tandem.apk"),
            ).joinToString(",") + "]",
        )
        assertEquals("v0.2.91", VersionComparator.newest(feed, "Tandem.apk")?.getString("tag_name"))
    }

    @Test fun noUsableReleaseIsNull() = assertNull(VersionComparator.newest(JSONArray("[]"), "Tandem.apk"))
}
