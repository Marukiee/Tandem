package nl.markmaaktmedia.tandem.audio

import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Holds the packets of sound between the network and the speaker.
 *
 * Packets arrive in bursts and now and then late, out of order or not at all; the speaker wants a
 * steady stream. This keeps a short cushion, drops what arrives too late to matter, fills a hole
 * with a moment of silence so the rest does not slide forward, and throws old sound away when the
 * two clocks have drifted so far apart that the cushion has grown into a delay.
 *
 * Safe to call [push] from one thread and [take] from another.
 */
class JitterBuffer(
    /** Bytes of one sample frame: channels times two. Silence is always a whole number of them. */
    private val frameBytes: Int,
    private val bytesPerMs: Int,
    /** Above this much waiting, the oldest sound is dropped. */
    private val maxMs: Int = 320,
    /** And it is dropped down to this much. */
    private val keepMs: Int = 120,
    /** The longest silence put into a hole. A longer hole is a stall, not a lost packet. */
    private val gapMs: Int = 40,
) {
    private val queue = LinkedBlockingDeque<ByteArray>()
    private val queued = AtomicInteger(0)

    @Volatile private var stream = -1
    @Volatile private var lastSeq = -1L

    /** How many packets were thrown away (late or too much waiting) since [reset]. For the log and the tests. */
    @Volatile var dropped = 0
        private set

    val queuedMs: Int get() = queued.get() / bytesPerMs

    /** Starts over for a new stream: whatever waits belongs to the old one. */
    fun reset(newStream: Int) {
        queue.clear()
        queued.set(0)
        stream = newStream
        lastSeq = -1
        dropped = 0
    }

    fun push(fromStream: Int, seq: Long, pcm: ByteArray) {
        if (fromStream != stream || pcm.isEmpty()) return
        val previous = lastSeq
        if (previous >= 0) {
            // Older than what was already played, or the same packet twice.
            if (seq <= previous) {
                dropped++
                return
            }
            val missing = seq - previous - 1
            if (missing > 0) {
                val bytes = minOf(missing * pcm.size, (gapMs * bytesPerMs).toLong()).toInt() / frameBytes * frameBytes
                if (bytes > 0) add(ByteArray(bytes))
            }
        }
        lastSeq = seq
        add(pcm)
        trim()
    }

    /** The next piece to play, waiting up to [timeoutMs] for one. Null when nothing came. */
    fun take(timeoutMs: Long): ByteArray? {
        val next = queue.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: return null
        queued.addAndGet(-next.size)
        return next
    }

    private fun add(bytes: ByteArray) {
        queue.addLast(bytes)
        queued.addAndGet(bytes.size)
    }

    private fun trim() {
        if (queued.get() <= maxMs * bytesPerMs) return
        while (queued.get() > keepMs * bytesPerMs) {
            val old = queue.pollFirst() ?: break
            queued.addAndGet(-old.size)
            dropped++
        }
    }
}
