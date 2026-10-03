package nl.markmaaktmedia.tandem

import nl.markmaaktmedia.tandem.media.showsPhoneMusic
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.tandem_core.TandemPlatform

class MediaTargetsTest {
    @Test fun theMacAndWindowsGetThePhonesMusic() {
        assertTrue(showsPhoneMusic(TandemPlatform.MAC_OS))
        assertTrue(showsPhoneMusic(TandemPlatform.WINDOWS))
    }

    @Test fun otherDevicesDoNot() {
        assertFalse(showsPhoneMusic(TandemPlatform.ANDROID))
        assertFalse(showsPhoneMusic(TandemPlatform.LINUX))
        assertFalse(showsPhoneMusic(TandemPlatform.IOS))
    }
}
