package nl.markmaaktmedia.tandem.watch.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WatchUpdateTest {
    private val file = ByteArray(WatchUpdate.PART_BYTES * 2 + 123) { (it * 31).toByte() }

    @Test
    fun `parts follow what the watch asks for until it has it all`() {
        var have = ByteArray(0)
        var want = Message.UpdateWant(302, 0)
        var parts = 0
        while (true) {
            val part = WatchUpdate.partFor(want, 302, file) ?: break
            assertEquals(have.size.toLong(), part.offset)
            have += part.bytes
            parts++
            want = Message.UpdateWant(302, have.size.toLong())
        }
        assertEquals(3, parts)
        assertContentEquals(file, have)
    }

    @Test
    fun `a watch that has part of it goes on from there`() {
        val part = WatchUpdate.partFor(Message.UpdateWant(302, WatchUpdate.PART_BYTES.toLong()), 302, file)!!
        assertEquals(WatchUpdate.PART_BYTES.toLong(), part.offset)
        assertContentEquals(file.copyOfRange(WatchUpdate.PART_BYTES, WatchUpdate.PART_BYTES * 2), part.bytes)
    }

    @Test
    fun `nothing is sent for another version or past the end`() {
        assertNull(WatchUpdate.partFor(Message.UpdateWant(301, 0), 302, file))
        assertNull(WatchUpdate.partFor(Message.UpdateWant(302, file.size.toLong()), 302, file))
        assertNull(WatchUpdate.partFor(Message.UpdateWant(302, -1), 302, file))
    }

    @Test
    fun `a part fits in one message of the link`() {
        val part = WatchUpdate.partFor(Message.UpdateWant(302, 0), 302, file)!!
        assert(MessageCodec.encode(part).size < 64 * 1024)
    }
}
