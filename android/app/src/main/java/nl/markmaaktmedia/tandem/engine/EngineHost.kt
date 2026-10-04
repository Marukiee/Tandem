package nl.markmaaktmedia.tandem.engine

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.BuildConfig
import nl.markmaaktmedia.tandem.data.TandemPrefs
import uniffi.tandem_core.TandemCaptureWhy
import uniffi.tandem_core.TandemConfig
import uniffi.tandem_core.TandemDevice
import uniffi.tandem_core.TandemEngine
import uniffi.tandem_core.TandemEvent
import uniffi.tandem_core.TandemEventSink
import uniffi.tandem_core.TandemOutgoingFile
import uniffi.tandem_core.TandemPlatform
import uniffi.tandem_core.TandemShareOrigin
import uniffi.tandem_core.TandemStatus
import uniffi.tandem_core.tandemInitLogging
import java.io.File

data class TransferItem(
    val id: String,
    val peer: String,
    val name: String,
    val done: Long,
    val total: Long,
    val incoming: Boolean,
    val state: State,
    val location: String? = null,
    val error: String? = null,
    val speed: Double = 0.0,
    val updatedAt: Long = System.currentTimeMillis(),
    /** Where a file that was sent came from on this phone, as far as it can still be reached. */
    val source: String? = null,
) {
    enum class State { Active, Done, Failed }

    val fraction: Float get() = if (total <= 0) (if (state == State.Done) 1f else 0f) else (done.toFloat() / total).coerceIn(0f, 1f)
}

sealed interface EngineState {
    data object Stopped : EngineState
    data object Starting : EngineState
    data object Running : EngineState
    data class Failed(val reason: String) : EngineState
}

/**
 * Owns the Rust engine for the whole process and turns its events into state the
 * screens can collect. The foreground service keeps the process alive; the screens
 * come and go.
 */
class EngineHost(
    private val context: Context,
    private val prefs: TandemPrefs,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<EngineState>(EngineState.Stopped)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _devices = MutableStateFlow<List<TandemDevice>>(emptyList())
    val devices: StateFlow<List<TandemDevice>> = _devices.asStateFlow()

    // The history of what was sent and received outlives the app: it is read back at start, so an
    // update (which restarts the app) no longer empties the Shared tab.
    private val historyFile = File(context.filesDir, "transfers.json")
    private val _transfers = MutableStateFlow(loadHistory())
    val transfers: StateFlow<List<TransferItem>> = _transfers.asStateFlow()

    init {
        @OptIn(kotlinx.coroutines.FlowPreview::class)
        scope.launch(Dispatchers.IO) {
            _transfers.drop(1).debounce(600).collect { saveHistory(it) }
        }
    }

    private fun loadHistory(): List<TransferItem> = runCatching {
        val array = org.json.JSONArray(historyFile.takeIf { it.exists() }?.readText() ?: return@runCatching emptyList())
        (0 until array.length()).map { array.getJSONObject(it) }.map { o ->
            TransferItem(
                id = o.getString("id"), peer = o.getString("peer"), name = o.getString("name"),
                done = o.optLong("size"), total = o.optLong("size"), incoming = o.getBoolean("incoming"),
                state = if (o.optBoolean("failed")) TransferItem.State.Failed else TransferItem.State.Done,
                location = o.optString("location").ifEmpty { null }, error = o.optString("error").ifEmpty { null },
                updatedAt = o.optLong("at"), source = o.optString("source").ifEmpty { null },
            )
        }
    }.getOrDefault(emptyList())

    /** Only what has finished: a transfer that was running when the app stopped cannot be picked up as it was. */
    private fun saveHistory(list: List<TransferItem>) {
        runCatching {
            val array = org.json.JSONArray()
            list.filter { it.state != TransferItem.State.Active }.take(60).forEach {
                array.put(
                    org.json.JSONObject().put("id", it.id).put("peer", it.peer).put("name", it.name).put("size", it.total)
                        .put("incoming", it.incoming).put("failed", it.state == TransferItem.State.Failed)
                        .put("location", it.location ?: "").put("error", it.error ?: "").put("at", it.updatedAt)
                        .put("source", it.source ?: ""),
                )
            }
            historyFile.writeText(array.toString())
        }
    }

    private val _events = MutableSharedFlow<TandemEvent>(extraBufferCapacity = 512)
    val events: SharedFlow<TandemEvent> = _events.asSharedFlow()

    private val _removed = MutableStateFlow(false)
    val removedFromCircle: StateFlow<Boolean> = _removed.asStateFlow()

    @Volatile
    var engine: TandemEngine? = null
        private set

    /** Where the Mac's sound goes. Set before the engine starts; the engine hands it every packet. */
    @Volatile
    var audioSink: uniffi.tandem_core.TandemAudioSink? = null

    /** Where the frames of a Mac's screen go. Set before the engine starts, like the audio sink. */
    @Volatile
    var mediaViewer: uniffi.tandem_core.TandemMediaViewer? = null

    val myId: String get() = engine?.id().orEmpty()
    val myName: String get() = engine?.name().orEmpty()

    fun start() {
        if (_state.value == EngineState.Starting || _state.value == EngineState.Running) return
        _state.value = EngineState.Starting
        scope.launch(Dispatchers.IO) {
            try {
                tandemInitLogging(BuildConfig.DEBUG)
                val name = prefs.deviceName.first() ?: defaultName()
                val config = TandemConfig(
                    dataDir = File(context.filesDir, "tandem").apply { mkdirs() }.absolutePath,
                    deviceName = name,
                    platform = TandemPlatform.ANDROID,
                    model = Build.MODEL,
                    appVersion = BuildConfig.VERSION_NAME,
                    port = 47820.toUShort(),
                    enableMdns = true,
                    caps = listOf("clipboard", "share", "notify", "call", "input", "battery", "hotspot", "screenshot", "media", "capture", "screen.view") +
                        nl.markmaaktmedia.tandem.live.LiveShare.caps(context),
                    lowPower = true,
                )
                val started = TandemEngine.start(config, AndroidVault(context), AndroidFiles(context), Sink())
                engine = started
                audioSink?.let { started.setAudioSink(it) }
                mediaViewer?.let { started.setMediaViewer(it) }
                _state.value = EngineState.Running
                refreshDevices()
            } catch (e: Exception) {
                Log.e(TAG, "engine failed to start", e)
                _state.value = EngineState.Failed(e.message ?: e.toString())
            }
        }
    }

    fun stop() {
        val current = engine ?: return
        engine = null
        _state.value = EngineState.Stopped
        scope.launch(Dispatchers.IO) { runCatching { current.shutdown() }; runCatching { current.close() } }
    }

    fun refreshDevices() {
        engine?.let { _devices.value = it.devices() }
    }

    fun device(id: String): TandemDevice? = _devices.value.firstOrNull { it.id == id }

    private fun defaultName(): String {
        val user = runCatching { Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull()
        return user?.takeIf { it.isNotBlank() } ?: Build.MODEL
    }

    // ---- Events ---------------------------------------------------------------

    private inner class Sink : TandemEventSink {
        override fun onEvent(event: TandemEvent) {
            handle(event)
            _events.tryEmit(event)
        }
    }

    private fun handle(event: TandemEvent) {
        when (event) {
            is TandemEvent.DevicesChanged, is TandemEvent.Connected, is TandemEvent.Disconnected,
            is TandemEvent.CircleChanged, is TandemEvent.Paired -> refreshDevices()

            is TandemEvent.RemovedFromCircle -> _removed.value = true

            is TandemEvent.Clipboard -> applyRemoteClipboard(event.text)

            is TandemEvent.ShareText -> {
                if (event.open && event.isUrl) {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(event.text))
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                } else {
                    applyRemoteClipboard(event.text)
                }
            }

            is TandemEvent.Progress -> updateTransfer(event)
            is TandemEvent.Finished -> finishTransfer(event)
            else -> Unit
        }
    }

    private fun applyRemoteClipboard(text: String) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("Tandem", text))
        lastRemoteClip = text
    }

    /** The text we just wrote to the clipboard ourselves, so we do not send it back. */
    @Volatile
    var lastRemoteClip: String? = null
        private set

    private fun key(offer: ULong, index: UInt, incoming: Boolean) = "$offer-$index-${if (incoming) "in" else "out"}"

    private fun updateTransfer(e: TandemEvent.Progress) {
        val id = key(e.offer, e.index, e.incoming)
        val now = System.currentTimeMillis()
        _transfers.update { list ->
            val existing = list.firstOrNull { it.id == id }
            val done = e.done.toLong()
            val item = if (existing != null) {
                val seconds = (now - existing.updatedAt) / 1000.0
                var speed = existing.speed
                if (seconds > 0.05 && done >= existing.done) {
                    val instant = (done - existing.done) / seconds
                    speed = if (speed == 0.0) instant else speed * 0.7 + instant * 0.3
                }
                existing.copy(done = done, total = e.total.toLong(), state = TransferItem.State.Active, speed = speed, updatedAt = now)
            } else {
                TransferItem(id, e.peer, e.name, done, e.total.toLong(), e.incoming, TransferItem.State.Active, updatedAt = now, source = sourceOf(e.incoming, e.name, e.total.toLong()))
            }
            if (existing != null) list.map { if (it.id == id) item else it } else (listOf(item) + list).take(60)
        }
    }

    private fun finishTransfer(e: TandemEvent.Finished) {
        val id = key(e.offer, e.index, e.incoming)
        _transfers.update { list ->
            val old = list.firstOrNull { it.id == id }
            val item = (old ?: TransferItem(id, e.peer, e.name, e.size.toLong(), e.size.toLong(), e.incoming, TransferItem.State.Done)).copy(
                state = if (e.error == null) TransferItem.State.Done else TransferItem.State.Failed,
                error = e.error,
                location = e.location,
                source = old?.source ?: sourceOf(e.incoming, e.name, e.size.toLong()),
                done = if (e.error == null) maxOf(e.size.toLong(), old?.total ?: 0) else old?.done ?: 0,
                total = maxOf(e.size.toLong(), old?.total ?: 0),
                speed = 0.0,
                updatedAt = System.currentTimeMillis(),
            )
            if (old != null) list.map { if (it.id == id) item else it } else (listOf(item) + list).take(60)
        }
    }

    /** Takes one finished transfer off the list. The file itself stays where it is. */
    fun removeTransfer(id: String) {
        _transfers.update { list -> list.filterNot { it.id == id && it.state != TransferItem.State.Active } }
    }

    fun clearFinishedTransfers() {
        _transfers.update { list -> list.filter { it.state == TransferItem.State.Active } }
    }

    // ---- Actions --------------------------------------------------------------

    /** Sends content shared into the app. Returns how many devices it went to. */
    suspend fun sendUris(uris: List<Uri>, targets: List<String>, origin: TandemShareOrigin, hold: Boolean = true): Int {
        val engine = engine ?: return 0
        val files = uris.mapNotNull { describe(it, hold) }
        if (files.isEmpty() || targets.isEmpty()) return 0
        return engine.sendFiles(targets, files, origin).sentTo.size
    }

    /** Sends what another device asked for, tagged with its request so it knows what the file answers. */
    suspend fun sendCapture(file: File, mime: String, target: String, request: Long): Boolean {
        val engine = engine ?: return false
        val outgoing = TandemOutgoingFile(source = file.absolutePath, name = file.name, size = file.length().toULong(), mime = mime)
        return engine.sendFiles(listOf(target), listOf(outgoing), TandemShareOrigin.Capture(request.toULong())).sentTo.isNotEmpty()
    }

    /** Tells the device that asked that there will be no picture, so its waiting screen closes. */
    suspend fun cancelCapture(target: String, request: Long, why: TandemCaptureWhy) {
        runCatching { engine?.cancelCapture(target, request.toULong(), why) }
    }

    /** The sources of the files that were offered, by name and size: the events about them say no more than that. */
    private val outgoingSources = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun sourceOf(incoming: Boolean, name: String, size: Long): String? =
        if (incoming) null else outgoingSources["$name|$size"]

    private fun describe(uri: Uri, hold: Boolean): TandemOutgoingFile? {
        val resolver = context.contentResolver
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        var size = 0L
        if (uri.scheme == "content") {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)?.let { name = it }
                    if (!cursor.isNull(1)) size = cursor.getLong(1)
                }
            }
        }
        val source = uri.toString()
        if (hold) {
            runCatching {
                val descriptor = resolver.openFileDescriptor(uri, "r") ?: return@runCatching
                AndroidFiles.hold(source, descriptor)
                if (size <= 0) size = descriptor.statSize.coerceAtLeast(0)
            }.onFailure { Log.w(TAG, "could not open $uri", it) }
        }
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        if (outgoingSources.size > 200) outgoingSources.clear()
        outgoingSources["$name|$size"] = source
        return TandemOutgoingFile(source = source, name = name, size = size.toULong(), mime = mime)
    }

    /** Sends the clipboard to the chosen devices. */
    suspend fun sendClipboard(targets: List<String>, text: String): Int {
        val engine = engine ?: return 0
        return engine.sendClipboard(targets, text, isUrl = text.startsWith("http://") || text.startsWith("https://")).size
    }

    fun onLocalClipboard(text: String) {
        if (text == lastRemoteClip) return
        scope.launch(Dispatchers.IO) {
            engine?.clipboardChanged(text, isUrl = text.startsWith("http://") || text.startsWith("https://"))
        }
    }

    fun pushStatus(status: TandemStatus) {
        val current = engine ?: return
        scope.launch(Dispatchers.IO) { runCatching { current.updateStatus(status) } }
    }

    fun onNetworkChanged() {
        engine?.networkChanged()
    }

    companion object {
        private const val TAG = "EngineHost"
        fun markSensitive(clip: ClipData) {
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
    }
}
