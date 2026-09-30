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
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.engine.Permissions
import nl.markmaaktmedia.tandem.graph
import uniffi.tandem_core.TandemEvent
import uniffi.tandem_core.TandemHotspot
import uniffi.tandem_core.TandemPlatform
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
    private var onStatusChanged: () -> Unit = {}

    val controller = HotspotController(
        context = context,
        scope = scope,
        shizuku = shizuku,
        detector = detector,
        notifications = notifications,
        enabled = { enabled },
        allowRoaming = { allowRoaming },
        macConnected = { graph.host.devices.value.any { it.online && it.platform == TandemPlatform.MAC_OS } },
        onStatusChanged = { onStatusChanged() },
        dataLimitMb = { dataLimitMb },
        onDataUsed = { bytes -> scope.launch { prefs.addDataUsed(bytes) } },
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

        jobs += scope.launch { graph.prefs.hotspotForMac.collectLatest { enabled = it; reconcile() } }
        jobs += scope.launch { graph.prefs.bluetoothMessages.collectLatest { messagesOn = it; reconcile() } }
        jobs += scope.launch { prefs.allowRoaming.collectLatest { allowRoaming = it } }
        jobs += scope.launch { prefs.dataLimitMb.collectLatest { dataLimitMb = it } }
        jobs += scope.launch { graph.host.state.collectLatest { reconcile() } }
        jobs += scope.launch { controller.snapshot.collectLatest { publish(it) } }
        jobs += scope.launch {
            while (true) {
                delay(TICK_MS)
                shizuku.refresh()
                reconcile()
                server?.refreshAdvertisement()
                controller.refresh()
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

    // ---- Over QUIC -------------------------------------------------------------

    fun onEvent(event: TandemEvent.Hotspot) {
        when (event.hotspot) {
            is TandemHotspot.Request -> {
                synchronized(quicAsked) { quicAsked += event.from }
                controller.request(on = true)
                val now = controller.snapshot.value
                if (now.phase == Phase.On) reply(event.from, now)
            }
            TandemHotspot.Stop -> controller.request(on = false)
            is TandemHotspot.State -> Unit
        }
    }

    private fun publish(snapshot: HotspotSnapshot) {
        server?.publish(snapshot.bleState(), snapshot.clients)
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
        val message = TandemHotspot.State(
            on = snapshot.on,
            ssid = null,
            clients = (snapshot.clients ?: 0).coerceIn(0, 255).toUByte(),
            dataUsed = null,
            error = when (snapshot.phase) {
                Phase.NeedsTap -> "manual"
                Phase.Refused -> snapshot.refusal?.let(HotspotPolicy::errorCode) ?: "failed"
                Phase.Failed -> "failed"
                else -> null
            },
        )
        scope.launch { runCatching { graph.host.engine?.sendHotspot(target, message) } }
    }

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
                onRequest = { action, _ -> controller.request(on = action == HotspotProtocol.ACTION_ON) },
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
