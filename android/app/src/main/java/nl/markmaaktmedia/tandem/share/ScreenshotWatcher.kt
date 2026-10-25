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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uniffi.tandem_core.TandemShareOrigin
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.data.TandemPrefs
import nl.markmaaktmedia.tandem.engine.ActionReceiver
import nl.markmaaktmedia.tandem.engine.Channels
import nl.markmaaktmedia.tandem.engine.EngineHost
import nl.markmaaktmedia.tandem.engine.Permissions

/**
 * Notices a new screenshot. It goes straight to the clipboards of the devices that share the clipboard with this phone (when
 * "Send screenshots to your devices" is on), and a heads-up notification offers to send it as a file, with a button per device.
 *
 * MediaStore is the only way in: Android 14's screen capture callback only tells an app about screenshots of its own activity
 * while it is in front, and without the picture (see docs/PARITY.md).
 */
class ScreenshotWatcher(
    private val context: Context,
    private val host: EngineHost,
    private val prefs: TandemPrefs,
    private val scope: CoroutineScope,
) {
    private var lastHandled = 0L
    /** One look at a time: a screenshot changes MediaStore several times, and each change asks for a look. */
    private val looking = Mutex()
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

    private suspend fun inspect() = looking.withLock {
        if (!Permissions.photos(context)) return@withLock
        val online = host.devices.value.filter { it.online }
        if (online.isEmpty()) return@withLock
        val offer = prefs.screenshotPrompt.first() && Channels.canPost(context)
        val clipboards = if (prefs.sendScreenshots.first()) online.filter { it.clipboardEnabled }.map { it.id } else emptyList()
        if (!offer && clipboards.isEmpty()) return@withLock

        val (uri, size) = latestScreenshot() ?: return@withLock
        // As large as the other side takes for its clipboard without asking.
        if (clipboards.isNotEmpty() && size <= EngineHost.CLIPBOARD_FILES_LIMIT) {
            runCatching { host.sendUris(listOf(uri), clipboards, TandemShareOrigin.Clipboard) }
        }
        if (offer) {
            val thumbnail = runCatching { context.contentResolver.loadThumbnail(uri, Size(720, 720), null) }.getOrNull()
            post(uri, online.map { it.id to it.name }, thumbnail)
        }
    }

    /** The newest screenshot from the last few seconds, with its size, if it has not been handled yet. */
    private fun latestScreenshot(): Pair<Uri, Long>? {
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.RELATIVE_PATH, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.IS_PENDING, MediaStore.Images.Media.SIZE)
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
                if (!isScreenshot(path, name) || pending != 0 || id <= lastHandled) continue
                lastHandled = id
                return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id) to cursor.getLong(5)
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

        /**
         * A screenshot is in a Screenshots folder (Pictures/Screenshots, DCIM/Screenshots), or is named like one. Never what is in
         * Downloads: a screenshot that another device sent lands there, and would otherwise be taken for a new one and sent back.
         */
        fun isScreenshot(relativePath: String, name: String): Boolean {
            if (relativePath.startsWith("Download", ignoreCase = true)) return false
            val inFolder = relativePath.split('/').any { it.equals("Screenshots", ignoreCase = true) }
            return inFolder || name.startsWith("Screenshot", ignoreCase = true)
        }

        /**
         * Turns the screenshot notification into a short status line (sending, sent, failed).
         * Same id and onlyAlertOnce, so it replaces the offer without buzzing again.
         */
        fun showStatus(context: Context, title: String, text: String? = null, sending: Boolean = false) {
            val builder = NotificationCompat.Builder(context, Channels.SCREENSHOT)
                .setSmallIcon(R.drawable.ic_stat_tandem)
                .setContentTitle(title)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setTimeoutAfter(if (sending) 30_000 else 5_000)
            text?.let { builder.setContentText(it) }
            if (sending) builder.setProgress(0, 0, true)
            context.getSystemService(NotificationManager::class.java).notify(SCREENSHOT_ID, builder.build())
        }
    }
}
