package nl.markmaaktmedia.tandem.watch.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationRulesTest {
    @Test
    fun `finds one-time codes`() {
        assertEquals("482913", NotificationRules.findOtp("Bank", "Je inlogcode is 482913"))
        assertEquals("1234", NotificationRules.findOtp("Your verification code", "1234"))
    }

    @Test
    fun `leaves ordinary numbers alone`() {
        assertNull(NotificationRules.findOtp("Agenda", "Afspraak om 1430 bij kantoor"))
        assertNull(NotificationRules.findOtp("Code review", "PR is klaar"))
        assertNull(NotificationRules.findOtp("Code", "Bedrag 12.50 en datum 05/11/2026"))
    }

    @Test
    fun `clip never splits an emoji`() {
        val clipped = NotificationRules.clip("ab😀cd", 3)
        assertFalse(clipped.any { it.isSurrogate() }, "got $clipped")
        assertEquals("abc…", NotificationRules.clip("abcdef", 3))
        assertEquals("abc", NotificationRules.clip("abc", 3))
    }

    private fun msg(key: String, ts: Long, silent: Boolean = false) =
        NotificationMsg(key, "a", "App", "t", "x", null, ts, false, silent, emptyList(), null, null)

    private val now = 100 * NotificationRules.FRESH_MS

    @Test
    fun `resync starts with the list of keys and is bounded with the newest first`() {
        val many = List(100) { msg("k$it", now - 100 + it) }
        val messages = NotificationRules.sync(many, { false }, now)
        val snapshot = messages.first() as Message.NotificationSnapshot
        assertEquals(NotificationRules.MAX_SNAPSHOT, snapshot.keys.size)
        assertEquals("k99", snapshot.keys.first())
        assertEquals(NotificationRules.MAX_SNAPSHOT + 1, messages.size)
    }

    @Test
    fun `a resync buzzes at most once, for the newest unseen notification`() {
        val messages = NotificationRules.sync(listOf(msg("old", now - 2), msg("new", now - 1)), { false }, now)
        val loud = messages.filterIsInstance<Message.Notification>().filter { !it.notification.silent }
        assertEquals(listOf("new"), loud.map { it.notification.key })
    }

    @Test
    fun `a resync never buzzes for something seen, silent or old`() {
        fun loud(n: NotificationMsg, seen: Boolean) = NotificationRules.sync(listOf(n), { seen }, now)
            .filterIsInstance<Message.Notification>().count { !it.notification.silent }
        assertEquals(0, loud(msg("a", now - 1), seen = true))
        assertEquals(0, loud(msg("a", now - 1, silent = true), seen = false))
        assertEquals(0, loud(msg("a", now - NotificationRules.FRESH_MS), seen = false))
        assertEquals(1, loud(msg("a", now - 1), seen = false))
    }

    @Test
    fun `a code is masked and the sentence stays readable`() {
        assertEquals("Je code is \u2022\u2022\u2022\u2022", NotificationRules.maskOtp("Je code is 1234", "1234"))
    }
}

class OutboundQueueTest {
    private val ringing = Message.Call(CallMsg("1", CallStatus.RINGING, true, null, null, 0, false))

    @Test
    fun `a call overtakes everything already waiting`() {
        val queue = OutboundQueue()
        queue.offer(Message.NotificationRemoved("a"))
        queue.offer(Message.NotificationRemoved("b"))
        queue.offer(ringing)
        assertEquals(ringing, queue.poll())
        assertEquals(Message.NotificationRemoved("a"), queue.poll())
    }

    @Test
    fun `only the latest call state is kept`() {
        val queue = OutboundQueue()
        queue.offer(ringing)
        queue.offer(Message.Call(null))
        assertEquals(Message.Call(null), queue.poll())
        assertTrue(queue.isEmpty)
    }

    @Test
    fun `a stuck link cannot grow the queue without limit, and a call is never dropped`() {
        val queue = OutboundQueue(capacity = 2)
        queue.offer(ringing)
        repeat(5) { queue.offer(Message.NotificationRemoved("k$it")) }
        assertEquals(ringing, queue.poll())
        assertEquals(Message.NotificationRemoved("k3"), queue.poll())
        assertEquals(Message.NotificationRemoved("k4"), queue.poll())
        assertNull(queue.poll())
    }

    @Test
    fun `the notification of a music app is a player, not something to read`() {
        assertTrue(NotificationRules.isPlayer("transport", false, null))
        assertTrue(NotificationRules.isPlayer(null, true, null))
        assertTrue(NotificationRules.isPlayer(null, false, "androidx.media.app.NotificationCompat\$MediaStyle"))
        assertTrue(NotificationRules.isPlayer(null, false, "android.app.Notification\$DecoratedMediaCustomViewStyle"))
        assertFalse(NotificationRules.isPlayer("msg", false, "android.app.Notification\$MessagingStyle"))
        assertFalse(NotificationRules.isPlayer(null, false, null))
    }
}
