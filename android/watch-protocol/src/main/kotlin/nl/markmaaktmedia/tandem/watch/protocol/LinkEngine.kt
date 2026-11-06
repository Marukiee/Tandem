package nl.markmaaktmedia.tandem.watch.protocol

import java.security.KeyPair

enum class Role { PHONE, WATCH }

enum class LinkFailure {
    /** The peer has no key for us (or we have none): pair again. */
    NOT_PAIRED,
    /** A packet failed authentication: wrong key or tampering. */
    AUTH_FAILED,
    /** The peer speaks an incompatible protocol version. */
    VERSION_MISMATCH,
    /** The comparison code flow was aborted or a commitment did not verify. */
    PAIRING_FAILED,
    /** Corrupt framing; the connection should be dropped and re-established. */
    PROTOCOL_ERROR,
}

interface LinkKeyStore {
    fun load(): ByteArray?
    fun save(key: ByteArray)
    fun clear()
}

interface LinkListener {
    /** Hand one chunk to the transport. Chunks must be delivered in the order given. */
    fun sendChunk(chunk: ByteArray)
    /** Show this code on screen and ask the user whether it matches the other device. */
    fun onComparisonCode(code: String)
    fun onPaired()
    /** The encrypted session works in both directions. */
    fun onReady()
    fun onMessage(message: Message)
    /** The connection should be closed. [LinkFailure.PROTOCOL_ERROR] is worth an automatic reconnect. */
    fun onFailure(failure: LinkFailure)
}

/**
 * Transport-independent link logic: pairing, session setup, encryption and chunking.
 *
 * Pairing is X25519 with a hash commitment and a six-digit code compared by the user. The watch commits
 * to its key before it sees the phone's key, so an attacker in the middle cannot search for keys that
 * produce matching codes. Each connection then derives fresh session keys from the stored link key and
 * two random nonces.
 *
 * Not thread-safe: call everything from one thread.
 */
class LinkEngine(
    private val role: Role,
    private val store: LinkKeyStore,
    private val listener: LinkListener,
) {
    private val reassembler = Reassembler()
    private var maxChunk = Chunker.MIN_CHUNK
    private var transportUp = false

    private var ownNonce: ByteArray? = null
    private var sealing: SealingKey? = null
    private var opening: OpeningKey? = null
    private var ready = false

    // Pairing state
    private var pairingOpen = false
    private var ownKeys: KeyPair? = null
    private var peerCommit: ByteArray? = null
    private var pendingLinkKey: ByteArray? = null
    private var localConfirmed = false
    private var remoteConfirmed = false
    private var codeShown = false

    val isReady: Boolean get() = ready
    val hasLinkKey: Boolean get() = store.load() != null

    fun onTransportUp(maxChunkSize: Int) {
        resetSession()
        transportUp = true
        maxChunk = maxChunkSize.coerceIn(Chunker.MIN_CHUNK, Chunker.MAX_CHUNK)
        if (role == Role.WATCH) startFromWatch()
    }

    fun onTransportDown() {
        transportUp = false
        resetSession()
    }

    /**
     * Phone: accept a pairing attempt. Watch: pair now, replacing any existing key once confirmed.
     * Stays open until pairing finishes or [cancelPairing] is called.
     */
    fun beginPairing() {
        pairingOpen = true
        if (role == Role.WATCH && transportUp) {
            resetSession()
            startFromWatch()
        }
    }

    fun cancelPairing() {
        // Only tell the peer when a key exchange is really running; otherwise a healthy session stays up.
        if (ownKeys != null) sendPair(PAIR_REJECT, ByteArray(0))
        clearPairing()
    }

    /** The user confirmed that the code matches. */
    fun confirmPairing() {
        if (pendingLinkKey == null || !codeShown) return
        localConfirmed = true
        sendPair(PAIR_CONFIRM, ByteArray(0))
        maybeFinishPairing()
    }

    /** Ends this pairing on both sides if the peer can be reached, and always on this side. */
    fun unpair() {
        send(Message.Unpair)
        store.clear()
        clearPairing()
        resetSession()
    }

    fun send(message: Message): Boolean {
        val key = sealing ?: return false
        if (!transportUp) return false
        val plain = MessageCodec.encode(message)
        if (plain.size > MAX_MESSAGE_BYTES) throw ProtocolException("message too large: ${plain.size}")
        emit(byteArrayOf(PACKET_SEALED) + key.seal(plain))
        return true
    }

    fun onChunk(chunk: ByteArray) {
        try {
            val packet = reassembler.push(chunk) ?: return
            handlePacket(packet)
        } catch (_: ProtocolException) {
            fail(LinkFailure.PROTOCOL_ERROR)
        }
    }

    private fun startFromWatch() {
        when {
            pairingOpen -> {
                val keys = Crypto.generateKeyPair()
                ownKeys = keys
                val nonce = Crypto.randomBytes(NONCE_BYTES)
                ownNonce = nonce
                sendPair(PAIR_COMMIT, Crypto.sha256(Crypto.rawPublicKey(keys), nonce))
            }
            store.load() != null -> sendHello()
            // Unpaired and not asked to pair: stay quiet until the user starts pairing.
        }
    }

    private fun sendHello() {
        val nonce = Crypto.randomBytes(NONCE_BYTES)
        ownNonce = nonce
        emit(ByteWriter().u8(PACKET_HELLO.toInt()).u8(PROTOCOL_VERSION).raw(nonce).toByteArray())
    }

    private fun handlePacket(packet: ByteArray) {
        val r = ByteReader(packet)
        when (r.u8().toByte()) {
            PACKET_PAIR -> handlePair(r.u8(), r.rest())
            PACKET_HELLO -> handleHello(r)
            PACKET_SEALED -> handleSealed(r.rest())
            PACKET_NOT_PAIRED -> fail(LinkFailure.NOT_PAIRED)
            // An unknown outer packet can only come from a future version; ignoring it keeps the link alive.
        }
    }

    private fun handleHello(r: ByteReader) {
        val version = r.u8()
        val peerNonce = r.raw(NONCE_BYTES)
        if (version != PROTOCOL_VERSION) return fail(LinkFailure.VERSION_MISMATCH)
        val key = store.load()
        when (role) {
            Role.PHONE -> {
                if (key == null) {
                    emit(byteArrayOf(PACKET_NOT_PAIRED))
                    return
                }
                val nonce = Crypto.randomBytes(NONCE_BYTES)
                // Keys first: the watch may answer with a sealed packet as soon as this hello goes out.
                deriveSession(key, watchNonce = peerNonce, phoneNonce = nonce)
                emit(ByteWriter().u8(PACKET_HELLO.toInt()).u8(PROTOCOL_VERSION).raw(nonce).toByteArray())
            }
            Role.WATCH -> {
                val own = ownNonce
                if (key == null || own == null) return fail(LinkFailure.NOT_PAIRED)
                deriveSession(key, watchNonce = own, phoneNonce = peerNonce)
                // The first sealed message proves to the phone that this watch holds the key.
                send(Message.SyncRequest)
            }
        }
    }

    private fun deriveSession(linkKey: ByteArray, watchNonce: ByteArray, phoneNonce: ByteArray) {
        // Both nonces are fresh on every connection, so a session key is never used twice.
        val watchToPhone = Crypto.sessionKey(linkKey, watchNonce, phoneNonce, Role.WATCH)
        val phoneToWatch = Crypto.sessionKey(linkKey, watchNonce, phoneNonce, Role.PHONE)
        if (role == Role.WATCH) {
            sealing = SealingKey(watchToPhone, DIR_WATCH_TO_PHONE)
            opening = OpeningKey(phoneToWatch, DIR_PHONE_TO_WATCH)
        } else {
            sealing = SealingKey(phoneToWatch, DIR_PHONE_TO_WATCH)
            opening = OpeningKey(watchToPhone, DIR_WATCH_TO_PHONE)
        }
    }

    private fun handleSealed(body: ByteArray) {
        val key = opening ?: return fail(LinkFailure.AUTH_FAILED)
        val plain = key.open(body) ?: return fail(LinkFailure.AUTH_FAILED)
        val message = MessageCodec.decode(plain)
        if (!ready) {
            ready = true
            listener.onReady()
        }
        when (message) {
            is Message.Ping -> send(Message.Pong(message.id))
            Message.Unpair -> store.clear()
            else -> Unit
        }
        listener.onMessage(message)
    }

    private fun handlePair(kind: Int, body: ByteArray) {
        when (role) {
            Role.PHONE -> when (kind) {
                PAIR_COMMIT -> {
                    if (!pairingOpen) {
                        sendPair(PAIR_REJECT, ByteArray(0))
                        return
                    }
                    if (body.size != 32) return fail(LinkFailure.PAIRING_FAILED)
                    peerCommit = body
                    val keys = Crypto.generateKeyPair()
                    ownKeys = keys
                    sendPair(PAIR_KEY, Crypto.rawPublicKey(keys))
                }
                PAIR_REVEAL -> {
                    val commit = peerCommit ?: return fail(LinkFailure.PAIRING_FAILED)
                    val keys = ownKeys ?: return fail(LinkFailure.PAIRING_FAILED)
                    if (body.size != 32 + NONCE_BYTES) return fail(LinkFailure.PAIRING_FAILED)
                    val watchPublic = body.copyOfRange(0, 32)
                    val watchNonce = body.copyOfRange(32, body.size)
                    if (!Crypto.constantTimeEquals(commit, Crypto.sha256(watchPublic, watchNonce))) {
                        return fail(LinkFailure.PAIRING_FAILED)
                    }
                    finishKeyExchange(keys, watchPublic, Crypto.rawPublicKey(keys), watchNonce, peerPublic = watchPublic)
                }
                PAIR_CONFIRM -> onRemoteConfirm()
                PAIR_REJECT -> if (pairingOpen || ownKeys != null) abortPairing()
                else -> Unit
            }
            Role.WATCH -> when (kind) {
                PAIR_KEY -> {
                    val keys = ownKeys ?: return fail(LinkFailure.PAIRING_FAILED)
                    val nonce = ownNonce ?: return fail(LinkFailure.PAIRING_FAILED)
                    if (body.size != 32) return fail(LinkFailure.PAIRING_FAILED)
                    val own = Crypto.rawPublicKey(keys)
                    sendPair(PAIR_REVEAL, own + nonce)
                    finishKeyExchange(keys, own, body, nonce, peerPublic = body)
                }
                PAIR_CONFIRM -> onRemoteConfirm()
                PAIR_REJECT -> if (pairingOpen || ownKeys != null) abortPairing()
                else -> Unit
            }
        }
    }

    private fun finishKeyExchange(
        keys: KeyPair,
        watchPublic: ByteArray,
        phonePublic: ByteArray,
        watchNonce: ByteArray,
        peerPublic: ByteArray,
    ) {
        val shared = try {
            Crypto.sharedSecret(keys, peerPublic)
        } catch (_: java.security.GeneralSecurityException) {
            return fail(LinkFailure.PAIRING_FAILED)
        }
        pendingLinkKey = Crypto.linkKey(shared, watchPublic, phonePublic)
        codeShown = true
        listener.onComparisonCode(Crypto.comparisonCode(watchPublic, phonePublic, watchNonce))
    }

    private fun onRemoteConfirm() {
        if (pendingLinkKey == null) return
        remoteConfirmed = true
        maybeFinishPairing()
    }

    private fun maybeFinishPairing() {
        val key = pendingLinkKey ?: return
        if (!localConfirmed || !remoteConfirmed) return
        store.save(key)
        clearPairing()
        listener.onPaired()
        if (role == Role.WATCH && transportUp) sendHello()
    }

    private fun abortPairing() {
        clearPairing()
        fail(LinkFailure.PAIRING_FAILED)
    }

    private fun clearPairing() {
        pairingOpen = false
        ownKeys = null
        peerCommit = null
        pendingLinkKey = null
        localConfirmed = false
        remoteConfirmed = false
        codeShown = false
    }

    private fun sendPair(kind: Int, body: ByteArray) {
        if (!transportUp) return
        emit(ByteWriter().u8(PACKET_PAIR.toInt()).u8(kind).raw(body).toByteArray())
    }

    private fun emit(packet: ByteArray) {
        Chunker.split(packet, maxChunk).forEach(listener::sendChunk)
    }

    private fun fail(failure: LinkFailure) {
        resetSession()
        listener.onFailure(failure)
    }

    private fun resetSession() {
        reassembler.reset()
        sealing = null
        opening = null
        ownNonce = null
        ready = false
        // A half-finished pairing cannot survive a reset; a user-opened pairing window stays open.
        ownKeys = null
        peerCommit = null
        pendingLinkKey = null
        localConfirmed = false
        remoteConfirmed = false
        codeShown = false
    }

    private companion object {
        const val PACKET_PAIR: Byte = 0x01
        const val PACKET_HELLO: Byte = 0x02
        const val PACKET_SEALED: Byte = 0x03
        const val PACKET_NOT_PAIRED: Byte = 0x04

        const val PAIR_COMMIT = 1
        const val PAIR_KEY = 2
        const val PAIR_REVEAL = 3
        const val PAIR_CONFIRM = 4
        const val PAIR_REJECT = 5

        const val NONCE_BYTES = 16
        // Different constants so a key can never collide with itself in the other direction's nonce space.
        const val DIR_WATCH_TO_PHONE = 0x57324850
        const val DIR_PHONE_TO_WATCH = 0x50325748
    }
}
