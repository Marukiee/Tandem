package nl.markmaaktmedia.tandem.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.util.LruCache
import android.util.Size
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Small pictures of what was shared, for the list: the picture itself, a frame of a video, the cover of a song, the
 * first page of a PDF. Anything else has no picture and gets an icon for its kind.
 *
 * Nothing here is allowed to fail loudly. A file can be gone, the permission to read a file that was sent from another
 * app ends when that app is done, and a document provider may not know thumbnails; all of that means "no picture".
 */
object Previews {
    private val cache = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** What kind of thing a file is, for the icon and for choosing how to make a picture of it. */
    enum class Kind { Image, Video, Audio, Pdf, Archive, Text, Other }

    fun kindOf(name: String, mime: String?): Kind {
        val type = mime?.takeIf { it != "application/octet-stream" } ?: mimeFromName(name).orEmpty()
        return when {
            type.startsWith("image/") -> Kind.Image
            type.startsWith("video/") -> Kind.Video
            type.startsWith("audio/") -> Kind.Audio
            type == "application/pdf" -> Kind.Pdf
            type.startsWith("text/") -> Kind.Text
            type in ARCHIVES || name.substringAfterLast('.', "").lowercase() in setOf("zip", "rar", "7z", "tar", "gz") -> Kind.Archive
            else -> Kind.Other
        }
    }

    private val ARCHIVES = setOf("application/zip", "application/x-rar-compressed", "application/x-7z-compressed", "application/gzip", "application/x-tar")

    private fun mimeFromName(name: String): String? =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())

    /** A square picture of [uri] about [sizePx] across, or null when there is none to be had. */
    suspend fun load(context: Context, uri: String, name: String, sizePx: Int): Bitmap? {
        val key = "$uri@$sizePx"
        cache.get(key)?.let { return it }
        val made = withContext(Dispatchers.IO) { runCatching { make(context, Uri.parse(uri), name, sizePx) }.getOrNull() }
        if (made != null) cache.put(key, made)
        return made
    }

    private fun make(context: Context, uri: Uri, name: String, sizePx: Int): Bitmap? {
        val resolver = context.contentResolver
        val kind = kindOf(name, resolver.getType(uri))
        val raw: Bitmap? = when (kind) {
            // The system knows how to make these for anything in the media store, and for most document providers.
            Kind.Image, Kind.Video, Kind.Audio -> runCatching { resolver.loadThumbnail(uri, Size(sizePx * 2, sizePx * 2), null) }.getOrNull()
                ?: if (kind == Kind.Video) frameOf(context, uri) else null
            Kind.Pdf -> firstPage(context, uri, sizePx * 2)
            else -> null
        }
        return raw?.let { ThumbnailUtils.extractThumbnail(it, sizePx, sizePx) }
    }

    private fun frameOf(context: Context, uri: Uri): Bitmap? = MediaMetadataRetriever().run {
        try {
            setDataSource(context, uri)
            getFrameAtTime(1_000_000)
        } finally {
            release()
        }
    }

    private fun firstPage(context: Context, uri: Uri, width: Int): Bitmap? {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
        descriptor.use {
            PdfRenderer(it).use { renderer ->
                if (renderer.pageCount == 0) return null
                renderer.openPage(0).use { page ->
                    val scale = width.toFloat() / page.width
                    val bitmap = Bitmap.createBitmap(width, (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    // A page is drawn on transparent paper otherwise.
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return bitmap
                }
            }
        }
    }
}
