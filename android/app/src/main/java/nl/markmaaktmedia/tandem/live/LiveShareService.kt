package nl.markmaaktmedia.tandem.live

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.LifecycleService
import nl.markmaaktmedia.tandem.MainActivity
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.graph
import uniffi.tandem_core.TandemMediaEnd
import uniffi.tandem_core.TandemMediaFacing
import uniffi.tandem_core.TandemMediaKind

/**
 * Keeps the capture alive while it is shown. Android lets an app record the screen or use the camera in the background
 * only from a foreground service of the right type, with a notification the person can see, so this is that and little
 * else: the sharing itself lives in [LiveShare].
 *
 * It is a lifecycle service because the camera is bound to a lifecycle, and this one is it for as long as it runs.
 */
class LiveShareService : LifecycleService() {
    private val live get() = graph.live

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> live.stopAll()
            ACTION_BEGIN -> begin(intent)
            else -> refresh()
        }
        return START_NOT_STICKY
    }

    private fun begin(intent: Intent) {
        val kind = runCatching { TandemMediaKind.valueOf(intent.getStringExtra(EXTRA_KIND).orEmpty()) }.getOrNull() ?: return refresh()
        val peer = intent.getStringExtra(EXTRA_PEER).orEmpty()
        val session = intent.getLongExtra(EXTRA_SESSION, 0).takeIf { it != 0L }?.toULong()
        val facing = runCatching { TandemMediaFacing.valueOf(intent.getStringExtra(EXTRA_FACING).orEmpty()) }.getOrDefault(TandemMediaFacing.ANY)

        // The system wants the notification and the type before it hands out the screen or the camera.
        val type = when (kind) {
            TandemMediaKind.SCREEN -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            TandemMediaKind.CAMERA -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        } or (if (live.isSharingAny()) currentType else 0)
        try {
            ServiceCompat.startForeground(this, LiveNotifications.ACTIVE_ID, notification(getString(R.string.live_active_starting)), type)
            currentType = type
        } catch (e: Exception) {
            Log.w(TAG, "could not go to the foreground", e)
            session?.let { live.deny(it, TandemMediaEnd.UNAVAILABLE) }
            stopSelf()
            return
        }
        val projection = if (kind == TandemMediaKind.SCREEN) {
            val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
            if (data == null) null else runCatching { getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data) }.getOrNull()
        } else {
            null
        }
        if (kind == TandemMediaKind.SCREEN && projection == null) {
            session?.let { live.deny(it, TandemMediaEnd.UNAVAILABLE) }
            refresh()
            return
        }
        live.begin(this, kind, peer, session, projection, facing)
    }

    private var currentType = 0

    private fun notification(text: String) = LiveNotifications.active(
        this, text,
        open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
        stop = PendingIntent.getBroadcast(
            this, 2, Intent(this, LiveActionReceiver::class.java).setAction(LiveActionReceiver.STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        ),
    )

    /** The notification and the pill follow what is shown; with nothing left the service goes. Main thread. */
    fun refresh() {
        val text = live.summary()
        if (text == null) {
            live.indicator.hide()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        getSystemService(NotificationManager::class.java).notify(LiveNotifications.ACTIVE_ID, notification(text))
        if (live.indicatorWanted && live.indicator.canShow()) live.indicator.show(text) { live.stopAll() } else live.indicator.hide()
    }

    override fun onDestroy() {
        live.indicator.hide()
        live.serviceGone(this)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TandemLive"
        const val ACTION_BEGIN = "nl.markmaaktmedia.tandem.LIVE_BEGIN"
        const val ACTION_STOP = "nl.markmaaktmedia.tandem.LIVE_STOP"
        const val EXTRA_KIND = "kind"
        const val EXTRA_PEER = "peer"
        const val EXTRA_SESSION = "session"
        const val EXTRA_FACING = "facing"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        /** Starts the sharing once the person and the system have agreed. Called while an activity of Tandem is on screen. */
        fun begin(
            context: Context, kind: TandemMediaKind, peer: String, session: ULong?, facing: TandemMediaFacing,
            resultCode: Int = 0, resultData: Intent? = null,
        ) {
            val intent = Intent(context, LiveShareService::class.java).setAction(ACTION_BEGIN)
                .putExtra(EXTRA_KIND, kind.name).putExtra(EXTRA_PEER, peer).putExtra(EXTRA_SESSION, session?.toLong() ?: 0L)
                .putExtra(EXTRA_FACING, facing.name).putExtra(EXTRA_RESULT_CODE, resultCode).putExtra(EXTRA_RESULT_DATA, resultData)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
