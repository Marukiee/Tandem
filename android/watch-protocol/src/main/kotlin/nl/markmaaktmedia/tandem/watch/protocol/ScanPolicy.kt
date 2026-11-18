package nl.markmaaktmedia.tandem.watch.protocol

/**
 * How hard the watch looks for its phone after it lost it. Scanning is the one part of the link that costs while nothing is going on, so
 * the speed follows how likely the phone is to be right there: fastest in the first minute, because that is mostly the app on the phone
 * restarting for an update and it advertises again within seconds, quicker while it is still likely close, and then easy.
 *
 * The fast minute is not repeated within [FAST_COOLDOWN_MS]. A link that comes and goes (a phone at the edge of the range, or an app
 * that keeps being stopped) would otherwise be looked for at full speed for a minute after every drop, which is most of the time.
 */
object ScanPolicy {
    enum class Speed { LOW_LATENCY, BALANCED, LOW_POWER }

    const val FAST_MS = 60_000L
    const val RECENT_MS = 2 * 60_000L
    const val FAST_COOLDOWN_MS = 10 * 60_000L

    /** A link that lasted this long was a real one: what went wrong before it is forgotten, and the next attempt is quick again. */
    const val STABLE_MS = 2 * 60_000L

    /**
     * [lostAt] is when the link was lost (or the first look began), [nowMs] now, and [fastFor] the [lostAt] of the last loss that got
     * the fast minute, or null when none did.
     */
    fun speed(lostAt: Long, nowMs: Long, fastFor: Long?): Speed {
        val since = nowMs - lostAt
        val fastAllowed = fastFor == null || fastFor == lostAt || lostAt - fastFor >= FAST_COOLDOWN_MS
        return when {
            since < FAST_MS && fastAllowed -> Speed.LOW_LATENCY
            since < RECENT_MS -> Speed.BALANCED
            else -> Speed.LOW_POWER
        }
    }
}
