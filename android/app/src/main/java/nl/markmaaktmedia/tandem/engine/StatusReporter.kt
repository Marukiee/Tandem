package nl.markmaaktmedia.tandem.engine

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import android.os.StatFs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.graph
import uniffi.tandem_core.TandemBattery
import uniffi.tandem_core.TandemNetKind
import uniffi.tandem_core.TandemNetwork
import uniffi.tandem_core.TandemStatus
import uniffi.tandem_core.TandemWatchSettings
import uniffi.tandem_core.TandemWatchMouse
import uniffi.tandem_core.TandemWatchStatus

/**
 * Tells the other devices how this phone is doing: battery, network, do not disturb.
 * Only what changed is sent, and bursts of changes (the battery ticks, the network
 * flaps) are folded into one update.
 */
class StatusReporter(
    private val context: Context,
    private val host: EngineHost,
    private val scope: CoroutineScope,
) {
    private var last = TandemStatus(null, null, null, null, null, null, null, null, null)
    private var pending: Job? = null

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // A phone that slept was away for the others, who only try it again after a while: it tries them itself as soon as it is
            // in a hand again, so a computer's pointer can come over at once.
            if (intent.action == Intent.ACTION_SCREEN_ON || intent.action == Intent.ACTION_USER_PRESENT) host.onNetworkChanged()
            // How it sounds was just changed, perhaps from another device that waits to show it.
            val sound = intent.action == AudioManager.RINGER_MODE_CHANGED_ACTION || intent.action == NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED
            schedule(if (sound) 150 else 1500)
        }
    }

    private var lastNetwork: Pair<Network, List<Boolean>>? = null

    /** What is set for the watch, as last read: a computer shows it and can change it, and sees what came of that here. */
    @Volatile
    private var watchSettings: TandemWatchSettings? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = networkChanged()
        override fun onLost(network: Network) = networkChanged()
        // This also comes with every change of signal strength, which is many times a minute. Trying every absent device again each time
        // is what kept a battery and a radio busy; the status only goes out when it changed.
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val kind = listOf(NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_CELLULAR, NetworkCapabilities.TRANSPORT_ETHERNET, NetworkCapabilities.TRANSPORT_VPN)
                .map(capabilities::hasTransport) + capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val now = network to kind
            if (now != lastNetwork) {
                lastNetwork = now
                networkChanged()
            } else {
                schedule()
            }
        }
    }

    fun start() {
        // The watch only talks to this phone, so the others hear about it from here.
        scope.launch { context.graph.watch.state.collect { schedule(WATCH_DELAY_MS) } }
        scope.launch {
            val prefs = context.graph.prefs
            combine(prefs.watchMouse, prefs.watchSleepQuiet, prefs.smartWake, prefs.heartAllDay, prefs.stepGoal) { mouse, quiet, wake, heart, goal ->
                TandemWatchSettings(mouse, quiet, wake, heart, goal.coerceAtLeast(0).toUInt())
            }.collect {
                watchSettings = it
                schedule(WATCH_DELAY_MS)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            addAction(NotificationManager.ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        context.registerReceiver(receiver, filter)
        current = this
        connectivity.registerDefaultNetworkCallback(networkCallback)
        schedule()
    }

    fun stop() {
        if (current === this) current = null
        runCatching { context.unregisterReceiver(receiver) }
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
    }

    /** A different network is a reason to reconnect, not just to report. */
    private fun networkChanged() {
        host.onNetworkChanged()
        schedule()
    }

    /** Sends the whole status again, for a device that just connected. */
    fun resend() {
        last = TandemStatus(null, null, null, null, null, null, null, null, null)
        schedule(0)
    }

    private fun schedule(delayMs: Long = 1500) {
        pending?.cancel()
        pending = scope.launch {
            delay(delayMs)
            val now = read()
            val change = TandemStatus(
                battery = now.battery.takeIf { it != last.battery },
                network = now.network.takeIf { it != last.network },
                hotspot = now.hotspot.takeIf { it != last.hotspot },
                dnd = now.dnd.takeIf { it != last.dnd },
                locked = now.locked.takeIf { it != last.locked },
                freeStorage = now.freeStorage.takeIf { it != last.freeStorage },
                asleep = null,
                wakeMac = null,
                muted = null,
                sound = now.sound.takeIf { it != last.sound },
                soundDnd = now.soundDnd.takeIf { it != last.soundDnd },
                lock = now.lock.takeIf { it != last.lock },
                watch = now.watch.takeIf { it != last.watch },
            )
            last = now
            host.pushStatus(change)
        }
    }

    private fun read(): TandemStatus {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.let {
            val raw = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (raw >= 0 && scale > 0) raw * 100 / scale else null
        }
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val power = context.getSystemService(PowerManager::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)

        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        val kind = when {
            capabilities == null -> TandemNetKind.NONE
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> TandemNetKind.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> TandemNetKind.CELLULAR
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> TandemNetKind.ETHERNET
            else -> TandemNetKind.OTHER
        }

        return TandemStatus(
            battery = level?.let { TandemBattery(it.toUByte(), plugged, power.isPowerSaveMode) },
            network = TandemNetwork(
                kind = kind,
                ssid = null,
                metered = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false,
                roaming = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING) == false,
                signal = null,
            ),
            hotspot = HotspotState.isOn,
            dnd = notifications.currentInterruptionFilter.let {
                it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
            },
            locked = keyguard.isKeyguardLocked,
            freeStorage = runCatching { StatFs(context.filesDir.absolutePath).availableBytes.toULong() }.getOrNull(),
            asleep = null,
            wakeMac = null,
            muted = null,
            sound = PhoneSoundMode.read(context),
            soundDnd = FocusMode.canUse(context),
            lock = nl.markmaaktmedia.tandem.live.TandemAccessibilityService.instance != null,
            watch = context.graph.watch.takeIf { it.supported }?.state?.value?.let { watch ->
                TandemWatchStatus(
                    watch.paired, watch.ready, watch.batteryLevel?.toUByte(), watch.charging, watch.ringing, watch.name, watch.sleeping, watchSettings,
                    watch.mouse?.takeIf { watch.ready }?.let { TandemWatchMouse(it.target, it.table) },
                )
            },
        )
    }

    companion object {
        /** Quickly, because someone may be waiting for a watch to stop ringing, but not for each step of a pairing. */
        private const val WATCH_DELAY_MS = 300L

        @Volatile
        private var current: StatusReporter? = null

        /** Something changed that sends no broadcast: the accessibility service came or went. */
        fun changed() {
            current?.schedule()
        }
    }
}

/** Whether the phone's own hotspot is on, as far as we know. Set by the hotspot module. */
object HotspotState {
    @Volatile
    var isOn: Boolean? = null
}
