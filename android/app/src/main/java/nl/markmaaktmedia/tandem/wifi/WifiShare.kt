package nl.markmaaktmedia.tandem.wifi

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.engine.Channels
import nl.markmaaktmedia.tandem.engine.Permissions
import nl.markmaaktmedia.tandem.graph
import uniffi.tandem_core.TandemEvent
import uniffi.tandem_core.TandemWifi
import uniffi.tandem_core.TandemWifiNetwork
import uniffi.tandem_core.TandemWifiRefusal
import uniffi.tandem_core.TandemWifiSecurity

/**
 * Taking over the Wi-Fi of a computer, the way an iPhone offers a friend the password. Each computer says which network it is on,
 * also over Bluetooth when that is the only way to it. When the phone is not on it and it is near, the phone asks once, with a
 * notification and a card in the app ([WifiOfferPolicy]). A yes asks the computer, whose person decides there. The password that
 * comes back goes into the system's own sheet for saving a network ([WifiJoinActivity]).
 *
 * [nl.markmaaktmedia.tandem.engine.TandemService] starts it and hands it the Wi-Fi events. The password is only ever in memory.
 */
class WifiShare private constructor(private val context: Context) {
    private val graph = context.graph
    private val scope = graph.scope
    private val store = context.getSharedPreferences("wifi_share", Context.MODE_PRIVATE)
    private val wifi = context.getSystemService(WifiManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)

    private val _networks = MutableStateFlow<Map<String, TandemWifiNetwork>>(emptyMap())
    /** The network each computer said it is on. */
    val networks: StateFlow<Map<String, TandemWifiNetwork>> = _networks.asStateFlow()

    private val _phoneSsid = MutableStateFlow<String?>(null)
    /** The network this phone is on, when Android tells Tandem its name. */
    val phoneSsid: StateFlow<String?> = _phoneSsid.asStateFlow()

    private val _phoneOnWifi = MutableStateFlow(false)
    val phoneOnWifi: StateFlow<Boolean> = _phoneOnWifi.asStateFlow()

    data class Offer(val computer: String, val network: TandemWifiNetwork)

    private val _offer = MutableStateFlow<Offer?>(null)
    /** What the phone offers now: the notification, and the card in the app that says the same. */
    val offer: StateFlow<Offer?> = _offer.asStateFlow()

    sealed interface Join {
        val computer: String
        val ssid: String

        data class Asking(override val computer: String, override val ssid: String) : Join

        /** The password has come, and waits for the sheet. */
        class Ready(override val computer: String, val network: TandemWifiNetwork, val password: String) : Join {
            override val ssid get() = network.ssid
        }

        data class Refused(override val computer: String, override val ssid: String, val why: TandemWifiRefusal) : Join
        data class NoAnswer(override val computer: String, override val ssid: String) : Join
    }

    private val _join = MutableStateFlow<Join?>(null)
    /** Where a question for a password is, for the screen that waits for it. One at a time. */
    val join: StateFlow<Join?> = _join.asStateFlow()

    /** The networks offered since the app started, so one is asked about once. */
    private val asked = mutableSetOf<String>()
    private var declined: Set<String> = store.getStringSet(KEY_DECLINED, emptySet()).orEmpty().toSet()
    private var nearby: Set<String> = emptySet()
    private var waiting: Job? = null
    private var forget: Job? = null
    private var devices: Job? = null
    private var started = false

    private val callback = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            _phoneSsid.value = (caps.transportInfo as? WifiInfo)?.ssid?.let(::cleanSsid)
            _phoneOnWifi.value = true
            evaluate()
        }

        override fun onLost(network: Network) {
            _phoneSsid.value = null
            _phoneOnWifi.value = false
            evaluate()
        }
    }

    private val scans = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            readScan()
            evaluate()
        }
    }

    fun start() {
        if (started) return
        started = true
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.wifi_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.wifi_channel_desc)
                setShowBadge(false)
            },
        )
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        runCatching { connectivity.registerNetworkCallback(request, callback) }
        ContextCompat.registerReceiver(context, scans, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        readScan()
        // A computer that comes in reach over Bluetooth alone, or goes, changes what may be offered.
        devices = scope.launch { graph.host.devices.collect { evaluate() } }
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { connectivity.unregisterNetworkCallback(callback) }
        runCatching { context.unregisterReceiver(scans) }
        devices?.cancel()
    }

    fun onEvent(event: TandemEvent.Wifi) {
        when (val msg = event.msg) {
            is TandemWifi.Network -> network(event.from, msg.network)
            is TandemWifi.Share -> shared(event.from, msg.network, msg.password)
            is TandemWifi.Refuse -> {
                val now = _join.value
                if (now is Join.Asking && now.computer == event.from && now.ssid == msg.ssid) settle(Join.Refused(event.from, msg.ssid, msg.why))
            }
            // Android does not let an app read the password of a network, so a device that asks hears that it cannot have it.
            is TandemWifi.Ask -> scope.launch {
                runCatching { graph.host.engine?.sendWifi(event.from, TandemWifi.Refuse(msg.ssid, TandemWifiRefusal.CANNOT_SHARE)) }
            }
        }
    }

    @Synchronized
    private fun network(from: String, network: TandemWifiNetwork?) {
        val before = _networks.value[from]
        _networks.value = if (network == null) _networks.value - from else _networks.value + (from to network)
        val kept = WifiOfferPolicy.forgetDeclined(declined, before, network)
        if (kept != declined) {
            declined = kept
            store.edit().putStringSet(KEY_DECLINED, kept).apply()
        }
        // A network no scan has named yet: a fresh scan says whether it is near.
        if (network != null && network.ssid !in nearby && canSeeNearby()) {
            @Suppress("DEPRECATION")
            runCatching { wifi.startScan() }
        }
        evaluate()
    }

    /** Offers a network when one may be offered, and takes back an offer that no longer holds. */
    @Synchronized
    fun evaluate() {
        val phone = WifiOfferPolicy.Phone(_phoneSsid.value, if (canSeeNearby()) nearby else null)
        _offer.value?.let { current ->
            val holds = _networks.value[current.computer]?.ssid == current.network.ssid && phone.ssid != current.network.ssid && _join.value == null
            if (holds) return
            withdraw()
        }
        if (_join.value != null) return
        for (device in graph.host.devices.value) {
            val network = _networks.value[device.id] ?: continue
            if (WifiOfferPolicy.shouldOffer(network, phone, bluetoothOnly = device.ble && !device.online, declined, asked)) {
                asked += network.ssid
                _offer.value = Offer(device.id, network)
                notifyOffer(device.id, device.name, network)
                return
            }
        }
    }

    /** "Not now": this network is not offered again until the computer has been on another one. */
    @Synchronized
    fun decline(ssid: String) {
        declined = declined + ssid
        store.edit().putStringSet(KEY_DECLINED, declined).apply()
        withdraw()
    }

    private fun withdraw() {
        _offer.value = null
        notifications.cancel(OFFER_ID)
    }

    /** Asks the computer for the password of the network it is on. The answer comes as [join]. False when it is on none. */
    fun ask(computer: String): Boolean {
        val network = _networks.value[computer] ?: return false
        withdraw()
        _join.value = Join.Asking(computer, network.ssid)
        waiting?.cancel()
        waiting = scope.launch {
            val sent = runCatching { graph.host.engine?.sendWifi(computer, TandemWifi.Ask(network.ssid)) != null }.getOrDefault(false)
            if (sent) delay(ASK_TIMEOUT_MS)
            // The computer says no by itself after a minute and a half without an answer there; this is for one that is gone.
            val now = _join.value
            if (now is Join.Asking && now.computer == computer) settle(Join.NoAnswer(computer, network.ssid))
        }
        return true
    }

    private fun settle(state: Join) {
        waiting?.cancel()
        _join.value = state
    }

    private fun shared(from: String, network: TandemWifiNetwork, password: String) {
        waiting?.cancel()
        val ready = Join.Ready(from, network, password)
        _join.value = ready
        withdraw()
        // Kept no longer than it takes to save the network, so it does not sit in memory for the rest of the day.
        forget?.cancel()
        forget = scope.launch {
            delay(READY_FOR_MS)
            if (_join.value === ready) finished()
        }
        // The screen that waits for it takes it from here; with the app open the sheet comes at once; otherwise a tap brings it.
        if (WifiJoinActivity.visible) return
        if (graph.appVisible) {
            runCatching { context.startActivity(WifiJoinActivity.saving(context, from).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        } else {
            notifyReady(from, network)
        }
    }

    /** The question is over (saved, refused, closed): the password is let go of. */
    fun finished() {
        waiting?.cancel()
        forget?.cancel()
        _join.value = null
        notifications.cancel(READY_ID)
        evaluate()
    }

    /** Whether Android lets Tandem see the networks nearby: Location for the app, and Location switched on. */
    fun canSeeNearby(): Boolean =
        Permissions.isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION) &&
            context.getSystemService(LocationManager::class.java).isLocationEnabled

    private fun readScan() {
        nearby = if (canSeeNearby()) runCatching { wifi.scanResults.mapNotNull(::nameOf).toSet() }.getOrDefault(emptySet()) else emptySet()
    }

    @Suppress("DEPRECATION")
    private fun nameOf(result: ScanResult): String? =
        (if (Build.VERSION.SDK_INT >= 33) result.wifiSsid?.toString() else result.SSID)?.let(::cleanSsid)

    /** What the system's sheet saves. Null when Android does not take this password for this kind of network. */
    fun suggestion(network: TandemWifiNetwork, password: String): WifiNetworkSuggestion? = runCatching {
        WifiNetworkSuggestion.Builder().setSsid(network.ssid).apply {
            when (network.security) {
                TandemWifiSecurity.WPA2 -> setWpa2Passphrase(password)
                TandemWifiSecurity.WPA3 -> setWpa3Passphrase(password)
                else -> Unit
            }
            if (network.hidden) setIsHiddenSsid(true)
        }.build()
    }.getOrNull()

    /** The system's own sheet that saves the network and joins it, as if it was picked by hand in the settings. */
    fun sheet(suggestion: WifiNetworkSuggestion): Intent =
        Intent(Settings.ACTION_WIFI_ADD_NETWORKS).putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(suggestion))

    /** For a phone without that sheet: a suggestion, which the system follows by itself once the network is in range. */
    fun suggest(suggestion: WifiNetworkSuggestion): Boolean =
        runCatching { wifi.addNetworkSuggestions(listOf(suggestion)) == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS }.getOrDefault(false)

    /** Whether the question about seeing nearby networks was put, after a first network was saved. Asked once. */
    var locationAsked: Boolean
        get() = store.getBoolean(KEY_LOCATION_ASKED, false)
        set(value) = store.edit().putBoolean(KEY_LOCATION_ASKED, value).apply()

    private fun notifyOffer(computer: String, name: String, network: TandemWifiNetwork) {
        if (!Channels.canPost(context)) return
        val use = PendingIntent.getActivity(
            context, OFFER_ID, WifiJoinActivity.asking(context, computer).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notNow = PendingIntent.getBroadcast(
            context, OFFER_ID,
            Intent(context, WifiActionReceiver::class.java).setAction(WifiActionReceiver.DECLINE).putExtra(WifiActionReceiver.EXTRA_SSID, network.ssid),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_tandem)
            .setContentTitle(context.getString(R.string.wifi_offer_title, name))
            .setContentText(network.ssid)
            .setContentIntent(use)
            .addAction(0, context.getString(R.string.wifi_not_now), notNow)
            .addAction(0, context.getString(R.string.wifi_use), use)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setTimeoutAfter(30 * 60_000L)
            .build()
        notifications.notify(OFFER_ID, notification)
    }

    private fun notifyReady(computer: String, network: TandemWifiNetwork) {
        if (!Channels.canPost(context)) return
        val name = graph.host.device(computer)?.name ?: context.getString(R.string.wifi_computer_fallback)
        val open = PendingIntent.getActivity(
            context, READY_ID, WifiJoinActivity.saving(context, computer).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_tandem)
            .setContentTitle(context.getString(R.string.wifi_ready_title, name, network.ssid))
            .setContentText(context.getString(R.string.wifi_ready_text))
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setTimeoutAfter(READY_FOR_MS)
            .build()
        notifications.notify(READY_ID, notification)
    }

    companion object {
        private const val CHANNEL = "wifi"
        private const val OFFER_ID = 0x5746
        private const val READY_ID = 0x5747
        private const val KEY_DECLINED = "declined"
        private const val KEY_LOCATION_ASKED = "location_asked"
        /** A little longer than the computer waits for its person. */
        private const val ASK_TIMEOUT_MS = 100_000L
        private const val READY_FOR_MS = 10 * 60_000L

        /** Android writes a name in quotes when it is text, and this when it may not say. */
        fun cleanSsid(raw: String): String? = raw.removeSurrounding("\"").takeIf { it.isNotEmpty() && it != "<unknown ssid>" }

        @Volatile private var instance: WifiShare? = null

        fun get(context: Context): WifiShare = instance ?: synchronized(this) {
            instance ?: WifiShare(context.applicationContext).also { instance = it }
        }
    }
}
