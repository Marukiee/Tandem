package nl.markmaaktmedia.tandem

import nl.markmaaktmedia.tandem.mirror.OtpDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OtpDetectorTest {
    @Test fun findsEnglishCode() = assertEquals("482913", OtpDetector.find("Your verification code is 482913"))

    @Test fun findsDutchCode() = assertEquals("7391", OtpDetector.find("Je code: 7391. Deel deze met niemand."))

    @Test fun findsSplitCode() = assertEquals("123456", OtpDetector.find("Use 123-456 to log in"))

    @Test fun ignoresPlainNumbers() = assertNull(OtpDetector.find("You have 3 new messages"))

    @Test fun ignoresLongNumbers() = assertNull(OtpDetector.find("Order 123456789012 shipped"))
}
