package nl.markmaaktmedia.tandem

import nl.markmaaktmedia.tandem.data.ClipHistory
import nl.markmaaktmedia.tandem.data.ClipItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipHistoryTest {
    @Test fun theNewestIsOnTop() {
        var list = emptyList<ClipItem>()
        list = ClipHistory.add(list, "one", "Mac", 1000)
        list = ClipHistory.add(list, "two", "Mac", 2000)
        assertEquals(listOf("two", "one"), list.map { it.text })
    }

    @Test fun theSameTextMovesUpAndKeepsItsPin() {
        var list = ClipHistory.add(emptyList(), "link", "Mac", 1000)
        list = list.map { it.copy(pinned = true) }
        list = ClipHistory.add(list, "other", "Mac", 2000)
        list = ClipHistory.add(list, "link", "", 3000)
        assertEquals(listOf("link", "other"), list.map { it.text })
        assertTrue(list.first().pinned)
        assertEquals("Mac", list.first().from)
    }

    @Test fun theOldestGoAfterTheLimitAndAPinStays() {
        var list = ClipHistory.add(emptyList(), "keep me", "", 0).map { it.copy(pinned = true) }
        for (n in 1..150) list = ClipHistory.add(list, "item $n", "", n.toLong() * 10)
        assertEquals(ClipHistory.LIMIT, list.size)
        assertTrue(list.any { it.text == "keep me" })
        assertEquals("item 150", list.first().text)
    }

    @Test fun whatWasSavedComesBackAndDamageIsNotFatal() {
        val list = listOf(ClipItem(5, "a \"quoted\" line\nand more", "Mac", 123, true))
        assertEquals(list, ClipHistory.decode(ClipHistory.encode(list)))
        assertTrue(ClipHistory.decode("not json").isEmpty())
        assertTrue(ClipHistory.decode(null).isEmpty())
    }

    @Test fun searchNeedsEveryWord() {
        val list = listOf(ClipItem(1, "meeting at ten", "Mac", 1), ClipItem(2, "pizza at ten", "Windows PC", 2))
        assertEquals(listOf("pizza at ten"), ClipHistory.search(list, "TEN pizza").map { it.text })
        assertEquals(listOf("meeting at ten"), ClipHistory.search(list, "mac").map { it.text })
        assertEquals(2, ClipHistory.search(list, "  ").size)
    }
}
