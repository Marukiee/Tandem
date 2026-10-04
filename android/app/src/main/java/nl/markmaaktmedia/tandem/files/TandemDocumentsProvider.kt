package nl.markmaaktmedia.tandem.files

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.engine.EngineState
import nl.markmaaktmedia.tandem.graph
import uniffi.tandem_core.TandemDevice
import uniffi.tandem_core.TandemEngine
import uniffi.tandem_core.TandemException
import uniffi.tandem_core.TandemFsEntry
import uniffi.tandem_core.TandemPlatform
import java.io.File
import java.io.FileNotFoundException

/**
 * The files of the other devices in the Files app of the system, and in the file picker of every other app: each device
 * that shares its files is a place there, next to the storage of the phone itself.
 *
 * A document is named `<device id>|<path on that device>`, and the path is the one the core speaks: `/` is the list of
 * folders that device shares, and `/Name/folder/file` is below it. The device decides what is seen and what may be
 * changed, so this only translates between the two.
 */
class TandemDocumentsProvider : DocumentsProvider() {

    private val thread by lazy { HandlerThread("tandem-documents").also { it.start() } }
    private val handler by lazy { Handler(thread.looper) }

    private val appContext: Context get() = requireNotNull(context)
    private val authority: String get() = appContext.packageName + ".documents"

    override fun onCreate(): Boolean = true

    // ---- Where the files are --------------------------------------------------------

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: ROOT_COLUMNS)
        // The process may have been started just for this: the engine comes up, and the roots are told when it has.
        appContext.graph.host.start()
        for (device in places(appContext)) {
            val icon = when (device.platform) {
                TandemPlatform.ANDROID -> R.drawable.sym_devices
                TandemPlatform.MAC_OS -> R.drawable.sym_laptop_mac
                else -> R.drawable.sym_computer
            }
            result.put(
                mapOf(
                    Root.COLUMN_ROOT_ID to device.id,
                    Root.COLUMN_DOCUMENT_ID to docId(device.id, "/"),
                    Root.COLUMN_TITLE to device.name,
                    Root.COLUMN_SUMMARY to summary(device),
                    Root.COLUMN_ICON to icon,
                    Root.COLUMN_FLAGS to (Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD),
                    Root.COLUMN_MIME_TYPES to "*/*",
                ),
            )
        }
        return result
    }

    private fun summary(device: TandemDevice): String = when (device.platform) {
        TandemPlatform.MAC_OS -> "Mac"
        TandemPlatform.WINDOWS -> "Windows"
        TandemPlatform.LINUX -> "Linux"
        TandemPlatform.ANDROID -> "Android"
        else -> ""
    }

    // ---- Looking --------------------------------------------------------------------

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        val doc = parse(documentId)
        if (doc.path == "/") {
            val name = appContext.graph.host.device(doc.device)?.name ?: doc.device
            result.row(documentId, name, null, Document.MIME_TYPE_DIR, 0, 0, 0)
        } else {
            val entry = call { it.fsStat(doc.device, doc.path) }
            result.add(documentId, entry, depth(doc.path))
        }
        return result
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val result = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        val parent = parse(parentDocumentId)
        try {
            val entries = call { it.fsList(parent.device, parent.path) }
            val depth = depth(parent.path) + 1
            for (entry in entries) result.add(docId(parent.device, child(parent.path, entry.name)), entry, depth)
        } catch (e: Exception) {
            Log.w(TAG, "could not list $parentDocumentId", e)
            // The Files app shows this where the list would be, so a device that says no is explained instead of looking empty.
            result.setExtras(Bundle().apply { putString(DocumentsContract.EXTRA_ERROR, explain(e)) })
        }
        result.setNotificationUri(appContext.contentResolver, DocumentsContract.buildChildDocumentsUri(authority, parentDocumentId))
        return result
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = parse(parentDocumentId)
        val doc = parse(documentId)
        if (parent.device != doc.device) return false
        return parent.path == "/" || doc.path.startsWith(parent.path + "/")
    }

    override fun getDocumentType(documentId: String): String {
        val doc = parse(documentId)
        if (doc.path == "/") return Document.MIME_TYPE_DIR
        val entry = call { it.fsStat(doc.device, doc.path) }
        return if (entry.dir) Document.MIME_TYPE_DIR else mimeOf(entry.name)
    }

    // ---- Opening --------------------------------------------------------------------

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val doc = parse(documentId)
        val access = ParcelFileDescriptor.parseMode(mode)
        if (access == ParcelFileDescriptor.MODE_READ_ONLY) return openForReading(doc)
        return openForWriting(doc, mode, access)
    }

    /** Reads what is asked for, when it is asked for, so a big file opens at once and only travels as far as it is read. */
    private fun openForReading(doc: Doc): ParcelFileDescriptor {
        val size = call { it.fsStat(doc.device, doc.path) }.size.toLong()
        val storage = appContext.getSystemService(StorageManager::class.java)
        return storage.openProxyFileDescriptor(
            ParcelFileDescriptor.MODE_READ_ONLY,
            object : ProxyFileDescriptorCallback() {
                override fun onGetSize(): Long = size

                override fun onRead(offset: Long, length: Int, data: ByteArray): Int {
                    if (offset >= size) return 0
                    val wanted = minOf(length.toLong(), size - offset)
                    val bytes = try {
                        call { it.fsRead(doc.device, doc.path, offset.toULong(), wanted.toULong()) }
                    } catch (e: Exception) {
                        Log.w(TAG, "read failed", e)
                        throw ErrnoException("onRead", OsConstants.EIO)
                    }
                    bytes.copyInto(data, 0, 0, bytes.size)
                    return bytes.size
                }

                override fun onRelease() {}
            },
            handler,
        )
    }

    /**
     * A file is written on this phone and goes to the device when it is closed, as one piece: the device never has half
     * of it, and a program that seeks around in the file it writes gets what it expects.
     */
    private fun openForWriting(doc: Doc, mode: String, access: Int): ParcelFileDescriptor {
        val local = File.createTempFile("doc", ".part", appContext.cacheDir)
        val keepsOld = mode.contains('a') || (mode.contains('r') && !mode.contains('t'))
        if (keepsOld) runCatching { call { it.fsDownload(doc.device, doc.path, local.path, null) } }
        return ParcelFileDescriptor.open(local, access, handler) { error ->
            try {
                if (error == null) {
                    call { it.fsUpload(doc.device, local.path, doc.path, true, null) }
                    changed(parentOf(docId(doc.device, doc.path)))
                } else {
                    Log.w(TAG, "write closed with an error", error)
                }
            } catch (e: Exception) {
                Log.w(TAG, "could not put ${doc.path} on the device", e)
            } finally {
                local.delete()
            }
        }
    }

    // ---- Changing -------------------------------------------------------------------

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = parse(parentDocumentId)
        val taken = call { it.fsList(parent.device, parent.path) }.map { it.name }.toSet()
        val name = freeName(displayName, taken)
        val path = child(parent.path, name)
        if (mimeType == Document.MIME_TYPE_DIR) {
            call { it.fsMkdir(parent.device, path) }
        } else {
            val empty = File.createTempFile("new", ".part", appContext.cacheDir)
            try {
                call { it.fsUpload(parent.device, empty.path, path, false, null) }
            } finally {
                empty.delete()
            }
        }
        changed(parentDocumentId)
        return docId(parent.device, path)
    }

    override fun deleteDocument(documentId: String) {
        val doc = parse(documentId)
        call { it.fsRemove(doc.device, doc.path, true) }
        changed(parentOf(documentId))
    }

    override fun renameDocument(documentId: String, displayName: String): String? {
        val doc = parse(documentId)
        val parentPath = doc.path.substringBeforeLast('/').ifEmpty { "/" }
        val target = child(parentPath, displayName)
        if (target == doc.path) return null
        call { it.fsRename(doc.device, doc.path, target, false) }
        changed(parentOf(documentId))
        return docId(doc.device, target)
    }

    // ---- Helpers --------------------------------------------------------------------

    private class Doc(val device: String, val path: String)

    private fun parse(documentId: String): Doc {
        val cut = documentId.indexOf('|')
        if (cut <= 0) throw FileNotFoundException("not a document: $documentId")
        return Doc(documentId.substring(0, cut), documentId.substring(cut + 1).ifEmpty { "/" })
    }

    private fun docId(device: String, path: String) = "$device|$path"

    private fun child(path: String, name: String) = (if (path == "/") "" else path) + "/" + name

    private fun parentOf(documentId: String): String {
        val doc = parse(documentId)
        return docId(doc.device, doc.path.substringBeforeLast('/').ifEmpty { "/" })
    }

    /** How deep a path is: `/` is 0, a shared folder 1. The shared folders themselves can be neither removed nor renamed. */
    private fun depth(path: String) = path.split('/').count { it.isNotEmpty() }

    private fun mimeOf(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    }

    private fun freeName(wanted: String, taken: Set<String>): String {
        if (wanted !in taken) return wanted
        val base = wanted.substringBeforeLast('.', wanted)
        val extension = if (wanted.contains('.')) "." + wanted.substringAfterLast('.') else ""
        return generateSequence(2) { it + 1 }.map { "$base ($it)$extension" }.first { it !in taken }
    }

    private fun MatrixCursor.add(id: String, entry: TandemFsEntry, depth: Int) {
        val changeable = !entry.readonly
        var flags = 0
        if (entry.dir) {
            if (changeable) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        } else if (changeable) {
            flags = flags or Document.FLAG_SUPPORTS_WRITE
        }
        if (changeable && depth > 1) flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        row(id, entry.name, null, if (entry.dir) Document.MIME_TYPE_DIR else mimeOf(entry.name), flags, entry.size.toLong(), entry.modifiedMs.toLong())
    }

    private fun MatrixCursor.row(id: String, name: String, summary: String?, mime: String, flags: Int, size: Long, modified: Long) {
        put(
            mapOf(
                Document.COLUMN_DOCUMENT_ID to id,
                Document.COLUMN_DISPLAY_NAME to name,
                Document.COLUMN_MIME_TYPE to mime,
                Document.COLUMN_FLAGS to flags,
                Document.COLUMN_SIZE to if (mime == Document.MIME_TYPE_DIR) null else size,
                Document.COLUMN_LAST_MODIFIED to if (modified == 0L) null else modified,
                Document.COLUMN_SUMMARY to summary,
            ),
        )
    }

    /** A row with the columns this cursor was asked for, in its order: the system may ask for fewer than there are. */
    private fun MatrixCursor.put(values: Map<String, Any?>) {
        val row = newRow()
        for (column in columnNames) row.add(values[column])
    }

    private fun changed(parentDocumentId: String) {
        appContext.contentResolver.notifyChange(DocumentsContract.buildChildDocumentsUri(authority, parentDocumentId), null)
    }

    /** What went wrong, in a sentence a person can read. The core already words what the device said. */
    private fun explain(e: Exception): String = when {
        e is TandemException.Files -> e.reason
        e is TandemException.NotConnected -> appContext.getString(R.string.docs_not_connected)
        else -> e.message ?: e.toString()
    }

    /** Runs one call against the engine from a thread that may wait, and waits for the engine if it is still coming up. */
    private fun <T> call(block: suspend (TandemEngine) -> T): T = runBlocking(Dispatchers.IO) {
        val host = appContext.graph.host
        host.start()
        val engine = withTimeoutOrNull(10_000) {
            host.state.first { it == EngineState.Running }
            host.engine
        } ?: throw FileNotFoundException(appContext.getString(R.string.docs_not_ready))
        try {
            block(engine)
        } catch (e: TandemException.Files) {
            if (e.code == "not_found") throw FileNotFoundException(e.reason)
            throw e
        }
    }

    companion object {
        private const val TAG = "TandemDocuments"

        private val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY,
            Root.COLUMN_ICON, Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES,
        )
        private val DOCUMENT_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_SUMMARY,
        )

        /** Opens the Files app on the files of one device. */
        fun open(context: Context, deviceId: String) {
            val root = DocumentsContract.buildRootUri(context.packageName + ".documents", deviceId)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(root, Root.MIME_TYPE_ITEM)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }

        /** The devices that offer their files and can be reached now. A device that is away has no place in the list. */
        fun places(context: Context): List<TandemDevice> =
            context.graph.host.devices.value.filter { it.online && "files" in it.caps }
    }
}
