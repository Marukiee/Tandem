package nl.markmaaktmedia.tandem.share

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import java.io.File

/**
 * A picture that was copied (or a screenshot) on another device, on this phone's clipboard. The core keeps it in its own folder
 * instead of Downloads (`kept_for_clipboard`), and Android's clipboard only holds a link to it, so the file stays while the
 * clipboard points at it: until the next picture or text comes in, or an hour has gone (when Android clears its clipboard too).
 * Never more than one, so they do not pile up.
 */
object ClipboardPictures {
    private const val KEPT_MS = 60 * 60 * 1000L
    private val main = Handler(Looper.getMainLooper())
    private var current: File? = null

    /** True for a single picture that waits in the core's clipboard folder. */
    fun waits(location: String): Boolean {
        if (location.startsWith("content://") || location.startsWith("file://")) return false
        return File(location).parentFile?.parentFile?.name == FOLDER
    }

    fun put(context: Context, location: String, label: String): Boolean {
        val file = File(location)
        val uri: Uri = runCatching { FileProvider.getUriForFile(context, authority(context), file) }.getOrNull() ?: return false
        val clip = ClipData.newUri(context.contentResolver, label, uri)
        val placed = runCatching { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip) }.isSuccess
        if (!placed) {
            remove(file)
            return false
        }
        synchronized(this) {
            current?.takeIf { it != file }?.let(::remove)
            current = file
        }
        main.postDelayed({ forget(file) }, KEPT_MS)
        return true
    }

    /** Something else went on the clipboard from here: the picture is no longer what it holds. */
    fun movedOn() {
        synchronized(this) { current?.let(::remove); current = null }
    }

    private fun forget(file: File) = synchronized(this) {
        remove(file)
        if (current == file) current = null
    }

    private fun remove(file: File) {
        file.delete()
        file.parentFile?.delete()
    }

    private fun authority(context: Context) = "${context.packageName}.clipboard"

    /** The folder the core keeps them in, in its data folder (see `CLIPBOARD_DIR` in transfer.rs). */
    private const val FOLDER = "clipboard"
}
