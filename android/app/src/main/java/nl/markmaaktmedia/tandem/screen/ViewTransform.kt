package nl.markmaaktmedia.tandem.screen

import kotlin.math.max
import kotlin.math.min

/**
 * Where the picture of the Mac sits on the phone: fitted into the screen with its shape kept, then zoomed and panned.
 * Pure arithmetic, so the rules (a focal point that stays under the fingers, a picture that never leaves the screen,
 * a position that turns back into a place on the Mac) are tested without a device.
 *
 * Positions are in pixels of the view. [panX] and [panY] are how far the centre of the picture is from the centre of
 * the view.
 */
class ViewTransform {
    var viewWidth = 0f
        private set
    var viewHeight = 0f
        private set
    var videoWidth = 0f
        private set
    var videoHeight = 0f
        private set
    var zoom = 1f
        private set
    var panX = 0f
        private set
    var panY = 0f
        private set

    fun setView(width: Float, height: Float) {
        viewWidth = width
        viewHeight = height
        clamp()
    }

    fun setVideo(width: Float, height: Float) {
        videoWidth = width
        videoHeight = height
        clamp()
    }

    /** Pixels of the view per pixel of the picture when the picture just fits. */
    val fitScale: Float
        get() = if (videoWidth <= 0f || videoHeight <= 0f || viewWidth <= 0f || viewHeight <= 0f) 1f
        else min(viewWidth / videoWidth, viewHeight / videoHeight)

    /** Pixels of the view per pixel of the picture now. */
    val scale: Float get() = fitScale * zoom

    val pictureWidth: Float get() = videoWidth * scale
    val pictureHeight: Float get() = videoHeight * scale
    val left: Float get() = (viewWidth - pictureWidth) / 2f + panX
    val top: Float get() = (viewHeight - pictureHeight) / 2f + panY

    fun reset() {
        zoom = 1f
        panX = 0f
        panY = 0f
    }

    fun panBy(dx: Float, dy: Float) {
        panX += dx
        panY += dy
        clamp()
    }

    /** Zooms by [factor] around a point of the view, which stays where it is. */
    fun zoomBy(factor: Float, focalX: Float, focalY: Float) {
        val next = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (next == zoom) return
        // The place on the picture under the fingers, in picture pixels, before and after.
        val px = (focalX - left) / scale
        val py = (focalY - top) / scale
        zoom = next
        val newLeft = focalX - px * scale
        val newTop = focalY - py * scale
        panX = newLeft - (viewWidth - pictureWidth) / 2f
        panY = newTop - (viewHeight - pictureHeight) / 2f
        clamp()
    }

    fun zoomTo(target: Float, focalX: Float, focalY: Float) = zoomBy(target / zoom, focalX, focalY)

    /** A picture smaller than the view stays in the middle; a larger one cannot be dragged away from the edges. */
    private fun clamp() {
        val slackX = max(0f, (pictureWidth - viewWidth) / 2f)
        val slackY = max(0f, (pictureHeight - viewHeight) / 2f)
        panX = panX.coerceIn(-slackX, slackX)
        panY = panY.coerceIn(-slackY, slackY)
    }

    /** A point of the view as a fraction of the picture, 0 to 1, kept inside it. */
    fun toPicture(x: Float, y: Float): Pair<Float, Float> {
        if (pictureWidth <= 0f || pictureHeight <= 0f) return 0.5f to 0.5f
        return ((x - left) / pictureWidth).coerceIn(0f, 1f) to ((y - top) / pictureHeight).coerceIn(0f, 1f)
    }

    /** A fraction of the picture as a point of the view. */
    fun toView(fx: Float, fy: Float): Pair<Float, Float> = (left + fx * pictureWidth) to (top + fy * pictureHeight)

    /**
     * Moves the view just far enough that a place on the picture is on the screen with some room around it. For a
     * pointer that is pushed to an edge of a zoomed picture, which would otherwise leave the screen.
     */
    fun keepVisible(fx: Float, fy: Float, margin: Float) {
        val (x, y) = toView(fx, fy)
        var dx = 0f
        var dy = 0f
        if (x < margin) dx = margin - x else if (x > viewWidth - margin) dx = viewWidth - margin - x
        if (y < margin) dy = margin - y else if (y > viewHeight - margin) dy = viewHeight - margin - y
        if (dx != 0f || dy != 0f) panBy(dx, dy)
    }

    companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 6f
    }
}
