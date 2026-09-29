package nl.markmaaktmedia.tandem.engine

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import uniffi.tandem_core.TandemException
import uniffi.tandem_core.TandemFiles
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Opening files to send and placing files that arrived.
 *
 * A file shared into the app arrives as a content URI with a grant that is revoked
 * the moment the receiving screen closes, but the Mac pulls the file later. So the
 * screen opens the file at once and hands the open descriptor to [hold]. From then on
 * the file is read through duplicates of that descriptor, which stay valid whatever
 * happens to the grant.
 */
class AndroidFiles(private val context: Context) : TandemFiles {

    override fun openRead(source: String): Int {
        held[source]?.let { original ->
            val copy = original.dup()
            // Duplicates share their read position with the original, and the core
            // always expects to start at the beginning.
            runCatching { Os.lseek(copy.fileDescriptor, 0, OsConstants.SEEK_SET) }
            return copy.detachFd()
        }
        val descriptor = try {
            if (source.startsWith("content://") || source.startsWith("file://")) {
                context.contentResolver.openFileDescriptor(Uri.parse(source), "r")
            } else {
                ParcelFileDescriptor.open(File(source), ParcelFileDescriptor.MODE_READ_ONLY)
            }
        } catch (e: Exception) {
            throw TandemException.Failed("Could not open $source: ${e.message}")
        }
        return (descriptor ?: throw TandemException.Failed("Could not open $source")).detachFd()
    }

    override fun storeDownload(tempPath: String, name: String, mime: String): String {
        val temp = File(tempPath)
        // Everything lands in the ordinary Downloads folder, where people look for it.
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val folder = Environment.DIRECTORY_DOWNLOADS
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime.ifBlank { "application/octet-stream" })
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val target = resolver.insert(collection, values)
            ?: throw TandemException.Failed("Could not create $name in $folder")
        try {
            resolver.openOutputStream(target)!!.use { output -> temp.inputStream().use { it.copyTo(output, 256 * 1024) } }
            resolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(target, null, null)
            throw TandemException.Failed("Could not save $name: ${e.message}")
        }
        temp.delete()
        return target.toString()
    }

    companion object {
        private val held = ConcurrentHashMap<String, ParcelFileDescriptor>()

        /** Keeps a file open for sending after its access grant is gone. */
        fun hold(source: String, descriptor: ParcelFileDescriptor) {
            if (held.size >= 48) held.keys.firstOrNull()?.let { held.remove(it)?.close() }
            held.put(source, descriptor)?.close()
        }

        fun release(source: String) {
            held.remove(source)?.close()
        }
    }
}
