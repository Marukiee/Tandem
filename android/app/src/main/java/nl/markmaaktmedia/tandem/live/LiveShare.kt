package nl.markmaaktmedia.tandem.live

import android.app.Activity
import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaCodecList
import android.media.projection.MediaProjection
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.engine.EngineHost
import nl.markmaaktmedia.tandem.engine.EngineState
import uniffi.tandem_core.TandemMediaAccept
import uniffi.tandem_core.TandemMediaCodec
import uniffi.tandem_core.TandemMediaEnd
import uniffi.tandem_core.TandemMediaFacing
import uniffi.tandem_core.TandemMediaHost
import uniffi.tandem_core.TandemMediaInput
import uniffi.tandem_core.TandemMediaKind
import uniffi.tandem_core.TandemMediaPermission
import uniffi.tandem_core.TandemMediaPush
import uniffi.tandem_core.TandemMediaRequest
import uniffi.tandem_core.TandemMediaUpdate
import java.util.concurrent.ConcurrentHashMap

/**
 * The phone as the host of live video: what a Mac asks for, what the person allows, and what is being sent.
 *
 * The core calls in on threads of its own, so everything is put on the main thread first, where the shares live. The
 * encoders push their frames to the core from their own threads, which the core allows because it never waits.
 *
 * Two ways in:
 * - a Mac asks (`onRequest`): the person is asked unless this Mac was allowed for good, then the system asks for the
 *   screen or the camera, then the sharing starts and the request is answered;
 * - the person starts it on the phone: the system asks first, the capture is kept ready, the Mac is offered the view
 *   (`mediaOffer`) and answers with an ordinary request, which is then answered without asking again.
 */
class LiveShare(
    private val app: Application,
    private val host: EngineHost,
    scope: CoroutineScope,
) : TandemMediaHost {
    private val main = Handler(Looper.getMainLooper())

    /** A request that waits for the person. */
    class Pending(val from: String, val request: TandemMediaRequest, val preApproved: Boolean)

    sealed interface Change {
        /** The Mac gave up, or the time ran out: whatever asks the person about it can close. */
        data class RequestGone(val session: ULong) : Change
    }

    /** What is shown now, or ready to be: for the device page. */
    data class Active(val peer: String, val kind: TandemMediaKind, val showing: Boolean)

    private val pending = ConcurrentHashMap<ULong, Pending>()
    private val _changes = MutableSharedFlow<Change>(extraBufferCapacity = 16)
    val changes: SharedFlow<Change> = _changes.asSharedFlow()
    private val _active = MutableStateFlow<List<Active>>(emptyList())
    val active: StateFlow<List<Active>> = _active.asStateFlow()

    private val shares = mutableListOf<Share>()

    @Volatile
    private var service: LiveShareService? = null
    private var startedActivities = 0
    val indicator = LiveIndicator(app)
    private val settings = app.getSharedPreferences("live", Context.MODE_PRIVATE)

    /** Whether a small pill is drawn on screen while sharing. Off unless asked for: the notification and the system say it already, and the pill is in the shared picture too. Needs the permission to draw over apps. */
    var indicatorWanted: Boolean
        get() = settings.getBoolean("indicator", false)
        set(value) = settings.edit().putBoolean("indicator", value).apply()

    init {
        scope.launch {
            host.state.collect { state ->
                if (state == EngineState.Running) host.engine?.setMediaHost(this@LiveShare)
            }
        }
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { startedActivities++ }
            override fun onActivityStopped(activity: Activity) { startedActivities-- }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private val inForeground get() = startedActivities > 0

    // ---- A capture kept for the next time -------------------------------------------------------------------

    /**
     * Android asks the person for every screen capture, and nothing an app does can answer for them. The way to ask
     * less is not to end a capture the moment it is over: for a Mac that is allowed for good it is kept alive a while with
     * nothing drawing on it, and the next request from that Mac is answered with it. The status bar and the notification
     * say all along that it is there, and the notification stops it.
     */
    private var warm: WarmCapture? = null
    private var warmTimeout: Runnable? = null
    private val warmCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            main.post { dropWarm() }
        }
    }

    private fun holdWarm(capture: WarmCapture) {
        dropWarm()
        warm = capture
        runCatching { capture.projection.registerCallback(warmCallback, main) }
        val timeout = Runnable { dropWarm() }
        warmTimeout = timeout
        main.postDelayed(timeout, WARM_MS)
    }

    private fun takeWarm(): WarmCapture? {
        val held = warm ?: return null
        warmTimeout?.let { main.removeCallbacks(it) }
        warmTimeout = null
        runCatching { held.projection.unregisterCallback(warmCallback) }
        warm = null
        return held
    }

    /** Ends the capture that was kept, and with it the permission of the system. */
    fun dropWarm() {
        val held = takeWarm() ?: return
        runCatching { held.display.release() }
        runCatching { held.projection.stop() }
        service?.refresh()
    }

    private fun keepsWarm(peer: String): Boolean =
        runCatching { host.engine?.mediaPolicy(peer)?.screen == TandemMediaPermission.ALWAYS }.getOrDefault(false)

    // ---- What the core asks -------------------------------------------------------------------------------

    override fun onRequest(from: String, request: TandemMediaRequest, preApproved: Boolean) {
        main.post { handleRequest(from, request, preApproved) }
    }

    override fun onKeyframe(session: ULong) {
        main.post { shares.firstOrNull { it.session == session }?.pipeline?.requestKeyframe() }
    }

    override fun onBitrate(session: ULong, bitsPerSecond: UInt) {
        main.post { shares.firstOrNull { it.session == session }?.pipeline?.setBitrate(bitsPerSecond.toInt()) }
    }

    private val remoteInput = RemoteInput(app)

    /** Only comes when control was granted: the core drops it otherwise. It needs the accessibility service to do anything. */
    override fun onInput(session: ULong, input: TandemMediaInput) {
        if (shares.any { it.session == session && it.control }) remoteInput.handle(input)
    }

    override fun onStop(session: ULong, reason: TandemMediaEnd) {
        main.post {
            if (pending.remove(session) != null) {
                _changes.tryEmit(Change.RequestGone(session))
                app.getSystemService(NotificationManager::class.java).cancel(LiveNotifications.askId(session))
            }
            shares.firstOrNull { it.session == session }?.teardown(tellMac = false)
            // The window on the computer was closed: nothing is kept ready for a next time, so the phone really stops
            // (the capture indicator of the system goes, and so does the notification).
            if (shares.isEmpty()) dropWarm()
            service?.refresh()
        }
    }

    private fun handleRequest(from: String, request: TandemMediaRequest, preApproved: Boolean) {
        val engine = host.engine ?: return
        val session = request.session
        val kind = request.kind
        if (!supports(app, kind) || TandemMediaCodec.H264 !in request.codecs) {
            runCatching { engine.mediaDeny(session, TandemMediaEnd.UNSUPPORTED) }
            return
        }
        // The Mac asking again for what it already shows replaces it: the old picture is over, whatever the core says.
        shares.filter { it.kind == kind && it.peer == from && it.session != null }.forEach { it.teardown(tellMac = false) }
        val ready = shares.firstOrNull { it.kind == kind && it.peer == from && it.session == null }
        if (ready != null) {
            ready.start(request)
            return
        }
        val running = service
        if (kind == TandemMediaKind.SCREEN && preApproved && warm != null && running != null) {
            // Allowed for good, and the capture of the last time is still alive: no question at all.
            val held = takeWarm()
            if (held != null) {
                pending[session] = Pending(from, request, true)
                begin(running, kind, from, session, held.projection, request.facing, held)
                return
            }
        }
        pending[session] = Pending(from, request, preApproved)
        askThePerson(session, from, kind)
    }

    private fun askThePerson(session: ULong, from: String, kind: TandemMediaKind) {
        val open = LiveShareActivity.forRequest(app, session)
        // With the app in front, or with permission to draw over other apps (which also lets it open a screen from the
        // background), the question opens by itself. Otherwise it is a notification that has to be tapped.
        if (inForeground || android.provider.Settings.canDrawOverlays(app)) {
            runCatching { app.startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        val name = host.device(from)?.name ?: app.getString(R.string.live_mac_fallback)
        val deny = PendingIntent.getBroadcast(
            app, LiveNotifications.askId(session),
            Intent(app, LiveActionReceiver::class.java).setAction(LiveActionReceiver.DENY).putExtra(LiveActionReceiver.EXTRA_SESSION, session.toLong()),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = LiveNotifications.ask(
            app, name, kind, LiveNotifications.activity(app, LiveNotifications.askId(session), open), deny,
        )
        app.getSystemService(NotificationManager::class.java).notify(LiveNotifications.askId(session), notification)
    }

    // ---- What the person answers ----------------------------------------------------------------------------

    fun pendingRequest(session: ULong): Pending? = pending[session]

    /** The person said no, or walked away. */
    fun deny(session: ULong, why: TandemMediaEnd) {
        if (pending.remove(session) == null) return
        app.getSystemService(NotificationManager::class.java).cancel(LiveNotifications.askId(session))
        runCatching { host.engine?.mediaDeny(session, why) }
        _changes.tryEmit(Change.RequestGone(session))
    }

    /** "Always allow this Mac": kept by the core, so nothing in the way can overrule it. */
    fun allowAlways(peer: String, kind: TandemMediaKind, control: Boolean = false) {
        val engine = host.engine ?: return
        runCatching {
            val policy = engine.mediaPolicy(peer)
            // A Mac that asks to click and type as well is only asked no more when that is allowed for good too: the core
            // does not call a request approved while one part of it still has to be asked.
            engine.setMediaPolicy(
                peer,
                if (kind == TandemMediaKind.CAMERA) {
                    policy.copy(camera = TandemMediaPermission.ALWAYS)
                } else {
                    policy.copy(screen = TandemMediaPermission.ALWAYS, control = if (control) TandemMediaPermission.ALWAYS else policy.control)
                },
            )
        }
    }

    /**
     * The person turned the accessibility service on or off while a screen is being shown: the Mac is told at once, so
     * the button for clicking on its side wakes up without showing the screen again.
     */
    fun accessibilityChanged() {
        main.post {
            shares.filter { it.kind == TandemMediaKind.SCREEN }.forEach { it.refreshControl() }
        }
    }

    // ---- Starting ---------------------------------------------------------------------------------------------

    /** From the service, once it runs in the foreground and the permission for the capture is in hand. */
    fun begin(
        service: LiveShareService, kind: TandemMediaKind, peer: String, session: ULong?, projection: MediaProjection?,
        facing: TandemMediaFacing, warmCapture: WarmCapture? = null,
    ) {
        this.service = service
        val share = Share(kind, peer, session, projection, facing, service, warmCapture)
        // A second share of the same kind to the same Mac replaces the first.
        shares.filter { it.kind == kind && it.peer == peer }.forEach { it.teardown(tellMac = true) }
        shares += share
        publish()
        val request = session?.let { pending.remove(it)?.request }
        if (request != null) {
            app.getSystemService(NotificationManager::class.java).cancel(LiveNotifications.askId(request.session))
            share.start(request)
        } else if (session == null) {
            share.arm()
        } else {
            // The request ran out while the person was answering.
            share.teardown(tellMac = false)
        }
        service.refresh()
    }

    fun stopAll() {
        main.post {
            shares.toList().forEach { it.teardown(tellMac = true) }
            // Stopping by hand is stopping: nothing is kept for the next time.
            dropWarm()
        }
    }

    fun isSharingAny(): Boolean = shares.isNotEmpty()

    fun isSharing(peer: String, kind: TandemMediaKind): Boolean = _active.value.any { it.peer == peer && it.kind == kind }

    fun serviceGone(gone: LiveShareService) {
        if (service === gone) service = null
        shares.toList().forEach { it.teardown(tellMac = true) }
        dropWarm()
    }

    /** What the notification says. */
    fun summary(): String? {
        if (shares.isEmpty()) return if (warm != null) app.getString(R.string.live_active_ready) else null
        val names = shares.map { host.device(it.peer)?.name ?: app.getString(R.string.live_mac_fallback) }.distinct().joinToString(", ")
        val screen = shares.any { it.kind == TandemMediaKind.SCREEN }
        val camera = shares.any { it.kind == TandemMediaKind.CAMERA }
        val waiting = shares.all { it.session == null }
        return app.getString(
            when {
                waiting -> R.string.live_active_waiting
                screen && camera -> R.string.live_active_both
                camera -> R.string.live_active_camera
                else -> R.string.live_active_screen
            },
            names,
        )
    }

    private fun publish() {
        _active.value = shares.map { Active(it.peer, it.kind, it.session != null) }
    }

    /** One thing being shown to one Mac. Lives on the main thread, except the frame callbacks. */
    inner class Share(
        val kind: TandemMediaKind,
        val peer: String,
        @Volatile var session: ULong?,
        private val projection: MediaProjection?,
        private val facing: TandemMediaFacing,
        private val service: LiveShareService,
        private val warmCapture: WarmCapture? = null,
    ) : PipelineEvents {
        var pipeline: LivePipeline? = null

        @Volatile
        private var accepted = false

        /** Whether the computer may click and type: it asked, and the accessibility service is on. The core clamps it by the policy. */
        @Volatile
        var control = false
        private var wantsControl = false
        private var config: ByteArray? = null
        private var armedTimeout: Runnable? = null
        private var plan: LivePlan.Plan? = null

        /** Ready and waiting for the Mac to look. */
        fun arm() {
            val engine = host.engine
            val offered = runCatching { engine?.mediaOffer(peer, kind, if (kind == TandemMediaKind.CAMERA) facing else TandemMediaFacing.ANY) }.isSuccess && engine != null
            if (!offered) {
                Toast.makeText(app, R.string.live_offer_failed, Toast.LENGTH_LONG).show()
                teardown(tellMac = false)
                return
            }
            val timeout = Runnable {
                if (session == null) {
                    Toast.makeText(app, app.getString(R.string.live_offer_unanswered, host.device(peer)?.name ?: ""), Toast.LENGTH_LONG).show()
                    teardown(tellMac = false)
                }
            }
            armedTimeout = timeout
            main.postDelayed(timeout, ARMED_MS)
        }

        fun start(request: TandemMediaRequest) {
            armedTimeout?.let { main.removeCallbacks(it) }
            armedTimeout = null
            session = request.session
            wantsControl = request.control && kind == TandemMediaKind.SCREEN
            control = wantsControl && TandemAccessibilityService.running
            publish()
            val limits = LivePlan.Limits(
                request.maxWidth.toInt(), request.maxHeight.toInt(), request.maxFps.toInt(), request.maxBitrate.toInt(),
            )
            val made: LivePipeline = when (kind) {
                TandemMediaKind.SCREEN -> {
                    val held = projection
                    if (held == null) {
                        fail(TandemMediaEnd.UNAVAILABLE)
                        return
                    }
                    ScreenPipeline(app, held, limits, this, warmCapture)
                }
                TandemMediaKind.CAMERA -> CameraPipeline(app, service, facing == TandemMediaFacing.FRONT || request.facing == TandemMediaFacing.FRONT, limits, this)
            }
            pipeline = made
            made.start()
        }

        /** Tells the Mac whether clicking is possible now, after the accessibility service came or went. */
        fun refreshControl() {
            val id = session ?: return
            if (!accepted || !wantsControl) return
            val now = TandemAccessibilityService.running
            if (now == control) return
            control = now
            val current = plan ?: return
            runCatching {
                host.engine?.mediaUpdate(id, TandemMediaUpdate(current.width.toUInt(), current.height.toUInt(), 0u, current.fps.toUInt(), now))
            }
        }

        // ---- What the capture says --------------------------------------------------------------------------

        override fun onReady(width: Int, height: Int, fps: Int, bitrate: Int, rotation: Int) {
            main.post {
                val id = session ?: return@post
                val engine = host.engine ?: return@post
                plan = LivePlan.Plan(width, height, fps, bitrate)
                val answered = runCatching {
                    engine.mediaAccept(id, TandemMediaAccept(TandemMediaCodec.H264, width.toUInt(), height.toUInt(), fps.toUInt(), bitrate.toUInt(), control))
                }
                if (answered.isFailure) {
                    Log.w(TAG, "accept failed", answered.exceptionOrNull())
                    teardown(tellMac = false)
                    return@post
                }
                if (rotation != 0) {
                    runCatching { engine.mediaUpdate(id, TandemMediaUpdate(width.toUInt(), height.toUInt(), rotation.toUShort(), fps.toUInt(), null)) }
                }
                config?.let { runCatching { engine.mediaPushConfig(id, it) } }
                accepted = true
                // The first picture of the Mac's window is a keyframe, whatever came out of the encoder before the answer.
                pipeline?.requestKeyframe()
                service.refresh()
            }
        }

        override fun onFormat(width: Int, height: Int, rotation: Int) {
            main.post {
                val id = session ?: return@post
                val fps = plan?.fps ?: LivePlan.DEFAULT_FPS
                runCatching { host.engine?.mediaUpdate(id, TandemMediaUpdate(width.toUInt(), height.toUInt(), rotation.toUShort(), fps.toUInt(), null)) }
                pipeline?.requestKeyframe()
            }
        }

        override fun onConfig(data: ByteArray) {
            config = data
            val id = session
            if (accepted && id != null) runCatching { host.engine?.mediaPushConfig(id, data) }
        }

        override fun onFrame(data: ByteArray, ptsUs: Long, keyframe: Boolean) {
            val id = session ?: return
            if (!accepted) return
            val result = host.engine?.mediaPushFrame(id, data, ptsUs.toULong(), keyframe) ?: return
            if (result == TandemMediaPush.NO_SESSION) main.post { teardown(tellMac = false) }
        }

        override fun onFailed(reason: PipelineEvents.Failure) {
            main.post {
                fail(if (reason == PipelineEvents.Failure.NO_ENCODER) TandemMediaEnd.UNSUPPORTED else TandemMediaEnd.UNAVAILABLE)
            }
        }

        override fun onEnded() {
            main.post { teardown(tellMac = true) }
        }

        private fun fail(why: TandemMediaEnd) {
            val id = session
            if (id != null && !accepted) {
                runCatching { host.engine?.mediaDeny(id, why) }
                teardown(tellMac = false)
            } else {
                teardown(tellMac = true)
            }
        }

        fun teardown(tellMac: Boolean) {
            if (!shares.remove(this)) return
            armedTimeout?.let { main.removeCallbacks(it) }
            armedTimeout = null
            accepted = false
            val id = session
            val ending = pipeline
            pipeline = null
            if (ending is ScreenPipeline) {
                val kept = ending.stop(keepWarm = id != null && keepsWarm(peer))
                if (kept != null) holdWarm(kept)
            } else {
                ending?.stop()
            }
            // A screen that was ready but never shown still holds the permission of the system.
            runCatching { if (id == null) projection?.stop() }
            if (tellMac && id != null) runCatching { host.engine?.mediaStop(id) }
            publish()
            service.refresh()
        }
    }

    companion object {
        private const val TAG = "TandemLive"

        /** How long a screen or camera waits, ready, for the Mac to look after it was offered. */
        private const val ARMED_MS = 30_000L

        /** How long a capture is kept for the next request of a Mac that is allowed for good. */
        private const val WARM_MS = 10 * 60_000L

        /** What this phone can really do, as caps for the hello. Nothing is promised that would fail. */
        fun caps(context: Context): List<String> {
            val caps = ArrayList<String>()
            if (hasH264Encoder()) {
                caps += "screen.host"
                if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) caps += "camera.host"
            }
            return caps
        }

        fun supports(context: Context, kind: TandemMediaKind): Boolean = when (kind) {
            TandemMediaKind.SCREEN -> hasH264Encoder()
            TandemMediaKind.CAMERA -> hasH264Encoder() && context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
        }

        private fun hasH264Encoder(): Boolean = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                info.isEncoder && info.supportedTypes.any { it.equals("video/avc", ignoreCase = true) }
            }
        }.getOrDefault(false)
    }
}
