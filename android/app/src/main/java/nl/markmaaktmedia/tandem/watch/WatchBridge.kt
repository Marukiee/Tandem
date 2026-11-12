package nl.markmaaktmedia.tandem.watch

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationManager
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
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.data.TandemPrefs
import nl.markmaaktmedia.tandem.engine.Channels
import nl.markmaaktmedia.tandem.engine.EngineHost
import nl.markmaaktmedia.tandem.engine.FindPhone
import nl.markmaaktmedia.tandem.engine.Permissions
import nl.markmaaktmedia.tandem.media.RemotePlayers
import nl.markmaaktmedia.tandem.ui.remote.RemoteLink
import nl.markmaaktmedia.tandem.watch.protocol.CallMsg
import nl.markmaaktmedia.tandem.watch.protocol.DeviceBattery
import nl.markmaaktmedia.tandem.watch.protocol.LinkEngine
import nl.markmaaktmedia.tandem.watch.protocol.LinkFailure
import nl.markmaaktmedia.tandem.watch.protocol.LinkListener
import nl.markmaaktmedia.tandem.watch.protocol.MediaCommandKind
import nl.markmaaktmedia.tandem.watch.protocol.MediaPlayer
import nl.markmaaktmedia.tandem.watch.protocol.Message
import nl.markmaaktmedia.tandem.watch.protocol.NotificationMsg
import nl.markmaaktmedia.tandem.watch.protocol.NotificationRules
import nl.markmaaktmedia.tandem.watch.protocol.OutboundQueue
import nl.markmaaktmedia.tandem.watch.protocol.PlayerRules
import nl.markmaaktmedia.tandem.watch.pointer.ArrivalStats
import nl.markmaaktmedia.tandem.watch.pointer.PointerPacer
import nl.markmaaktmedia.tandem.watch.protocol.PointerTarget
import nl.markmaaktmedia.tandem.watch.protocol.Role
import nl.markmaaktmedia.tandem.watch.protocol.StageRun
import uniffi.tandem_core.TandemHealthKind
import uniffi.tandem_core.TandemHealthRecord
import uniffi.tandem_core.TandemMediaAction
import uniffi.tandem_core.TandemMediaPlayer
import uniffi.tandem_core.TandemNotification
import uniffi.tandem_core.TandemPlatform
import uniffi.tandem_core.TandemSleepStage
import uniffi.tandem_core.TandemStageRun

data class WatchState(
    val paired: Boolean = false,
    val connected: Boolean = false,
    val ready: Boolean = false,
    val pairingOpen: Boolean = false,
    val code: String? = null,
    val batteryLevel: Int? = null,
    val stepsToday: Long = 0,
    val activeMinutesToday: Int = 0,
    /** Today per five minutes from midnight on, 0 where nothing was measured. */
    val heartToday: List<Int> = emptyList(),
    /** The steps of today per hour from midnight on. Empty from a watch from before. */
    val stepsByHour: List<Int> = emptyList(),
    val charging: Boolean = false,
    val ringing: Boolean = false,
    val sleeping: Boolean = false,
    /** What the person called it. Null is the plain name. */
    val name: String? = null,
    /** The computer the mouse screen of the watch steers now, as the watch said; null while it is closed. */
    val mouse: WatchMouseNow? = null,
    /** The Bluetooth of this phone is off, so the watch cannot connect: said, and a way given to turn it on. */
    val bluetoothOff: Boolean = false,
    /** The heart rate now, while a screen asks for it ([WatchBridge.liveHeart]); null when there is none. */
    val heartNow: Int? = null,
)

/**
 * How long a phone leaves the watch alone after [failures] failed sessions in a row: nothing for the first two, then a minute, five,
 * a quarter of an hour, and an hour from then on. A watch that came back to this phone pairs again, which always goes ahead.
 */
internal fun watchRestAfter(failures: Int): Long = when {
    failures < 3 -> 0L
    failures == 3 -> 60_000L
    failures == 4 -> 5 * 60_000L
    failures == 5 -> 15 * 60_000L
    else -> 60 * 60_000L
}

/** Which computer the watch steers, and whether with the hand on a table. */
data class WatchMouseNow(val target: String, val table: Boolean)

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
    private val remotePlayers: StateFlow<Map<String, RemotePlayers>>,
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
    private val updater = WatchUpdater(context, scope, handler, { enqueue(it) }) { pointer != null || calls.active }

    /** How the app on the watch is kept at the version of this one. */
    val appUpdate: StateFlow<WatchAppState> = updater.state

    /** A computer asked for the watch as its mouse: the watch opens the mouse screen for it by itself, as the table mouse when [table]. */
    fun openMouse(computerId: String, table: Boolean = false) {
        handler.post { enqueue(Message.PointerOpen(computerId, table)) }
    }

    /** A computer is done with the watch as its mouse. Only the one that is steered may close it, so one computer cannot end another's. */
    fun closeMouse(computerId: String) {
        handler.post { if (_state.value.mouse?.target == computerId) enqueue(Message.PointerClose) }
    }

    fun retryAppUpdate() {
        handler.post { updater.retry(_state.value.ready) }
    }
    private val media = WatchMedia(context, handler, { mediaExcluded }, { cover -> enqueue(cover) }) { publishPlayers() }
    private var computerPlayers: List<MediaPlayer> = emptyList()
    private var stepGoal = TandemPrefs.DEFAULT_STEP_GOAL
    private var heartAllDay = true
    private var smartWake = false
    private var wakeStrength = Message.SmartWakeAlarm.DEFAULT_STRENGTH
    private val alarmChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            handler.post { sendAlarm() }
        }
    }
    private val quiet = WatchSleepQuiet(context, handler)
    private var running = false
    private var active = false

    /** Sessions with the watch that failed in a row: a watch that holds the key of another phone keeps coming back and failing. */
    private var failedInARow = 0
    /** Until when (elapsed realtime) this phone does not offer itself to the watch, after too many failures in a row. */
    private var restUntil = 0L
    /** The watch is online with another phone of the circle: this one does not offer itself to it meanwhile. */
    private var withOtherPhone = false
    /** When the watch last paired with this phone: the other phone may say for a while yet that it has the watch. */
    private var pairedAt = 0L
    private val endRest = Runnable {
        restUntil = 0L
        applyServer()
    }
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
                BluetoothAdapter.STATE_ON -> handler.post {
                    _state.update { it.copy(bluetoothOff = false) }
                    applyServer()
                }
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> handler.post {
                    _state.update { it.copy(bluetoothOff = true) }
                    server?.stop()
                }
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
                ownBattery = DeviceBattery("", host.myName, level * 100 / scale, plugged, DeviceBattery.PHONE)
                publishBatteries()
            }
        }
    }

    /**
     * Only in builds for testing: shows a watch that is not there, in memory until the app restarts, so the start page and Health can be
     * seen on an emulator. Nothing is stored and no key is made: a real pairing stays as it was.
     */
    fun showDemoWatch() {
        if (!nl.markmaaktmedia.tandem.BuildConfig.DEBUG) return
        val byHour = listOf(0, 0, 0, 0, 0, 0, 40, 620, 980, 210, 120, 340, 760, 421)
        _state.update {
            it.copy(
                paired = true, name = it.name ?: "Pixel Watch", batteryLevel = it.batteryLevel ?: 76, stepsToday = byHour.sum().toLong(),
                activeMinutesToday = 21, stepsByHour = byHour + List(24 - byHour.size) { 0 },
            )
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
        jobs += scope.launch { prefs.watchName.collect { name -> _state.update { it.copy(name = name) } } }
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
        context.registerReceiver(alarmChanged, IntentFilter(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED), Context.RECEIVER_NOT_EXPORTED)
        jobs += scope.launch {
            prefs.smartWake.collect { on ->
                handler.post {
                    smartWake = on
                    sendAlarm()
                }
            }
        }
        jobs += scope.launch {
            prefs.wakeStrength.collect { strength ->
                handler.post {
                    wakeStrength = strength
                    sendAlarm()
                }
            }
        }
        jobs += scope.launch {
            prefs.heartAllDay.collect { on ->
                handler.post {
                    heartAllDay = on
                    enqueue(Message.HeartAllDay(on))
                }
            }
        }
        jobs += scope.launch {
            prefs.stepGoal.collect { goal ->
                handler.post {
                    stepGoal = goal
                    enqueue(Message.StepGoal(goal))
                }
            }
        }
        jobs += scope.launch {
            combine(remotePlayers, host.devices) { remote, devices ->
                remote.flatMap { (device, entry) ->
                    val name = devices.firstOrNull { it.id == device }?.name ?: return@flatMap emptyList()
                    entry.players.map { player -> player.forWatch(device, name, entry.at) }
                }
            }.collect { players ->
                handler.post {
                    computerPlayers = players
                    publishPlayers()
                }
            }
        }
        // A watch talks to one phone: online with another one, it holds that phone's key and only fails here.
        jobs += scope.launch {
            host.devices
                .map { devices -> devices.any { it.platform == TandemPlatform.ANDROID && it.online && it.status.watch?.online == true } }
                .distinctUntilChanged()
                .collect { other ->
                    handler.post {
                        withOtherPhone = other
                        applyServer()
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
                        btAddress = device.status.btAddress,
                    )
                }
                val others = devices.filter { it.online }.mapNotNull { device ->
                    val kind = if (device.platform == TandemPlatform.ANDROID) DeviceBattery.PHONE else DeviceBattery.COMPUTER
                    device.status.battery?.let { DeviceBattery(device.id, device.name, it.level.toInt(), it.charging, kind) }
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
        runCatching { context.unregisterReceiver(alarmChanged) }
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
        if (should == active) {
            if (active) applyServer()
            return
        }
        active = should
        if (should) {
            _state.update { it.copy(bluetoothOff = context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.isEnabled == false) }
            applyServer()
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

    /**
     * Offers this phone to the watch, or stops: not while it rests after failing too often, nor while the watch is with another phone.
     * Pairing always goes ahead, and a session that works is never stopped.
     */
    private fun applyServer() {
        val now = _state.value
        val clock = SystemClock.elapsedRealtime()
        val elsewhere = withOtherPhone && clock - pairedAt > JUST_PAIRED_MS
        val resting = elsewhere || clock < restUntil
        if (active && !now.bluetoothOff && (now.pairingOpen || now.ready || !resting)) server?.start() else server?.stop()
    }

    private fun sessionFailed() {
        failedInARow++
        val rest = watchRestAfter(failedInARow)
        if (rest == 0L || _state.value.pairingOpen) return
        Log.i(TAG, "The watch failed $failedInARow times in a row, resting ${rest / 1000} s")
        restUntil = SystemClock.elapsedRealtime() + rest
        handler.removeCallbacks(endRest)
        handler.postDelayed(endRest, rest)
        applyServer()
    }

    /**
     * A screen shows the heart rate now: the watch sends it every few seconds. Asked again every half minute while it does, as the
     * watch stops by itself; off when it closes.
     */
    fun liveHeart(on: Boolean) {
        handler.post {
            enqueue(Message.HeartLive(on))
            if (!on) {
                handler.removeCallbacks(heartStale)
                _state.update { it.copy(heartNow = null) }
            }
        }
    }

    private val heartStale = Runnable { _state.update { it.copy(heartNow = null) } }

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
    /** From this phone or from a computer that shows the watch as a device of its own. */
    fun rename(name: String) {
        scope.launch { prefs.setWatchName(name) }
    }

    fun unpair() {
        // A next watch starts with the plain name.
        scope.launch { prefs.setWatchName("") }
        handler.post {
            engine?.unpair()
            outbound.clear()
            iconsSent.clear()
            alerted.clear()
            endPointer()
            _state.update { WatchState(name = it.name) }
            handler.postDelayed({
                server?.disconnect()
                updateActive()
            }, UNPAIR_FLUSH_MS)
        }
    }

    /**
     * Lets go of the paired watch to pair one again, keeping its name: the same watch with Tandem put on again, or another one in its
     * place. The pair screen starts looking as soon as none is paired.
     */
    fun repair() {
        handler.post {
            engine?.unpair()
            outbound.clear()
            iconsSent.clear()
            alerted.clear()
            endPointer()
            _state.update { WatchState(name = it.name) }
            server?.disconnect()
            updateActive()
        }
    }

    fun ringWatch(on: Boolean) {
        handler.post { enqueue(Message.FindDevice(on)) }
    }

    fun tryWakeBuzz() {
        handler.post { enqueue(Message.TryWakeBuzz) }
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
        publishPlayers()
        publishTargets()
        publishBatteries()
        enqueue(Message.Ringing(phoneRinging))
        enqueue(Message.StepGoal(stepGoal))
        enqueue(Message.HeartAllDay(heartAllDay))
        sendAlarm()
    }

    private fun sendAlarm() {
        val next = context.getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime ?: 0L
        enqueue(Message.SmartWakeAlarm(if (smartWake) next else 0L, wakeStrength))
    }

    private fun publishPlayers() {
        enqueue(Message.MediaPlayers(PlayerRules.withComputers(media.players, computerPlayers)))
    }

    private fun sendToComputer(device: String, player: String, kind: MediaCommandKind) {
        val action = when (kind) {
            MediaCommandKind.PLAY_PAUSE -> TandemMediaAction.TOGGLE
            MediaCommandKind.NEXT -> TandemMediaAction.NEXT
            MediaCommandKind.PREVIOUS -> TandemMediaAction.PREVIOUS
            MediaCommandKind.VOLUME_UP -> TandemMediaAction.VOLUME_UP
            MediaCommandKind.VOLUME_DOWN -> TandemMediaAction.VOLUME_DOWN
        }
        scope.launch(Dispatchers.IO) { runCatching { host.engine?.sendMediaCommand(device, player, action, null) } }
    }

    /**
     * A program on a computer says something short. It goes to the wrist when the watch is there and otherwise stays on this phone,
     * so it is never lost because a watch is charging.
     */
    fun nudge(from: String, notification: TandemNotification) {
        scope.launch {
            if (from in prefs.nudgeMuted.first()) return@launch
            handler.post {
                if (engine?.isReady == true) {
                    enqueue(
                        Message.Notification(
                            NotificationMsg(
                                key = notification.key, appId = notification.appId, appName = notification.appName, title = notification.title,
                                text = notification.text, subText = null, ts = notification.ts.toLong(), ongoing = false, silent = false,
                                buttons = emptyList(), otp = null, progress = null,
                            ),
                        ),
                    )
                } else if (Channels.canPost(context)) {
                    context.getSystemService(NotificationManager::class.java).notify(
                        notification.key, NUDGE_NOTIFICATION,
                        NotificationCompat.Builder(context, Channels.NUDGE)
                            .setSmallIcon(R.drawable.ic_stat_tandem)
                            .setContentTitle(notification.title)
                            .setContentText(notification.text)
                            .setAutoCancel(true)
                            .build(),
                    )
                }
            }
        }
    }

    private fun publishTargets() {
        enqueue(Message.PointerTargets(mouseOn, targets, canHold = true))
    }

    private fun publishBatteries() {
        enqueue(Message.DeviceBatteries(listOfNotNull(ownBattery) + batteries))
    }

    private var pointerId: String? = null
    private val pacer = PointerPacer()
    private val arrivals = ArrivalStats()
    private var pacing = false
    private val pace = object : Runnable {
        override fun run() {
            pacer.tick(SystemClock.uptimeMillis())?.let { pointer?.pointer(it.dx, it.dy) }
            if (pacer.busy) handler.postDelayed(this, PointerPacer.TICK_MS) else pacing = false
        }
    }

    private fun movePointer(dx: Int, dy: Int) {
        val now = SystemClock.uptimeMillis()
        arrivals.note(now, kotlin.math.abs(dx) + kotlin.math.abs(dy))
        pacer.add(now, dx, dy)
        if (!pacing) {
            pacing = true
            handler.postDelayed(pace, PointerPacer.TICK_MS)
        }
    }

    /** Before a click or a button: it has to land where the pointer was going. */
    private fun catchUpPointer() {
        pacer.drain()?.let { pointer?.pointer(it.dx, it.dy) }
    }

    /** The watch only gets to steer what was offered to it, and only while the person allows that here. */
    private fun startPointer(id: String?) {
        endPointer()
        if (id == null || !mouseOn) return
        if (targets.none { it.id == id && it.online && it.allowsInput }) return
        pointerId = id
        pointer = RemoteLink(host, id, scope).also { it.start() }
    }

    private fun endPointer() {
        handler.removeCallbacks(pace)
        pacing = false
        // One line per session, so how evenly the watch's movement came in can be read afterwards.
        if (pointer != null) Log.i(TAG, "Watch pointer came in: ${arrivals.summary()}")
        pacer.reset()
        arrivals.reset()
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
        sessionFailed()
    }

    // LinkListener

    override fun sendChunk(chunk: ByteArray) {
        server?.send(chunk)
    }

    override fun onComparisonCode(code: String) {
        _state.update { it.copy(code = code) }
    }

    override fun onPaired() {
        pairedAt = SystemClock.elapsedRealtime()
        handler.removeCallbacks(pairingTimeout)
        alerted.clear()
        _state.update { it.copy(paired = true, pairingOpen = false, code = null) }
        updateActive()
    }

    override fun onReady() {
        handler.removeCallbacks(authTimeout)
        handler.removeCallbacks(endRest)
        failedInARow = 0
        restUntil = 0L
        _state.update { it.copy(ready = true) }
    }

    override fun onMessage(message: Message) {
        when (message) {
            Message.SyncRequest -> sendFullState()
            Message.Unpair -> {
                alerted.clear()
                endPointer()
                _state.update { WatchState(name = it.name) }
                server?.disconnect()
                updateActive()
            }
            is Message.NotificationAction -> notifications?.perform(message.key, message.button, message.reply, message.dismiss)
            is Message.CallAction -> calls.perform(message.kind)
            is Message.MediaCommand -> {
                val computer = PlayerRules.computerPlayer(message.playerId)
                if (computer != null) sendToComputer(computer.first, computer.second, message.kind) else media.perform(message.playerId, message.kind)
            }
            is Message.MediaSeek -> {
                val computer = PlayerRules.computerPlayer(message.playerId)
                if (computer != null) {
                    scope.launch(Dispatchers.IO) {
                        runCatching { host.engine?.sendMediaCommand(computer.first, computer.second, TandemMediaAction.SEEK, message.positionMs.toULong()) }
                    }
                } else {
                    media.seek(message.playerId, message.positionMs)
                }
            }
            is Message.PointerSession -> startPointer(message.targetId)
            is Message.PointerMove -> if (pointer != null) movePointer(message.dx, message.dy)
            is Message.PointerScroll -> pointer?.scroll(message.dx, message.dy)
            is Message.PointerClick -> {
                catchUpPointer()
                pointer?.click(message.button, message.count)
            }
            is Message.PointerButton -> {
                catchUpPointer()
                pointer?.hold(message.button, message.down)
            }
            is Message.PointerKey -> pointer?.key(message.code.toShort(), message.mods)
            is Message.FindDevice -> main.post { if (message.ring) FindPhone.start(context) else FindPhone.stop() }
            is Message.Ringing -> _state.update { it.copy(ringing = message.on) }
            is Message.MouseState -> _state.update { it.copy(mouse = if (message.open) WatchMouseNow(message.targetId.orEmpty(), message.table) else null) }
            is Message.WatchStatus -> _state.update {
                it.copy(
                    stepsToday = message.stepsToday, activeMinutesToday = message.activeMinutesToday, stepsByHour = message.stepsByHour,
                    batteryLevel = message.level, charging = message.charging,
                )
            }
            is Message.MouseEnabled -> scope.launch { prefs.setWatchMouse(message.on) }
            is Message.HeartToday -> _state.update { it.copy(heartToday = message.curve) }
            is Message.HeartNow -> {
                _state.update { it.copy(heartNow = message.bpm.takeIf { bpm -> bpm > 0 }) }
                // A value that stops coming is not the heart rate now any more.
                handler.removeCallbacks(heartStale)
                if (message.bpm > 0) handler.postDelayed(heartStale, HEART_STALE_MS)
            }
            is Message.HeartAllDay -> scope.launch { prefs.setHeartAllDay(message.on) }
            is Message.SleepMode -> onSleepMode(message.on)
            is Message.HealthRecord -> onHealthRecord(message)
            is Message.AppVersion -> updater.onVersion(message)
            is Message.UpdateWant -> updater.onWant(message)
            is Message.UpdateState -> updater.onState(message)
            is Message.Ping, is Message.Pong, is Message.Unknown -> Unit
            // These only travel from phone to watch.
            is Message.NotificationSnapshot, is Message.Notification, is Message.NotificationRemoved, is Message.AppIcon,
            is Message.HealthAck, is Message.StepGoal, is Message.SmartWakeAlarm, Message.TryWakeBuzz, is Message.HeartLive, is Message.UpdateOffer, is Message.UpdatePart, is Message.PointerOpen,
            Message.PointerClose,
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
                .onSuccess {
                    handler.post { enqueue(Message.HealthAck(message.id)) }
                    writeToHealthConnect(listOf(message.toTandem()))
                }
                .onFailure { Log.w(TAG, "Could not keep a health record", it) }
        }
    }

    /** A failure here costs nothing: the record is safe on this phone, and turning the switch on again writes everything. */
    private fun writeToHealthConnect(records: List<TandemHealthRecord>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        scope.launch(Dispatchers.IO) {
            if (prefs.healthConnectWrite.first()) runCatching { HealthConnectImport.write(context, records) }
        }
    }

    private fun onSleepMode(on: Boolean) {
        if (on && _state.value.sleeping) return
        _state.update { it.copy(sleeping = on) }
        scope.launch {
            val wanted = prefs.watchSleepQuiet.first()
            handler.post { if (!on || wanted) quiet.turn(on) }
            if (on) fellAsleep() else wokeUp()
        }
    }

    /** Whether music still played half an hour after falling asleep, with pausing it switched off: then the morning asks. */
    private var playedThrough = false
    private val checkMusic = Runnable { if (_state.value.sleeping && musicPlaying()) playedThrough = true }

    private fun musicPlaying(): Boolean = media.anyPlaying || computerPlayers.any { it.playing }

    private suspend fun fellAsleep() {
        playedThrough = false
        val pause = prefs.sleepPauseMusic.first()
        val lock = prefs.sleepLockComputers.first()
        handler.post {
            if (pause) {
                media.pauseAll()
                computerPlayers.filter { it.playing }.forEach { player ->
                    val (device, id) = PlayerRules.computerPlayer(player.id) ?: return@forEach
                    scope.launch(Dispatchers.IO) { runCatching { host.engine?.sendMediaCommand(device, id, TandemMediaAction.PAUSE, null) } }
                }
            } else if (musicPlaying()) {
                handler.removeCallbacks(checkMusic)
                handler.postDelayed(checkMusic, MUSIC_THROUGH_MS)
            }
        }
        if (lock) {
            host.devices.value.filter { it.platform != TandemPlatform.ANDROID && it.online }.forEach { computer ->
                scope.launch(Dispatchers.IO) { runCatching { host.engine?.lock(computer.id) } }
            }
        }
    }

    /** Music played on for half an hour of sleep: ask once whether it should stop by itself from now on, at most once a week. */
    private suspend fun wokeUp() {
        handler.removeCallbacks(checkMusic)
        if (!playedThrough) return
        playedThrough = false
        if (prefs.sleepPauseMusic.first() || prefs.sleepMusicNeverAsk.first()) return
        val now = System.currentTimeMillis()
        if (now - prefs.sleepMusicAskedAt.first() < WEEK_MS) return
        prefs.setSleepMusicAskedAt(now)
        SleepMusicQuestion.ask(context)
    }

    override fun onFailure(failure: LinkFailure) {
        Log.i(TAG, "Link failure: $failure")
        if (failure == LinkFailure.AUTH_FAILED || failure == LinkFailure.NOT_PAIRED) sessionFailed()
        outbound.clear()
        iconsSent.clear()
        updater.onDisconnected()
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
        updater.onDisconnected()
        handler.removeCallbacks(authTimeout)
        _state.update { it.copy(connected = false, ready = false, code = null, ringing = false, mouse = null, heartNow = null) }
    }

    override fun onChunk(chunk: ByteArray) {
        engine?.onChunk(chunk)
    }

    override fun onSendIdle() = pump()

    override fun onBleLog(line: String) {
        Log.i(TAG, line)
    }

    private companion object {
        const val MUSIC_THROUGH_MS = 30 * 60_000L
        const val WEEK_MS = 7 * 24 * 3_600_000L
        const val TAG = "TandemWatch"
        const val PAIRING_WINDOW_MS = 2 * 60_000L
        const val AUTH_WINDOW_MS = 30_000L
        /** The watch sends the heart rate now every two seconds: this long without one, there is none. */
        const val HEART_STALE_MS = 8_000L
        /** After pairing, how long the other phone may still say it has the watch: its status goes out a moment later. */
        const val JUST_PAIRED_MS = 60_000L
        const val UNPAIR_FLUSH_MS = 500L
    }
}

private const val NUDGE_NOTIFICATION = 7301

/** The watch counts the position on by itself from here, so it has to be where the player is now and not where it was when it said so. */
private fun TandemMediaPlayer.forWatch(device: String, deviceName: String, at: Long): MediaPlayer {
    val position = positionMs?.toLong() ?: 0L
    return MediaPlayer(
        id = PlayerRules.computerPlayerId(device, id),
        app = deviceName,
        title = title,
        artist = artist,
        album = album,
        playing = playing,
        positionMs = position + if (playing) SystemClock.elapsedRealtime() - at else 0L,
        durationMs = durationMs?.toLong() ?: 0L,
    )
}

private fun Message.HealthRecord.toTandem() = TandemHealthRecord(
    id = id,
    kind = when {
        day -> TandemHealthKind.DAY
        workout -> TandemHealthKind.WORKOUT
        else -> TandemHealthKind.SLEEP
    },
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
    activeMinutes = activeMinutes.toUInt(),
    heartCurve = heartCurve.map { it.toUInt() },
    stepCurve = stepCurve.map { it.toUInt() },
)
