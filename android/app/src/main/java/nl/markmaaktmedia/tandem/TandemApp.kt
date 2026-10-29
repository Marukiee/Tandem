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
    val live = nl.markmaaktmedia.tandem.live.LiveShare(app, host, scope)

    /** This phone as the next screen of a computer's shared mouse. */
    val pointerIn = nl.markmaaktmedia.tandem.live.PointerIn(app, host, prefs, scope)
    val clipHistory = nl.markmaaktmedia.tandem.data.ClipHistory(app)
    val quickShare = nl.markmaaktmedia.tandem.quickshare.QuickShareHost(app, scope) { host.myName }

    /** This phone as the viewer of a Mac's screen. */
    val screen = nl.markmaaktmedia.tandem.screen.ScreenViewer(app, host).also { host.mediaViewer = it }

    /** Picked device icons, hot from the first frame so a list does not flash the default ones. */
    val deviceIcons: kotlinx.coroutines.flow.StateFlow<Map<String, String>> = prefs.deviceIcons.stateIn(
        scope, SharingStarted.Eagerly, runBlocking { prefs.deviceIcons.first() },
    )

    init {
        nl.markmaaktmedia.tandem.widget.BatteryWidgetStore.start(app, host, deviceIcons, media, scope)
        nl.markmaaktmedia.tandem.hotspot.ShizukuClipboard.start(app, host, prefs, scope)
        // Quick Share starts once the engine runs, when the person left it on.
        scope.launch {
            host.state.first { it == nl.markmaaktmedia.tandem.engine.EngineState.Running }
            quickShare.startIfEnabled()
        }
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

    /** Set by the tile of Quick Share: the page of Quick Share is to be opened. */
    val openQuickShare = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** Set when another device asks this phone to open Tandem's page of permissions. */
    val openAccess = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** Set by the reminder of the tour: the tour is to be opened. */
    val openTour = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** Set when another device asks this phone to open the sharing page of its settings. */
    val openSharing = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** Set when a computer asks this phone to be its second screen: the id of that computer, for the screen to open on. */
    val openDisplay = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    /** A computer offered to show its own display here: its viewer opens. */
    val openMirror = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** Set by a ring of the battery widget: the id of the device whose page is to be opened. */
    val openDevice = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** Whether the app is on the screen now, so an offer from a computer opens it at once and does not wait as a notification. */
    @Volatile var appVisible = false

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
