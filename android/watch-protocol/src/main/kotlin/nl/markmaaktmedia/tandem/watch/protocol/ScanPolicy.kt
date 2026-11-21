package nl.markmaaktmedia.tandem.watch.protocol

/**
 * How hard the watch looks for its phone after it lost it. Scanning is the one part of the link that costs while nothing is going on, so
 * the speed follows how likely the phone is to be right there: fastest in the first minute, because that is mostly the app on the phone
 * restarting for an update and it advertises again within seconds, quicker while it is still likely close, and then easy.
 *
 * The fast minute is not repeated within [FAST_COOLDOWN_MS]. A link that comes and goes (a phone at the edge of the range, or an app
 * that keeps being stopped) would otherwise be looked for at full speed for a minute after every drop, which is most of the time.
 *
 * A phone that has been gone for [AWAY_MS] (left at home, switched off, or the app stopped) is not about to be back within seconds, and
 * a scan that never stops is the one thing that costs all day while nothing happens. From there the scan looks for [LOOK_MS] and rests
 * for [REST_MS]; the watch also looks at once when its screen comes on, because then someone may be about to use it.
 */
object ScanPolicy {
    enum class Speed { LOW_LATENCY, BALANCED, LOW_POWER }

    const val FAST_MS = 60_000L
    const val RECENT_MS = 2 * 60_000L
    const val FAST_COOLDOWN_MS = 10 * 60_000L

    /** A link that lasted this long was a real one: what went wrong before it is forgotten, and the next attempt is quick again. */
    const val STABLE_MS = 2 * 60_000L

    const val AWAY_MS = 30 * 60_000L
    const val LOOK_MS = 30_000L
    const val REST_MS = 2 * 60_000L

    /** A scan that runs for hours is not guaranteed to keep delivering results, so a long one is started again now and then. */
    const val CYCLE_MS = 10 * 60_000L

    /** Android allows a few scan starts per half minute: never restart one sooner than this. */
    const val MIN_STINT_MS = 7_000L

    /** Someone waking the screen to look for the phone is asked again at most this often, so a screen that keeps lighting does not become a scan that never stops. */
    const val NUDGE_EVERY_MS = 60_000L

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

    /** How long the scan that starts now runs before the client looks at the policy again. */
    fun stintMs(lostAt: Long, nowMs: Long, fastFor: Long?): Long {
        val since = nowMs - lostAt
        val stint = when (speed(lostAt, nowMs, fastFor)) {
            Speed.LOW_LATENCY -> FAST_MS - since
            Speed.BALANCED -> RECENT_MS - since
            // Up to the moment the long absence begins, so the look after it starts from a clean cycle.
            Speed.LOW_POWER -> if (since >= AWAY_MS) LOOK_MS else minOf(CYCLE_MS, AWAY_MS - since)
        }
        return stint.coerceAtLeast(MIN_STINT_MS)
    }

    /** After a scan stint ended: how long to wait before the next one, or null to go on at once. */
    fun restMs(lostAt: Long, nowMs: Long): Long? = if (nowMs - lostAt >= AWAY_MS) REST_MS else null
}
