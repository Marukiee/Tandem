package nl.markmaaktmedia.tandem.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.data.TandemPrefs
import nl.markmaaktmedia.tandem.engine.EngineHost
import nl.markmaaktmedia.tandem.mirror.MirrorListener
import uniffi.tandem_core.TandemEvent
import uniffi.tandem_core.TandemInput
import uniffi.tandem_core.TandemMediaAction
import uniffi.tandem_core.TandemMediaKey
import uniffi.tandem_core.TandemMediaPlayer
import uniffi.tandem_core.TandemPlatform
import java.io.ByteArrayOutputStream

/** What a device said it is playing, and when it said so, for counting the position on from there. */
data class RemotePlayers(val players: List<TandemMediaPlayer>, val at: Long)

/**
 * Shares what plays on this phone with the Mac, and lets the Mac press the buttons; and keeps what
 * plays on the Mac so this phone can show and control it.
 *
 * What the phone plays is read from the system's media sessions, which every music and video app
 * publishes for the lock screen. Reading them needs the notification access that notification
 * mirroring already asks for. Apps the person left out are never read, and nothing is sent at all
 * with the switch off.
 */
class MediaMirror(
    private val context: Context,
    private val prefs: TandemPrefs,
    private val host: EngineHost,
    private val scope: CoroutineScope,
) {
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val listener = ComponentName(context, MirrorListener::class.java)
    private val main = Handler(Looper.getMainLooper())

    private class Tracked(val controller: MediaController, val callback: MediaController.Callback)

    private val tracked = LinkedHashMap<MediaSession.Token, Tracked>()
    private val lock = Any()

    @Volatile private var share = true
    @Volatile private var excluded: Set<String> = emptySet()
    private var registered = false
    private var jobs = mutableListOf<Job>()
    private var publishJob: Job? = null

    /** What this phone shares, after leaving out the apps the person excluded. For comparing with the Mac's. */
    private val _local = MutableStateFlow<List<TandemMediaPlayer>>(emptyList())
    val local: StateFlow<List<TandemMediaPlayer>> = _local.asStateFlow()

    private val _remote = MutableStateFlow<Map<String, RemotePlayers>>(emptyMap())

    /** What each Mac plays, by device id. */
    val remote: StateFlow<Map<String, RemotePlayers>> = _remote.asStateFlow()

    private val _remoteArt = MutableStateFlow<Map<Long, Bitmap>>(emptyMap())
    val remoteArt: StateFlow<Map<Long, Bitmap>> = _remoteArt.asStateFlow()

    /** False when the notification access that reading sessions needs has not been given. */
    private val _access = MutableStateFlow(true)
    val hasAccess: StateFlow<Boolean> = _access.asStateFlow()

    /** The covers already sent to each device, so one goes out once. */
    private val sentArt = java.util.concurrent.ConcurrentHashMap<String, MutableSet<Long>>()
    private val artCache = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > 24
    }

    fun start() {
        if (jobs.isNotEmpty()) return
        jobs += scope.launch {
            prefs.mediaShare.collect {
                share = it
                if (!it) _remote.value = emptyMap()
                schedulePublish()
            }
        }
        jobs += scope.launch { prefs.mediaExcluded.collect { excluded = it; schedulePublish() } }
        jobs += scope.launch { host.events.collect { handle(it) } }
        // The listener may only get its access after this starts, so look again now and then.
        jobs += scope.launch {
            while (true) {
                register()
                delay(RETRY_MS)
            }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        publishJob?.cancel()
        synchronized(lock) {
            tracked.values.forEach { runCatching { it.controller.unregisterCallback(it.callback) } }
            tracked.clear()
            if (registered) runCatching { manager.removeOnActiveSessionsChangedListener(sessions) }
            registered = false
        }
    }

    // ---- Reading the phone's sessions ------------------------------------------

    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { controllers -> update(controllers.orEmpty()) }

    private fun register() {
        synchronized(lock) {
            try {
                if (!registered) {
                    manager.addOnActiveSessionsChangedListener(sessions, listener, main)
                    registered = true
                }
                _access.value = true
                update(manager.getActiveSessions(listener))
            } catch (e: SecurityException) {
                // No notification access yet: nothing to read, and nothing wrong.
                _access.value = false
                registered = false
            }
        }
    }

    private fun update(controllers: List<MediaController>) {
        var changed = false
        synchronized(lock) {
            val keep = controllers.map { it.sessionToken }.toSet()
            tracked.keys.filter { it !in keep }.forEach { token ->
                tracked.remove(token)?.let { runCatching { it.controller.unregisterCallback(it.callback) } }
                changed = true
            }
            for (controller in controllers) {
                if (controller.sessionToken in tracked) continue
                val callback = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) = schedulePublish()
                    override fun onMetadataChanged(metadata: MediaMetadata?) = schedulePublish()
                    override fun onSessionDestroyed() = schedulePublish()
                }
                runCatching { controller.registerCallback(callback, main) }
                tracked[controller.sessionToken] = Tracked(controller, callback)
                changed = true
            }
        }
        // The look every few seconds finds nothing new most of the time, and then nothing is sent.
        if (changed) schedulePublish()
    }

    // ---- Sending --------------------------------------------------------------

    private class Built(val player: TandemMediaPlayer, val cover: ByteArray?)

    private fun schedulePublish(only: List<String>? = null) {
        publishJob?.cancel()
        publishJob = scope.launch(Dispatchers.Default) {
            // A track change fires several callbacks within a moment. Send the result once.
            delay(DEBOUNCE_MS)
            publish(only)
        }
    }

    private suspend fun publish(only: List<String>? = null) {
        val built = if (share) build() else emptyList()
        _local.value = built.map { it.player }
        val engine = host.engine ?: return
        val targets = only ?: computers()
        if (targets.isEmpty()) return
        val players = built.map { it.player }
        Log.d(TAG, "sharing ${players.size} player(s) with ${targets.size} device(s): ${players.joinToString { "${it.app}: ${it.title}" }}")
        // The cover goes first: the players name it by its key, and a computer that hears the key before it has the
        // picture shows an empty square for a moment.
        for (b in built) {
            val key = b.player.art
            val cover = b.cover ?: continue
            if (key == 0UL) continue
            val fresh = targets.filter { sentArt.getOrPut(it) { java.util.concurrent.ConcurrentHashMap.newKeySet() }.add(key.toLong()) }
            if (fresh.isNotEmpty()) runCatching { engine.sendMediaArt(fresh, key, cover) }
        }
        runCatching { engine.sendMediaPlayers(targets, players) }
    }

    private fun computers(): List<String> = host.devices.value.filter { showsPhoneMusic(it.platform) }.map { it.id }

    private fun build(): List<Built> {
        val controllers = synchronized(lock) { tracked.values.map { it.controller } }
        val seen = HashSet<String>()
        val out = ArrayList<Built>()
        for (controller in controllers) {
            val pkg = controller.packageName
            if (pkg in excluded || !seen.add(pkg)) continue
            build(controller)?.let { out += it }
        }
        // What plays comes first, so the Mac's first row is the one that matters.
        return out.sortedByDescending { it.player.playing }
    }

    private fun build(controller: MediaController): Built? {
        val meta = controller.metadata
        val state = controller.playbackState
        val title = (meta?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)).orEmpty()
        val playing = state?.state == PlaybackState.STATE_PLAYING || state?.state == PlaybackState.STATE_BUFFERING
        // A session with nothing to say and nothing playing is an app that is only open.
        if (title.isBlank() && !playing) return null
        val artist = (
            meta?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ).orEmpty()
        val album = meta?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        val duration = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 }
        val position = state?.let { s ->
            if (s.position < 0) return@let null
            val moved = if (s.state == PlaybackState.STATE_PLAYING) {
                ((SystemClock.elapsedRealtime() - s.lastPositionUpdateTime) * s.playbackSpeed).toLong()
            } else 0L
            (s.position + moved).coerceAtLeast(0)
        }
        val actions = state?.actions ?: 0L
        val bitmap = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: coverFromUri(meta)
        val key = if (bitmap == null) 0L else (listOf(title, artist, album, fingerprint(bitmap)).hashCode().toLong() and 0x7fffffffL) + 1
        val cover = if (key != 0L && bitmap != null) synchronized(artCache) { artCache.getOrPut(key) { jpeg(bitmap) } } else null
        return Built(
            TandemMediaPlayer(
                id = controller.packageName,
                app = label(controller.packageName),
                title = title,
                artist = artist,
                album = album,
                playing = playing,
                positionMs = position?.toULong(),
                durationMs = duration?.toULong(),
                canPrev = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
                canNext = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
                canSeek = actions and PlaybackState.ACTION_SEEK_TO != 0L,
                art = key.toULong(),
            ),
            cover,
        )
    }

    /**
     * What a picture is, in a number that stays the same for the same picture. The generation id of a bitmap is new for
     * every copy the player hands over, so with it the cover got a new key at every update and the computer showed
     * an empty square each time while the same picture came in again.
     */
    private fun fingerprint(bitmap: Bitmap): Int {
        var hash = bitmap.width * 31 + bitmap.height
        if (bitmap.config == Bitmap.Config.HARDWARE) return hash
        for (row in 0 until 6) for (column in 0 until 6) {
            val x = (bitmap.width - 1) * column / 5
            val y = (bitmap.height - 1) * row / 5
            hash = hash * 31 + bitmap.getPixel(x, y)
        }
        return hash
    }

    private fun coverFromUri(meta: MediaMetadata?): Bitmap? {
        val uri = (meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI) ?: meta?.getString(MediaMetadata.METADATA_KEY_ART_URI))
            ?.takeIf { it.startsWith("content://") } ?: return null
        return runCatching {
            context.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
    }

    private fun jpeg(source: Bitmap): ByteArray {
        val longest = maxOf(source.width, source.height).coerceAtLeast(1)
        val scale = minOf(1f, COVER_PX.toFloat() / longest)
        val small = if (scale < 1f) Bitmap.createScaledBitmap(source, (source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1), true) else source
        return ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
    }

    private fun label(packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    // ---- Events ---------------------------------------------------------------

    private fun handle(event: TandemEvent) {
        when (event) {
            is TandemEvent.MediaPlayers -> if (share) _remote.update { it + (event.from to RemotePlayers(event.players, SystemClock.elapsedRealtime())) }
            is TandemEvent.MediaArt -> remoteArt(event.key, event.jpeg)
            is TandemEvent.MediaCommand -> if (share) command(event)
            is TandemEvent.Connected -> {
                // A device that just connected knows nothing yet, and has not seen any cover.
                sentArt.remove(event.id)
                if (host.device(event.id)?.platform?.let(::showsPhoneMusic) == true) schedulePublish(listOf(event.id))
            }
            is TandemEvent.Disconnected -> _remote.update { it - event.id }
            else -> Unit
        }
    }

    private fun remoteArt(key: ULong, bytes: ByteArray) {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
        _remoteArt.update { current ->
            // Keeps the newest few covers, which is all that is on screen.
            (current + (key.toLong() to bitmap)).entries.toList().takeLast(24).associate { it.key to it.value }
        }
    }

    private fun command(event: TandemEvent.MediaCommand) {
        val action = event.action ?: return
        if (event.player in excluded) return
        val controller = synchronized(lock) { tracked.values.map { it.controller }.firstOrNull { it.packageName == event.player } } ?: return
        main.post {
            val controls = controller.transportControls
            val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
            runCatching {
                when (action) {
                    TandemMediaAction.PLAY -> controls.play()
                    TandemMediaAction.PAUSE -> controls.pause()
                    TandemMediaAction.TOGGLE -> if (playing) controls.pause() else controls.play()
                    TandemMediaAction.NEXT -> controls.skipToNext()
                    TandemMediaAction.PREVIOUS -> controls.skipToPrevious()
                    TandemMediaAction.SEEK -> event.positionMs?.let { controls.seekTo(it.toLong()) }
                }
            }.onFailure { Log.w(TAG, "could not $action ${event.player}", it) }
        }
    }

    // ---- Controlling the Mac ----------------------------------------------------

    /** The apps that have a media session right now, so they can be left out even if they declare no media service. */
    fun activePackages(): List<String> = synchronized(lock) { tracked.values.map { it.controller.packageName }.distinct() }

    /** Presses a button of one of the Mac's players. */
    fun commandMac(deviceId: String, player: String, action: TandemMediaAction, positionMs: Long? = null) {
        val engine = host.engine ?: return
        scope.launch(Dispatchers.IO) { runCatching { engine.sendMediaCommand(deviceId, player, action, positionMs?.toULong()) } }
    }

    /**
     * Presses a media key on the Mac, the same keys the trackpad has. Volume is the Mac's own, not a player's, so it
     * goes this way and not as a command to Spotify or Music.
     */
    fun pressMacKey(deviceId: String, key: TandemMediaKey) {
        val engine = host.engine ?: return
        scope.launch(Dispatchers.IO) { runCatching { engine.sendInput(deviceId, TandemInput.Media(key)) } }
    }

    private companion object {
        const val TAG = "MediaMirror"
        const val DEBOUNCE_MS = 300L
        const val RETRY_MS = 20_000L
        const val COVER_PX = 160
    }
}

/** The computers that put the phone's music in their own media controls (Now Playing on the Mac, the overlay on Windows). */
internal fun showsPhoneMusic(platform: TandemPlatform): Boolean =
    platform == TandemPlatform.MAC_OS || platform == TandemPlatform.WINDOWS
