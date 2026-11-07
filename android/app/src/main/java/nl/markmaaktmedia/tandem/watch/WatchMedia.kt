package nl.markmaaktmedia.tandem.watch

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.SystemClock
import nl.markmaaktmedia.tandem.mirror.MirrorListener
import nl.markmaaktmedia.tandem.watch.protocol.MediaCommandKind
import nl.markmaaktmedia.tandem.watch.protocol.MediaPlayer
import nl.markmaaktmedia.tandem.watch.protocol.Message
import nl.markmaaktmedia.tandem.watch.protocol.PlayerRules
import nl.markmaaktmedia.tandem.watch.protocol.SessionView
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.min

/** Publishes active media sessions. Requires notification access, which is also what grants session access. */
class WatchMedia(
    private val context: Context,
    private val handler: Handler,
    private val excluded: () -> Set<String>,
    private val onCover: (Message.MediaArt) -> Unit,
    private val onChange: (List<MediaPlayer>) -> Unit,
) {
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(context, MirrorListener::class.java)
    /** In the order of the system, which is the last word when nothing else tells two players apart. */
    private val callbacks = LinkedHashMap<MediaController, MediaController.Callback>()
    private var last: List<MediaPlayer> = emptyList()
    private var listening = false
    private var lastSentAt = 0L
    private val coverOfPlayer = HashMap<String, Message.MediaArt>()
    private val staleCovers = HashSet<String>()
    private val wasPlaying = HashSet<String>()
    private val playedAt = HashMap<String, Long>()

    /** The covers of the players on the watch, the one in front first, for a watch that just connected. */
    val covers: List<Message.MediaArt> get() = last.mapNotNull { coverOfPlayer[it.id] }

    private val publishRunnable = Runnable { publish() }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        handler.post { follow(controllers.orEmpty()) }
    }

    val players: List<MediaPlayer> get() = last

    fun refresh() {
        try {
            if (!listening) {
                sessions.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
                listening = true
            }
            follow(sessions.getActiveSessions(component))
        } catch (_: SecurityException) {
            // No notification access yet; the listener calls refresh again once it has.
        }
    }

    fun stop() {
        if (listening) sessions.removeOnActiveSessionsChangedListener(sessionsListener)
        listening = false
        callbacks.forEach { (controller, callback) -> controller.unregisterCallback(callback) }
        callbacks.clear()
        handler.removeCallbacks(publishRunnable)
        last = emptyList()
        coverOfPlayer.clear()
        staleCovers.clear()
    }

    fun perform(playerId: String, kind: MediaCommandKind) {
        val controller = callbacks.keys.firstOrNull { it.packageName == playerId }
        when (kind) {
            MediaCommandKind.VOLUME_UP -> adjustVolume(AudioManager.ADJUST_RAISE)
            MediaCommandKind.VOLUME_DOWN -> adjustVolume(AudioManager.ADJUST_LOWER)
            MediaCommandKind.PLAY_PAUSE -> controller?.transportControls?.let { controls ->
                if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) controls.pause() else controls.play()
            }
            MediaCommandKind.NEXT -> controller?.transportControls?.skipToNext()
            MediaCommandKind.PREVIOUS -> controller?.transportControls?.skipToPrevious()
        }
    }

    /** Pauses every player on this phone that plays now. True when one did. */
    fun pauseAll(): Boolean {
        val playing = callbacks.keys.filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        playing.forEach { it.transportControls.pause() }
        return playing.isNotEmpty()
    }

    /** Whether any player on this phone plays now. */
    val anyPlaying: Boolean get() = callbacks.keys.any { it.playbackState?.state == PlaybackState.STATE_PLAYING }

    /** The crown on the watch turned through the song. */
    fun seek(playerId: String, positionMs: Long) {
        callbacks.keys.firstOrNull { it.packageName == playerId }?.transportControls?.seekTo(positionMs.coerceAtLeast(0))
    }

    private fun adjustVolume(direction: Int) {
        context.getSystemService(AudioManager::class.java)
            ?.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
    }

    private fun follow(controllers: List<MediaController>) {
        callbacks.forEach { (controller, callback) -> controller.unregisterCallback(callback) }
        callbacks.clear()
        controllers.forEach { controller ->
            staleCovers += controller.packageName
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = schedulePublish()
                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    staleCovers += controller.packageName
                    schedulePublish()
                }
                override fun onSessionDestroyed() = schedulePublish()
            }
            controller.registerCallback(callback, handler)
            callbacks[controller] = callback
        }
        schedulePublish()
    }

    /** Players send bursts of updates when a track changes; one message is enough. */
    fun schedulePublish() {
        handler.removeCallbacks(publishRunnable)
        handler.postDelayed(publishRunnable, 300)
    }

    private fun publish() {
        val left = excluded()
        val found = callbacks.keys
            .filter { it.packageName !in left }
            .mapNotNull { controller -> toPlayer(controller)?.let { controller to it } }
        found.forEach { (_, player) -> notePlaying(player) }
        val views = found.map { (controller, player) ->
            val state = controller.playbackState?.state
            val song = listOf(player.title, player.artist).filter { it.isNotBlank() }.joinToString("|")
            SessionView(
                id = player.id,
                song = song,
                playing = player.playing,
                usable = song.isNotBlank() && state != null && state !in NOTHING_TO_OFFER,
                lastPlayingAt = playedAt[player.id] ?: 0L,
            )
        }
        val shown = PlayerRules.order(views).mapNotNull { id -> found.firstOrNull { (_, player) -> player.id == id } }
        coverOfPlayer.keys.retainAll(shown.map { (_, player) -> player.id }.toSet())
        // The one in front first: its cover is the one being looked at, and the link takes a moment for each.
        val players = shown.map { (controller, player) ->
            val before = coverOfPlayer[player.id]
            val art = coverOf(controller)
            if (art != null && art.key != before?.key) onCover(art)
            player.copy(artKey = art?.key.orEmpty())
        }
        // Position changes on every update, so that alone is no reason to send: the watch works out how time passes by itself.
        if (players.map { it.copy(positionMs = 0) } == last.map { it.copy(positionMs = 0) } && !jumped(players)) return
        last = players
        lastSentAt = SystemClock.elapsedRealtime()
        onChange(players)
    }

    /** The moment a player pauses counts as well: that is what keeps it in front of the ones that have been waiting longer. */
    private fun notePlaying(player: MediaPlayer) {
        if (player.playing == player.id in wasPlaying) return
        playedAt[player.id] = SystemClock.elapsedRealtime()
        if (player.playing) wasPlaying += player.id else wasPlaying -= player.id
    }

    /** Someone moved through the track, so the ring on the watch would be wrong from here on. */
    private fun jumped(players: List<MediaPlayer>): Boolean = players.any { now ->
        val before = last.firstOrNull { it.id == now.id } ?: return@any false
        val expected = before.positionMs + if (before.playing) SystemClock.elapsedRealtime() - lastSentAt else 0
        abs(now.positionMs - expected) > JUMP_MS
    }

    private fun positionNow(state: PlaybackState): Long {
        // What a player reports is where it was at its last update, which can be long ago.
        val sinceUpdate = if (state.state == PlaybackState.STATE_PLAYING) SystemClock.elapsedRealtime() - state.lastPositionUpdateTime else 0
        return (state.position + (sinceUpdate * state.playbackSpeed).toLong()).coerceAtLeast(0)
    }

    /** Only makes a new image when the track changed: players also report every pause and every jump in position. */
    private fun coverOf(controller: MediaController): Message.MediaArt? {
        val id = controller.packageName
        if (id !in staleCovers) return coverOfPlayer[id]
        staleCovers -= id
        val bitmap = controller.metadata?.let { metadata -> COVER_KEYS.firstNotNullOfOrNull { metadata.getBitmap(it) } }
        val art = bitmap?.let { source -> runCatching { smallCover(source, id) }.getOrNull() }
        if (art == null) coverOfPlayer -= id else coverOfPlayer[id] = art
        return art
    }

    private fun smallCover(source: Bitmap, playerId: String): Message.MediaArt? {
        val side = min(source.width, source.height)
        val square = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
        val small = Bitmap.createScaledBitmap(square, COVER_PIXELS, COVER_PIXELS, true)
        for (quality in COVER_QUALITIES) {
            val out = ByteArrayOutputStream()
            small.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, out)
            val image = out.toByteArray()
            // Named after what is in it: players send the same cover again as a new object, and that should not cross the link twice.
            if (image.size <= Message.MediaArt.MAX_BYTES) return Message.MediaArt(image.contentHashCode().toUInt().toString(16), image, playerId)
        }
        return null
    }

    private fun toPlayer(controller: MediaController): MediaPlayer? {
        val metadata = controller.metadata ?: return null
        val state = controller.playbackState
        val packageId = controller.packageName
        val label = runCatching {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageId, 0)).toString()
        }.getOrDefault(packageId)
        return MediaPlayer(
            id = packageId,
            app = label,
            title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
            album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            playing = state?.state == PlaybackState.STATE_PLAYING,
            positionMs = state?.let(::positionNow) ?: 0L,
            durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0),
        )
    }

    private companion object {
        val COVER_KEYS = listOf(MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON)

        /** The size it has on the watch, at the density of the Pixel Watch. */
        const val COVER_PIXELS = 152
        const val JUMP_MS = 3_000L
        val NOTHING_TO_OFFER = setOf(PlaybackState.STATE_NONE, PlaybackState.STATE_STOPPED, PlaybackState.STATE_ERROR)
        val COVER_QUALITIES = intArrayOf(70, 50, 30)
    }
}
