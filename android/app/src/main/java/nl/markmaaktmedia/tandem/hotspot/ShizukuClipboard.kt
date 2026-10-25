package nl.markmaaktmedia.tandem.hotspot

import android.content.Context
import android.os.Binder
import android.os.Parcel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.data.TandemPrefs
import nl.markmaaktmedia.tandem.engine.EngineHost
import nl.markmaaktmedia.tandem.engine.EngineState

/**
 * Every copy on this phone to the devices that share the clipboard with it, as the computers do, while Shizuku runs and the person left
 * it on. Without Shizuku Android only lets the app in front read the clipboard, and then the tile and the share button send it.
 */
object ShizukuClipboard {
    private val _watching = MutableStateFlow(false)
    /** Copies are heard now. */
    val watching: StateFlow<Boolean> = _watching.asStateFlow()

    private val _problem = MutableStateFlow<String?>(null)
    /** Why Shizuku could not listen (root that is not allowed to, an Android that changed the interface), for the settings. */
    val problem: StateFlow<String?> = _problem.asStateFlow()

    fun start(context: Context, host: EngineHost, prefs: TandemPrefs, scope: CoroutineScope) {
        val copies = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != ShellClipboard.TX_CLIP) return super.onTransact(code, data, reply, flags)
                val text = data.readString() ?: return true
                // Some apps set the clipboard twice for one copy.
                val now = android.os.SystemClock.elapsedRealtime()
                if (text == last && now - lastAt < SAME_COPY_MS) return true
                last = text
                lastAt = now
                host.onLocalClipboard(text)
                return true
            }
        }
        scope.launch {
            // Not while the app is still being put together: the hotspot module asks for the graph of the app.
            host.state.first { it == EngineState.Running }
            val shizuku = HotspotModule.get(context).shizuku
            prefs.clipboardShizuku.collectLatest { on ->
                // Asked again now and then: Shizuku stops when the phone restarts (without root), and its process may be ended.
                while (isActive) {
                    if (on && shizuku.refresh() == ShizukuState.Ready) {
                        val result = shizuku.watchClipboard(copies)
                        _watching.value = result.ok
                        _problem.value = when {
                            result.ok -> null
                            result.message == ShellClipboard.ROOT -> context.getString(R.string.shizuku_clipboard_root)
                            else -> context.getString(R.string.shizuku_clipboard_failed)
                        }
                        if (!result.ok) android.util.Log.w("ShizukuClipboard", "could not listen: ${result.message}")
                    } else if (_watching.value || (!on && shizuku.refresh() == ShizukuState.Ready)) {
                        // Also when it was turned off while the app was away: the process of Shizuku outlives the app.
                        shizuku.unwatchClipboard()
                        _watching.value = false
                    }
                    delay(CHECK_MS)
                }
            }
        }
    }

    @Volatile private var last: String? = null
    @Volatile private var lastAt = 0L

    private const val CHECK_MS = 20_000L
    private const val SAME_COPY_MS = 2_000L
}
