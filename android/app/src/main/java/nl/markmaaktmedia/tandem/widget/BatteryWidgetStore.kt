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

    fun read(context: Context): Pair<List<WidgetBattery>, String?> =
        BatteryWidgetLogic.decode(runCatching { File(context.filesDir, FILE).takeIf { it.exists() }?.readText() }.getOrNull())

    /** This phone's battery: level and whether it is on the charger. */
    fun ownBattery(context: Context): Pair<Int, Boolean>? {
        val manager = context.getSystemService(BatteryManager::class.java) ?: return null
        val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 } ?: return null
        return Pair(level, manager.isCharging)
    }

    @OptIn(FlowPreview::class)
    fun start(context: Context, host: EngineHost, icons: StateFlow<Map<String, String>>, scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            // Only while the engine runs: before that the list of devices is empty, which would wipe what the widget last knew.
            combine(host.state, host.devices, icons) { state, devices, picked -> Triple(state, devices, picked) }
                .filter { it.first == EngineState.Running }
                .debounce(1_000)
                .collect { (_, devices, picked) ->
                    val (old, oldName) = read(context)
                    val inputs = devices.map { d ->
                        WidgetDeviceInput(
                            d.id, d.name, picked[d.id] ?: platformIcon(d.platform), d.online,
                            d.status.battery?.level?.toInt(), d.status.battery?.charging == true,
                        )
                    }
                    val next = BatteryWidgetLogic.merge(old, inputs)
                    val name = host.myName.ifEmpty { null } ?: oldName
                    if (next == old && name == oldName) return@collect
                    runCatching { File(context.filesDir, FILE).writeText(BatteryWidgetLogic.encode(next, name)) }
                    if (BatteryWidgetLogic.differsVisibly(old, next) || name != oldName) redraw(context)
                }
        }
        // This phone's own ring: the battery tells the app often, the widget only hears of a change it would show.
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val now = ownBattery(context) ?: return
                val last = ownLast
                ownLast = now
                if (last == null) return
                val shows = last.second != now.second || BatteryWidgetLogic.band(last.first) != BatteryWidgetLogic.band(now.first) ||
                    kotlin.math.abs(last.first - now.first) >= 5
                if (shows) scope.launch { redraw(context) } else ownLast = last
            }
        }, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    private suspend fun redraw(context: Context) {
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
