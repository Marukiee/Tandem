package nl.markmaaktmedia.tandem.live

import uniffi.tandem_core.TandemEdge

/**
 * Where the pointer of a computer's mouse is on this screen, counted from the place it came in. Pure counting, so it can be tested:
 * the movements go in, the place and whether the pointer ran back out past the edge it came in by come out.
 */
class PointerTrack(
    private val width: Float,
    private val height: Float,
    /** The side of this screen that the pointer came in by. */
    val cameInBy: TandemEdge,
    startX: Float,
    startY: Float,
) {
    var x = startX.coerceIn(0f, width - 1)
        private set
    var y = startY.coerceIn(0f, height - 1)
        private set

    /**
     * The pointer moves by [dx], [dy] pixels. Returns how far along the edge it came in by the pointer was when it ran out past it
     * (0 to 1), or null while it stays. A pointer that is held ([hold]: a drag) is stopped at the edge instead and stays.
     */
    fun move(dx: Float, dy: Float, hold: Boolean): Float? {
        val ax = x + dx
        val ay = y + dy
        val out = when (cameInBy) {
            TandemEdge.LEFT -> ax < 0
            TandemEdge.RIGHT -> ax > width - 1
            TandemEdge.TOP -> ay < 0
            TandemEdge.BOTTOM -> ay > height - 1
        }
        val along = when (cameInBy) {
            TandemEdge.LEFT, TandemEdge.RIGHT -> (ay / (height - 1).coerceAtLeast(1f)).coerceIn(0f, 1f)
            TandemEdge.TOP, TandemEdge.BOTTOM -> (ax / (width - 1).coerceAtLeast(1f)).coerceIn(0f, 1f)
        }
        x = ax.coerceIn(0f, width - 1)
        y = ay.coerceIn(0f, height - 1)
        return if (out && !hold) along else null
    }

    companion object {
        /** The place where the pointer arrives, when it leaves the other computer by [edge] of its screen, [along] of the way down. */
        fun enter(width: Float, height: Float, edge: TandemEdge, along: Float): PointerTrack {
            val a = along.coerceIn(0f, 1f)
            return when (edge) {
                TandemEdge.RIGHT -> PointerTrack(width, height, TandemEdge.LEFT, 0f, a * (height - 1))
                TandemEdge.LEFT -> PointerTrack(width, height, TandemEdge.RIGHT, width - 1, a * (height - 1))
                TandemEdge.BOTTOM -> PointerTrack(width, height, TandemEdge.TOP, a * (width - 1), 0f)
                TandemEdge.TOP -> PointerTrack(width, height, TandemEdge.BOTTOM, a * (width - 1), height - 1)
            }
        }
    }
}
