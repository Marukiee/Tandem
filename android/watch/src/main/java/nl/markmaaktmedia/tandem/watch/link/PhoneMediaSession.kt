package nl.markmaaktmedia.tandem.watch.link

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import nl.markmaaktmedia.tandem.watch.R
import nl.markmaaktmedia.tandem.watch.protocol.MediaCommandKind
import nl.markmaaktmedia.tandem.watch.protocol.MediaPlayer
import nl.markmaaktmedia.tandem.watch.protocol.Message
import nl.markmaaktmedia.tandem.watch.ui.PlayerActivity

/**
 * The music on the phone as a media session of this watch. Wear OS then treats it like music on the watch itself: its own controls
 * and the crown work on it, which it otherwise only does for a phone that runs Google's companion app. Nothing plays here: every
 * press goes to the phone.
 */
class PhoneMediaSession(private val context: Context, private val send: (String, MediaCommandKind) -> Unit) {
    private val session = MediaSession(context, "TandemPhone")
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val ownPlayer = PendingIntent.getActivity(context, 0, Intent(context, PlayerActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    private val fromWatchFace = systemPlayer()?.let { PendingIntent.getActivity(context, 1, it, PendingIntent.FLAG_IMMUTABLE) } ?: ownPlayer
    private var player: MediaPlayer? = null
    private var metadataOf: Triple<String, String, String>? = null
    private var coverImage: Bitmap? = null
    private var postedFor: Pair<Triple<String, String, String>, Boolean>? = null

    init {
        session.setCallback(
            object : MediaSession.Callback() {
                override fun onPlay() = pressIf(player?.playing == false, MediaCommandKind.PLAY_PAUSE)

                override fun onPause() = pressIf(player?.playing == true, MediaCommandKind.PLAY_PAUSE)

                override fun onStop() = pressIf(player?.playing == true, MediaCommandKind.PLAY_PAUSE)

                override fun onSkipToNext() = pressIf(true, MediaCommandKind.NEXT)

                override fun onSkipToPrevious() = pressIf(true, MediaCommandKind.PREVIOUS)
            },
        )
        // This is what the icon on the watch face opens, as measured on a Pixel Watch 2. The notification below does not decide that.
        session.setSessionActivity(fromWatchFace)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_music), NotificationManager.IMPORTANCE_MIN),
        )
        // The phone knows how loud it is; here there is only louder and softer, which is all a crown says anyway.
        session.setPlaybackToRemote(
            object : VolumeProvider(VOLUME_CONTROL_RELATIVE, VOLUME_STEPS, VOLUME_STEPS / 2) {
                override fun onAdjustVolume(direction: Int) {
                    if (direction > 0) pressIf(true, MediaCommandKind.VOLUME_UP)
                    if (direction < 0) pressIf(true, MediaCommandKind.VOLUME_DOWN)
                }
            },
        )
    }

    /** What the icon on the watch face opens: the system's own player opens from that icon with an animation that an app cannot make. */
    private fun systemPlayer(): Intent? =
        Intent(ACTION_MEDIA_CONTROLS).setPackage(SYSTEM_MEDIA_APP).takeIf { context.packageManager.resolveActivity(it, 0) != null }

    private fun pressIf(wanted: Boolean, kind: MediaCommandKind) {
        val id = player?.id ?: return
        if (wanted) send(id, kind)
    }

    /** What the screens of the system show: the same player as the screen of this app, or nothing. */
    fun show(shown: MediaPlayer?, cover: Message.MediaArt?) {
        player = shown
        if (shown == null) {
            session.isActive = false
            metadataOf = null
            coverImage = null
            postedFor = null
            notifications.cancel(NOTIFICATION_ID)
            return
        }
        val about = Triple(shown.title, shown.artist, cover?.key.orEmpty())
        if (about != metadataOf) {
            metadataOf = about
            coverImage = cover?.let { runCatching { BitmapFactory.decodeByteArray(it.image, 0, it.image.size) }.getOrNull() }
            session.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, shown.title.ifBlank { shown.app })
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, shown.artist)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, shown.album)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, shown.durationMs)
                    .apply { coverImage?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }
                    .build(),
            )
        }
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(ACTIONS)
                .setState(
                    if (shown.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    shown.positionMs,
                    if (shown.playing) 1f else 0f,
                )
                .build(),
        )
        session.isActive = true
        post(shown, about)
    }

    /**
     * The system only looks at a media session that a notification points to: this is what brings the icon on the watch face. As an
     * ongoing activity Wear OS keeps that notification out of the list, where it stood at the top: the music is on the watch face and in
     * the player already. Ongoing while paused too, or the notification would come back into the list at each pause.
     */
    private fun post(shown: MediaPlayer, about: Triple<String, String, String>) {
        val now = about to shown.playing
        if (now == postedFor) return
        postedFor = now
        val title = shown.title.ifBlank { shown.app }
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.sym_music_note)
            .setContentTitle(title)
            .setContentText(shown.artist)
            .setShowWhen(false)
            .setLargeIcon(coverImage)
            .setContentIntent(ownPlayer)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setLocalOnly(true)
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken))
        // The library fills a builder of its own, which knows no media style: what it adds there is only data in the extras, copied onto
        // this one, so the media session stays where the system looks for it.
        val ongoing = NotificationCompat.Builder(context, CHANNEL)
        OngoingActivity.Builder(context, NOTIFICATION_ID, ongoing)
            .setStaticIcon(R.drawable.sym_music_note)
            .setTouchIntent(fromWatchFace)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setTitle(title)
            .build()
            .apply(context)
        notification.addExtras(ongoing.extras)
        notifications.notify(NOTIFICATION_ID, notification.build())
    }

    private companion object {
        const val ACTION_MEDIA_CONTROLS = "com.google.android.wearable.action.MEDIA_CONTROLS"
        const val SYSTEM_MEDIA_APP = "com.google.android.wearable.media.sessions"
        const val CHANNEL = "music"
        /** Not 300: that is the recording, whose notification the music then replaced, and took away again when it stopped. */
        const val NOTIFICATION_ID = 350
        const val VOLUME_STEPS = 20
        const val ACTIONS = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP
    }
}
