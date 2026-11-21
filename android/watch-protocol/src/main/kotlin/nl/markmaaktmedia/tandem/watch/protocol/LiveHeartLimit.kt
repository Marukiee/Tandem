package nl.markmaaktmedia.tandem.watch.protocol

/**
 * How long the watch measures the heart rate now for a phone or computer that keeps asking. The light of the sensor and a wake-up for
 * every value make this the most expensive thing the watch does, so it must not run all day for a window that nobody looks at any more
 * (a page that stayed open behind other windows asks every half minute for as long as it lives). A person who watches the number gets
 * [MAX_RUN_MS] of it at a time, and then the watch rests before it starts again.
 *
 * Times are from a clock that only goes up. [stopped] is for a request that says off or stays away: what comes after that is a new run.
 */
class LiveHeartLimit(private val maxRunMs: Long = MAX_RUN_MS, private val restMs: Long = REST_MS) {
    private var runSince: Long? = null
    private var restUntil = 0L

    /** A request to measure, at [nowMs]: true when the watch should measure, or go on measuring. */
    fun ask(nowMs: Long): Boolean {
        if (nowMs < restUntil) return false
        val since = runSince ?: nowMs.also { runSince = it }
        if (nowMs - since < maxRunMs) return true
        runSince = null
        restUntil = nowMs + restMs
        return false
    }

    /** Nobody asks any more, or someone said so: whoever asks next starts a run of its own. */
    fun stopped() {
        runSince = null
    }

    companion object {
        const val MAX_RUN_MS = 15 * 60_000L
        const val REST_MS = 30 * 60_000L
    }
}
