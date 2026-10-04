package nl.markmaaktmedia.tandem

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import nl.markmaaktmedia.tandem.data.TandemPrefs
import nl.markmaaktmedia.tandem.engine.EngineHost
import nl.markmaaktmedia.tandem.update.UpdateRepository

/** The few things that live as long as the process. */
class Graph(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val prefs = TandemPrefs(app)
    val host = EngineHost(app, prefs, scope)
    val updater = UpdateRepository(app, prefs)
    val media = nl.markmaaktmedia.tandem.media.MediaMirror(app, prefs, host, scope)
    val audio = nl.markmaaktmedia.tandem.audio.RemoteAudioPlayer(app, prefs, host, scope).also { host.audioSink = it }

    /** This phone as the viewer of a Mac's screen. */
    val screen = nl.markmaaktmedia.tandem.screen.ScreenViewer(app, host).also { host.mediaViewer = it }

    /** Picked device icons, hot from the first frame so a list does not flash the default ones. */
    val deviceIcons: kotlinx.coroutines.flow.StateFlow<Map<String, String>> = prefs.deviceIcons.stateIn(
        scope, SharingStarted.Eagerly, runBlocking { prefs.deviceIcons.first() },
    )

    init {
        // The places in the Files app are the devices that share their files and can be reached, so it is told when they change.
        scope.launch {
            host.devices
                .map { list -> list.filter { it.online && "files" in it.caps }.map { it.id }.toSet() }
                .distinctUntilChanged()
                .collect {
                    app.contentResolver.notifyChange(android.provider.DocumentsContract.buildRootsUri(app.packageName + ".documents"), null)
                }
        }
    }

    /** Set by the first-run flow so the home screen opens straight on pairing. */
    val startAtPair = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** A tandem:// pairing link that opened the app from outside. */
    val pairLink = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
}

class TandemApp : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
    }
}

val android.content.Context.graph: Graph
    get() = (applicationContext as TandemApp).graph
