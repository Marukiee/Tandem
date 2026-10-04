package nl.markmaaktmedia.tandem.screen

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import nl.markmaaktmedia.tandem.engine.EngineHost
import uniffi.tandem_core.TandemMediaAccept
import uniffi.tandem_core.TandemMediaCodec
import uniffi.tandem_core.TandemMediaEnd
import uniffi.tandem_core.TandemMediaFacing
import uniffi.tandem_core.TandemMediaInput
import uniffi.tandem_core.TandemMediaKind
import uniffi.tandem_core.TandemMediaStats
import uniffi.tandem_core.TandemMediaUpdate
import uniffi.tandem_core.TandemMediaViewer
import uniffi.tandem_core.TandemMediaWant

/** Where a remote desktop session is, in words the screen can turn into something to read. */
sealed interface ViewerState {
    data object Idle : ViewerState

    /** The request is out. */
    data object Connecting : ViewerState

    /** The Mac has had the request for a moment and has not said yes: someone is probably being asked there. */
    data object WaitingForApproval : ViewerState

    data object Streaming : ViewerState

    /** The session was lost and a new one is being set up, [attempt] being the number of the try. */
    data class Reconnecting(val attempt: Int) : ViewerState

    /** Over, for a reason that a retry does not change by itself. */
    data class Ended(val outcome: Outcome) : ViewerState

    /** Tried [SessionPolicy.MAX_ATTEMPTS] times. */
    data object Lost : ViewerState

    /** The decoder of this phone gave up. */
    data class DecoderFailed(val why: String) : ViewerState
}

/** What the Mac gave: the size of its picture and whether this phone may use its mouse and keyboard. */
data class StreamInfo(val width: Int, val height: Int, val fps: Int, val bitrate: Int, val control: Boolean)

/** The numbers behind the stats overlay, once a second. */
data class ViewStats(
    val decoder: DecoderStats = DecoderStats(),
    val core: TandemMediaStats? = null,
    /** Bits per second that arrived in the last second. */
    val receivedBps: Long = 0,
    val rttMs: Int? = null,
)

/**
 * The phone as the viewer of a Mac's screen. Lives as long as the app, so turning the phone or a screen that comes and
 * goes does not end the session; only leaving the viewer does.
 *
 * The core keeps a session alive for ten seconds after the connection drops and brings the picture back by itself. This
 * class covers what is left: a session that did end (the Mac slept, the app there restarted, the link was gone for
 * longer) is set up again, quickly at first and then calmer, asking for less each time, until it works or the tries run
 * out. A "no" from the Mac, or the person there pressing Stop, is never retried.
 */
class ScreenViewer(
    private val context: Context,
    private val host: EngineHost,
    private val prefs: ScreenPrefs = ScreenPrefs(context),
) : TandemMediaViewer {
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<ViewerState>(ViewerState.Idle)
    val state: StateFlow<ViewerState> = _state.asStateFlow()

    private val _info = MutableStateFlow<StreamInfo?>(null)
    val info: StateFlow<StreamInfo?> = _info.asStateFlow()

    /** A picture of this session has been on the screen. */
    private val _picture = MutableStateFlow(false)
    val picture: StateFlow<Boolean> = _picture.asStateFlow()

    private val _stats = MutableStateFlow(ViewStats())
    val stats: StateFlow<ViewStats> = _stats.asStateFlow()

    /** No picture for a while although the link is up. */
    private val _stalled = MutableStateFlow(false)
    val stalled: StateFlow<Boolean> = _stalled.asStateFlow()

    /** The device this is about, or null when idle. */
    @Volatile var peer: String? = null
        private set

    @Volatile private var session = 0UL
    @Volatile private var control = false
    @Volatile private var lastFrameAt = 0L
    private var attempt = 0
    private var requestJob: Job? = null
    private var tickJob: Job? = null
    private var retryJob: Job? = null
    private var pauseJob: Job? = null
    private var lastKeyframeAsk = 0L
    private var lastBytes = 0L
    private val heldButtons = HashSet<Int>()

    private val decoder = ScreenDecoder(
        needKeyframe = { askKeyframe() },
        firstPicture = { main.launch { _picture.value = true; attempt = 0 } },
        failed = { why -> main.launch { fail(why) } },
    )

    val pointerMapper = PointerMapper(prefs.pointerSpeed)

    // ---- What the screen asks for ----------------------------------------------------------

    /** Starts looking at a Mac. Called again for the same one (a rotation, a recomposition), it keeps what runs. */
    fun open(id: String) {
        pauseJob?.cancel()
        val running = _state.value
        if (peer == id && (running is ViewerState.Streaming || running is ViewerState.Connecting ||
                running is ViewerState.WaitingForApproval || running is ViewerState.Reconnecting)
        ) return
        shutDown(tell = true)
        peer = id
        attempt = 0
        decoder.reset()
        startTick()
        request()
    }

    /** The person left. The Mac is told and stops sharing. */
    fun close() {
        pauseJob?.cancel()
        shutDown(tell = true)
        peer = null
        _state.value = ViewerState.Idle
    }

    /** The screen is not in front. A short visit to another app keeps the session; a long one ends it. */
    fun pause() {
        pauseJob?.cancel()
        pauseJob = main.launch {
            delay(PAUSE_LIMIT_MS)
            val id = peer ?: return@launch
            shutDown(tell = true)
            peer = id
            _state.value = ViewerState.Idle
        }
    }

    fun resume() {
        pauseJob?.cancel()
        val id = peer
        if (id != null && _state.value == ViewerState.Idle) open(id)
    }

    /** After a "no", a stop on the Mac or a lost connection: once more, from the start. */
    fun retry() {
        val id = peer ?: return
        shutDown(tell = true)
        peer = null
        open(id)
    }

    fun setSurface(surface: Surface?) = decoder.setSurface(surface)

    // ---- Input ---------------------------------------------------------------------------------

    val canControl: Boolean get() = control

    fun send(input: TandemMediaInput) {
        val current = session
        if (current == 0UL || !control) return
        when (input) {
            is TandemMediaInput.Button -> if (input.down) heldButtons += input.button.toInt() else heldButtons -= input.button.toInt()
            else -> Unit
        }
        runCatching { host.engine?.mediaSendInput(current, input) }
    }

    fun setPointerSpeed(value: Float) {
        prefs.pointerSpeed = value
        pointerMapper.speed = value
    }

    var directTouch: Boolean
        get() = prefs.directTouch
        set(value) {
            prefs.directTouch = value
        }

    // ---- Requests and retries --------------------------------------------------------------

    private fun request() {
        val id = peer ?: return
        val engine = host.engine
        if (engine == null) {
            scheduleRetry()
            return
        }
        _state.value = if (attempt > 0) ViewerState.Reconnecting(attempt) else ViewerState.Connecting
        _picture.value = false
        _info.value = null
        control = false
        try {
            session = engine.mediaRequest(id, want())
        } catch (e: Exception) {
            Log.w(TAG, "request failed", e)
            session = 0UL
            scheduleRetry()
            return
        }
        val mine = session
        requestJob?.cancel()
        requestJob = main.launch {
            delay(WAITING_AFTER_MS)
            if (session == mine && _state.value is ViewerState.Connecting) _state.value = ViewerState.WaitingForApproval
        }
    }

    private fun want(): TandemMediaWant {
        val metrics = context.resources.displayMetrics
        val long = maxOf(metrics.widthPixels, metrics.heightPixels)
        val short = minOf(metrics.widthPixels, metrics.heightPixels)
        val unmetered = isUnmetered()
        val width = (long * 1.25f).toInt().coerceIn(1280, if (unmetered) 2560 else 1920)
        val height = (short * 1.25f).toInt().coerceIn(720, if (unmetered) 1600 else 1200)
        val bitrate = SessionPolicy.maxBitrate(if (unmetered) 12_000_000 else 4_000_000, attempt)
        return TandemMediaWant(
            kind = TandemMediaKind.SCREEN,
            codecs = listOf(TandemMediaCodec.H264),
            maxWidth = width.toUInt(),
            maxHeight = height.toUInt(),
            maxFps = if (unmetered) 60u else 30u,
            maxBitrate = bitrate.toUInt(),
            control = true,
            facing = TandemMediaFacing.ANY,
        )
    }

    private fun isUnmetered(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return true
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** A new try, after a pause that grows, and once the Mac can be reached again. */
    private fun scheduleRetry() {
        val id = peer ?: return
        attempt++
        if (attempt > SessionPolicy.MAX_ATTEMPTS) {
            session = 0UL
            _state.value = ViewerState.Lost
            return
        }
        _state.value = ViewerState.Reconnecting(attempt)
        retryJob?.cancel()
        retryJob = main.launch {
            delay(SessionPolicy.retryDelayMs(attempt))
            // No use asking a Mac that is not there. The wait is part of the try: a Mac that does not come back within
            // a minute counts as one that failed.
            val online = withTimeoutOrNull(ONLINE_WAIT_MS) {
                host.devices.first { list -> list.firstOrNull { it.id == id }?.online == true }
            }
            if (peer != id) return@launch
            if (online == null) scheduleRetry() else request()
        }
    }

    private fun fail(why: String) {
        Log.w(TAG, "decoder gave up: $why")
        val current = session
        session = 0UL
        if (current != 0UL) runCatching { host.engine?.mediaStop(current) }
        _state.value = ViewerState.DecoderFailed(why)
    }

    /** Stops what runs. [tell] sends the stop to the Mac, which is what makes the indicator there go away. */
    private fun shutDown(tell: Boolean) {
        requestJob?.cancel()
        retryJob?.cancel()
        tickJob?.cancel()
        val current = session
        session = 0UL
        control = false
        if (current != 0UL && tell) {
            // A button that is held must not stay held on the Mac when the viewer goes.
            for (button in heldButtons) runCatching { host.engine?.mediaSendInput(current, TandemMediaInput.Button(button.toUByte(), false, 1u)) }
            runCatching { host.engine?.mediaStop(current) }
        }
        heldButtons.clear()
        pointerMapper.reset()
        _picture.value = false
        _stalled.value = false
        _info.value = null
    }

    // ---- From the core ------------------------------------------------------------------------

    override fun onAccepted(session: ULong, accept: TandemMediaAccept) {
        main.launch {
            if (session != this@ScreenViewer.session) return@launch
            requestJob?.cancel()
            control = accept.control
            _info.value = StreamInfo(accept.width.toInt(), accept.height.toInt(), accept.fps.toInt(), accept.bitrate.toInt(), accept.control)
            lastFrameAt = SystemClock.elapsedRealtime()
            _state.value = ViewerState.Streaming
        }
    }

    override fun onUpdate(session: ULong, update: TandemMediaUpdate) {
        main.launch {
            if (session != this@ScreenViewer.session) return@launch
            val old = _info.value ?: return@launch
            update.control?.let { control = it }
            _info.value = old.copy(
                width = update.width?.toInt() ?: old.width,
                height = update.height?.toInt() ?: old.height,
                fps = update.fps?.takeIf { it > 0u }?.toInt() ?: old.fps,
                control = update.control ?: old.control,
            )
        }
    }

    override fun onFrame(session: ULong, ptsUs: ULong, keyframe: Boolean, discontinuity: Boolean, data: ByteArray) {
        if (session != this.session) return
        val now = SystemClock.elapsedRealtime()
        lastFrameAt = now
        decoder.queue(VideoFrame(ptsUs.toLong(), keyframe, discontinuity, data, now))
    }

    override fun onEnded(session: ULong, reason: TandemMediaEnd) {
        main.launch {
            if (session != this@ScreenViewer.session) return@launch
            this@ScreenViewer.session = 0UL
            control = false
            requestJob?.cancel()
            val outcome = SessionPolicy.outcome(reason)
            Log.i(TAG, "session ended: $reason -> $outcome")
            if (outcome == Outcome.Retry) {
                scheduleRetry()
            } else {
                tickJob?.cancel()
                _state.value = ViewerState.Ended(outcome)
            }
        }
    }

    // ---- Keyframes and the numbers -------------------------------------------------------------

    private fun askKeyframe() {
        val current = session
        if (current == 0UL) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastKeyframeAsk < 250) return
        lastKeyframeAsk = now
        runCatching { host.engine?.mediaRequestKeyframe(current) }
    }

    private fun startTick() {
        tickJob?.cancel()
        tickJob = main.launch {
            while (true) {
                delay(1000)
                val now = SystemClock.elapsedRealtime()
                val current = session
                val engine = host.engine
                val decoderStats = decoder.stats()
                val core = if (current != 0UL) engine?.mediaStats(current) else null
                val received = decoderStats.bytesIn
                val bps = if (lastBytes in 1..received) (received - lastBytes) * 8 else 0
                lastBytes = received
                _stats.value = ViewStats(decoderStats, core, bps, core?.rttMs?.toInt())
                if (_state.value == ViewerState.Streaming) {
                    val quiet = now - lastFrameAt
                    // A still screen is repeated by the Mac about once a second, so a longer silence is not stillness.
                    if (quiet > NUDGE_MS) askKeyframe()
                    _stalled.value = quiet > STALL_MS
                } else {
                    _stalled.value = false
                }
            }
        }
    }

    private companion object {
        const val TAG = "ScreenViewer"
        const val WAITING_AFTER_MS = 1500L
        const val ONLINE_WAIT_MS = 60_000L
        const val PAUSE_LIMIT_MS = 20_000L
        const val NUDGE_MS = 2500L
        const val STALL_MS = 4000L
    }
}

/** Small things the viewer remembers. Not in the shared preferences of the app: nothing else reads them. */
class ScreenPrefs(context: Context) {
    private val store = context.getSharedPreferences("screen_viewer", Context.MODE_PRIVATE)

    var directTouch: Boolean
        get() = store.getBoolean("direct_touch", false)
        set(value) = store.edit().putBoolean("direct_touch", value).apply()

    var pointerSpeed: Float
        get() = store.getFloat("pointer_speed", 1f)
        set(value) = store.edit().putFloat("pointer_speed", value).apply()
}
