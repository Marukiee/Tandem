package nl.markmaaktmedia.tandem.ui.screens.settings

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * How strong each day of the month calendar is coloured, apart from Compose so it can be tested. Every strength stays inside 0..1:
 * a colour with an alpha outside it throws, and a month with one value or only equal values would otherwise divide by zero.
 */
internal object CalendarHeat {
    const val SLEEP_GOAL = 420L
    private const val SHARE_FLOOR = 0.15f
    private const val HEART_FLOOR = 0.2f

    /** The usual of a month: the rounded mean of its days with a value. */
    fun usual(values: List<Long?>): Long? {
        val measured = values.filterNotNull()
        if (measured.isEmpty()) return null
        return (measured.sum().toDouble() / measured.size).roundToInt().toLong()
    }

    /** Toward the goal, or without one toward the highest day of the month: from a faint tile for a little to full at the goal. */
    fun shares(values: List<Long?>, goal: Long?): List<Float?> {
        val top = goal?.takeIf { it > 0 } ?: values.filterNotNull().maxOrNull()?.takeIf { it > 0 }
        return values.map { value ->
            if (value == null) null
            else if (top == null) SHARE_FLOOR
            else (SHARE_FLOOR + (1f - SHARE_FLOOR) * (value.toFloat() / top).coerceIn(0f, 1f)).coerceIn(0f, 1f)
        }
    }

    fun met(value: Long?, goal: Long?): Boolean = value != null && goal != null && goal > 0 && value >= goal

    /**
     * The heart against the usual of the month: above it positive, below it negative, by how far compared with the furthest day of
     * that month. Within 1 bpm of the usual it is 0, a neutral tile.
     */
    fun heart(values: List<Long?>): List<Float?> {
        val usual = usual(values) ?: return values.map { null }
        val furthest = values.filterNotNull().maxOfOrNull { abs(it - usual) } ?: 0L
        return values.map { value ->
            if (value == null) return@map null
            val off = value - usual
            if (abs(off) <= 1 || furthest <= 1) 0f
            else {
                val strength = (HEART_FLOOR + (1f - HEART_FLOOR) * (abs(off).toFloat() / furthest)).coerceIn(0f, 1f)
                if (off > 0) strength else -strength
            }
        }
    }

    /** Today when it has a value, otherwise the last day of the month with one. */
    fun defaultPick(values: List<Long?>, today: Int?): Int? =
        if (today != null && values.getOrNull(today) != null) today else values.indexOfLast { it != null }.takeIf { it >= 0 }
}
