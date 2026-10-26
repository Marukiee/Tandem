package nl.markmaaktmedia.tandem.files

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.engine.Channels
import java.util.concurrent.atomic.AtomicInteger

/**
 * How far a file of another device has been read through the Files app (see [TandemDocumentsProvider]), as a notification like the one
 * of a transfer: the Files app copies it, and only Tandem knows how far that is. Small files and the first moment of a read are left out,
 * so opening a picture or making a thumbnail does not flash one by.
 */
internal class ReadProgress(private val context: Context, private val name: String, private val from: String?, private val size: Long) {
    private val id = NEXT_ID.getAndIncrement()
    private val started = SystemClock.elapsedRealtime()
    private var highest = 0L
    private var posted = 0L
    private var shown = false

    @Synchronized
    fun read(end: Long) {
        if (size < MIN_SIZE) return
        highest = maxOf(highest, end)
        val now = SystemClock.elapsedRealtime()
        if (highest >= size) return done()
        if (now - started < WAIT_MS || now - posted < STEP_MS) return
        posted = now
        shown = true
        post((highest * 100 / size).toInt())
    }

    @Synchronized
    fun done() {
        if (!shown) return
        shown = false
        NotificationManagerCompat.from(context).cancel(TAG, id)
    }

    // Without the permission to post nothing shows, which is all there is to it: the copy goes on.
    @SuppressLint("MissingPermission")
    private fun post(percent: Int) {
        val notification = NotificationCompat.Builder(context, Channels.TRANSFERS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(name)
            // The percentage in words too: Android shows a silent notification folded, without its bar.
            .setContentText(from?.let { context.getString(R.string.files_reading_from, percent, it) } ?: "$percent%")
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(TAG, id, notification) }
    }

    private companion object {
        const val TAG = "files-read"
        val NEXT_ID = AtomicInteger(1)
        const val MIN_SIZE = 4L * 1024 * 1024
        const val WAIT_MS = 700L
        const val STEP_MS = 300L
    }
}
