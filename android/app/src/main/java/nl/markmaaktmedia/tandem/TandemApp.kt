package nl.markmaaktmedia.tandem

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
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

    /** Picked device icons, hot from the first frame so a list does not flash the default ones. */
    val deviceIcons: kotlinx.coroutines.flow.StateFlow<Map<String, String>> = prefs.deviceIcons.stateIn(
        scope, SharingStarted.Eagerly, runBlocking { prefs.deviceIcons.first() },
    )

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
