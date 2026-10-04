package nl.markmaaktmedia.tandem.screen

/** One access unit as the core hands it over. */
class VideoFrame(
    val ptsUs: Long,
    val keyframe: Boolean,
    val discontinuity: Boolean,
    val data: ByteArray,
    /** When it reached the app, for the delay shown in the stats. */
    val arrivedAt: Long,
)

/**
 * The frames between the core and the decoder, with the one rule that keeps a picture from falling apart: a frame is of
 * no use without the ones it was predicted from, so whenever one is lost, nothing is fed to the decoder until the next
 * keyframe. A freeze is better than a smear.
 *
 * Holds only a few frames. A decoder that cannot keep up is not waited for, because a queue that grows is a delay that
 * grows; the queue is emptied and a keyframe is asked for instead.
 */
class FrameQueue(private val capacity: Int = 6) {
    private val frames = ArrayDeque<VideoFrame>()

    /** True from the start and after anything was lost: only a keyframe is taken. */
    var waitingForKeyframe = true
        private set

    /** Frames that were thrown away, for the stats. */
    var dropped = 0L
        private set

    val size: Int get() = frames.size

    enum class Offer { Queued, OverflowNeedKeyframe }

    fun offer(frame: VideoFrame): Offer {
        if (frames.size >= capacity && !frame.keyframe) {
            dropped += frames.size + 1
            frames.clear()
            waitingForKeyframe = true
            return Offer.OverflowNeedKeyframe
        }
        if (frame.keyframe && frames.isNotEmpty()) {
            // Everything before a keyframe is worth less than the keyframe itself.
            dropped += frames.size
            frames.clear()
        }
        frames.addLast(frame)
        return Offer.Queued
    }

    /** The next frame that may be fed, or null. Frames that cannot be decoded yet are dropped on the way. */
    fun peek(): VideoFrame? {
        while (true) {
            val head = frames.firstOrNull() ?: return null
            if (waitingForKeyframe && !head.keyframe) {
                frames.removeFirst()
                dropped++
                continue
            }
            return head
        }
    }

    /** The frame [peek] returned has been fed. */
    fun consume() {
        val head = frames.removeFirstOrNull() ?: return
        if (head.keyframe) waitingForKeyframe = false
    }

    /** The head cannot be used (too big for the decoder, no surface): it goes, and so does what depends on it. */
    fun discardHead() {
        if (frames.removeFirstOrNull() != null) dropped++
        waitingForKeyframe = true
    }

    /** The decoder broke, or was thrown away: start again from a keyframe. */
    fun markBroken() {
        dropped += frames.size
        frames.clear()
        waitingForKeyframe = true
    }
}
