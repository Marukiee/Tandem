package nl.markmaaktmedia.tandem.watch

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.data.TandemPrefs
import nl.markmaaktmedia.tandem.engine.EngineHost
import nl.markmaaktmedia.tandem.engine.FindPhone
import nl.markmaaktmedia.tandem.engine.Permissions
import nl.markmaaktmedia.tandem.ui.remote.RemoteLink
import nl.markmaaktmedia.tandem.watch.protocol.CallMsg
import nl.markmaaktmedia.tandem.watch.protocol.DeviceBattery
import nl.markmaaktmedia.tandem.watch.protocol.LinkEngine
import nl.markmaaktmedia.tandem.watch.protocol.LinkFailure
import nl.markmaaktmedia.tandem.watch.protocol.LinkListener
import nl.markmaaktmedia.tandem.watch.protocol.MediaPlayer
import nl.markmaaktmedia.tandem.watch.protocol.Message
import nl.markmaaktmedia.tandem.watch.protocol.StageRun
import uniffi.tandem_core.TandemHealthKind
import uniffi.tandem_core.TandemHealthRecord
import uniffi.tandem_core.TandemSleepStage
import uniffi.tandem_core.TandemStageRun
import nl.markmaaktmedia.tandem.watch.protocol.NotificationMsg
import nl.markmaaktmedia.tandem.watch.protocol.NotificationRules
import nl.markmaaktmedia.tandem.watch.protocol.OutboundQueue
import nl.markmaaktmedia.tandem.watch.protocol.PointerTarget
import nl.markmaaktmedia.tandem.watch.protocol.Role
import uniffi.tandem_core.TandemPlatform

data class WatchState(
    val paired: Boolean = false,
    val connected: Boolean = false,
    val ready: Boolean = false,
    val pairingOpen: Boolean = false,
    val code: String? = null,
    val batteryLevel: Int? = null,
    val charging: Boolean = false,
    val ringing: Boolean = false,
    val sleeping: Boolean = false,
)

/**
 * The phone side of the watch link: owns the secure session and everything the watch should see. All work happens on one
 * thread, so the hooks in the rest of the app only post to it. With no watch paired nothing runs: no advertising, no
 * listeners.
 */
@SuppressLint("NewApi") // PhoneBleServer needs Android 13; start and beginPairing stop at [supported].
class WatchBridge(
    private val context: Context,
    private val host: EngineHost,
    private val prefs: TandemPrefs,
    private val scope: CoroutineScope,
) : LinkListener, PhoneBleServer.Events {
    val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    private val thread = HandlerThread("tandem-watch").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val pairingTimeout = Runnable { cancelPairing() }
    private val authTimeout = Runnable { dropUnauthenticated() }

    private val _state = MutableStateFlow(WatchState())
    val state: StateFlow<WatchState> = _state.asStateFlow()

    private var engine: LinkEngine? = null
    private var server: PhoneBleServer? = null
    private var notifications: WatchNotifications? = null
    private val calls = WatchCalls(context, handler) { update -> enqueue(Message.Call(update)) }
    private val media = WatchMedia(context, handler, { mediaExcluded }, { cover -> enqueue(cover) }) { update -> enqueue(Message.MediaPlayers(update)) }
    private val quiet = WatchSleepQuiet(context, handler)
    private var running = false
    private var active = false
    private val jobs = mutableListOf<Job>()

    private val alerted = HashSet<String>()
    private val outbound = OutboundQueue()

    /** Per connection, not for good: what was on its way when the link dropped never arrived, and an icon is small enough to send again. */
    private val iconsSent = HashSet<String>()

    @Volatile private var mirrorAll = true

    @Volatile private var mirrorExcluded: Set<String> = emptySet()

    @Volatile private var mirrorApps: Set<String> = emptySet()

    @Volatile private var mediaExcluded: Set<String> = emptySet()

    private var mouseOn = false
    private var targets: List<PointerTarget> = emptyList()
    private var pointer: RemoteLink? = null
    private var batteries: List<DeviceBattery> = emptyList()
    private var ownBattery: DeviceBattery? = null
    private var phoneRinging = false

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> handler.post { if (active) server?.start() }
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> handler.post { server?.stop() }
            }
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level < 0 || scale <= 0) return
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            handler.post {
                ownBattery = DeviceBattery("", host.myName, level * 100 / scale, plugged)
                publishBatteries()
            }
        }
    }

    fun start() {
        if (!supported) return
        handler.post {
            if (running) return@post
            running = true
            val link = LinkEngine(Role.PHONE, SecureLinkKeyStore(context), this)
            engine = link
            server = PhoneBleServer(context, handler, this)
            _state.update { it.copy(paired = link.hasLinkKey) }
            quiet.checkLeftOver()
            updateActive()
        }
        jobs += scope.launch { prefs.mirrorAllApps.collect { mirrorAll = it } }
        jobs += scope.launch { prefs.mirrorExcluded.collect { mirrorExcluded = it } }
        jobs += scope.launch { prefs.mirrorApps.collect { mirrorApps = it } }
        jobs += scope.launch {
            prefs.mediaExcluded.collect {
                mediaExcluded = it
                handler.post { media.schedulePublish() }
            }
        }
        jobs += scope.launch {
            prefs.watchMouse.collect { on ->
                handler.post {
                    mouseOn = on
                    if (!on) endPointer()
                    publishTargets()
                }
            }
        }
        jobs += scope.launch {
            host.devices.collect { devices ->
                val computers = devices.filter { it.platform != TandemPlatform.ANDROID && "input" in it.caps }.map { device ->
                    PointerTarget(
                        device.id,
                        device.name,
                        device.online,
                        // A computer that was not allowed to move its pointer says so with this feature.
                        allowsInput = device.features.firstOrNull { it.id == "control" }?.ready != false,
                    )
                }
                val others = devices.filter { it.online }.mapNotNull { device ->
                    device.status.battery?.let { DeviceBattery(device.id, device.name, it.level.toInt(), it.charging) }
                }
                handler.post {
                    targets = computers
                    batteries = others
                    if (pointer != null && computers.none { it.id == pointerId && it.online }) endPointer()
                    publishTargets()
                    publishBatteries()
                }
            }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        handler.post {
            running = false
            updateActive()
            engine = null
            server = null
        }
    }

    /** Bluetooth and the listeners run for a paired watch, or while one may pair. */
    private fun updateActive() {
        val should = running && (_state.value.paired || _state.value.pairingOpen) && Permissions.bluetooth(context)
        if (should == active) return
        active = should
        if (should) {
            server?.start()
            calls.start()
            media.refresh()
            context.registerReceiver(bluetoothReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), Context.RECEIVER_EXPORTED)
            context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } else {
            server?.stop()
            calls.stop()
            media.stop()
            endPointer()
            runCatching { context.unregisterReceiver(bluetoothReceiver) }
            runCatching { context.unregisterReceiver(batteryReceiver) }
        }
    }

    fun beginPairing() {
        if (!supported) return
        handler.post {
            engine?.beginPairing()
            handler.removeCallbacks(pairingTimeout)
            handler.removeCallbacks(authTimeout)
            handler.postDelayed(pairingTimeout, PAIRING_WINDOW_MS)
            _state.update { it.copy(pairingOpen = true, code = null) }
            updateActive()
        }
    }

    fun cancelPairing() {
        handler.post {
            engine?.cancelPairing()
            handler.removeCallbacks(pairingTimeout)
            _state.update { it.copy(pairingOpen = false, code = null) }
            updateActive()
        }
    }

    fun confirmPairing() {
        handler.post { engine?.confirmPairing() }
    }

    /** Works with the watch out of reach too: a lost watch must not keep access to this phone. */
    fun unpair() {
        handler.post {
            engine?.unpair()
            outbound.clear()
            iconsSent.clear()
            alerted.clear()
            endPointer()
            _state.update { WatchState() }
            handler.postDelayed({
                server?.disconnect()
                updateActive()
            }, UNPAIR_FLUSH_MS)
        }
    }

    fun ringWatch(on: Boolean) {
        handler.post { enqueue(Message.FindDevice(on)) }
    }

    // Hooks from the rest of the app

    fun onListener(listener: NotificationListenerService?) {
        handler.post {
            notifications = listener?.let(::WatchNotifications)
            if (listener == null) return@post
            if (engine?.isReady == true) sendNotificationSync()
            // The system granted notification access, which also opens media sessions.
            if (active) media.refresh()
        }
    }

    fun onPosted(sbn: StatusBarNotification) {
        handler.post {
            if (engine?.isReady != true || !mirrors(sbn.packageName)) return@post
            val source = notifications ?: return@post
            val notification = source.toMessage(sbn, source.ranking()) ?: return@post
            val first = alerted.add(notification.key)
            sendIcon(notification.appId)
            enqueue(Message.Notification(if (first) notification else notification.copy(silent = true)))
        }
    }

    fun onRemoved(key: String) {
        handler.post {
            alerted.remove(key)
            enqueue(Message.NotificationRemoved(key))
        }
    }

    /** Stopped by hand or by another device, the watch's button has to follow. */
    fun onPhoneRinging(on: Boolean) {
        handler.post {
            phoneRinging = on
            enqueue(Message.Ringing(on))
        }
    }

    // Internals

    /** The apps chosen for the other devices count for the watch too: nobody expects a hidden app to show up on a wrist. */
    private fun mirrors(packageName: String) =
        if (mirrorAll) packageName !in mirrorExcluded else packageName in mirrorApps

    private fun sendNotificationSync() {
        val current = notifications?.snapshot().orEmpty().filter { mirrors(it.appId) }
        NotificationRules.sync(current, alerted::contains, System.currentTimeMillis()).forEach { message ->
            if (message is Message.Notification) sendIcon(message.notification.appId)
            enqueue(message)
        }
        current.forEach { alerted.add(it.key) }
    }

    /** Ahead of the notification in the same queue, so the first one from an app already looks right. */
    private fun sendIcon(appId: String) {
        if (iconsSent.add(appId)) notifications?.icon(appId)?.let(::enqueue)
    }

    private fun sendFullState() {
        enqueue(Message.Call(calls.state))
        sendNotificationSync()
        media.covers.forEach(::enqueue)
        enqueue(Message.MediaPlayers(media.players))
        publishTargets()
        publishBatteries()
        enqueue(Message.Ringing(phoneRinging))
    }

    private fun publishTargets() {
        enqueue(Message.PointerTargets(mouseOn, targets))
    }

    private fun publishBatteries() {
        enqueue(Message.DeviceBatteries(listOfNotNull(ownBattery) + batteries))
    }

    private var pointerId: String? = null

    /** The watch only gets to steer what was offered to it, and only while the person allows that here. */
    private fun startPointer(id: String?) {
        endPointer()
        if (id == null || !mouseOn) return
        if (targets.none { it.id == id && it.online && it.allowsInput }) return
        pointerId = id
        pointer = RemoteLink(host, id, scope).also { it.start() }
    }

    private fun endPointer() {
        pointer?.release()
        pointer = null
        pointerId = null
    }

    private fun enqueue(message: Message) {
        if (engine?.isReady != true) return
        outbound.offer(message)
        pump()
    }

    /** One message at a time: a ringing phone can overtake whatever has not been sealed yet. */
    private fun pump() {
        val link = engine ?: return
        val ble = server ?: return
        while (ble.isIdle) {
            val next = outbound.poll() ?: return
            if (!link.send(next)) return
        }
    }

    private fun dropUnauthenticated() {
        val link = engine ?: return
        if (link.isReady || _state.value.pairingOpen) return
        Log.i(TAG, "No valid session in time, dropping the connection")
        server?.disconnect()
    }

    // LinkListener

    override fun sendChunk(chunk: ByteArray) {
        server?.send(chunk)
    }

    override fun onComparisonCode(code: String) {
        _state.update { it.copy(code = code) }
    }

    override fun onPaired() {
        handler.removeCallbacks(pairingTimeout)
        alerted.clear()
        _state.update { it.copy(paired = true, pairingOpen = false, code = null) }
        updateActive()
    }

    override fun onReady() {
        handler.removeCallbacks(authTimeout)
        _state.update { it.copy(ready = true) }
    }

    override fun onMessage(message: Message) {
        when (message) {
            Message.SyncRequest -> sendFullState()
            Message.Unpair -> {
                alerted.clear()
                endPointer()
                _state.update { WatchState() }
                server?.disconnect()
                updateActive()
            }
            is Message.NotificationAction -> notifications?.perform(message.key, message.button, message.reply, message.dismiss)
            is Message.CallAction -> calls.perform(message.kind)
            is Message.MediaCommand -> media.perform(message.playerId, message.kind)
            is Message.PointerSession -> startPointer(message.targetId)
            is Message.PointerMove -> pointer?.pointer(message.dx, message.dy)
            is Message.PointerScroll -> pointer?.scroll(message.dx, message.dy)
            is Message.PointerClick -> pointer?.click(message.button, message.count)
            is Message.PointerKey -> pointer?.key(message.code.toShort(), message.mods)
            is Message.FindDevice -> main.post { if (message.ring) FindPhone.start(context) else FindPhone.stop() }
            is Message.Ringing -> _state.update { it.copy(ringing = message.on) }
            is Message.WatchStatus -> _state.update { it.copy(batteryLevel = message.level, charging = message.charging) }
            is Message.SleepMode -> onSleepMode(message.on)
            is Message.HealthRecord -> onHealthRecord(message)
            is Message.Ping, is Message.Pong, is Message.Unknown -> Unit
            // These only travel from phone to watch.
            is Message.NotificationSnapshot, is Message.Notification, is Message.NotificationRemoved, is Message.AppIcon,
            is Message.HealthAck,
            is Message.Call, is Message.MediaPlayers, is Message.MediaArt, is Message.PointerTargets, is Message.DeviceBatteries,
            -> Unit
        }
    }

    /** Comes again after each reconnect, also after this app restarted, so it can not go by what it saw last. */
    /** Only says so to the watch when it is safe here. Without an engine right now the watch simply offers it again next time. */
    private fun onHealthRecord(message: Message.HealthRecord) {
        val engine = host.engine ?: return
        scope.launch {
            runCatching { engine.addHealthRecords(listOf(message.toTandem())) }
                .onSuccess { handler.post { enqueue(Message.HealthAck(message.id)) } }
                .onFailure { Log.w(TAG, "Could not keep a health record", it) }
        }
    }

    private fun onSleepMode(on: Boolean) {
        if (on && _state.value.sleeping) return
        _state.update { it.copy(sleeping = on) }
        scope.launch {
            val wanted = prefs.watchSleepQuiet.first()
            handler.post { if (!on || wanted) quiet.turn(on) }
        }
    }

    override fun onFailure(failure: LinkFailure) {
        Log.i(TAG, "Link failure: $failure")
        outbound.clear()
        iconsSent.clear()
        _state.update {
            it.copy(ready = false, code = null, pairingOpen = if (failure == LinkFailure.PAIRING_FAILED) false else it.pairingOpen)
        }
        server?.disconnect()
        updateActive()
    }

    // PhoneBleServer.Events

    override fun onLinkUp(maxChunk: Int) {
        engine?.onTransportUp(maxChunk)
        _state.update { it.copy(connected = true, ready = false) }
        handler.removeCallbacks(authTimeout)
        handler.postDelayed(authTimeout, AUTH_WINDOW_MS)
    }

    override fun onLinkDown() {
        engine?.onTransportDown()
        outbound.clear()
        iconsSent.clear()
        // The watch cannot say it stopped over a link that is gone.
        endPointer()
        handler.removeCallbacks(authTimeout)
        _state.update { it.copy(connected = false, ready = false, code = null, ringing = false) }
    }

    override fun onChunk(chunk: ByteArray) {
        engine?.onChunk(chunk)
    }

    override fun onSendIdle() = pump()

    override fun onBleLog(line: String) {
        Log.i(TAG, line)
    }

    private companion object {
        const val TAG = "TandemWatch"
        const val PAIRING_WINDOW_MS = 2 * 60_000L
        const val AUTH_WINDOW_MS = 30_000L
        const val UNPAIR_FLUSH_MS = 500L
    }
}

private fun Message.HealthRecord.toTandem() = TandemHealthRecord(
    id = id,
    kind = if (workout) TandemHealthKind.WORKOUT else TandemHealthKind.SLEEP,
    startMs = startMs.toULong(),
    endMs = endMs.toULong(),
    sleepMinutes = sleepMinutes.toUInt(),
    awakeMinutes = awakeMinutes.toUInt(),
    awakenings = awakenings.toUInt(),
    efficiencyPercent = efficiencyPercent.toUInt(),
    lightMinutes = lightMinutes?.toUInt(),
    deepMinutes = deepMinutes?.toUInt(),
    remMinutes = remMinutes?.toUInt(),
    stages = stages.map {
        TandemStageRun(
            when (it.stage) {
                StageRun.WAKE -> TandemSleepStage.WAKE
                StageRun.LIGHT -> TandemSleepStage.LIGHT
                StageRun.DEEP -> TandemSleepStage.DEEP
                StageRun.REM -> TandemSleepStage.REM
                else -> TandemSleepStage.UNKNOWN
            },
            it.seconds.toUInt(),
        )
    },
    heartRateMean = heartRateMean?.toUInt(),
    heartRateLow = heartRateLow?.toUInt(),
    heartRateHigh = heartRateHigh?.toUInt(),
    steps = steps.toULong(),
)
