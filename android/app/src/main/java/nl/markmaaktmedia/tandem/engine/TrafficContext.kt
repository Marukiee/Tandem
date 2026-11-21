package nl.markmaaktmedia.tandem.engine

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import uniffi.tandem_core.TandemTrafficContext
import uniffi.tandem_core.TandemTrafficContextSource

/**
 * What the traffic log of the core asks for as it closes a line: the screen, the network and the battery, read from what is at hand at
 * that moment. Nothing is registered, polled or started for it, so it cannot wake the phone: it is only asked while the engine is
 * working anyway.
 */
class TrafficContext(private val context: Context) : TandemTrafficContextSource {
    override fun context(): TandemTrafficContext {
        val power = context.getSystemService(PowerManager::class.java)
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = runCatching { connectivity?.getNetworkCapabilities(connectivity.activeNetwork) }.getOrNull()
        // A VPN (Tailscale) names the network underneath it as well: the mobile data under it is what counts.
        val network = when {
            capabilities == null -> "none"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
        val battery = context.getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        // The sticky intent is handed over without a receiver: a null receiver registers nothing.
        val sticky = runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status?.let { it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL }
        return TandemTrafficContext(
            screenOn = power?.isInteractive,
            network = network,
            battery = battery?.takeIf { it in 0..100 }?.toUByte(),
            charging = charging,
        )
    }
}
