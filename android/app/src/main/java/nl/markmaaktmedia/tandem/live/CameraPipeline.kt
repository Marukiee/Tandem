package nl.markmaaktmedia.tandem.live

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.OrientationEventListener
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner

/**
 * A camera of the phone, through CameraX, drawing on the surface of the encoder.
 *
 * The camera delivers the picture the way the sensor sees it, which for most phones is sideways. The encoder keeps it
 * that way and the Mac is told how many degrees to turn it (the rotation of the update), which follows the phone as it
 * is held. That costs no work on the phone and the Mac turns a picture for free.
 */
class CameraPipeline(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val front: Boolean,
    private val limits: LivePlan.Limits,
    private val events: PipelineEvents,
) : LivePipeline {
    private val main = Handler(Looper.getMainLooper())
    private val executor = ContextCompat.getMainExecutor(context)
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var encoder: LiveEncoder? = null
    private var orientation: OrientationEventListener? = null
    private var stopped = false
    private var ready = false
    private var rotation = 0

    @Volatile
    private var generation = 0

    override fun start() {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (stopped) return@addListener
            try {
                val cameras = future.get()
                provider = cameras
                val wanted = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                val other = if (front) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                val selector = when {
                    cameras.hasCamera(wanted) -> wanted
                    cameras.hasCamera(other) -> other
                    else -> return@addListener events.onFailed(PipelineEvents.Failure.CAMERA_BUSY)
                }
                val made = Preview.Builder().setResolutionSelector(resolutionSelector()).build()
                made.setSurfaceProvider(executor) { request -> provide(request) }
                preview = made
                cameras.unbindAll()
                cameras.bindToLifecycle(owner, selector, made)
                followTheHand(made)
            } catch (e: Exception) {
                Log.w(TAG, "camera did not start", e)
                events.onFailed(PipelineEvents.Failure.CAMERA_BUSY)
            }
        }, executor)
    }

    /** The size the Mac asked for, as near as the camera comes. The camera reports it in the way of its sensor: wide. */
    private fun resolutionSelector(): ResolutionSelector {
        val long = maxOf(limits.maxWidth, limits.maxHeight).takeIf { it > 0 } ?: 1280
        val short = minOf(limits.maxWidth, limits.maxHeight).takeIf { it > 0 } ?: 720
        return ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy(Size(long, short), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
            .build()
    }

    private fun provide(request: SurfaceRequest) {
        if (stopped) {
            request.willNotProvideSurface()
            return
        }
        val size = request.resolution
        val fps = minOf(LivePlan.fps(limits.maxFps), CAMERA_FPS)
        val bitrate = LivePlan.bitrate(size.width, size.height, fps, limits.maxBitrate)
        val mine = ++generation
        val made = try {
            LiveEncoder(size.width, size.height, fps, bitrate, repeatWhenStill = false, sink = object : LiveEncoder.Sink {
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
            request.willNotProvideSurface()
            events.onFailed(PipelineEvents.Failure.NO_ENCODER)
            return
        }
        val old = encoder
        encoder = made
        made.start()
        request.provideSurface(made.surface, executor) { }
        request.setTransformationInfoListener(executor) { info ->
            if (stopped || mine != generation) return@setTransformationInfoListener
            rotation = info.rotationDegrees
            if (!ready) {
                ready = true
                events.onReady(size.width, size.height, fps, bitrate, rotation)
            } else {
                events.onFormat(size.width, size.height, rotation)
            }
        }
        // A camera that came back with another size (after an interruption) is a new shape for the Mac.
        if (old != null) {
            if (ready) events.onFormat(size.width, size.height, rotation)
            old.stop()
        }
        // Should the listener never be called, the picture still goes out, unturned.
        main.postDelayed({
            if (!stopped && !ready && mine == generation) {
                ready = true
                events.onReady(size.width, size.height, fps, bitrate, rotation)
            }
        }, 1500)
    }

    /** The picture is turned for the way the phone is held, whether or not the screen follows. */
    private fun followTheHand(made: Preview) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(degrees: Int) {
                LivePlan.surfaceRotation(degrees)?.let { if (made.targetRotation != it) made.targetRotation = it }
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        orientation = listener
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
        orientation?.disable()
        orientation = null
        preview?.let { runCatching { provider?.unbind(it) } }
        preview = null
        encoder?.stop()
        encoder = null
    }

    companion object {
        private const val TAG = "TandemLive"

        /** What a phone camera does in dim light is lower; this is what the encoder is told to expect. */
        private const val CAMERA_FPS = 30
    }
}
