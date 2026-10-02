package nl.markmaaktmedia.tandem.hotspot

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The mobile data the hotspot used, kept per day. Nothing in here touches Android, so it is tested on the JVM.
 *
 * Days are stored as `2026-10-02=123456;2026-10-01=789`, newest first, so a few weeks of history cost a few hundred
 * bytes in the preferences and an older entry can simply be cut off.
 */
object HotspotUsage {
    /** How many days are kept. */
    const val KEEP_DAYS = 60

    fun dayKey(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().toString()

    /** A stored string back to days. A piece that does not parse is skipped: a damaged counter must not be fatal. */
    fun decode(text: String?): Map<String, Long> {
        if (text.isNullOrBlank()) return emptyMap()
        val days = LinkedHashMap<String, Long>()
        for (piece in text.split(';')) {
            val at = piece.indexOf('=')
            if (at <= 0) continue
            val day = piece.substring(0, at)
            val bytes = piece.substring(at + 1).toLongOrNull() ?: continue
            if (bytes < 0 || runCatching { LocalDate.parse(day) }.isFailure) continue
            days[day] = (days[day] ?: 0L) + bytes
        }
        return days
    }

    /** Newest first, and no more than [KEEP_DAYS]. */
    fun encode(days: Map<String, Long>): String =
        days.entries
            .sortedByDescending { it.key }
            .take(KEEP_DAYS)
            .joinToString(";") { "${it.key}=${it.value}" }

    /** [bytes] more on [day], with the days that fall out of the history dropped. */
    fun add(days: Map<String, Long>, day: String, bytes: Long): Map<String, Long> {
        if (bytes <= 0) return days
        val next = LinkedHashMap(days)
        next[day] = (next[day] ?: 0L) + bytes
        return decode(encode(next))
    }

    /**
     * What a counter that starts again at zero when the phone restarts (the system's mobile byte count) added between
     * two readings. A first reading, [previous] below zero, adds nothing: there is nothing to compare it with. A
     * reading lower than the one before means a restart, and everything since is new.
     */
    fun delta(previous: Long, current: Long): Long = when {
        previous < 0 || current < 0 -> 0L
        current >= previous -> current - previous
        else -> current
    }

    /** The days to list, newest first: those with something used, at most [count]. */
    fun recent(days: Map<String, Long>, count: Int): List<Pair<String, Long>> =
        days.entries.filter { it.value > 0 }.sortedByDescending { it.key }.take(count).map { it.key to it.value }
}
