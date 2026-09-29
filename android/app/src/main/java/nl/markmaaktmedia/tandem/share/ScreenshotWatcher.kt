package nl.markmaaktmedia.tandem.share

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.data.TandemPrefs
import nl.markmaaktmedia.tandem.engine.ActionReceiver
import nl.markmaaktmedia.tandem.engine.Channels
import nl.markmaaktmedia.tandem.engine.EngineHost
import nl.markmaaktmedia.tandem.engine.Permissions

/**
 * Notices a new screenshot and offers to send it: a heads-up notification with a
 * button per device. Your devices are one tap away instead of three.
 */
class ScreenshotWatcher(
    private val context: Context,
    private val host: EngineHost,
    private val prefs: TandemPrefs,
    private val scope: CoroutineScope,
) {
    private var lastHandled = 0L
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            scope.launch { inspect() }
        }
    }

    fun start() {
        context.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
    }

    fun stop() {
        context.contentResolver.unregisterContentObserver(observer)
    }

    private suspend fun inspect() {
        if (!prefs.screenshotPrompt.first() || !Permissions.photos(context) || !Channels.canPost(context)) return
        val online = host.devices.value.filter { it.online }
        if (online.isEmpty()) return

        val uri = latestScreenshot() ?: return
        val thumbnail = runCatching { context.contentResolver.loadThumbnail(uri, Size(720, 720), null) }.getOrNull()
        post(uri, online.map { it.id to it.name }, thumbnail)
    }

    /** The newest screenshot from the last few seconds, if it has not been offered yet. */
    private fun latestScreenshot(): Uri? {
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.RELATIVE_PATH, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.IS_PENDING)
        val since = System.currentTimeMillis() / 1000 - 12
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection,
            "${MediaStore.Images.Media.DATE_ADDED} >= ?", arrayOf(since.toString()),
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val path = cursor.getString(2).orEmpty()
                val name = cursor.getString(3).orEmpty()
                val pending = cursor.getInt(4)
                val isScreenshot = path.contains("Screenshot", true) || name.startsWith("Screenshot", true)
                if (!isScreenshot || pending != 0 || id <= lastHandled) continue
                lastHandled = id
                return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            }
        }
        return null
    }

    private fun post(uri: Uri, devices: List<Pair<String, String>>, thumbnail: Bitmap?) {
        fun send(ids: List<String>, request: Int) = PendingIntent.getBroadcast(
            context, request,
            Intent(context, ActionReceiver::class.java).setAction(ActionReceiver.SEND_SCREENSHOT)
                .putExtra(ActionReceiver.EXTRA_URI, uri.toString())
                .putExtra(ActionReceiver.EXTRA_TARGETS, ids.toTypedArray()),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val choose = PendingIntent.getActivity(
            context, uri.hashCode(),
            Intent(context, ShareTargetActivity::class.java).setAction(Intent.ACTION_SEND).setType("image/png")
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(context, Channels.SCREENSHOT)
            .setSmallIcon(R.drawable.ic_stat_tandem)
            .setContentTitle(context.getString(R.string.screenshot_title))
            .setContentText(context.getString(R.string.screenshot_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setTimeoutAfter(25_000)
            .setOnlyAlertOnce(true)
        if (thumbnail != null) {
            builder.setLargeIcon(thumbnail)
            builder.setStyle(NotificationCompat.BigPictureStyle().bigPicture(thumbnail).bigLargeIcon(null as Bitmap?))
        }

        // At most three buttons: a device each when there are few, otherwise "all" and "choose".
        when (devices.size) {
            1 -> {
                builder.addAction(0, context.getString(R.string.screenshot_to, devices[0].second), send(listOf(devices[0].first), 1))
                builder.addAction(0, context.getString(R.string.screenshot_choose), choose)
            }
            2 -> {
                builder.addAction(0, context.getString(R.string.screenshot_to, devices[0].second), send(listOf(devices[0].first), 1))
                builder.addAction(0, context.getString(R.string.screenshot_to, devices[1].second), send(listOf(devices[1].first), 2))
                builder.addAction(0, context.getString(R.string.share_all), send(devices.map { it.first }, 3))
            }
            else -> {
                builder.addAction(0, context.getString(R.string.share_all), send(devices.map { it.first }, 3))
                builder.addAction(0, context.getString(R.string.screenshot_choose), choose)
            }
        }
        context.getSystemService(NotificationManager::class.java).notify(SCREENSHOT_ID, builder.build())
    }

    companion object {
        const val SCREENSHOT_ID = 3
    }
}
