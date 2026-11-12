package nl.markmaaktmedia.tandem

import nl.markmaaktmedia.tandem.watch.watchRestAfter
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchRestTest {
    @Test
    fun aWatchThatKeepsFailingIsLeftAloneLongerEachTime() {
        assertEquals(0L, watchRestAfter(1))
        assertEquals(0L, watchRestAfter(2))
        assertEquals(60_000L, watchRestAfter(3))
        assertEquals(5 * 60_000L, watchRestAfter(4))
        assertEquals(15 * 60_000L, watchRestAfter(5))
        assertEquals(60 * 60_000L, watchRestAfter(6))
        assertEquals(60 * 60_000L, watchRestAfter(40))
    }
}
