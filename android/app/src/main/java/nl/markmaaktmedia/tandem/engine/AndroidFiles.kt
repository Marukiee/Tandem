package nl.markmaaktmedia.tandem.engine

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import android.util.Log
import uniffi.tandem_core.TandemException
import uniffi.tandem_core.TandemFiles
import java.io.File

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
        // Duplicated under the lock, so a file that is let go at the same moment is never read closed.
        synchronized(lock) { held[source]?.dup() }?.let { copy ->
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
        val target = resolver.insert(collection, values) ?: run {
            temp.delete()
            throw TandemException.Failed("Could not create $name in $folder")
        }
        try {
            resolver.openOutputStream(target)!!.use { output -> temp.inputStream().use { it.copyTo(output, 256 * 1024) } }
            resolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(target, null, null)
            // Nothing will fetch this copy again, and it would stay for days.
            temp.delete()
            throw TandemException.Failed("Could not save $name: ${e.message}")
        }
        temp.delete()
        return target.toString()
    }

    companion object {
        private const val TAG = "TandemFiles"

        /** Far more than one share holds, and far below what a process may have open. */
        private const val MAX_HELD = 1024

        /** The core forgets an offer after six hours; what nobody fetched by then is let go too. */
        private const val OFFER_LIFE_MS = (6 * 60 + 10) * 60_000L

        private val lock = Any()

        // In the order they were opened, so a full table lets go of the oldest one, not of one picked at random.
        private val held = LinkedHashMap<String, ParcelFileDescriptor>()

        /** Per offer: the source of each file, and the devices that still have to fetch it. */
        private class Offer(val sources: List<String>, val waiting: List<MutableSet<String>>, val at: Long)

        private val offers = HashMap<ULong, Offer>()

        // A small file can be fetched before the offer is known here: sendFiles returns after the other device was told.
        private val early = HashMap<Triple<ULong, Int, String>, Long>()

        /** Keeps a file open for sending after its access grant is gone. */
        fun hold(source: String, descriptor: ParcelFileDescriptor) {
            synchronized(lock) {
                held.remove(source)?.close()
                while (held.size >= MAX_HELD) {
                    val oldest = held.keys.first()
                    Log.w(TAG, "too many files open, letting go of $oldest")
                    held.remove(oldest)?.close()
                }
                held[source] = descriptor
            }
        }

        /** The offer [offer] went out with these files to [peers]; each file stays open until every one of them has it. */
        fun offered(offer: ULong, sources: List<String>, peers: List<String>) {
            synchronized(lock) {
                sweep()
                if (peers.isNotEmpty()) {
                    val waiting = sources.indices.map { index -> peers.filterTo(mutableSetOf()) { early.remove(Triple(offer, index, it)) == null } }
                    offers[offer] = Offer(sources, waiting, System.currentTimeMillis())
                }
                sources.forEach(::releaseIfUnused)
            }
        }

        /** The files never went out: nothing will fetch them. */
        fun abandoned(sources: List<String>) {
            synchronized(lock) { sources.forEach(::releaseIfUnused) }
        }

        /**
         * A file of an offer is done for one device. After a checksum the other device rejected it fetches the file again, so
         * only a file that arrived or was cancelled counts.
         */
        fun finished(offer: ULong, index: Int, peer: String, error: String?) {
            // Declined and expired end it for that device as well: it will not fetch the file any more.
            if (error != null && error !in setOf("cancelled", "declined", "expired")) return
            synchronized(lock) {
                val entry = offers[offer]
                if (entry == null) {
                    early[Triple(offer, index, peer)] = System.currentTimeMillis()
                    return
                }
                entry.waiting.getOrNull(index)?.remove(peer)
                if (entry.waiting.all { it.isEmpty() }) offers.remove(offer)
                entry.sources.getOrNull(index)?.let(::releaseLater)
            }
        }

        /** Sending [offer] to [peer] was stopped on this phone: what only that device still wanted is let go. */
        fun cancelled(offer: ULong, peer: String) {
            synchronized(lock) {
                val entry = offers[offer] ?: return
                entry.waiting.forEach { it.remove(peer) }
                if (entry.waiting.all { it.isEmpty() }) offers.remove(offer)
                entry.sources.forEach(::releaseLater)
            }
        }

        private val main = Handler(Looper.getMainLooper())

        // A receiver whose last file failed its check pulls it again, also when its answer got lost and this side counted
        // the file as done: so a file is let go a little later, when no new offer wants it either.
        private fun releaseLater(source: String) {
            main.postDelayed({ synchronized(lock) { releaseIfUnused(source) } }, 2 * 60_000L)
        }

        // Called with the lock held.
        private fun releaseIfUnused(source: String) {
            val wanted = offers.values.any { offer -> offer.sources.indices.any { offer.sources[it] == source && offer.waiting[it].isNotEmpty() } }
            if (!wanted) held.remove(source)?.close()
        }

        // Called with the lock held. A refused offer, or one the other device never fetched, says nothing, so time ends it.
        private fun sweep() {
            val now = System.currentTimeMillis()
            early.entries.removeAll { now - it.value > 60_000L }
            val old = offers.filterValues { now - it.at > OFFER_LIFE_MS }
            if (old.isEmpty()) return
            old.keys.forEach(offers::remove)
            old.values.flatMap { it.sources }.toSet().forEach(::releaseIfUnused)
        }
    }
}
