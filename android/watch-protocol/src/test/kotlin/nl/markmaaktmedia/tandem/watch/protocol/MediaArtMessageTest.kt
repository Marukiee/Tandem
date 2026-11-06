package nl.markmaaktmedia.tandem.watch.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaArtMessageTest {
    private fun art(key: String, size: Int = 32) = Message.MediaArt(key, ByteArray(size) { it.toByte() })

    @Test
    fun aCoverSurvivesTheLink() {
        val cover = art("abc123", Message.MediaArt.MAX_BYTES)
        assertEquals(cover, MessageCodec.decode(MessageCodec.encode(cover)))
    }

    @Test
    fun theLargestCoverFitsInOneMessage() {
        assertTrue(MessageCodec.encode(art("ffffffff", Message.MediaArt.MAX_BYTES)).size <= MAX_MESSAGE_BYTES)
    }

    @Test
    fun aPlayerKnowsWhichCoverIsItsOwn() {
        val player = MediaPlayer("app", "App", "Title", "Artist", "Album", true, 1, 2, artKey = "abc123")
        val sent = Message.MediaPlayers(listOf(player))
        assertEquals(sent, MessageCodec.decode(MessageCodec.encode(sent)))
    }

    @Test
    fun aPhoneThatDoesNotKnowCoversStillGetsThrough() {
        val old = ByteWriter().str("app").str("App").str("Title").str("Artist").str("Album").bool(true).varint(1).varint(2).toByteArray()
        val decoded = MessageCodec.decode(ByteWriter().u8(0x30).varint(1).bytes(old).toByteArray()) as Message.MediaPlayers
        assertEquals("", decoded.players.single().artKey)
        assertEquals("Title", decoded.players.single().title)
    }

    @Test
    fun onlyTheLastCoverWaitsInTheQueue() {
        val queue = OutboundQueue()
        queue.offer(art("first"))
        queue.offer(art("second"))
        assertEquals("second", (queue.poll() as Message.MediaArt).key)
        assertNull(queue.poll())
    }

    @Test
    fun aCallOvertakesACover() {
        val queue = OutboundQueue()
        queue.offer(art("cover"))
        queue.offer(Message.Call(null))
        assertTrue(queue.poll() is Message.Call)
    }
}
