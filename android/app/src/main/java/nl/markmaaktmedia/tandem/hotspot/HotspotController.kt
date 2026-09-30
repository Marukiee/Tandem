package nl.markmaaktmedia.tandem.hotspot

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.engine.HotspotState

enum class Phase {
    Off,
    Starting,

    /** Waiting for the person to flip the switch themselves. */
    NeedsTap,
    On,

    /** Said no, for a reason in [HotspotSnapshot.refusal]. Clears itself. */
    Refused,
    Failed,
}

data class HotspotSnapshot(
    val phase: Phase = Phase.Off,
    val clients: Int? = null,
    val refusal: Refusal? = null,
    val error: String? = null,
) {
    val on: Boolean get() = phase == Phase.On

    fun bleState(): BleState = when (phase) {
        Phase.Off -> BleState.Off
        Phase.Starting -> BleState.Starting
        Phase.NeedsTap -> BleState.Manual
        Phase.On -> BleState.On
        Phase.Refused -> refusal?.let(HotspotPolicy::stateFor) ?: BleState.Failed
        Phase.Failed -> BleState.Failed
    }
}

enum class HotspotMethod { Shizuku, Manual }

/** What the last try to turn the hotspot on came to, so a person can see why it needed a tap. */
data class HotspotAttempt(
    val at: Long,
    val viaShizuku: Boolean,
    val ok: Boolean,
    /** What Shizuku or the system said when it failed. Technical, and meant for reading out. */
    val detail: String = "",
)

/**
 * Turns the phone's hotspot on and off when a Mac asks, and keeps track of it.
 *
 * A normal app cannot start the hotspot. With Shizuku (the shell's identity) it can;
 * without it the best that can be done is a notification that opens the switch. Both
 * ways end the same: the hotspot is seen coming up, and [snapshot] says so.
 */
class HotspotController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val shizuku: ShizukuBridge,
    private val detector: HotspotDetector,
    private val notifications: HotspotNotifications,
    private val enabled: () -> Boolean,
    private val allowRoaming: () -> Boolean,
    /** A Mac is connected right now. Judges "in use" when the system cannot count clients. */
    private val macConnected: () -> Boolean,
    /** The hotspot came on or went off: the status the other devices see must follow. */
    private val onStatusChanged: () -> Unit,
    /** Megabytes a session may use, 0 for no limit. */
    private val dataLimitMb: () -> Long = { 0L },
    /** Called with the bytes a finished stretch of the session used, to add to the running total. */
    private val onDataUsed: (Long) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _snapshot = MutableStateFlow(HotspotSnapshot())
    val snapshot: StateFlow<HotspotSnapshot> = _snapshot.asStateFlow()

    private val jobLock = Any()
    private var job: Job? = null
    private var clearJob: Job? = null
    private var watching: Job? = null
    private val idle = IdleTracker()

    private val _lastAttempt = MutableStateFlow<HotspotAttempt?>(null)
    val lastAttempt: StateFlow<HotspotAttempt?> = _lastAttempt.asStateFlow()

    private val _sessionBytes = MutableStateFlow(0L)
    /** What the Mac has used since this hotspot session started. */
    val sessionBytes: StateFlow<Long> = _sessionBytes.asStateFlow()
    private var meterBase = -1L
    private var meterCounted = 0L

    // Whether we started it, and whether a Mac asked. A hotspot the person switched on
    // for their own reasons is never turned off behind their back.
    @Volatile private var startedByUs = false
    @Volatile private var askedFor = false

    fun method(): HotspotMethod =
        if (shizuku.state.value == ShizukuState.Ready) HotspotMethod.Shizuku else HotspotMethod.Manual

    /** Asks for the hotspot on or off. Returns at once, the result is in [snapshot]. */
    fun request(on: Boolean) {
        if (on && _snapshot.value.phase != Phase.On) {
            // Set before the job runs, so a Mac that asks and immediately looks at the
            // state sees "starting" and not the stale "off".
            publish(HotspotSnapshot(Phase.Starting))
        }
        submit { if (on) turnOn() else turnOff() }
    }

    /** The settings button: on for a while, then off again. */
    fun test(holdMs: Long = TEST_HOLD_MS) {
        publish(HotspotSnapshot(Phase.Starting))
        submit {
            turnOn()
            if (_snapshot.value.phase == Phase.On) {
                delay(holdMs)
                turnOff()
            }
        }
    }

    /** Something changed on the phone that may have flipped the hotspot. */
    fun refresh() {
        scope.launch { refreshNow() }
    }

    suspend fun refreshNow() {
        val on = isOn()
        val phase = _snapshot.value.phase
        when {
            on && phase != Phase.On && phase != Phase.Starting -> {
                // The person turned it on themselves, or a request finished after we stopped watching.
                idle.reset()
                publish(HotspotSnapshot(Phase.On, clients = _snapshot.value.clients))
            }
            !on && phase == Phase.On -> finishOff()
        }
        syncStatus(on)
        if (_snapshot.value.phase == Phase.On) watchOn()
    }

    fun shutdown() {
        synchronized(jobLock) { job?.cancel(); job = null }
        clearJob?.cancel()
        watching?.cancel()
        notifications.cancelAll()
    }

    // ---- Jobs ------------------------------------------------------------------

    private fun submit(block: suspend () -> Unit) {
        val next: Job
        synchronized(jobLock) {
            val previous = job
            next = scope.launch(start = CoroutineStart.LAZY) {
                previous?.cancelAndJoin()
                block()
            }
            job = next
        }
        next.start()
    }

    private suspend fun turnOn() {
        val refusal = HotspotPolicy.refusal(
            enabled = enabled(),
            batteryPercent = PhoneConditions.batteryPercent(context),
            charging = PhoneConditions.charging(context),
            roaming = PhoneConditions.roaming(context),
            allowRoaming = allowRoaming(),
        )
        if (refusal != null) {
            refuse(refusal)
            return
        }
        askedFor = true

        if (isOn()) {
            idle.reset()
            publish(HotspotSnapshot(Phase.On, clients = _snapshot.value.clients))
            watchOn()
            return
        }

        var shizukuFailure: String? = null
        if (method() == HotspotMethod.Shizuku) {
            publish(HotspotSnapshot(Phase.Starting))
            val result = shizuku.start()
            if (result.ok) {
                startedByUs = true
                if (waitFor(START_WAIT_MS) { isOn() }) {
                    idle.reset()
                    _lastAttempt.value = HotspotAttempt(clock(), viaShizuku = true, ok = true)
                    publish(HotspotSnapshot(Phase.On))
                    watchOn()
                } else {
                    startedByUs = false
                    _lastAttempt.value = HotspotAttempt(clock(), viaShizuku = true, ok = false, detail = "the hotspot did not come up")
                    fail("the hotspot did not come up")
                }
                return
            }
            // Shizuku refused or died: the person can still do it by hand.
            Log.w(TAG, "Shizuku could not start the hotspot: ${result.message}")
            shizukuFailure = result.message
            _lastAttempt.value = HotspotAttempt(clock(), viaShizuku = true, ok = false, detail = result.message)
        }

        publish(HotspotSnapshot(Phase.NeedsTap))
        notifications.postRequest(shizuku.state.value)
        val came = waitFor(TAP_WAIT_MS) { isOn() }
        notifications.cancelRequest()
        if (came) {
            idle.reset()
            // A tap after Shizuku failed keeps the failure on record: that is what needs fixing.
            if (shizukuFailure == null) _lastAttempt.value = HotspotAttempt(clock(), viaShizuku = false, ok = true)
            publish(HotspotSnapshot(Phase.On))
            watchOn()
        } else {
            if (shizukuFailure == null) _lastAttempt.value = HotspotAttempt(clock(), viaShizuku = false, ok = false, detail = "nobody turned it on")
            askedFor = false
            publish(HotspotSnapshot(Phase.Off))
        }
    }

    private suspend fun turnOff() {
        notifications.cancelRequest()
        if (!isOn()) {
            finishOff()
            return
        }
        // Only what a Mac asked for is ours to switch off.
        if (!(startedByUs || askedFor)) return
        if (method() == HotspotMethod.Shizuku) {
            shizuku.stop()
            if (waitFor(STOP_WAIT_MS) { !isOn() }) finishOff()
        }
        // Without Shizuku the ongoing notification stays and its button opens the switch.
    }

    private fun finishOff() {
        settleMeter()
        startedByUs = false
        askedFor = false
        idle.reset()
        notifications.cancelAll()
        publish(HotspotSnapshot(Phase.Off))
    }

    /** Adds what this stretch used to the running total and starts the next one from zero. */
    private fun settleMeter() {
        if (meterBase < 0) return
        val used = (PhoneConditions.mobileBytes() - meterBase).coerceAtLeast(0L)
        val fresh = used - meterCounted
        if (fresh > 0) onDataUsed(fresh)
        meterBase = -1L
        meterCounted = 0L
    }

    private fun refuse(refusal: Refusal) {
        Log.i(TAG, "refused: $refusal")
        publish(HotspotSnapshot(Phase.Refused, refusal = refusal))
        settleLater()
    }

    private fun fail(reason: String) {
        Log.w(TAG, "failed: $reason")
        publish(HotspotSnapshot(Phase.Failed, error = reason))
        settleLater()
    }

    /** A "no" is news for a moment, then the hotspot is simply off again. */
    private fun settleLater() {
        clearJob?.cancel()
        clearJob = scope.launch {
            delay(SETTLE_MS)
            val now = _snapshot.value.phase
            if (now == Phase.Refused || now == Phase.Failed) publish(HotspotSnapshot(Phase.Off))
        }
    }

    // ---- While it is on --------------------------------------------------------

    /** Keeps the client count fresh, shows the notification, and turns it off when idle. */
    private fun watchOn() {
        if (watching?.isActive == true) return
        watching = scope.launch {
            if (meterBase < 0) {
                meterBase = PhoneConditions.mobileBytes()
                meterCounted = 0L
                _sessionBytes.value = 0L
            }
            while (_snapshot.value.phase == Phase.On) {
                val used = (PhoneConditions.mobileBytes() - meterBase).coerceAtLeast(0L)
                _sessionBytes.value = used
                val limit = dataLimitMb() * 1_048_576L
                if (limit > 0 && used >= limit && (startedByUs || askedFor)) {
                    Log.i(TAG, "data limit reached: $used of $limit bytes")
                    notifications.postLimitReached(dataLimitMb())
                    turnOff()
                    return@launch
                }
                val automatic = shizuku.state.value == ShizukuState.Ready
                val clients = if (automatic && startedByUs) shizuku.status().clients else null
                _snapshot.update { if (it.phase == Phase.On) it.copy(clients = clients) else it }

                if (askedFor) notifications.postInUse(automatic = automatic && startedByUs, clients = clients, usedBytes = _sessionBytes.value)

                if (startedByUs && automatic) {
                    val inUse = clients?.let { it > 0 } ?: macConnected()
                    if (idle.shouldTurnOff(clock(), inUse)) {
                        Log.i(TAG, "nothing on the hotspot for a while, turning it off")
                        turnOff()
                        return@launch
                    }
                }
                delay(if (dataLimitMb() > 0) WATCH_METERED_MS else WATCH_MS)
                // The person may have switched it off by hand.
                if (!isOn()) {
                    finishOff()
                    syncStatus(false)
                    return@launch
                }
            }
        }
    }

    /**
     * On when the access point interface is up. Under Shizuku the tethering service is
     * asked as well, because interface names differ between makers and a wrong guess
     * must not make a working hotspot look failed.
     */
    private suspend fun isOn(deep: Boolean = startedByUs || _snapshot.value.phase == Phase.Starting): Boolean {
        if (detector.isOn()) return true
        // Asking Shizuku starts its process, so only when a hotspot of ours is at stake.
        if (!deep || shizuku.state.value != ShizukuState.Ready) return false
        return (shizuku.status().tethered ?: 0) > 0
    }

    private suspend fun waitFor(timeoutMs: Long, condition: suspend () -> Boolean): Boolean {
        val end = clock() + timeoutMs
        while (clock() < end) {
            if (condition()) return true
            delay(POLL_MS)
        }
        return condition()
    }

    private fun publish(snapshot: HotspotSnapshot) {
        _snapshot.value = snapshot
        syncStatus(snapshot.on)
    }

    private fun syncStatus(on: Boolean) {
        if (HotspotState.isOn == on) return
        HotspotState.isOn = on
        onStatusChanged()
    }

    companion object {
        private const val TAG = "HotspotController"
        const val TEST_HOLD_MS = 20_000L
        private const val START_WAIT_MS = 20_000L
        private const val STOP_WAIT_MS = 10_000L
        private const val TAP_WAIT_MS = 3 * 60_000L
        private const val POLL_MS = 1_000L
        private const val WATCH_MS = 15_000L
        private const val WATCH_METERED_MS = 5_000L
        private const val SETTLE_MS = 30_000L
    }
}
