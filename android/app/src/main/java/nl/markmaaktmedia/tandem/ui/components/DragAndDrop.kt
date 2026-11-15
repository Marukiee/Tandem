package nl.markmaaktmedia.tandem.ui.components

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.view.View
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

/**
 * Files of another app dropped on something that sends them (R-D4 a). [onFiles] gets the content addresses of what was dropped. The
 * grant that comes with a drop ends with the screen unless it is asked for, so it is asked for here, and let go of when [onFiles]
 * is done: a file is opened at once and held by the engine, so nothing needs the grant after that.
 */
@Composable
fun Modifier.fileDropTarget(
    onOver: (Boolean) -> Unit = {},
    onFiles: suspend (List<Uri>) -> Unit,
): Modifier {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val over by rememberUpdatedState(onOver)
    val files by rememberUpdatedState(onFiles)
    val target = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) = over(true)
            override fun onExited(event: DragAndDropEvent) = over(false)
            override fun onEnded(event: DragAndDropEvent) = over(false)
            override fun onDrop(event: DragAndDropEvent): Boolean {
                over(false)
                val drag = event.toAndroidDragEvent()
                val clip = drag.clipData ?: return false
                val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }.filter { it.scheme == "content" || it.scheme == "file" }
                // Text and the like have no file to send: the drop is refused, so the other app shows it came back.
                if (uris.isEmpty()) return false
                val permissions = context.activity()?.requestDragAndDropPermissions(drag)
                scope.launch {
                    try {
                        files(uris)
                    } finally {
                        permissions?.release()
                    }
                }
                return true
            }
        }
    }
    return dragAndDropTarget(shouldStartDragAndDrop = { event -> event.toAndroidDragEvent().clipDescription != null }, target = target)
}

/**
 * Lets files that came in be dragged out of Tandem into another app, with a long press (R-D4 b). Only a file that is in Downloads
 * through the media store has an address another app may read; the grant is for that one drop. Without [locations] nothing drags.
 */
@Composable
fun Modifier.fileDragSource(locations: List<String>?): Modifier {
    val context = LocalContext.current
    val uris = locations?.filter { it.startsWith("content://") }?.map(Uri::parse).orEmpty()
    if (uris.isEmpty()) return this
    return dragAndDropSource { _ ->
        val clip = ClipData.newUri(context.contentResolver, uris.first().lastPathSegment ?: "file", uris.first())
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        DragAndDropTransferData(clip, flags = View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_GLOBAL_URI_READ)
    }
}

private fun Context.activity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
