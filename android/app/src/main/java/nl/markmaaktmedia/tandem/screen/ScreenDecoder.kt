package nl.markmaaktmedia.tandem.screen

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** What the decoder did lately, for the stats and for the decision to ask for a new keyframe. */
data class DecoderStats(
    val framesIn: Long = 0,
    val framesShown: Long = 0,
    val framesDropped: Long = 0,
    val bytesIn: Long = 0,
    /** Pictures shown in the last second. */
    val fps: Int = 0,
    /** From a frame reaching the app to it being on the screen, averaged. */
    val delayMs: Int = 0,
    /** When the last picture was shown, on the clock of [SystemClock.elapsedRealtime]. */
    val lastShownAt: Long = 0,
    val codecName: String = "",
)

/**
 * H.264 from the core to a surface, with the smallest delay the phone can give.
 *
 * Everything that touches the codec runs on one thread of its own, so there is no locking to get wrong: frames are queued
 * from the core's thread and a message tells the decoder thread to feed what it can. A keyframe carries its parameter
 * sets, and the codec is built from the sets of the keyframe it starts with, and built again when a later keyframe brings
 * different ones (the Mac changed its resolution). A surface that goes away takes the codec with it; the next keyframe
 * builds a new one for the new surface.
 */
class ScreenDecoder(
    /** The decoder cannot go on without a keyframe: ask the Mac for one. Called from the decoder thread. */
    private val needKeyframe: () -> Unit,
    /** The first picture after (re)starting is on the screen. */
    private val firstPicture: () -> Unit,
    /** The codec broke in a way that building it again did not help. */
    private val failed: (String) -> Unit,
) {
    private val thread = HandlerThread("tandem-screen-decoder", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)
    private val lock = Any()
    private val queue = FrameQueue()
    private val pumpPosted = AtomicBoolean(false)

    // Only touched on the decoder thread.
    private var surface: Surface? = null
    private var codec: MediaCodec? = null
    private var configured: AnnexB.ParameterSets? = null
    private var lastSets: AnnexB.ParameterSets? = null
    private val inputs = ArrayDeque<Int>()
    private var announcedPicture = false
    private var errors = 0
    private var lastError = 0L
    private var codecName = ""
    private val shownAt = ArrayDeque<Long>()
    private val arrivals = HashMap<Long, Long>()
    private var delayTotal = 0L
    private var delayCount = 0L

    @Volatile private var framesIn = 0L
    @Volatile private var framesShown = 0L
    @Volatile private var bytesIn = 0L
    @Volatile private var fps = 0
    @Volatile private var delayMs = 0
    @Volatile private var lastShown = 0L
    @Volatile private var released = false

    fun stats(): DecoderStats = DecoderStats(
        framesIn = framesIn, framesShown = framesShown, framesDropped = synchronized(lock) { queue.dropped } + codecDrops,
        bytesIn = bytesIn, fps = fps, delayMs = delayMs, lastShownAt = lastShown, codecName = codecName,
    )

    @Volatile private var codecDrops = 0L

    /** Frames from the core, in order. Returns at once. */
    fun queue(frame: VideoFrame) {
        if (released) return
        framesIn++
        bytesIn += frame.data.size
        val overflow = synchronized(lock) { queue.offer(frame) == FrameQueue.Offer.OverflowNeedKeyframe }
        if (overflow) handler.post { needKeyframe() }
        if (pumpPosted.compareAndSet(false, true)) handler.post { pump() }
    }

    /** The surface to draw on, or null when it went away. Blocks until the codec is off the old one. */
    fun setSurface(next: Surface?) {
        if (released) return
        val done = CountDownLatch(1)
        handler.post {
            try {
                surface = next
                if (next == null) {
                    releaseCodec()
                    synchronized(lock) { queue.markBroken() }
                } else {
                    // A new surface needs a picture to start from, and the codec for it is built from the next keyframe.
                    releaseCodec()
                    synchronized(lock) { queue.markBroken() }
                    needKeyframe()
                }
            } finally {
                done.countDown()
            }
        }
        // The surface may not be touched once its owner has returned from "destroyed".
        if (next == null) done.await(1, TimeUnit.SECONDS)
    }

    /** A new session: nothing of the old one is of any use. */
    fun reset() {
        handler.post {
            releaseCodec()
            synchronized(lock) { queue.markBroken() }
            lastSets = null
            announcedPicture = false
        }
    }

    fun release() {
        released = true
        handler.post {
            releaseCodec()
            thread.quitSafely()
        }
    }

    // ---- Decoder thread ------------------------------------------------------------------

    private fun pump() {
        pumpPosted.set(false)
        if (released) return
        while (true) {
            val frame = synchronized(lock) { queue.peek() } ?: return
            if (frame.keyframe) prepare(frame)
            val current = codec
            if (current == null) {
                // No surface, or a codec that could not be made: this frame has nowhere to go.
                synchronized(lock) { queue.discardHead() }
                codecDrops++
                continue
            }
            if (frame.discontinuity && !frame.keyframe) {
                // Cannot happen on the wire (a discontinuity starts with a keyframe), and if it does, wait for one.
                synchronized(lock) { queue.discardHead() }
                needKeyframe()
                continue
            }
            if (inputs.isEmpty()) return
            val index = inputs.removeFirst()
            val buffer = runCatching { current.getInputBuffer(index) }.getOrNull()
            if (buffer == null || buffer.capacity() < frame.data.size) {
                // Too big for the codec's buffer: this picture and what depends on it cannot be shown.
                inputs.addFirst(index)
                synchronized(lock) { queue.discardHead() }
                needKeyframe()
                continue
            }
            buffer.clear()
            buffer.put(frame.data)
            arrivals[frame.ptsUs] = frame.arrivedAt
            if (arrivals.size > 256) arrivals.clear()
            try {
                current.queueInputBuffer(index, 0, frame.data.size, frame.ptsUs, if (frame.keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                synchronized(lock) { queue.consume() }
            } catch (e: Exception) {
                Log.w(TAG, "queueInputBuffer failed", e)
                broke("queue: ${e.message}")
                return
            }
        }
    }

    /** Builds the codec when this keyframe needs a different one from the one that runs. */
    private fun prepare(frame: VideoFrame) {
        val sets = AnnexB.parameterSets(frame.data) ?: return
        lastSets = sets
        if (codec != null && sets == configured) return
        val target = surface ?: return
        releaseCodec()
        val size = AnnexB.dimensions(sets.sps) ?: return
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size.first, size.second).apply {
            setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(AnnexB.withStartCode(sets.sps)))
            setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(AnnexB.withStartCode(sets.pps)))
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT)
            // Hand pictures over as soon as they are decoded, and do not wait for more input to fill a pipeline.
            setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
        }
        try {
            val made = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            made.setCallback(callback, handler)
            made.configure(format, target, null, 0)
            made.start()
            codec = made
            configured = sets
            codecName = made.name
            announcedPicture = false
        } catch (e: Exception) {
            Log.w(TAG, "could not start the decoder", e)
            codec = null
            configured = null
            broke("start: ${e.message}")
        }
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            if (codec !== this@ScreenDecoder.codec) return
            inputs.addLast(index)
            pump()
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (codec !== this@ScreenDecoder.codec) return
            val now = SystemClock.elapsedRealtime()
            try {
                codec.releaseOutputBuffer(index, info.size > 0)
            } catch (e: Exception) {
                broke("release: ${e.message}")
                return
            }
            if (info.size <= 0) return
            framesShown++
            lastShown = now
            arrivals.remove(info.presentationTimeUs)?.let {
                delayTotal += now - it
                delayCount++
                if (delayCount >= 30) {
                    delayMs = (delayTotal / delayCount).toInt()
                    delayTotal = 0
                    delayCount = 0
                }
            }
            shownAt.addLast(now)
            while (shownAt.isNotEmpty() && now - shownAt.first() > 1000) shownAt.removeFirst()
            fps = shownAt.size
            if (!announcedPicture) {
                announcedPicture = true
                errors = 0
                firstPicture()
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            if (codec !== this@ScreenDecoder.codec) return
            Log.w(TAG, "decoder error", e)
            broke("codec: ${e.diagnosticInfo}")
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = Unit
    }

    /** The codec is gone; the next keyframe builds a new one. Too many in a row and the screen is told. */
    private fun broke(why: String) {
        releaseCodec()
        synchronized(lock) { queue.markBroken() }
        val now = SystemClock.elapsedRealtime()
        errors = if (now - lastError < 10_000) errors + 1 else 1
        lastError = now
        if (errors > 5) {
            failed(why)
        } else {
            needKeyframe()
        }
    }

    private fun releaseCodec() {
        val old = codec ?: return
        codec = null
        configured = null
        inputs.clear()
        arrivals.clear()
        runCatching { old.stop() }
        runCatching { old.release() }
    }

    private companion object {
        const val TAG = "ScreenDecoder"
        const val MAX_INPUT = 4 * 1024 * 1024
    }
}
