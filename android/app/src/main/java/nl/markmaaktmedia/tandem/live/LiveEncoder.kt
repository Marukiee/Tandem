package nl.markmaaktmedia.tandem.live

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * H.264 from a surface: whatever is drawn on [surface] (the screen through a virtual display, or the camera) comes out
 * as access units in Annex B, which is what the core takes.
 *
 * Tuned for the shortest way to the Mac: no B-frames, low latency where the device knows it, a keyframe only when one
 * is needed (the core asks) and every few seconds, and the parameter sets in front of every keyframe so any keyframe can
 * be started from. Output is handled on a thread of its own and handed on at once; nothing here waits for the network.
 */
class LiveEncoder(
    val width: Int,
    val height: Int,
    fps: Int,
    bitrate: Int,
    /** A screen that does not change draws nothing; this keeps a trickle of repeated frames going so the Mac sees it alive. */
    repeatWhenStill: Boolean,
    private val sink: Sink,
) {
    interface Sink {
        /** SPS and PPS, as the encoder gives them apart from its frames. */
        fun onConfig(data: ByteArray)
        fun onFrame(data: ByteArray, ptsUs: Long, keyframe: Boolean)
        fun onError(error: Throwable)
    }

    private val thread = HandlerThread("tandem-live-encoder", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY).also { it.start() }
    private val handler = Handler(thread.looper)
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    val surface: Surface
    private val closed = AtomicBoolean(false)

    @Volatile
    var framesOut = 0L
        private set

    @Volatile
    var bytesOut = 0L
        private set

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try {
                val buffer = codec.getOutputBuffer(index)
                if (buffer != null && info.size > 0 && !closed.get()) {
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val data = ByteArray(info.size)
                    buffer.get(data)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        sink.onConfig(data)
                    } else {
                        framesOut++
                        bytesOut += data.size
                        sink.onFrame(data, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "output failed", e)
            } finally {
                runCatching { codec.releaseOutputBuffer(index, false) }
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            if (!closed.get()) sink.onError(e)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = Unit
    }

    init {
        codec.setCallback(callback, handler)

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEYFRAME_SECONDS)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
            if (repeatWhenStill) setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_AFTER_US)
        }
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (first: Exception) {
            // Some encoders refuse a key they do not know; the picture matters more than the tuning.
            Log.w(TAG, "configure with the full tuning failed, trying the plain one", first)
            val plain = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEYFRAME_SECONDS)
            }
            codec.reset()
            codec.setCallback(callback, handler)
            codec.configure(plain, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        surface = codec.createInputSurface()
    }

    fun start() {
        codec.start()
    }

    /** The Mac lost the thread of the picture: the next frame is a full one. */
    fun requestKeyframe() {
        if (closed.get()) return
        runCatching { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    /** The link carries more or less: move the encoder with it. */
    fun setBitrate(bitsPerSecond: Int) {
        if (closed.get()) return
        runCatching { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitsPerSecond) }) }
    }

    /** Stops the output at once (a frame in flight is dropped) and lets go of the encoder. */
    fun stop() {
        if (!closed.compareAndSet(false, true)) return
        handler.post {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { surface.release() }
            thread.quitSafely()
        }
    }

    companion object {
        private const val TAG = "TandemLive"
        private const val KEYFRAME_SECONDS = 3
        private const val REPEAT_AFTER_US = 500_000L
    }
}
