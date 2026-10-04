package nl.markmaaktmedia.tandem.live

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display

/**
 * The screen of the phone, through a virtual display that draws on the surface of the encoder.
 *
 * When the phone turns, the display changes shape and so must the picture: the encoder cannot change size, so a new one
 * takes over, the Mac is told before its first frame, and that frame is a keyframe by nature.
 */
class ScreenPipeline(
    private val context: Context,
    private val projection: MediaProjection,
    private val limits: LivePlan.Limits,
    private val events: PipelineEvents,
) : LivePipeline {
    private val main = Handler(Looper.getMainLooper())
    private val displays = context.getSystemService(DisplayManager::class.java)
    private var encoder: LiveEncoder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var plan: LivePlan.Plan? = null
    private var densityDpi = 320
    @Volatile
    private var generation = 0
    private var stopped = false
    private var ready = false

    /** Size of what is captured now: the display, or the app the person chose to share on Android 14 and up. */
    private var sourceWidth = 0
    private var sourceHeight = 0

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (!stopped) events.onEnded()
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            if (width > 0 && height > 0) sourceChanged(width, height)
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY || stopped) return
            val (width, height) = realSize()
            sourceChanged(width, height)
        }
    }

    @Suppress("DEPRECATION")
    private fun realSize(): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        displays.getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics)
        densityDpi = metrics.densityDpi
        return metrics.widthPixels to metrics.heightPixels
    }

    override fun start() {
        try {
            // Android 14 wants the callback before the first virtual display.
            projection.registerCallback(projectionCallback, main)
            val (width, height) = realSize()
            sourceWidth = width
            sourceHeight = height
            val next = LivePlan.plan(width, height, limits)
            plan = next
            val made = makeEncoder(next) ?: return
            encoder = made
            made.start()
            virtualDisplay = projection.createVirtualDisplay(
                "Tandem", next.width, next.height, densityDpi,
                android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, made.surface, null, null,
            )
            if (virtualDisplay == null) {
                events.onFailed(PipelineEvents.Failure.NO_CAPTURE)
                return
            }
            displays.registerDisplayListener(displayListener, main)
            ready = true
            events.onReady(next.width, next.height, next.fps, next.bitrate, 0)
        } catch (e: Exception) {
            Log.w(TAG, "screen capture did not start", e)
            events.onFailed(PipelineEvents.Failure.NO_CAPTURE)
        }
    }

    private fun makeEncoder(target: LivePlan.Plan): LiveEncoder? {
        val mine = ++generation
        return try {
            LiveEncoder(target.width, target.height, target.fps, target.bitrate, repeatWhenStill = true, sink = object : LiveEncoder.Sink {
                override fun onConfig(data: ByteArray) {
                    if (mine == generation && !stopped) events.onConfig(data)
                }

                override fun onFrame(data: ByteArray, ptsUs: Long, keyframe: Boolean) {
                    if (mine == generation && !stopped) events.onFrame(data, ptsUs, keyframe)
                }

                override fun onError(error: Throwable) {
                    Log.w(TAG, "encoder failed", error)
                    if (mine == generation && !stopped) main.post { if (!stopped) events.onFailed(PipelineEvents.Failure.OTHER) }
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "no encoder", e)
            events.onFailed(PipelineEvents.Failure.NO_ENCODER)
            null
        }
    }

    private fun sourceChanged(width: Int, height: Int) {
        if (stopped || !ready || (width == sourceWidth && height == sourceHeight)) return
        sourceWidth = width
        sourceHeight = height
        val next = LivePlan.plan(width, height, limits)
        val current = plan ?: return
        if (next.width == current.width && next.height == current.height) return
        val old = encoder
        val made = makeEncoder(next) ?: return
        plan = next
        encoder = made
        made.start()
        // The Mac hears of the new shape before the first frame of it, and the old encoder is silent from here on.
        events.onFormat(next.width, next.height, 0)
        virtualDisplay?.let {
            it.resize(next.width, next.height, densityDpi)
            it.surface = made.surface
        }
        old?.stop()
    }

    override fun requestKeyframe() {
        encoder?.requestKeyframe()
    }

    override fun setBitrate(bitsPerSecond: Int) {
        encoder?.setBitrate(bitsPerSecond)
    }

    override val framesOut: Long get() = encoder?.framesOut ?: 0
    override val bytesOut: Long get() = encoder?.bytesOut ?: 0

    override fun stop() {
        if (stopped) return
        stopped = true
        generation++
        runCatching { displays.unregisterDisplayListener(displayListener) }
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        encoder?.stop()
        encoder = null
        runCatching { projection.unregisterCallback(projectionCallback) }
        runCatching { projection.stop() }
    }

    companion object {
        private const val TAG = "TandemLive"
    }
}
