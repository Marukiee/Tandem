package nl.markmaaktmedia.tandem.media

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A pretend music app, for trying the music buttons without playing anything real: it publishes a
 * media session like Spotify does and does what the buttons ask. It is in the developer options.
 */
object FakePlayer {
    private var session: MediaSession? = null
    private var index = 0
    private var playing = true
    private var startedAt = 0L
    private var offsetMs = 0L

    private val titles = listOf("Test song", "Another test song", "Third test song")

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    fun start(context: Context) {
        if (session != null) return
        val s = MediaSession(context.applicationContext, "tandem-test")
        s.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = change(playing = true)
            override fun onPause() = change(playing = false)
            override fun onSkipToNext() = skip(1)
            override fun onSkipToPrevious() = skip(-1)
            override fun onSeekTo(pos: Long) {
                offsetMs = pos
                startedAt = SystemClock.elapsedRealtime()
                publish()
            }
        })
        session = s
        index = 0
        playing = true
        offsetMs = 0
        startedAt = SystemClock.elapsedRealtime()
        s.isActive = true
        publish()
        _running.value = true
    }

    fun stop() {
        session?.run {
            isActive = false
            release()
        }
        session = null
        _running.value = false
    }

    private fun position(): Long = offsetMs + if (playing) SystemClock.elapsedRealtime() - startedAt else 0L

    private fun change(playing: Boolean) {
        offsetMs = position().coerceAtMost(DURATION)
        this.playing = playing
        startedAt = SystemClock.elapsedRealtime()
        publish()
    }

    private fun skip(step: Int) {
        index = (index + step).mod(titles.size)
        offsetMs = 0
        startedAt = SystemClock.elapsedRealtime()
        publish()
    }

    private fun publish() {
        val s = session ?: return
        s.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, titles[index])
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Tandem")
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "Test album")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, DURATION)
                .build(),
        )
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO,
                )
                .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, position(), if (playing) 1f else 0f)
                .build(),
        )
    }

    private const val DURATION = 180_000L
}
