package nl.markmaaktmedia.tandem.engine

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import android.os.StatFs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uniffi.tandem_core.TandemBattery
import uniffi.tandem_core.TandemNetKind
import uniffi.tandem_core.TandemNetwork
import uniffi.tandem_core.TandemStatus

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
        override fun onReceive(context: Context, intent: Intent) = schedule()
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = networkChanged()
        override fun onLost(network: Network) = networkChanged()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = networkChanged()
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        context.registerReceiver(receiver, filter)
        connectivity.registerDefaultNetworkCallback(networkCallback)
        schedule()
    }

    fun stop() {
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
        )
    }
}

/** Whether the phone's own hotspot is on, as far as we know. Set by the hotspot module. */
object HotspotState {
    @Volatile
    var isOn: Boolean? = null
}
