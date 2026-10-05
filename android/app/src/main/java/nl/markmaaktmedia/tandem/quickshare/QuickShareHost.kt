package nl.markmaaktmedia.tandem.quickshare

import android.app.Application
import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.markmaaktmedia.tandem.engine.AndroidFiles
import uniffi.tandem_core.TandemQsFile
import uniffi.tandem_core.TandemQsText
import uniffi.tandem_core.TandemQsTextInfo
import uniffi.tandem_core.TandemQsTextKind
import uniffi.tandem_core.TandemQsKind
import uniffi.tandem_core.TandemQsPeer
import uniffi.tandem_core.TandemQuickShare
import uniffi.tandem_core.TandemQuickShareSink

/**
 * Quick Share on this phone, without Google services (see docs/QUICKSHARE.md): other devices with Quick Share can send files
 * here and this phone can send to them. Off until the person turns it on, because while it is on this phone can be found by
 * everyone on the same network.
 *
 * Received files are written to the cache first and then put in Downloads, where people look for them.
 */
class QuickShareHost(
    private val app: Application,
    private val scope: CoroutineScope,
    private val nameOf: () -> String,
) : TandemQuickShareSink {

    data class Peer(val id: String, val name: String, val kind: TandemQsKind)

    data class Incoming(
        val id: ULong,
        val sender: String,
        val pin: String,
        val files: List<TandemQsFile>,
        val texts: List<TandemQsTextInfo> = emptyList(),
        val accepted: Boolean = false,
        val done: ULong = 0u,
        val saved: List<String>? = null,
        /** The texts that came in, once the transfer is done: they are on the clipboard. */
        val receivedTexts: List<TandemQsText> = emptyList(),
        val failure: String? = null,
    ) {
        val total: ULong get() = files.fold(0uL) { sum, file -> sum + file.size }

        /** The link that came in, when there is one. */
        val link: String? get() = receivedTexts.firstOrNull { it.kind == TandemQsTextKind.URL }?.text?.trim()
    }

    data class Outgoing(
        val id: ULong,
        val peerName: String,
        val pin: String? = null,
        val done: ULong = 0u,
        val total: ULong = 0u,
        val state: State = State.Sending,
    ) {
        enum class State { Sending, Sent, Refused, Failed }
    }

    private val prefs = app.getSharedPreferences("quickshare", Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean(ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** How long it stays on after it was turned on, in minutes. Zero is until it is turned off. */
    private val _visibleMinutes = MutableStateFlow(prefs.getInt(VISIBLE_MINUTES, 0))
    val visibleMinutes: StateFlow<Int> = _visibleMinutes.asStateFlow()

    /** What a tap on the tile does: opens the page when true, turns Quick Share on or off when false. */
    private val _tileOpens = MutableStateFlow(prefs.getBoolean(TILE_OPENS, false))
    val tileOpens: StateFlow<Boolean> = _tileOpens.asStateFlow()

    private var offJob: Job? = null

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    private val _incoming = MutableStateFlow<List<Incoming>>(emptyList())
    val incoming: StateFlow<List<Incoming>> = _incoming.asStateFlow()

    private val _outgoing = MutableStateFlow<List<Outgoing>>(emptyList())
    val outgoing: StateFlow<List<Outgoing>> = _outgoing.asStateFlow()

    /** Why it could not start, or null. */
    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem.asStateFlow()

    private var service: TandemQuickShare? = null
    private var lock: WifiManager.MulticastLock? = null
    private val notifications = QuickShareNotifications(app)

    /** Starts it when the person left it on, unless the time they gave it has run out. Called once the engine runs. */
    fun startIfEnabled() {
        if (!_enabled.value) return
        val until = prefs.getLong(UNTIL, 0)
        if (until != 0L && System.currentTimeMillis() >= until) {
            setEnabled(false)
            return
        }
        scheduleOff(until)
        scope.launch { start() }
    }

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(ENABLED, on).apply()
        _enabled.value = on
        if (on) scheduleOff(0) else cancelOff()
        scope.launch { if (on) start() else stop() }
        QuickShareTileService.refresh(app)
    }

    fun setVisibleMinutes(minutes: Int) {
        prefs.edit().putInt(VISIBLE_MINUTES, minutes).apply()
        _visibleMinutes.value = minutes
        if (_enabled.value) scheduleOff(0)
    }

    fun setTileOpens(opens: Boolean) {
        prefs.edit().putBoolean(TILE_OPENS, opens).apply()
        _tileOpens.value = opens
    }

    /** Turns itself off after the time that was chosen. `until` is a time that was kept from before, or zero for a fresh start. */
    private fun scheduleOff(until: Long) {
        offJob?.cancel()
        val minutes = _visibleMinutes.value
        if (minutes <= 0) {
            prefs.edit().putLong(UNTIL, 0).apply()
            return
        }
        val end = if (until > 0) until else System.currentTimeMillis() + minutes * 60_000L
        prefs.edit().putLong(UNTIL, end).apply()
        offJob = scope.launch {
            delay((end - System.currentTimeMillis()).coerceAtLeast(0))
            setEnabled(false)
        }
    }

    private fun cancelOff() {
        offJob?.cancel()
        offJob = null
        prefs.edit().putLong(UNTIL, 0).apply()
    }

    private suspend fun start() = withContext(Dispatchers.IO) {
        if (service != null) return@withContext
        // Multicast packets are dropped by the radio unless an app asks for them: without this nobody is found.
        lock = (app.getSystemService(Context.WIFI_SERVICE) as WifiManager).createMulticastLock("tandem-quickshare").apply {
            setReferenceCounted(false)
            acquire()
        }
        try {
            service = TandemQuickShare.start(nameOf(), TandemQsKind.PHONE, this@QuickShareHost)
            _problem.value = null
        } catch (e: Exception) {
            Log.w(TAG, "quick share did not start", e)
            _problem.value = e.message ?: e.toString()
            runCatching { lock?.release() }
            lock = null
        }
    }

    private suspend fun stop() = withContext(Dispatchers.IO) {
        service?.stop()
        service?.close()
        service = null
        runCatching { lock?.release() }
        lock = null
        _peers.value = emptyList()
        _incoming.value.forEach { notifications.cancel(it.id) }
        _incoming.value = emptyList()
    }

    // ---- What the person does ------------------------------------------------------------------------------------------------

    fun accept(id: ULong) {
        val folder = File(app.cacheDir, "quickshare/$id").apply { mkdirs() }
        _incoming.update { list -> list.map { if (it.id == id) it.copy(accepted = true) else it } }
        service?.respond(id, folder.absolutePath)
        _incoming.value.firstOrNull { it.id == id }?.let { notifications.progress(it) }
    }

    fun decline(id: ULong) {
        service?.respond(id, null)
        _incoming.update { list -> list.filterNot { it.id == id } }
        notifications.cancel(id)
    }

    fun dismiss(id: ULong) {
        _incoming.update { list -> list.filterNot { it.id == id } }
        _outgoing.update { list -> list.filterNot { it.id == id } }
        notifications.cancel(id)
    }

    /** Sends the things behind these addresses (from the share sheet or the file picker) to a device that was found. */
    suspend fun send(uris: List<Uri>, peer: Peer): Result<ULong> = withContext(Dispatchers.IO) {
        runCatching {
            val live = service ?: error("Quick Share is off")
            val folder = File(app.cacheDir, "quickshare/out/${System.currentTimeMillis()}").apply { mkdirs() }
            val paths = uris.map { copyToCache(it, folder) }
            val total = paths.sumOf { File(it).length() }.toULong()
            val id = live.send(peer.id, nameOf(), TandemQsKind.PHONE, paths)
            _outgoing.update { it + Outgoing(id, peer.name, total = total) }
            id
        }
    }

    /** Sends a link or a note to a device that was found. */
    suspend fun sendText(text: String, peer: Peer): Result<ULong> = withContext(Dispatchers.IO) {
        runCatching {
            val live = service ?: error("Quick Share is off")
            val id = live.sendText(peer.id, nameOf(), TandemQsKind.PHONE, text)
            _outgoing.update { it + Outgoing(id, peer.name, total = text.length.toULong()) }
            id
        }
    }

    private fun copyToCache(uri: Uri, folder: File): String {
        val name = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment ?: "file"
        val target = File(folder, name.substringAfterLast('/'))
        app.contentResolver.openInputStream(uri)!!.use { input -> target.outputStream().use { input.copyTo(it, 256 * 1024) } }
        return target.absolutePath
    }

    // ---- What the service says -----------------------------------------------------------------------------------------------

    override fun peerFound(peer: TandemQsPeer) {
        val found = Peer(peer.id, peer.name, peer.kind)
        _peers.update { list -> list.filterNot { it.id == found.id } + found }
    }

    override fun peerLost(id: String) {
        _peers.update { list -> list.filterNot { it.id == id } }
    }

    override fun incoming(id: ULong, sender: String, pin: String, files: List<TandemQsFile>, texts: List<TandemQsTextInfo>) {
        val item = Incoming(id, sender, pin, files, texts)
        _incoming.update { it + item }
        notifications.ask(item)
    }

    override fun progress(id: ULong, done: ULong, total: ULong) {
        _incoming.update { list -> list.map { if (it.id == id) it.copy(done = done) else it } }
        _outgoing.update { list -> list.map { if (it.id == id) it.copy(done = done, total = total) else it } }
        _incoming.value.firstOrNull { it.id == id && it.accepted }?.let { notifications.progress(it) }
    }

    override fun received(id: ULong, paths: List<String>, texts: List<TandemQsText>) {
        scope.launch(Dispatchers.IO) {
            // What was sent as text is put on the clipboard, so it can be pasted at once.
            texts.lastOrNull()?.let { last ->
                withContext(Dispatchers.Main) {
                    runCatching {
                        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Quick Share", last.text))
                    }
                }
            }
            val files = AndroidFiles(app)
            val stored = paths.map { path ->
                val name = File(path).name
                runCatching { files.storeDownload(path, name, mimeOf(name)) }.getOrDefault(path)
            }
            File(app.cacheDir, "quickshare/$id").deleteRecursively()
            _incoming.update { list -> list.map { if (it.id == id) it.copy(saved = stored, receivedTexts = texts, done = it.total) else it } }
            _incoming.value.firstOrNull { it.id == id }?.let { notifications.received(it) }
        }
    }

    override fun pin(id: ULong, pin: String) {
        _outgoing.update { list -> list.map { if (it.id == id) it.copy(pin = pin) else it } }
    }

    override fun sent(id: ULong, refused: Boolean) {
        _outgoing.update { list ->
            list.map { if (it.id == id) it.copy(state = if (refused) Outgoing.State.Refused else Outgoing.State.Sent) else it }
        }
        File(app.cacheDir, "quickshare/out").deleteRecursively()
    }

    override fun failed(id: ULong, reason: String) {
        Log.w(TAG, "quick share transfer $id failed: $reason")
        _incoming.update { list -> list.map { if (it.id == id) it.copy(failure = reason) else it } }
        _outgoing.update { list -> list.map { if (it.id == id) it.copy(state = Outgoing.State.Failed) else it } }
        _incoming.value.firstOrNull { it.id == id }?.let { notifications.failed(it) }
    }

    private fun mimeOf(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    }

    companion object {
        private const val TAG = "TandemQuickShare"
        private const val ENABLED = "enabled"
        private const val VISIBLE_MINUTES = "visible_minutes"
        private const val TILE_OPENS = "tile_opens"
        private const val UNTIL = "until"
    }
}
