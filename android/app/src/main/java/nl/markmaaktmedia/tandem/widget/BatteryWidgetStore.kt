package nl.markmaaktmedia.tandem.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.engine.EngineHost
import nl.markmaaktmedia.tandem.engine.EngineState
import nl.markmaaktmedia.tandem.media.MediaMirror
import uniffi.tandem_core.TandemPlatform
import java.io.File

/**
 * Keeps the file the battery widget draws from: the other devices, as the engine last heard them. The widget is drawn by the launcher
 * while Tandem may not run, so it reads a file and not the engine. The battery of this phone it reads itself, it is always there.
 */
object BatteryWidgetStore {
    private const val FILE = "battery-widget.json"

    private val _version = MutableStateFlow(0L)
    /** Bumped on every change worth drawing, so a widget whose content is still alive in this process draws again. */
    val version: StateFlow<Long> = _version.asStateFlow()

    private var ownLast: Pair<Int, Boolean>? = null

    /** When this phone went on or came off the charger, for the ring to play that once (see [WidgetBattery.chargeChangedAt]). */
    @Volatile var selfChargeChangedAt = 0L
        private set

    private fun text(context: Context): String? = runCatching { File(context.filesDir, FILE).takeIf { it.exists() }?.readText() }.getOrNull()

    fun read(context: Context): Pair<List<WidgetBattery>, String?> = BatteryWidgetLogic.decode(text(context))

    /** Whether something plays on this phone, as the app last saw it. */
    fun selfPlaying(context: Context): Boolean = BatteryWidgetLogic.selfPlaying(text(context))

    /** This phone's battery: level and whether it is on the charger. */
    fun ownBattery(context: Context): Pair<Int, Boolean>? {
        val manager = context.getSystemService(BatteryManager::class.java) ?: return null
        val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 } ?: return null
        return Pair(level, manager.isCharging)
    }

    @OptIn(FlowPreview::class)
    private data class Seen(
        val state: EngineState,
        val devices: List<uniffi.tandem_core.TandemDevice>,
        val icons: Map<String, String>,
        val playingThere: Set<String>,
        val playingHere: Boolean,
    )

    fun start(context: Context, host: EngineHost, icons: StateFlow<Map<String, String>>, media: MediaMirror, scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            // Only while the engine runs: before that the list of devices is empty, which would wipe what the widget last knew.
            combine(host.state, host.devices, icons, media.remote, media.local) { state, devices, picked, remote, local ->
                Seen(state, devices, picked, remote.filterValues { r -> r.players.any { it.playing } }.keys, local.any { it.playing })
            }
                .filter { it.state == EngineState.Running }
                .debounce(1_000)
                .collect { seen ->
                    val (old, oldName) = read(context)
                    val oldPlaying = selfPlaying(context)
                    val inputs = seen.devices.map { d ->
                        WidgetDeviceInput(
                            d.id, d.name, seen.icons[d.id] ?: platformIcon(d.platform), d.online,
                            d.status.battery?.level?.toInt(), d.status.battery?.charging == true, d.id in seen.playingThere,
                        )
                    }
                    val next = BatteryWidgetLogic.merge(old, inputs)
                    val name = host.myName.ifEmpty { null } ?: oldName
                    if (next == old && name == oldName && seen.playingHere == oldPlaying) return@collect
                    runCatching { File(context.filesDir, FILE).writeText(BatteryWidgetLogic.encode(next, name, seen.playingHere)) }
                    if (BatteryWidgetLogic.differsVisibly(old, next) || name != oldName || seen.playingHere != oldPlaying) redraw(context)
                }
        }
        // This phone's own ring: the battery tells the app often, the widget only hears of a change it would show.
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val now = ownBattery(context) ?: return
                val last = ownLast
                ownLast = now
                if (last == null) return
                if (last.second != now.second) selfChargeChangedAt = System.currentTimeMillis()
                val shows = last.second != now.second || BatteryWidgetLogic.band(last.first) != BatteryWidgetLogic.band(now.first) ||
                    kotlin.math.abs(last.first - now.first) >= 5
                if (shows) scope.launch { redraw(context) } else ownLast = last
            }
        }, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    internal suspend fun redraw(context: Context) {
        _version.value += 1
        runCatching { BatteryWidget().updateAll(context) }
    }

    fun platformIcon(platform: TandemPlatform): String = when (platform) {
        TandemPlatform.ANDROID, TandemPlatform.IOS -> "phone"
        TandemPlatform.MAC_OS -> "laptop"
        TandemPlatform.WINDOWS -> "windows"
        TandemPlatform.LINUX, TandemPlatform.OTHER -> "computer"
    }
}
