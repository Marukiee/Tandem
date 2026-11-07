package nl.markmaaktmedia.tandem.watch.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class MemoryStore(var key: ByteArray? = null) : LinkKeyStore {
    override fun load() = key
    override fun save(key: ByteArray) { this.key = key }
    override fun clear() { key = null }
}

private class Side(role: Role, val store: MemoryStore = MemoryStore()) : LinkListener {
    val engine = LinkEngine(role, store, this)
    var peer: Side? = null
    var tamper: ((ByteArray) -> ByteArray)? = null
    var code: String? = null
    var paired = false
    var ready = false
    val messages = mutableListOf<Message>()
    val failures = mutableListOf<LinkFailure>()
    val sentChunks = mutableListOf<ByteArray>()

    override fun sendChunk(chunk: ByteArray) {
        sentChunks += chunk
        peer?.engine?.onChunk(tamper?.invoke(chunk) ?: chunk)
    }
    override fun onComparisonCode(code: String) { this.code = code }
    override fun onPaired() { paired = true }
    override fun onReady() { ready = true }
    override fun onMessage(message: Message) { messages += message }
    override fun onFailure(failure: LinkFailure) { failures += failure }
}

private fun link(phone: Side = Side(Role.PHONE), watch: Side = Side(Role.WATCH), chunk: Int = 20): Pair<Side, Side> {
    phone.peer = watch
    watch.peer = phone
    phone.engine.onTransportUp(chunk)
    watch.engine.onTransportUp(chunk)
    return phone to watch
}

private fun pairedLink(chunk: Int = 20): Pair<Side, Side> {
    val (phone, watch) = link(chunk = chunk)
    phone.engine.beginPairing()
    watch.engine.beginPairing()
    phone.engine.confirmPairing()
    watch.engine.confirmPairing()
    return phone to watch
}

class LinkEngineTest {
    @Test
    fun `both sides show the same code and store the same key after confirming`() {
        val (phone, watch) = link()
        phone.engine.beginPairing()
        watch.engine.beginPairing()

        assertNotNull(phone.code)
        assertEquals(6, phone.code!!.length)
        assertEquals(phone.code, watch.code)
        assertNull(phone.store.key, "no key may be stored before the user confirms")

        phone.engine.confirmPairing()
        assertNull(watch.store.key, "one-sided confirmation must not pair")
        watch.engine.confirmPairing()

        assertTrue(phone.paired && watch.paired)
        assertContentEquals(phone.store.key, watch.store.key)
    }

    @Test
    fun `session starts right after pairing and carries messages both ways`() {
        val (phone, watch) = pairedLink()
        assertTrue(phone.ready, "phone is ready after the watch's first sealed message")
        assertEquals(listOf<Message>(Message.SyncRequest), phone.messages)

        val call = Message.Call(CallMsg("1", CallStatus.RINGING, true, "+31612345678", "Oma", 123, false))
        phone.engine.send(call)
        assertTrue(watch.ready)
        assertEquals(call, watch.messages.single())

        watch.engine.send(Message.CallAction("1", CallActionKind.ANSWER))
        assertEquals(Message.CallAction("1", CallActionKind.ANSWER), phone.messages.last())
    }

    @Test
    fun `phone refuses pairing unless the user opened it`() {
        val (phone, watch) = link()
        watch.engine.beginPairing()
        assertNull(phone.code)
        assertEquals(listOf(LinkFailure.PAIRING_FAILED), watch.failures)
    }

    @Test
    fun `reconnecting uses the stored key without pairing again`() {
        val (phone, watch) = pairedLink()
        phone.engine.onTransportDown()
        watch.engine.onTransportDown()
        phone.messages.clear()

        phone.engine.onTransportUp(20)
        watch.engine.onTransportUp(20)

        assertEquals(listOf<Message>(Message.SyncRequest), phone.messages)
        assertTrue(phone.engine.send(Message.NotificationRemoved("k")))
        assertEquals(Message.NotificationRemoved("k"), watch.messages.last())
    }

    @Test
    fun `a phone that lost its key tells the watch to pair again`() {
        val (phone, watch) = pairedLink()
        phone.store.clear()
        phone.engine.onTransportDown()
        watch.engine.onTransportDown()
        phone.engine.onTransportUp(20)
        watch.engine.onTransportUp(20)
        assertEquals(listOf(LinkFailure.NOT_PAIRED), watch.failures)
    }

    @Test
    fun `different keys fail authentication`() {
        val phone = Side(Role.PHONE, MemoryStore(ByteArray(32) { 1 }))
        val watch = Side(Role.WATCH, MemoryStore(ByteArray(32) { 2 }))
        link(phone, watch)
        assertEquals(listOf(LinkFailure.AUTH_FAILED), phone.failures)
        assertFalse(phone.ready)
    }

    @Test
    fun `tampered ciphertext is rejected`() {
        val (phone, watch) = pairedLink()
        phone.tamper = { chunk -> chunk.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() } }
        phone.engine.send(Message.NotificationRemoved("key"))
        assertEquals(listOf(LinkFailure.AUTH_FAILED), watch.failures)
        assertFalse(watch.messages.contains(Message.NotificationRemoved("key")))
    }

    @Test
    fun `replayed ciphertext is rejected`() {
        val (phone, watch) = pairedLink(chunk = 512)
        phone.engine.send(Message.NotificationRemoved("key"))
        val captured = phone.sentChunks.last()
        watch.engine.onChunk(captured)
        assertEquals(listOf(LinkFailure.AUTH_FAILED), watch.failures)
    }

    @Test
    fun `a commitment that does not match the revealed key aborts pairing`() {
        val phone = Side(Role.PHONE)
        val watch = Side(Role.WATCH)
        // Flip one byte of the reveal packet: header, outer type, pair kind, then the key.
        watch.tamper = { chunk ->
            if (chunk.size > 3 && chunk[1].toInt() == 1 && chunk[2].toInt() == 3) {
                chunk.copyOf().also { it[3] = (it[3] + 1).toByte() }
            } else {
                chunk
            }
        }
        link(phone, watch, chunk = 200)
        phone.engine.beginPairing()
        watch.engine.beginPairing()
        assertEquals(listOf(LinkFailure.PAIRING_FAILED), phone.failures)
        assertNull(phone.code)
    }

    @Test
    fun `garbage from a stranger never crashes the engine`() {
        val (phone, _) = link()
        val failures = phone.failures
        listOf(
            byteArrayOf(),
            byteArrayOf(0x05, 0x01),
            byteArrayOf(0x80.toByte()),
            byteArrayOf(0x80.toByte(), 0x03, 1, 2, 3),
            byteArrayOf(0x80.toByte(), 0x02),
        ).forEach { phone.engine.onChunk(it) }
        assertTrue(failures.isNotEmpty())
    }

    @Test
    fun `a notification with long text survives the smallest chunk size`() {
        val (phone, watch) = pairedLink(chunk = 20)
        val notification = Message.Notification(
            NotificationMsg(
                key = "0|nl.example|1|null|10",
                appId = "nl.example",
                appName = "Voorbeeld",
                title = "Titel met é en 😀",
                text = "x".repeat(NotificationRules.MAX_TEXT),
                subText = "sub",
                ts = 1_000L,
                ongoing = false,
                silent = false,
                buttons = listOf(NotifButton("0", "Antwoord", true), NotifButton("1", "Gelezen", false)),
                otp = null,
                progress = 50,
            ),
        )
        phone.engine.send(notification)
        assertEquals(notification, watch.messages.last())
    }

    @Test
    fun `unpairing from the phone clears the key on both sides`() {
        val (phone, watch) = pairedLink()
        phone.engine.unpair()
        assertNull(phone.store.key)
        assertNull(watch.store.key)
        assertEquals(Message.Unpair, watch.messages.last())
    }

    @Test
    fun `unpairing a lost watch still clears the key on the phone`() {
        val (phone, watch) = pairedLink()
        phone.peer = null
        phone.engine.onTransportDown()
        phone.engine.unpair()
        assertNull(phone.store.key)
        assertNotNull(watch.store.key)
    }

    @Test
    fun `a failed pairing attempt leaves a working session alone`() {
        val (phone, watch) = pairedLink()
        phone.engine.beginPairing()
        phone.engine.cancelPairing()
        assertTrue(watch.failures.isEmpty(), "got ${watch.failures}")
        assertTrue(phone.engine.send(Message.NotificationRemoved("k")))
        assertEquals(Message.NotificationRemoved("k"), watch.messages.last())
    }

    @Test
    fun `each connection uses new session keys`() {
        val (phone, watch) = pairedLink(chunk = 512)
        phone.engine.send(Message.NotificationRemoved("same"))
        val first = phone.sentChunks.last()
        phone.engine.onTransportDown()
        watch.engine.onTransportDown()
        phone.engine.onTransportUp(512)
        watch.engine.onTransportUp(512)
        phone.engine.send(Message.NotificationRemoved("same"))
        val second = phone.sentChunks.last()
        assertFalse(first.contentEquals(second))
        // A packet from the previous connection must not open in the new one.
        watch.engine.onChunk(first)
        assertEquals(listOf(LinkFailure.AUTH_FAILED), watch.failures)
    }
}

class MessageCodecTest {
    private val samples = listOf(
        Message.SyncRequest,
        Message.Ping(7),
        Message.Pong(Long.MAX_VALUE),
        Message.Notification(
            NotificationMsg("k", "p", "App", "T", "tekst", "sub", 5, true, false, listOf(NotifButton("0", "Ok", false)), "123456", 0),
        ),
        Message.Notification(NotificationMsg("k", "p", "App", "", "", null, 0, false, true, emptyList(), null, null)),
        Message.NotificationSnapshot(emptyList()),
        Message.NotificationSnapshot(listOf("a", "b")),
        Message.Unpair,
        Message.NotificationRemoved("k"),
        Message.NotificationAction("k", "Antwoord", "Hoi", false),
        Message.NotificationAction("k", "Antwoord", "", false),
        Message.NotificationAction("k", "", null, true),
        Message.Call(null),
        Message.Call(CallMsg("1", CallStatus.ACTIVE, false, null, null, 0, true)),
        Message.CallAction("1", CallActionKind.HANGUP),
        Message.MediaPlayers(listOf(MediaPlayer("m", "VLC", "Nummer", "Artiest", "Album", true, 10, 20))),
        Message.MediaCommand("m", MediaCommandKind.NEXT),
        Message.MouseEnabled(true),
        Message.PointerButton(1, true),
        Message.PointerTargets(true, listOf(PointerTarget("mac", "MacBook", true, true)), canHold = true),
        Message.StepGoal(8000),
        Message.HeartAllDay(false),
        Message.SmartWakeAlarm(1_700_000_000_000),
    )

    @Test
    fun `every message survives a round trip`() {
        samples.forEach { assertEquals(it, MessageCodec.decode(MessageCodec.encode(it))) }
    }

    @Test
    fun `no two kinds of message share a number`() {
        // Two that did made the mouse switch arrive as a press of the mouse button.
        val numbers = samples.groupBy({ it::class }, { MessageCodec.encode(it)[0] }).mapValues { it.value.toSet() }
        numbers.values.forEach { assertEquals(1, it.size) }
        assertEquals(numbers.size, numbers.values.map { it.single() }.toSet().size, numbers.toString())
    }

    @Test
    fun `unknown type decodes to Unknown so older peers keep working`() {
        val decoded = MessageCodec.decode(byteArrayOf(0x7F, 1, 2))
        assertEquals(Message.Unknown(0x7F, byteArrayOf(1, 2)), decoded)
    }

    @Test
    fun `fields appended by a newer peer are ignored`() {
        val withExtra = MessageCodec.encode(Message.NotificationRemoved("k")) + byteArrayOf(9, 9)
        assertEquals(Message.NotificationRemoved("k"), MessageCodec.decode(withExtra))
    }

    @Test
    fun `truncated input throws ProtocolException`() {
        val encoded = MessageCodec.encode(Message.CallAction("id", CallActionKind.ANSWER))
        for (size in 1 until encoded.size) {
            assertFailsWith<ProtocolException> { MessageCodec.decode(encoded.copyOf(size)) }
        }
    }

    @Test
    fun `an absurd snapshot count cannot allocate memory`() {
        val data = ByteWriter().u8(0x10).varint(1L shl 40).toByteArray()
        assertFailsWith<ProtocolException> { MessageCodec.decode(data) }
    }
}

class ChunkerTest {
    @Test
    fun `sequence numbers wrap and messages reassemble`() {
        val message = ByteArray(5_000) { it.toByte() }
        val chunks = Chunker.split(message, 20)
        assertTrue(chunks.size > 128, "must exercise sequence wrap")
        val r = Reassembler()
        val results = chunks.mapNotNull(r::push)
        assertContentEquals(message, results.single())
    }

    @Test
    fun `a missing chunk is detected`() {
        val chunks = Chunker.split(ByteArray(100), 20)
        val r = Reassembler()
        r.push(chunks[0])
        assertFailsWith<ProtocolException> { r.push(chunks[2]) }
    }

    @Test
    fun `an empty message still produces one final chunk`() {
        val chunks = Chunker.split(ByteArray(0), 20)
        assertEquals(1, chunks.size)
        assertEquals(0, Reassembler().push(chunks[0])!!.size)
    }

    @Test
    fun `an endless message is cut off`() {
        val r = Reassembler()
        var sequence = 0
        assertFailsWith<ProtocolException> {
            while (true) {
                r.push(byteArrayOf(sequence.toByte()) + ByteArray(500))
                sequence = (sequence + 1) and 0x7F
            }
        }
    }
}
