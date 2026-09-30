package nl.markmaaktmedia.tandem.hotspot

import java.util.UUID

/**
 * The Bluetooth side of the hotspot request, see docs/HOTSPOT.md. Nothing in here
 * touches Android, so the byte layouts can be tested on the JVM.
 */
object HotspotProtocol {
    val SERVICE: UUID = UUID.fromString("6f2d7a10-8b1c-4e6f-a3d5-1c9e5b7f2a40")

    /** Read: 16 fresh random bytes per read, good for one request. */
    val CHALLENGE: UUID = UUID.fromString("6f2d7a11-8b1c-4e6f-a3d5-1c9e5b7f2a40")

    /** Write with response: the signed request. */
    val REQUEST: UUID = UUID.fromString("6f2d7a12-8b1c-4e6f-a3d5-1c9e5b7f2a40")

    /** Notify and read: what the hotspot is doing. */
    val STATE: UUID = UUID.fromString("6f2d7a13-8b1c-4e6f-a3d5-1c9e5b7f2a40")

    /** The standard descriptor a central writes to switch notifications on. */
    /** Writes from the Mac: pieces of sealed clipboard and notification frames. */
    val MSG_IN: UUID = UUID.fromString("6f2d7a14-8b1c-4e6f-a3d5-1c9e5b7f2a40")

    /** Notifies the Mac of pieces of frames going the other way. */
    val MSG_OUT: UUID = UUID.fromString("6f2d7a15-8b1c-4e6f-a3d5-1c9e5b7f2a40")

    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val VERSION = 1
    const val CHALLENGE_LEN = 16
    const val SIGNATURE_LEN = 64
    const val MAX_ID_LEN = 64

    const val ACTION_ON = 1
    const val ACTION_OFF = 2

    /** Sent as the client count when the phone cannot tell. */
    const val CLIENTS_UNKNOWN = 255

    /**
     * An ATT application error (0x80 to 0x9F is ours to use). Insufficient
     * authentication would make a Mac start Bluetooth pairing, which nobody wants here.
     */
    const val ATT_REFUSED = 0x80
}

/** One byte in the state characteristic. Keep in step with docs/HOTSPOT.md. */
enum class BleState(val code: Int) {
    Off(0),
    Starting(1),
    On(2),

    /** The phone cannot start it alone: a notification waits for a tap. */
    Manual(3),
    Failed(4),
    RefusedAuth(5),
    RefusedBattery(6),
    RefusedRoaming(7),

    /** The person switched the feature off on the phone. */
    Disabled(8),
    ;

    companion object {
        fun fromCode(code: Int): BleState? = entries.firstOrNull { it.code == code }
    }
}

object StateCodec {
    /** Two bytes: the state, then the number of connected devices. */
    fun encode(state: BleState, clients: Int?): ByteArray =
        byteArrayOf(state.code.toByte(), (clients?.coerceIn(0, 254) ?: HotspotProtocol.CLIENTS_UNKNOWN).toByte())

    fun decode(bytes: ByteArray): Pair<BleState, Int?>? {
        if (bytes.isEmpty()) return null
        val state = BleState.fromCode(bytes[0].toInt() and 0xFF) ?: return null
        val raw = if (bytes.size > 1) bytes[1].toInt() and 0xFF else HotspotProtocol.CLIENTS_UNKNOWN
        return state to raw.takeIf { it != HotspotProtocol.CLIENTS_UNKNOWN }
    }
}

/** What the Mac writes: `[version][action][time 8, big endian][id length][id][signature 64]`. */
class HotspotRequest(
    val action: Int,
    val timestampMs: Long,
    val deviceId: String,
    val signature: ByteArray,
) {
    fun encode(): ByteArray {
        val id = deviceId.toByteArray(Charsets.US_ASCII)
        val out = ByteArray(1 + 1 + 8 + 1 + id.size + signature.size)
        out[0] = HotspotProtocol.VERSION.toByte()
        out[1] = action.toByte()
        for (i in 0 until 8) out[2 + i] = (timestampMs ushr (56 - 8 * i)).toByte()
        out[10] = id.size.toByte()
        id.copyInto(out, 11)
        signature.copyInto(out, 11 + id.size)
        return out
    }

    companion object {
        /** Null for anything that is not exactly a well-formed request. */
        fun decode(bytes: ByteArray): HotspotRequest? {
            if (bytes.size < 11) return null
            if (bytes[0].toInt() != HotspotProtocol.VERSION) return null
            val action = bytes[1].toInt()
            if (action != HotspotProtocol.ACTION_ON && action != HotspotProtocol.ACTION_OFF) return null
            var time = 0L
            for (i in 0 until 8) time = (time shl 8) or (bytes[2 + i].toLong() and 0xFF)
            val idLen = bytes[10].toInt() and 0xFF
            if (idLen == 0 || idLen > HotspotProtocol.MAX_ID_LEN) return null
            if (bytes.size != 11 + idLen + HotspotProtocol.SIGNATURE_LEN) return null
            val idBytes = bytes.copyOfRange(11, 11 + idLen)
            if (idBytes.any { it < 0x21 || it > 0x7E }) return null
            val signature = bytes.copyOfRange(11 + idLen, bytes.size)
            return HotspotRequest(action, time, String(idBytes, Charsets.US_ASCII), signature)
        }
    }
}

/**
 * Hands out challenges. One per connected central: reading again replaces the old one,
 * and checking a request uses it up, so a captured request is worth nothing.
 */
class ChallengeStore(
    private val random: () -> ByteArray,
    private val clock: () -> Long,
    private val ttlMs: Long = 30_000,
    private val maxPeers: Int = 16,
) {
    private class Issued(val bytes: ByteArray, val at: Long)

    private val issued = LinkedHashMap<String, Issued>()

    @Synchronized
    fun issue(peer: String): ByteArray {
        val now = clock()
        issued.entries.removeAll { now - it.value.at > ttlMs }
        issued.remove(peer)
        // A crowd of strangers reading challenges must not grow this without bound.
        while (issued.size >= maxPeers) issued.remove(issued.keys.first())
        val bytes = random()
        issued[peer] = Issued(bytes, now)
        return bytes.copyOf()
    }

    /** The challenge issued to `peer`, once. Null when there is none or it went stale. */
    @Synchronized
    fun take(peer: String): ByteArray? {
        val found = issued.remove(peer) ?: return null
        return if (clock() - found.at > ttlMs) null else found.bytes
    }

    @Synchronized
    fun forget(peer: String) {
        issued.remove(peer)
    }
}

/** Counts failed attempts per central so guessing signatures is not free. */
class FailureLimiter(
    private val clock: () -> Long,
    private val limit: Int = 5,
    private val windowMs: Long = 60_000,
) {
    private val failures = HashMap<String, ArrayDeque<Long>>()

    @Synchronized
    fun record(peer: String) {
        val now = clock()
        val list = failures.getOrPut(peer) { ArrayDeque() }
        list.addLast(now)
        prune(list, now)
    }

    @Synchronized
    fun blocked(peer: String): Boolean {
        val list = failures[peer] ?: return false
        prune(list, clock())
        return list.size >= limit
    }

    @Synchronized
    fun forget(peer: String) {
        failures.remove(peer)
    }

    private fun prune(list: ArrayDeque<Long>, now: Long) {
        while (list.isNotEmpty() && now - list.first() > windowMs) list.removeFirst()
    }
}

sealed interface AuthResult {
    class Accepted(val request: HotspotRequest) : AuthResult
    data object Malformed : AuthResult
    data object NoChallenge : AuthResult
    data object BadSignature : AuthResult
    data object Blocked : AuthResult
}

/**
 * Decides whether a written request is real. The two functions come from the Rust core
 * (the exact signed bytes, and "is this a circle member's signature"), so this class
 * has no crypto of its own and both apps cannot disagree about the layout.
 */
class HotspotAuth(
    private val challenges: ChallengeStore,
    private val limiter: FailureLimiter,
    private val buildMessage: (challenge: ByteArray, deviceId: String, action: Int, timestampMs: Long) -> ByteArray,
    private val verify: (deviceId: String, message: ByteArray, signature: ByteArray) -> Boolean,
) {
    fun check(peer: String, bytes: ByteArray): AuthResult {
        if (limiter.blocked(peer)) return AuthResult.Blocked
        val request = HotspotRequest.decode(bytes)
        if (request == null) {
            limiter.record(peer)
            return AuthResult.Malformed
        }
        // Taken before the signature is looked at, so a wrong guess burns the challenge.
        val challenge = challenges.take(peer)
        if (challenge == null) {
            limiter.record(peer)
            return AuthResult.NoChallenge
        }
        val message = buildMessage(challenge, request.deviceId, request.action, request.timestampMs)
        if (!verify(request.deviceId, message, request.signature)) {
            limiter.record(peer)
            return AuthResult.BadSignature
        }
        limiter.forget(peer)
        return AuthResult.Accepted(request)
    }
}
