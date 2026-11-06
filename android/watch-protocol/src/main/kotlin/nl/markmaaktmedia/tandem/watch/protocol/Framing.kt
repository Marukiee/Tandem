package nl.markmaaktmedia.tandem.watch.protocol

/**
 * Splits messages into BLE-sized chunks. Each chunk starts with one header byte:
 * bit 7 marks the last chunk, bits 0-6 are a sequence number that wraps at 128.
 * BLE delivers in order on one connection, so a gap means the link is broken and the
 * connection is torn down; recovery happens through the resync after reconnecting.
 */
object Chunker {
    private const val FINAL = 0x80

    /** Smallest usable ATT payload (default MTU 23 minus 3 bytes of ATT header). */
    const val MIN_CHUNK = 20

    /** Same ceiling as Tandem's BLE link: larger writes gain little and fail on more stacks. */
    const val MAX_CHUNK = 180

    fun split(message: ByteArray, maxChunk: Int): List<ByteArray> {
        require(maxChunk >= 2) { "chunk size too small" }
        require(message.size <= MAX_MESSAGE_BYTES + OVERHEAD) { "message too large: ${message.size}" }
        val payload = maxChunk - 1
        val count = maxOf(1, (message.size + payload - 1) / payload)
        return List(count) { i ->
            val start = i * payload
            val end = minOf(start + payload, message.size)
            val last = i == count - 1
            val header = (i and 0x7F) or (if (last) FINAL else 0)
            byteArrayOf(header.toByte()) + message.copyOfRange(start, end)
        }
    }

    /** Room for the outer packet type, the AES-GCM counter and tag on top of a full message. */
    const val OVERHEAD = 1 + 8 + 16
}

class Reassembler {
    private var buffer = ByteArray(0)
    private var nextSequence = 0

    /** Returns a complete message when [chunk] finishes one, otherwise null. */
    fun push(chunk: ByteArray): ByteArray? {
        if (chunk.isEmpty()) throw ProtocolException("empty chunk")
        val header = chunk[0].toInt() and 0xFF
        val sequence = header and 0x7F
        if (sequence != nextSequence) throw ProtocolException("chunk gap: expected $nextSequence, got $sequence")
        if (buffer.size + chunk.size - 1 > MAX_MESSAGE_BYTES + Chunker.OVERHEAD) throw ProtocolException("message too large")
        buffer += chunk.copyOfRange(1, chunk.size)
        if (header and 0x80 == 0) {
            nextSequence = (nextSequence + 1) and 0x7F
            return null
        }
        val complete = buffer
        reset()
        return complete
    }

    fun reset() {
        buffer = ByteArray(0)
        nextSequence = 0
    }
}
