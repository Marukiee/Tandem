package nl.markmaaktmedia.tandem.ui.components

import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.runtime.getValue
import nl.markmaaktmedia.tandem.R

/**
 * What can be done with a file in the list of shared things: open it, find it in the Files app, copy where it is, take
 * it off the list, and delete it from this phone. The same five things the Mac offers on a right click.
 */
class TransferActions(
    /** Null when there is nothing to open: the file is still coming, failed, or is not reachable. */
    val open: (() -> Unit)?,
    val showFolder: (() -> Unit)?,
    val copyPath: (() -> Unit)?,
    /** Null while it is being transferred. */
    val remove: (() -> Unit)?,
    /** Deletes the file from this phone, true when it is gone. Only for what was received here. */
    val deleteFile: (() -> Boolean)?,
)

object FileActions {
    /** Opens the file in the app that knows it. */
    fun open(context: Context, location: String) {
        val uri = Uri.parse(location)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { Toast.makeText(context, R.string.transfer_open_failed, Toast.LENGTH_SHORT).show() }
    }

    /** Opens the folder the received files go to, in the Files app. */
    fun showInFiles(context: Context) {
        val folder = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload"), "vnd.android.document/directory")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(folder) }.onFailure {
            // Not every Files app takes a folder: the list of downloads is the next best thing.
            runCatching { context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    /** Where the file is, as a person would write it down. */
    fun displayPath(context: Context, location: String): String {
        val uri = Uri.parse(location)
        if (uri.authority == MediaStore.AUTHORITY) {
            runCatching {
                context.contentResolver.query(
                    uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME), null, null, null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val folder = cursor.getString(0).orEmpty()
                        val name = cursor.getString(1).orEmpty()
                        if (name.isNotEmpty()) return "/storage/emulated/0/$folder$name"
                    }
                }
            }
        }
        return location
    }

    fun copyPath(context: Context, location: String) {
        val manager = context.getSystemService(ClipboardManager::class.java)
        manager.setPrimaryClip(ClipData.newPlainText("path", displayPath(context, location)))
        Toast.makeText(context, R.string.transfer_path_copied, Toast.LENGTH_SHORT).show()
    }

    /** True when the file is gone. A file that is not this app's to delete is left alone. */
    fun delete(context: Context, location: String): Boolean =
        runCatching { context.contentResolver.delete(Uri.parse(location), null, null) > 0 }.getOrDefault(false)
}

/** The five actions for one item of the list, as lambdas that know where the file is. */
@androidx.compose.runtime.Composable
fun rememberTransferActions(item: nl.markmaaktmedia.tandem.engine.TransferItem, onRemove: () -> Unit): TransferActions {
    val context = androidx.compose.ui.platform.LocalContext.current
    val onRemoveNow by androidx.compose.runtime.rememberUpdatedState(onRemove)
    return androidx.compose.runtime.remember(item.id, item.state, item.location, item.source, item.incoming) {
        val done = item.state == nl.markmaaktmedia.tandem.engine.TransferItem.State.Done
        val where = item.location ?: item.source
        val received = done && item.incoming
        TransferActions(
            open = where?.takeIf { done }?.let { { FileActions.open(context, it) } },
            showFolder = item.location?.takeIf { received }?.let { { FileActions.showInFiles(context) } },
            copyPath = where?.takeIf { done }?.let { { FileActions.copyPath(context, it) } },
            remove = if (item.state != nl.markmaaktmedia.tandem.engine.TransferItem.State.Active) ({ onRemoveNow() }) else null,
            deleteFile = item.location?.takeIf { received }?.let { { FileActions.delete(context, it) } },
        )
    }
}
