package nl.markmaaktmedia.tandem.hotspot

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.engine.Permissions
import nl.markmaaktmedia.tandem.graph
import uniffi.tandem_core.TandemEvent
import uniffi.tandem_core.TandemHotspot
import uniffi.tandem_core.TandemPlatform
import uniffi.tandem_core.TandemWifiNetwork
import uniffi.tandem_core.tandemHotspotAuthMessage
import java.security.SecureRandom

enum class BleAvailability { Ready, PrefOff, NoPermission, BluetoothOff, NoEngine, Unsupported }

/**
 * Everything the hotspot feature needs, wired together. [TandemService] starts it,
 * forwards the hotspot events of the QUIC connection to it and stops it. The settings
 * screen reaches the same instance through [get].
 */
class HotspotModule private constructor(private val context: Context) {
    private val graph = context.graph
    private val scope = graph.scope
    private val random = SecureRandom()

    val shizuku = ShizukuBridge(context)
    val prefs = HotspotPrefs(context)
    private val notifications = HotspotNotifications(context)
    private val detector = HotspotDetector(context)

    @Volatile private var enabled = false
    @Volatile private var allowRoaming = false
    @Volatile private var dataLimitMb = 0L
    @Volatile private var dataExtra = "" to 0L

    /** The days as stored, with what was just counted added at once, so the limit does not wait for the write. */
    @Volatile private var days: Map<String, Long> = emptyMap()
    private var onStatusChanged: () -> Unit = {}

    val controller = HotspotController(
        context = context,
        scope = scope,
        shizuku = shizuku,
        detector = detector,
        notifications = notifications,
        enabled = { switchedOn() },
        allowRoaming = { allowRoaming },
        macConnected = { graph.host.devices.value.any { it.online && it.platform == TandemPlatform.MAC_OS } },
        onStatusChanged = { onStatusChanged() },
        dataLimitMb = { HotspotUsage.limitToday(dataLimitMb, dataExtra.first, dataExtra.second, HotspotUsage.dayKey(System.currentTimeMillis())) },
        onDataUsed = { bytes ->
            val now = System.currentTimeMillis()
            days = HotspotUsage.add(days, HotspotUsage.dayKey(now), bytes)
            scope.launch { prefs.addDataUsed(bytes, now) }
        },
        usedToday = { days[HotspotUsage.dayKey(System.currentTimeMillis())] ?: 0L },
    )

    private val challenges = ChallengeStore(
        random = { ByteArray(HotspotProtocol.CHALLENGE_LEN).also { random.nextBytes(it) } },
        clock = System::currentTimeMillis,
    )
    private val auth = HotspotAuth(
        challenges = challenges,
        limiter = FailureLimiter(System::currentTimeMillis),
        buildMessage = { challenge, id, action, time -> tandemHotspotAuthMessage(challenge, id, action.toUByte(), time.toULong()) },
        verify = { id, message, signature -> graph.host.engine?.verifyMember(id, message, signature) ?: false },
    )

    private var server: BleHotspotServer? = null
    val messenger = BleMessenger(scope) { graph.host.engine }
    @Volatile private var messagesOn = true
    private val jobs = mutableListOf<Job>()
    private var started = false

    /** Devices that asked over QUIC and are waiting to hear how it went. */
    private val quicAsked = HashSet<String>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            reconcile()
            controller.refresh()
        }
    }

    fun start(onStatusChanged: () -> Unit) {
        if (started) return
        started = true
        this.onStatusChanged = onStatusChanged
        shizuku.register()

        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            // Sent by the system whenever the hotspot switches, under names that are
            // hidden constants; the interface check in the controller has the final say.
            addAction("android.net.wifi.WIFI_AP_STATE_CHANGED")
            addAction("android.net.conn.TETHER_STATE_CHANGED")
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)

        // The status says whether computers may use the hotspot, so a switch turned here shows on them at once.
        jobs += scope.launch { graph.prefs.hotspotForMac.collectLatest { enabled = it; reconcile(); onStatusChanged() } }
        jobs += scope.launch { graph.prefs.bluetoothMessages.collectLatest { messagesOn = it; reconcile() } }
        jobs += scope.launch { prefs.allowRoaming.collectLatest { allowRoaming = it } }
        jobs += scope.launch { prefs.dataLimitMb.collectLatest { dataLimitMb = it } }
        jobs += scope.launch { prefs.dataExtra.collectLatest { dataExtra = it } }
        jobs += scope.launch { prefs.dailyUsage.collectLatest { days = it } }
        jobs += scope.launch { graph.host.state.collectLatest { reconcile() } }
        jobs += scope.launch { controller.snapshot.collectLatest { publish(it) } }
        // Each time Shizuku comes up (after a restart of the phone, or once Tandem is allowed), and at every start of the app after an
        // update, the app-op that skips the screen recording question is checked and set again if it went.
        jobs += scope.launch {
            shizuku.state.collectLatest { if (it == ShizukuState.Ready) nl.markmaaktmedia.tandem.live.ProjectionGrant.ensure(context) }
        }
        jobs += scope.launch {
            combine(shizuku.state, graph.prefs.copyCodes) { state, _ -> state }.collectLatest {
                if (it == ShizukuState.Ready) nl.markmaaktmedia.tandem.messages.CodeGrant.sync(context)
            }
        }
        // A Mac that comes in reach, Shizuku that comes up, or the switch that goes on: the name is said again where it is not known.
        jobs += scope.launch {
            combine(shizuku.state, graph.host.devices, graph.prefs.hotspotForMac) { _, _, _ -> }.collectLatest { announceName() }
        }
        jobs += scope.launch {
            while (true) {
                delay(TICK_MS)
                shizuku.refresh()
                reconcile()
                server?.refreshAdvertisement()
                controller.refresh()
                // A hotspot renamed in the system settings is noticed here: it sends no event an app can hear.
                announceName()
            }
        }
        current = this
    }

    fun stop() {
        if (!started) return
        started = false
        jobs.forEach { it.cancel() }
        jobs.clear()
        runCatching { context.unregisterReceiver(receiver) }
        messenger.detach()
        server?.stop()
        controller.shutdown()
        shizuku.unregister()
        if (current === this) current = null
    }

    /** More for today only, and at once: the request that comes right after it must not be refused for the old limit. */
    fun giveExtra() {
        val today = HotspotUsage.dayKey(System.currentTimeMillis())
        val had = dataExtra.takeIf { it.first == today }?.second ?: 0L
        dataExtra = today to had + HotspotUsage.EXTRA_MB
        notifications.cancelLimit()
        scope.launch { prefs.addDataExtra(today, HotspotUsage.EXTRA_MB) }
    }

    // ---- Over QUIC -------------------------------------------------------------

    fun onEvent(event: TandemEvent.Hotspot) {
        when (event.hotspot) {
            is TandemHotspot.Request -> {
                synchronized(quicAsked) { quicAsked += event.from }
                val reason = (event.hotspot as TandemHotspot.Request).reason
                if (reason == "extra") giveExtra()
                controller.request(on = true, byPerson = reason == "asked" || reason == "extra")
                val now = controller.snapshot.value
                if (now.phase == Phase.On) reply(event.from, now)
            }
            TandemHotspot.Stop -> controller.request(on = false)
            TandemHotspot.Leave -> controller.leave()
            // Both are for the Mac to read: a phone does not act on the name of another phone.
            is TandemHotspot.State, is TandemHotspot.Name -> Unit
        }
    }

    private fun publish(snapshot: HotspotSnapshot) {
        server?.publish(snapshot.bleState(), snapshot.clients, snapshot.reason)
        // "Starting" is not news for a Mac that is watching, and the first "off" is
        // just the state before anyone asked.
        if (snapshot.phase == Phase.Starting) return
        val targets = synchronized(quicAsked) { quicAsked.toList() }
        if (targets.isEmpty()) return
        targets.forEach { reply(it, snapshot) }
        if (snapshot.phase == Phase.Off || snapshot.phase == Phase.Refused || snapshot.phase == Phase.Failed) {
            synchronized(quicAsked) { quicAsked.clear() }
        }
    }

    private fun reply(target: String, snapshot: HotspotSnapshot) {
        scope.launch { replyNow(target, snapshot) }
    }

    private suspend fun replyNow(target: String, snapshot: HotspotSnapshot) {
        // Said with the answer too, for a Mac that asks for the hotspot while this phone has not told it the name yet.
        val message = TandemHotspot.State(
            on = snapshot.on,
            ssid = if (snapshot.on) ownNetwork()?.ssid else null,
            clients = (snapshot.clients ?: 0).coerceIn(0, 255).toUByte(),
            dataUsed = null,
            error = when (snapshot.phase) {
                Phase.NeedsTap -> "manual"
                Phase.Refused -> snapshot.refusal?.let(HotspotPolicy::errorCode) ?: "failed"
                // "failed:" and the reason, with what Shizuku said for its own error: a Mac that does not know the reason still reads a failure.
                Phase.Failed -> snapshot.reason?.let { reason ->
                    val detail = snapshot.error?.takeIf { reason == FailReason.ShizukuError }?.take(MAX_DETAIL)
                    "failed:${reason.key}" + (detail?.let { ":$it" } ?: "")
                } ?: "failed"
                else -> null
            },
        )
        runCatching { graph.host.engine?.sendHotspot(target, message) }
    }

    // ---- The name of the hotspot -----------------------------------------------

    /** The name and security of this phone's own hotspot. Null without Shizuku: no app may read it (see [ShellWifi.hotspot]). */
    suspend fun ownNetwork(): TandemWifiNetwork? {
        val read = shizuku.ownHotspot()
        return if (read.ok) HotspotNaming.parse(read.message) else null
    }

    /** Whether this phone shares its hotspot with a Mac at all: the switch in the settings. */
    fun sharesWithMac(): Boolean = switchedOn()

    /**
     * The switch as the person sees it: on, and with what it needs allowed (see [Permissions.hotspot]). A switch that shows off for a
     * missing permission is off here too: the Sony had notifications off, showed its switch off, and still said it shared and turned its
     * hotspot on when the Mac asked (2026-11-27).
     */
    private fun switchedOn(): Boolean = enabled && Permissions.hotspot(context)

    /** A permission was given or taken: the switch may read differently now, and the computers hear it at once. */
    fun permissionsChanged() {
        reconcile()
        onStatusChanged()
    }

    /** The password of the own hotspot named [ssid], or null when it cannot be read or is no longer called that. */
    suspend fun ownPassword(ssid: String): String? {
        val read = shizuku.ownHotspotPassword(ssid)
        return if (read.ok) read.message else null
    }

    /** The Macs that heard what this hotspot is called, and the name. */
    private val toldName = HashMap<String, String>()

    private class NameRead(val key: Triple<Boolean, Set<String>, ShizukuState>, val at: Long, val ssid: String?)

    private val nameLock = Any()
    private var nameRead: NameRead? = null

    /**
     * The name of the own hotspot for [announceName]. The read goes through Shizuku into the shell and this runs every tick and at every
     * change of the devices, also with the screen off, so a read stands for five minutes unless what it depended on changed (the switch,
     * the Macs in reach, Shizuku). A hotspot renamed in between is noticed within those five minutes.
     */
    private suspend fun ownNameNow(reachable: Set<String>): String? {
        val key = Triple(enabled, reachable, shizuku.state.value)
        val now = android.os.SystemClock.elapsedRealtime()
        val last = synchronized(nameLock) { nameRead }
        if (last != null && HotspotNaming.readStands(last.key, last.at, key, now)) return last.ssid
        val ssid = ownNetwork()?.ssid
        synchronized(nameLock) { nameRead = NameRead(key, now, ssid) }
        return ssid
    }

    /**
     * Tells every Mac that is connected what this phone's hotspot is called, once and again when the name changes. A Mac that does not
     * know the name yet (a new phone, a hotspot that was renamed) says so while it still has a network, and not only when it stands
     * in front of a hotspot it cannot join.
     */
    private suspend fun announceName() {
        val reachable = graph.host.devices.value
            .filter { it.online && it.platform == TandemPlatform.MAC_OS && "hotspot" in it.caps }
            .map { it.id }.toSet()
        val name = if (switchedOn() && reachable.isNotEmpty()) ownNameNow(reachable) else null
        val targets = synchronized(toldName) {
            val kept = HotspotNaming.stillTold(toldName, reachable)
            toldName.clear()
            toldName.putAll(kept)
            HotspotNaming.peersToTell(name, reachable, toldName)
        }
        for (id in targets) {
            if (name != null && runCatching { graph.host.engine?.sendHotspot(id, TandemHotspot.Name(name)) }.isSuccess) {
                synchronized(toldName) { toldName[id] = name }
            }
        }
    }

    /**
     * Computers may use this phone's hotspot: the switch is on (as it shows, with its permissions) and there is a SIM for mobile data. Said in the status, so a computer
     * leaves the hotspot out (no button, not asked) for a phone that does not share it.
     */
    fun sharesWithComputers(): Boolean = switchedOn() && PhoneConditions.noMobileData(context) == null

    // ---- Over Bluetooth --------------------------------------------------------

    /** For the hotspot: the switch has to be on as well. */
    fun availability(): BleAvailability = if (!enabled) BleAvailability.PrefOff else serverAvailability()

    /** The Bluetooth server serves the hotspot and the message link, so either reason keeps it up. */
    private fun serverAvailability(): BleAvailability {
        if (!enabled && !messagesOn) return BleAvailability.PrefOff
        if (!Permissions.bluetooth(context)) return BleAvailability.NoPermission
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return BleAvailability.Unsupported
        if (!adapter.isEnabled) return BleAvailability.BluetoothOff
        if (adapter.bluetoothLeAdvertiser == null) return BleAvailability.Unsupported
        if (graph.host.engine == null) return BleAvailability.NoEngine
        return BleAvailability.Ready
    }

    /** Starts or stops the Bluetooth server so it matches the preference and permissions. */
    @Synchronized
    fun reconcile() {
        if (!started) return
        if (serverAvailability() == BleAvailability.Ready) {
            val current = server ?: BleHotspotServer(
                context = context,
                auth = auth,
                challenges = challenges,
                myId = { graph.host.myId },
                currentState = { controller.snapshot.value.let { it.bleState() to it.clients } },
                currentReason = { controller.snapshot.value.reason },
                onRequest = { action, _ ->
                    when (action) {
                        HotspotProtocol.ACTION_LEAVE -> controller.leave()
                        HotspotProtocol.ACTION_OFF -> controller.request(on = false)
                        HotspotProtocol.ACTION_ON_EXTRA -> {
                            giveExtra()
                            controller.request(on = true, byPerson = true)
                        }
                        else -> controller.request(on = true, byPerson = action == HotspotProtocol.ACTION_ON_ASKED)
                    }
                },
                sink = messenger,
            ).also { server = it }
            if (!current.running && !current.start()) Log.w(TAG, "the Bluetooth server did not start") else messenger.attach(current)
        } else {
            messenger.detach()
            server?.stop()
        }
    }

    companion object {
        private const val TAG = "HotspotModule"

        /** What Shizuku said, cut short: a message to read, not a log. */
        private const val MAX_DETAIL = 120

        private const val TICK_MS = 20_000L

        @Volatile
        var current: HotspotModule? = null
            private set

        @Volatile
        private var instance: HotspotModule? = null

        fun get(context: Context): HotspotModule =
            instance ?: synchronized(this) {
                instance ?: HotspotModule(context.applicationContext).also { instance = it }
            }
    }
}
