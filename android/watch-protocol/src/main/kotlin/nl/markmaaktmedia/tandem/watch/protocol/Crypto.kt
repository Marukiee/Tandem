package nl.markmaaktmedia.tandem.watch.protocol

import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object Crypto {
    private const val XDH = "XDH"

    /** Fixed X.509 prefix of an X25519 public key; only the 32 raw bytes go over the air. */
    private val X25519_PREFIX = byteArrayOf(
        0x30, 0x2A, 0x30, 0x05, 0x06, 0x03, 0x2B, 0x65, 0x6E, 0x03, 0x21, 0x00,
    )

    fun generateKeyPair(): KeyPair =
        KeyPairGenerator.getInstance(XDH).apply { initialize(255) }.generateKeyPair()

    fun rawPublicKey(pair: KeyPair): ByteArray {
        val encoded = pair.public.encoded
        check(encoded.size == X25519_PREFIX.size + 32) { "unexpected X25519 encoding" }
        return encoded.copyOfRange(X25519_PREFIX.size, encoded.size)
    }

    fun sharedSecret(own: KeyPair, peerRawPublic: ByteArray): ByteArray {
        if (peerRawPublic.size != 32) throw ProtocolException("bad public key length")
        val spec = X509EncodedKeySpec(X25519_PREFIX + peerRawPublic)
        val peer = KeyFactory.getInstance(XDH).generatePublic(spec)
        return KeyAgreement.getInstance(XDH).run {
            init(own.private)
            doPhase(peer, true)
            generateSecret()
        }
    }

    fun sha256(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run {
        parts.forEach { update(it) }
        digest()
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(data)
    }

    /** RFC 5869. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: String, length: Int): ByteArray {
        val prk = hmac(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val infoBytes = info.encodeToByteArray()
        val output = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            previous = hmac(prk, previous + infoBytes + counter.toByte())
            val take = minOf(previous.size, length - offset)
            previous.copyInto(output, offset, 0, take)
            offset += take
            counter++
        }
        return output
    }

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { SecureRandom().nextBytes(it) }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    /** Six digits shown on both screens, from both public keys and the nonce behind the watch's commitment. */
    fun comparisonCode(watchPublic: ByteArray, phonePublic: ByteArray, watchNonce: ByteArray): String {
        val digest = sha256("tandem-watch sas v$PROTOCOL_VERSION".encodeToByteArray(), watchPublic, phonePublic, watchNonce)
        val value = ByteBuffer.wrap(digest).int.toLong() and 0xFFFFFFFFL
        return (value % 1_000_000L).toString().padStart(6, '0')
    }

    fun linkKey(shared: ByteArray, watchPublic: ByteArray, phonePublic: ByteArray): ByteArray =
        hkdf(shared, sha256(watchPublic, phonePublic), "tandem-watch v$PROTOCOL_VERSION link watch+phone", 32)

    /** A separate key per direction, bound to the protocol version and the role that sends with it. */
    fun sessionKey(linkKey: ByteArray, watchNonce: ByteArray, phoneNonce: ByteArray, sender: Role): ByteArray =
        hkdf(linkKey, watchNonce + phoneNonce, "tandem-watch v$PROTOCOL_VERSION session from ${sender.name}", 32)
}

/** One direction of AES-256-GCM with a strictly increasing counter, so replayed packets are rejected. */
class SealingKey(key: ByteArray, private val direction: Int) {
    private val secret = SecretKeySpec(key, "AES")
    private var counter = 0L

    fun seal(plain: ByteArray): ByteArray {
        val current = ++counter
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(TAG_BITS, nonce(direction, current)))
        cipher.updateAAD(AAD)
        val body = cipher.doFinal(plain)
        return ByteBuffer.allocate(8 + body.size).putLong(current).put(body).array()
    }

    companion object {
        internal const val TRANSFORM = "AES/GCM/NoPadding"
        internal const val TAG_BITS = 128
        internal val AAD = "tw1".encodeToByteArray()
        internal fun nonce(direction: Int, counter: Long): ByteArray =
            ByteBuffer.allocate(12).putInt(direction).putLong(counter).array()
    }
}

class OpeningKey(key: ByteArray, private val direction: Int) {
    private val secret = SecretKeySpec(key, "AES")
    private var highest = 0L

    /** Returns the plaintext, or null when the packet is forged, corrupt or replayed. */
    fun open(sealed: ByteArray): ByteArray? {
        if (sealed.size < 8 + SealingKey.TAG_BITS / 8) return null
        val counter = ByteBuffer.wrap(sealed, 0, 8).long
        if (counter <= highest) return null
        val cipher = Cipher.getInstance(SealingKey.TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(SealingKey.TAG_BITS, SealingKey.nonce(direction, counter)))
        cipher.updateAAD(SealingKey.AAD)
        return try {
            val plain = cipher.doFinal(sealed, 8, sealed.size - 8)
            highest = counter
            plain
        } catch (_: AEADBadTagException) {
            null
        }
    }
}
