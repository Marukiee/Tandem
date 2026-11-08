package nl.markmaaktmedia.tandem.watch.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppIconMessageTest {
    private fun icon(appId: String, size: Int = 32) = Message.AppIcon(appId, ByteArray(size) { it.toByte() })

    @Test
    fun anIconSurvivesTheLink() {
        val sent = icon("com.example.chat", Message.AppIcon.MAX_BYTES)
        assertEquals(sent, MessageCodec.decode(MessageCodec.encode(sent)))
    }

    @Test
    fun anIconIsThereBeforeItsNotification() {
        val queue = OutboundQueue()
        queue.offer(icon("com.example.chat"))
        queue.offer(Message.NotificationRemoved("key"))
        assertTrue(queue.poll() is Message.AppIcon)
    }

    @Test
    fun theIconsOfDifferentAppsAllWait() {
        val queue = OutboundQueue()
        queue.offer(icon("com.example.chat"))
        queue.offer(icon("com.example.mail"))
        assertEquals("com.example.chat", (queue.poll() as Message.AppIcon).appId)
        assertEquals("com.example.mail", (queue.poll() as Message.AppIcon).appId)
    }

    @Test
    fun findingTheWatchOvertakesTheRest() {
        val queue = OutboundQueue()
        queue.offer(icon("com.example.chat"))
        queue.offer(Message.UpdatePart(302, 0, ByteArray(16)))
        queue.offer(Message.FindDevice(true))
        queue.offer(Message.FindDevice(false))
        assertEquals(Message.FindDevice(false), queue.poll())
        assertTrue(queue.poll() is Message.AppIcon)
    }

    @Test
    fun aCallOvertakesAnIcon() {
        val queue = OutboundQueue()
        queue.offer(icon("com.example.chat"))
        queue.offer(Message.Call(null))
        assertTrue(queue.poll() is Message.Call)
    }
}
