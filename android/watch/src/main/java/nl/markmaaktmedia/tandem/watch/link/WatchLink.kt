package nl.markmaaktmedia.tandem.watch.link

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import nl.markmaaktmedia.tandem.watch.notify.CallAlerts
import nl.markmaaktmedia.tandem.watch.notify.FindAlerts
import nl.markmaaktmedia.tandem.watch.notify.PhoneNotifications
import nl.markmaaktmedia.tandem.watch.protocol.CallActionKind
import nl.markmaaktmedia.tandem.watch.protocol.CallMsg
import nl.markmaaktmedia.tandem.watch.protocol.DeviceBattery
import nl.markmaaktmedia.tandem.watch.protocol.ExpectedPlay
import nl.markmaaktmedia.tandem.watch.protocol.LinkEngine
import nl.markmaaktmedia.tandem.watch.protocol.LinkFailure
import nl.markmaaktmedia.tandem.watch.protocol.LinkListener
import nl.markmaaktmedia.tandem.watch.protocol.MediaCommandKind
import nl.markmaaktmedia.tandem.watch.protocol.MediaPlayer
import nl.markmaaktmedia.tandem.watch.protocol.Message
import nl.markmaaktmedia.tandem.watch.protocol.PlayerRules
import nl.markmaaktmedia.tandem.watch.pointer.ArrivalStats
import nl.markmaaktmedia.tandem.watch.protocol.PointerTarget
import nl.markmaaktmedia.tandem.watch.protocol.Role
import nl.markmaaktmedia.tandem.watch.track.GentleWake
import nl.markmaaktmedia.tandem.watch.track.HealthOutbox
import nl.markmaaktmedia.tandem.watch.track.HeartTracker
import nl.markmaaktmedia.tandem.watch.track.Tracking
import nl.markmaaktmedia.tandem.watch.track.TrackingStore
import nl.markmaaktmedia.tandem.watch.update.SelfUpdate

data class WatchUi(
    val paired: Boolean = false,
    val connected: Boolean = false,
    val ready: Boolean = false,
    val pairing: Boolean = false,
    val code: String? = null,
    val failure: LinkFailure? = null,
    val call: CallMsg? = null,
    val players: List<MediaPlayer> = emptyList(),
    /** By the key the players refer to them with. */
    val covers: Map<String, Message.MediaArt> = emptyMap(),
    /** The player gone to on the watch, see [PlayerRules.keptPick]. */
    val pickedPlayer: String? = null,
    val pointerEnabled: Boolean = false,
    val pointerTargets: List<PointerTarget> = emptyList(),
    /** Whether the phone can hold a button down, for a drag. An older phone cannot. */
    val pointerCanHold: Boolean = false,
    val batteries: List<DeviceBattery> = emptyList(),
    val findingPhone: Boolean = false,
    val ringing: Boolean = false,
    /** The Bluetooth of the watch is off, so it cannot reach its phone at all: said, instead of looking for the phone forever. */
    val bluetoothOff: Boolean = false,
)

/** The watch side of the link. Everything runs on one thread; the screens read [ui] and post commands here. */
object WatchLink : LinkListener, WatchBleClient.Events {
    private const val TAG = "TandemWatch"
    private const val PAIRING_WINDOW_MS = 2 * 60_000L
    private const val PING_MS = 5 * 60_000L

    /** The phone is awake and close when its services just changed, so a missing answer shows up quickly. */
    private const val REFRESH_PONG_MS = 6_000L
    private const val PONG_TIMEOUT_MS = 20_000L
    private const val UNPAIR_FLUSH_MS = 500L

    private val thread = HandlerThread("tandem-watch-link").apply { start() }
    private val handler = Handler(thread.looper)

    private val _ui = MutableStateFlow(WatchUi())
    val ui: StateFlow<WatchUi> = _ui.asStateFlow()

    private var running = false
    private var engine: LinkEngine? = null
    private var client: WatchBleClient? = null
    private var notifications: PhoneNotifications? = null
    private var mediaSession: PhoneMediaSession? = null
    private var calls: CallAlerts? = null
    private var finder: FindAlerts? = null
    private var pointerTarget: String? = null
    private var pendingX = 0
    private var pendingY = 0
    private var pendingScrollX = 0
    private var pendingScrollY = 0
    private val pointerSends = ArrivalStats()
    private var sleeping = false
    private var outbox: HealthOutbox? = null
    private var status: Message.WatchStatus? = null
    private var pingId = 0L
    private var appContext: Context? = null
    private var fromPhone: List<MediaPlayer> = emptyList()
    private var expected: ExpectedPlay? = null
    private var playersShownAt = 0L

    private val expectedOver = Runnable {
        expected = null
        showPlayers()
    }

    private val pairingTimeout = Runnable { cancelPairing() }
    private val pongTimeout = Runnable {
        Log.i(TAG, "No answer from the phone, reconnecting")
        client?.reconnect()
    }
    private val pingLoop = object : Runnable {
        override fun run() {
            val link = engine ?: return
            // A silent link can look fine for a long time. Only an answer proves the phone is still there.
            if (link.send(Message.Ping(++pingId))) handler.postDelayed(pongTimeout, PONG_TIMEOUT_MS)
            handler.postDelayed(this, PING_MS)
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> handler.post {
                    _ui.update { it.copy(bluetoothOff = false) }
                    updateClient()
                }
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> handler.post {
                    _ui.update { it.copy(bluetoothOff = true) }
                    client?.stop()
                }
            }
        }
    }

    fun start(context: Context) {
        val app = context.applicationContext
        handler.post {
            if (running) return@post
            running = true
            appContext = app
            if (outbox == null) outbox = HealthOutbox(app)
            val link = LinkEngine(Role.WATCH, SecureLinkKeyStore(app), this)
            engine = link
            client = WatchBleClient(app, handler, this)
            notifications = PhoneNotifications(app)
            calls = CallAlerts(app)
            mediaSession = PhoneMediaSession(app, ::sendMediaCommand)
            finder = FindAlerts(app, handler) { ringing ->
                _ui.update { it.copy(ringing = ringing) }
                engine?.send(Message.Ringing(ringing))
            }
            val adapterOn = app.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.isEnabled ?: false
            _ui.update { it.copy(paired = link.hasLinkKey, bluetoothOff = !adapterOn) }
            app.registerReceiver(bluetoothReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), Context.RECEIVER_EXPORTED)
            updateClient()
        }
    }

    fun beginPairing() {
        handler.post {
            handler.removeCallbacks(pairingTimeout)
            handler.postDelayed(pairingTimeout, PAIRING_WINDOW_MS)
            _ui.update { it.copy(pairing = true, code = null, failure = null) }
            engine?.beginPairing()
            client?.start()
        }
    }

    fun cancelPairing() {
        handler.post {
            handler.removeCallbacks(pairingTimeout)
            engine?.cancelPairing()
            _ui.update { it.copy(pairing = false, code = null) }
            updateClient()
        }
    }

    fun confirmPairing() {
        handler.post { engine?.confirmPairing() }
    }

    /** Takes everything from the phone off the watch too: nothing of it should outlast the pairing. */
    fun unpair() {
        handler.post {
            engine?.unpair()
            forgetPhoneData()
            _ui.update { WatchUi() }
            handler.postDelayed({ client?.stop() }, UNPAIR_FLUSH_MS)
        }
    }

    fun sendNotificationAction(key: String, button: String, reply: String?, dismiss: Boolean) {
        handler.post { engine?.send(Message.NotificationAction(key, button, reply, dismiss)) }
    }

    /** False when the phone cannot be reached, so the button can say so instead of doing nothing. */
    fun sendCallAction(kind: CallActionKind): Boolean {
        val state = _ui.value
        val call = state.call
        if (!state.ready || call == null) return false
        handler.post {
            if (kind == CallActionKind.SILENCE) calls?.stopRinging()
            engine?.send(Message.CallAction(call.id, kind))
        }
        return true
    }

    fun seekMedia(playerId: String, positionMs: Long) {
        handler.post { engine?.send(Message.MediaSeek(playerId, positionMs)) }
    }

    fun sendMediaCommand(playerId: String, kind: MediaCommandKind) {
        handler.post {
            engine?.send(Message.MediaCommand(playerId, kind))
            if (kind == MediaCommandKind.PLAY_PAUSE) showPress(playerId)
        }
    }

    private fun showPress(playerId: String) {
        val now = SystemClock.elapsedRealtime()
        expected = PlayerRules.pressed(_ui.value.players, playerId, now - playersShownAt, now) ?: return
        handler.removeCallbacks(expectedOver)
        handler.postDelayed(expectedOver, PlayerRules.EXPECT_MS)
        showPlayers()
    }

    private fun showPlayers() {
        playersShownAt = SystemClock.elapsedRealtime()
        val players = PlayerRules.withExpected(fromPhone, expected)
        _ui.update { ui ->
            ui.copy(
                players = players,
                pickedPlayer = PlayerRules.keptPick(ui.pickedPlayer, ui.players, players),
                covers = ui.covers.filterKeys { key -> players.any { it.artKey == key } },
            )
        }
        showOnSystem()
    }

    private fun forgetPlayers() {
        fromPhone = emptyList()
        expected = null
        handler.removeCallbacks(expectedOver)
    }

    /** Going to another player, from a small cover or a swipe. */
    fun pickPlayer(id: String) {
        handler.post {
            _ui.update { it.copy(pickedPlayer = id) }
            showOnSystem()
        }
    }

    private fun showOnSystem() {
        val ui = _ui.value
        val shown = PlayerRules.shown(ui.players, ui.pickedPlayer)
        mediaSession?.show(shown, shown?.let { ui.covers[it.artKey] })
    }

    /** The same switch as on the phone: the phone keeps it, and answers with the list of computers as usual. */
    fun setMouseEnabled(on: Boolean) {
        handler.post {
            _ui.update { it.copy(pointerEnabled = on) }
            engine?.send(Message.MouseEnabled(on))
        }
    }

    /** Steer this computer from now on. Movement is summed here and sent as soon as the link can take it. */
    fun startPointer(targetId: String) {
        handler.post {
            pointerTarget = targetId
            pointerSends.reset()
            clearPointerMovement()
            client?.setFast(true)
            engine?.send(Message.PointerSession(targetId))
        }
    }

    /** Tells the phone the mouse screen opened for [targetId] or closed, so the computers can show it and offer to stop it. */
    fun reportMouse(open: Boolean, targetId: String?, table: Boolean) {
        handler.post { engine?.send(Message.MouseState(open, targetId?.ifEmpty { null }, table)) }
    }

    fun stopPointer() {
        handler.post {
            // One line per session, so how evenly the link carried it can be read afterwards.
            if (pointerTarget != null) Log.i(TAG, "Pointer sent: ${pointerSends.summary()}")
            pointerTarget = null
            client?.setFast(false)
            engine?.send(Message.PointerSession(null))
        }
    }

    fun movePointer(dx: Int, dy: Int) {
        handler.post {
            pendingX += dx
            pendingY += dy
            flushPointer(force = false)
        }
    }

    fun scrollPointer(dx: Int, dy: Int) {
        handler.post {
            pendingScrollX += dx
            pendingScrollY += dy
            flushPointer(force = false)
        }
    }

    fun clickPointer(button: Int, count: Int = 1) {
        handler.post {
            // The click has to land where the pointer is by then.
            flushPointer(force = true)
            if (pointerTarget != null) engine?.send(Message.PointerClick(button, count))
        }
    }

    /** A button down or up, for a drag. What moved before it is sent first, so the drag starts where the pointer is. */
    fun holdPointer(button: Int, down: Boolean) {
        handler.post {
            flushPointer(force = true)
            if (pointerTarget != null) engine?.send(Message.PointerButton(button, down))
        }
    }

    fun sendKey(code: Int, mods: Int = 0) {
        handler.post { if (pointerTarget != null) engine?.send(Message.PointerKey(code, mods)) }
    }

    private fun flushPointer(force: Boolean) {
        val link = engine ?: return
        if (pointerTarget == null) return
        if (!force && client?.isIdle != true) return
        if (pendingX != 0 || pendingY != 0) {
            pointerSends.note(SystemClock.uptimeMillis(), kotlin.math.abs(pendingX) + kotlin.math.abs(pendingY))
            link.send(Message.PointerMove(pendingX, pendingY))
            pendingX = 0
            pendingY = 0
        }
        if (pendingScrollX != 0 || pendingScrollY != 0) {
            link.send(Message.PointerScroll(pendingScrollX, pendingScrollY))
            pendingScrollX = 0
            pendingScrollY = 0
        }
    }

    /** The button follows what the phone reports, so it also goes back when someone stops the phone by hand. */
    fun findPhone(ring: Boolean) {
        handler.post { engine?.send(Message.FindDevice(ring)) }
    }

    private fun clearPointerMovement() {
        pendingX = 0
        pendingY = 0
        pendingScrollX = 0
        pendingScrollY = 0
    }

    /** Stops this watch's own ringing, from the button on its screen. */
    fun foundWatch() {
        handler.post { finder?.stop() }
    }

    fun setSleepMode(on: Boolean) {
        handler.post {
            sleeping = on
            engine?.send(Message.SleepMode(on))
        }
    }

    /** A finished night or workout. It stays here until the phone says it has it, however long that takes. */
    fun sendHealth(context: Context, record: Message.HealthRecord) {
        val app = context.applicationContext
        handler.post {
            val box = outbox ?: HealthOutbox(app).also { outbox = it }
            box.add(record)
            sendNextHealth()
        }
    }

    private fun sendNextHealth() {
        outbox?.oldest()?.let { engine?.send(it) }
    }

    fun reportBattery(level: Int, charging: Boolean) {
        handler.post {
            reportStatus((status ?: Message.WatchStatus(level, charging)).copy(level = level, charging = charging))
        }
    }

    /** At most once per batch of the step sensor, so a few times an hour. */
    fun reportHeart(curve: List<Int>) {
        handler.post { engine?.send(Message.HeartToday(curve)) }
    }

    fun sendHeartAllDay(on: Boolean) {
        handler.post { engine?.send(Message.HeartAllDay(on)) }
    }

    /** See [nl.markmaaktmedia.tandem.watch.track.LiveHeart]. */
    fun sendHeartNow(bpm: Int) {
        handler.post { engine?.send(Message.HeartNow(bpm)) }
    }

    fun reportSteps(steps: Long, activeMinutes: Int, byHour: List<Int> = emptyList()) {
        handler.post {
            status?.let { reportStatus(it.copy(stepsToday = steps, activeMinutesToday = activeMinutes, stepsByHour = byHour)) }
        }
    }

    private fun reportStatus(next: Message.WatchStatus) {
        if (next == status) return
        status = next
        engine?.send(next)
    }

    private fun updateClient() {
        val link = engine ?: return
        if (link.hasLinkKey || _ui.value.pairing) client?.start() else client?.stop()
    }

    private fun forgetPhoneData() {
        notifications?.clear()
        calls?.update(null)
        forgetPlayers()
        mediaSession?.show(null, null)
    }

    // LinkListener

    override fun sendChunk(chunk: ByteArray) {
        client?.send(chunk)
    }

    override fun onComparisonCode(code: String) {
        _ui.update { it.copy(code = code) }
    }

    override fun onPaired() {
        handler.removeCallbacks(pairingTimeout)
        _ui.update { it.copy(paired = true, pairing = false, code = null, failure = null) }
    }

    override fun onReady() {
        handler.removeCallbacks(pongTimeout)
        handler.removeCallbacks(pingLoop)
        handler.postDelayed(pingLoop, PING_MS)
        _ui.update { it.copy(ready = true, failure = null) }
        // Things the phone may have missed while the link was down.
        status?.let { engine?.send(it) }
        engine?.send(Message.SleepMode(sleeping))
        sendNextHealth()
        HeartTracker.sendToday()
        // The phone ends a session when the link drops, so a mouse screen that stayed open has to ask again.
        pointerTarget?.let { engine?.send(Message.PointerSession(it)) }
        // The version of this app, for a phone that may bring it up to its own.
        appContext?.let { SelfUpdate.onReady(it) }
    }

    /** For the update of this app ([SelfUpdate]), from any thread. */
    fun sendUpdate(message: Message) {
        handler.post { engine?.send(message) }
    }

    /** Runs [work] on the thread of the link, where the update keeps its state. */
    fun post(work: () -> Unit) {
        handler.post(work)
    }

    override fun onMessage(message: Message) {
        when (message) {
            is Message.NotificationSnapshot -> notifications?.reconcile(message.keys)
            is Message.Notification -> notifications?.post(message.notification)
            is Message.NotificationRemoved -> notifications?.remove(message.key)
            is Message.AppIcon -> notifications?.keepIcon(message)
            is Message.Call -> {
                calls?.update(message.call)
                _ui.update { it.copy(call = message.call) }
            }
            is Message.MediaPlayers -> {
                fromPhone = message.players
                expected = PlayerRules.keptExpected(expected, fromPhone, SystemClock.elapsedRealtime())
                if (expected == null) handler.removeCallbacks(expectedOver)
                showPlayers()
            }
            is Message.MediaArt -> {
                _ui.update { it.copy(covers = it.covers + (message.key to message)) }
                showOnSystem()
            }
            is Message.PointerTargets -> _ui.update {
                it.copy(pointerEnabled = message.enabled, pointerTargets = message.targets, pointerCanHold = message.canHold)
            }
            is Message.DeviceBatteries -> _ui.update { it.copy(batteries = message.devices) }
            is Message.HealthAck -> {
                outbox?.done(message.id)
                sendNextHealth()
            }
            is Message.FindDevice -> if (message.ring) finder?.start() else finder?.stop()
            is Message.Ringing -> _ui.update { it.copy(findingPhone = message.on) }
            Message.Unpair -> {
                forgetPhoneData()
                _ui.update { WatchUi() }
                client?.stop()
            }
            is Message.SmartWakeAlarm -> appContext?.let {
                val store = TrackingStore(it)
                store.smartWakeAlarmMs = message.atMs
                store.smartWakeStrength = message.strength.coerceIn(Message.SmartWakeAlarm.MIN_STRENGTH, Message.SmartWakeAlarm.MAX_STRENGTH)
            }
            is Message.StepGoal -> {
                appContext?.let { TrackingStore(it).stepGoal = message.steps }
                Tracking.publishStepGoal(message.steps)
            }
            Message.TryWakeBuzz -> appContext?.let { GentleWake.tryOnce(it) }
            is Message.HeartAllDay -> appContext?.let { HeartTracker.setEnabled(it, message.on, tellPhone = false) }
            is Message.HeartLive -> appContext?.let { nl.markmaaktmedia.tandem.watch.track.LiveHeart.ask(it, message.on) }
            is Message.Pong -> handler.removeCallbacks(pongTimeout)
            is Message.PointerOpen -> appContext?.let { nl.markmaaktmedia.tandem.watch.ui.PointerActivity.openFromAfar(it, message.targetId, message.table) }
            Message.PointerClose -> appContext?.let { nl.markmaaktmedia.tandem.watch.ui.PointerActivity.closeFromAfar(it) }
            is Message.UpdateOffer -> appContext?.let { SelfUpdate.onOffer(it, message) }
            is Message.UpdatePart -> appContext?.let { SelfUpdate.onPart(it, message) }
            is Message.Ping, is Message.Unknown -> Unit
            // These only travel from watch to phone.
            is Message.AppVersion, is Message.UpdateWant, is Message.UpdateState,
            is Message.HeartToday, is Message.HeartNow, is Message.HealthRecord, Message.SyncRequest, is Message.NotificationAction, is Message.CallAction, is Message.MediaCommand,
            is Message.PointerSession, is Message.PointerMove, is Message.PointerScroll, is Message.PointerClick,
            is Message.PointerKey, is Message.PointerButton, is Message.MediaSeek, is Message.WatchStatus, is Message.SleepMode, is Message.MouseEnabled,
            is Message.MouseState,
            -> Unit
        }
    }

    override fun onFailure(failure: LinkFailure) {
        Log.i(TAG, "Link failure: $failure")
        _ui.update { it.copy(ready = false, code = null, failure = failure, pairing = false) }
        handler.removeCallbacks(pairingTimeout)
        when (failure) {
            // Trying again cannot fix these, and retrying forever only costs battery.
            LinkFailure.NOT_PAIRED, LinkFailure.AUTH_FAILED, LinkFailure.VERSION_MISMATCH -> client?.stop()
            LinkFailure.PROTOCOL_ERROR, LinkFailure.PAIRING_FAILED -> {
                client?.reconnect()
                updateClient()
            }
        }
    }

    // WatchBleClient.Events

    override fun onLinkUp(maxChunk: Int) {
        _ui.update { it.copy(connected = true, ready = false) }
        engine?.onTransportUp(maxChunk)
    }

    override fun onLinkDown() {
        engine?.onTransportDown()
        nl.markmaaktmedia.tandem.watch.track.LiveHeart.linkDown()
        handler.removeCallbacks(pingLoop)
        handler.removeCallbacks(pongTimeout)
        // The call may have ended while we could not hear about it, and a watch must not ring on forever.
        // What built up while the link was gone would land as one big jump of the pointer.
        clearPointerMovement()
        calls?.update(null)
        forgetPlayers()
        _ui.update {
            it.copy(
                connected = false,
                ready = false,
                code = null,
                call = null,
                players = emptyList(),
                covers = emptyMap(),
                pickedPlayer = null,
                pointerEnabled = false,
                pointerTargets = emptyList(),
                batteries = emptyList(),
                findingPhone = false,
            )
        }
        showOnSystem()
    }

    override fun onChunk(chunk: ByteArray) {
        engine?.onChunk(chunk)
    }

    override fun onSendIdle() = flushPointer(force = false)

    override fun onServicesRefreshed() {
        handler.removeCallbacks(pongTimeout)
        if (engine?.send(Message.Ping(++pingId)) == true) {
            handler.postDelayed(pongTimeout, REFRESH_PONG_MS)
        } else {
            client?.reconnect()
        }
    }

    override fun onBleLog(line: String) {
        Log.i(TAG, line)
    }
}
