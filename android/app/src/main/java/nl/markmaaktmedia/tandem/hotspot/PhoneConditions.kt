package nl.markmaaktmedia.tandem.hotspot

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.net.Inet4Address
import java.net.NetworkInterface

private val Context.hotspotStore: DataStore<Preferences> by preferencesDataStore(name = "tandem_hotspot")

/** Settings that belong to the hotspot feature alone, kept out of the shared prefs. */
class HotspotPrefs(private val context: Context) {
    private val roamingKey = booleanPreferencesKey("allow_roaming")

    /** Off by default: a Mac can eat a lot of data, and abroad data is expensive. */
    val allowRoaming: Flow<Boolean> = context.hotspotStore.data.map { it[roamingKey] ?: false }

    suspend fun setAllowRoaming(value: Boolean) {
        context.hotspotStore.edit { it[roamingKey] = value }
    }

    private val limitKey = androidx.datastore.preferences.core.longPreferencesKey("data_limit_mb")
    private val usedKey = androidx.datastore.preferences.core.longPreferencesKey("data_used_bytes")

    /** Megabytes a Mac may use in one session before the hotspot switches itself off. 0 is no limit. */
    val dataLimitMb: Flow<Long> = context.hotspotStore.data.map { it[limitKey] ?: 0L }
    /** Everything the Mac has used over the hotspot, since it was last reset by hand. Never reset by itself. */
    val dataUsedBytes: Flow<Long> = context.hotspotStore.data.map { it[usedKey] ?: 0L }

    private val daysKey = androidx.datastore.preferences.core.stringPreferencesKey("data_days")
    private val sinceKey = androidx.datastore.preferences.core.longPreferencesKey("data_since")

    /** What was used on each day, by date (`2026-10-02`), for the last two months. */
    val dailyUsage: Flow<Map<String, Long>> = context.hotspotStore.data.map { HotspotUsage.decode(it[daysKey]) }

    /** When counting began, or began again after a reset. Null until something has been counted. */
    val usageSince: Flow<Long?> = context.hotspotStore.data.map { it[sinceKey] }

    suspend fun setDataLimitMb(value: Long) {
        context.hotspotStore.edit { it[limitKey] = value }
    }

    /** Adds to the running total and to the day it was used on, in one write, as it is used. */
    suspend fun addDataUsed(bytes: Long, at: Long = System.currentTimeMillis()) {
        if (bytes <= 0) return
        context.hotspotStore.edit { prefs ->
            prefs[usedKey] = (prefs[usedKey] ?: 0L) + bytes
            prefs[daysKey] = HotspotUsage.encode(HotspotUsage.add(HotspotUsage.decode(prefs[daysKey]), HotspotUsage.dayKey(at), bytes))
            if (prefs[sinceKey] == null) prefs[sinceKey] = at
        }
    }

    suspend fun resetDataUsed() {
        context.hotspotStore.edit {
            it[usedKey] = 0L
            it.remove(daysKey)
            it.remove(sinceKey)
        }
    }
}

/** What the phone is doing right now, for deciding whether to say yes. */
object PhoneConditions {
    /**
     * Mobile data moved since the phone started, in bytes, both ways. The hotspot's traffic
     * goes over the same connection, so the difference between two readings is what a Mac used
     * in between (plus whatever the phone did itself, which is small next to a laptop).
     */
    fun mobileBytes(): Long = (android.net.TrafficStats.getMobileRxBytes() + android.net.TrafficStats.getMobileTxBytes())
        .takeIf { it >= 0 } ?: 0L

    fun batteryPercent(context: Context): Int? {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return if (level >= 0 && scale > 0) level * 100 / scale else null
    }

    fun charging(context: Context): Boolean {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        return battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    /** Roaming on the mobile connection the hotspot would share. */
    fun roaming(context: Context): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
    }
}

/**
 * Tells whether the phone's own Wi-Fi hotspot is up, without any privilege: the access
 * point shows up as a network interface of its own with a private address, next to
 * (and not the same as) the interface the phone uses to join Wi-Fi.
 */
class HotspotDetector(private val context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    fun isOn(): Boolean = accessPointInterface() != null

    fun accessPointInterface(): String? {
        @Suppress("DEPRECATION")
        val joined = connectivity.allNetworks.mapNotNull { connectivity.getLinkProperties(it)?.interfaceName }.toSet()
        val all = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull().orEmpty()
        return all.firstOrNull { candidate ->
            runCatching {
                candidate.isUp && !candidate.isLoopback && AP_NAME.matches(candidate.name) && candidate.name !in joined &&
                    candidate.inetAddresses.toList().any { it is Inet4Address && it.isSiteLocalAddress }
            }.getOrDefault(false)
        }?.name
    }

    companion object {
        // ap0 on Pixels, swlan0 on Samsung, wlan1 and wlan2 on many others.
        private val AP_NAME = Regex("^(ap|swlan|wlan|softap)\\d+$")
    }
}
