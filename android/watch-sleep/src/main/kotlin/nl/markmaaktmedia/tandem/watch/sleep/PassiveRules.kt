package nl.markmaaktmedia.tandem.watch.sleep

/** One heart rate value from the passive monitoring of the watch itself, with whether its sensor stood behind it. */
data class PassiveHeart(val timeMs: Long, val bpm: Int, val reliable: Boolean)

/** One value for a slot of five minutes: the time of the newest sample in it, and the middle value of what could be trusted. */
data class SlotHeart(val timeMs: Long, val bpm: Int)

/**
 * What to do with heart rate that the watch measures for everyone (Health Services). Our own measurement of half a minute every five is
 * only needed for the slots where nothing good came from there, so the light of the sensor is not switched on twice for the same wrist.
 */
object PassiveHeartRules {
    const val SLOT_MS = DayHeart.MINUTES_PER_SLOT * 60_000L

    /**
     * How old the newest good value may be for our own measurement to stay off. Delivery comes in batches, and the newest value of a batch
     * is as old as the batch is long, so this is a few slots and not one.
     */
    const val FRESH_MS = 20 * 60_000L

    /**
     * A delivery can hold many values, and a nap or the day curve are made of one per slot: the middle value of the usable ones in each
     * slot, oldest slot first. Not usable is a sensor that has no skin to look at, or one that says it does not know.
     */
    fun perSlot(samples: List<PassiveHeart>): List<SlotHeart> =
        samples
            .filter { it.reliable && it.bpm in DayHeart.MIN_BPM..DayHeart.MAX_BPM }
            .groupBy { it.timeMs / SLOT_MS }
            .toSortedMap()
            .values
            .map { slot ->
                val sorted = slot.map { it.bpm }.sorted()
                SlotHeart(slot.maxOf { it.timeMs }, sorted[sorted.size / 2])
            }

    /** Whether the tick should measure itself: nothing good has come from the system lately, or the clock moved back. */
    fun needsOwnMeasurement(lastGoodMs: Long, nowMs: Long): Boolean =
        lastGoodMs <= 0 || nowMs - lastGoodMs > FRESH_MS || lastGoodMs - nowMs > SLOT_MS
}

/**
 * Who counts the steps of the day. Health Services keeps the total of the day for everyone and delivers it in batches; the step counter
 * of the watch stays registered next to it for little (it never wakes the processor) and only counts when Health Services stays silent
 * for hours while the counter moves, so a day does not stay at zero on a watch that registers fine and then says nothing.
 *
 * Two sources count from two baselines: a total of the day and a total since the watch started. [Feed.baseline] says what the running
 * total of [DailySteps] has to be made equal to before the next value is counted, so a switch never counts a day twice.
 */
class StepFeed(
    fedBy: Source? = null,
    private val silentMs: Long = SILENT_MS,
) {
    enum class Source { HEALTH_SERVICES, COUNTER }

    /** Count [total] of [source]; when [baseline] is not null the last total of the day first becomes that. */
    data class Feed(val source: Source, val total: Long, val baseline: Long?)

    /** The source of the last value counted. Kept on disk, because the total in [DailySteps] belongs to it. */
    var fedBy: Source? = fedBy
        private set

    private var registered = false
    private var registeredAtMs = 0L
    private var lastDailyMs = -1L
    private var counter = -1L
    private var counterAtDaily = -1L
    private var counterMovedMs = -1L

    /** Health Services accepted what was asked of it. */
    fun registered(nowMs: Long) {
        if (registered) return
        registered = true
        registeredAtMs = nowMs
        lastDailyMs = -1
        counterAtDaily = counter
    }

    /** It did not, or it took the permission back: the counter counts again. */
    fun unregistered() {
        registered = false
    }

    /** Who should count at [nowMs]. */
    fun counting(nowMs: Long): Source {
        if (!registered) return Source.COUNTER
        val since = if (lastDailyMs >= 0) lastDailyMs else registeredAtMs
        val silent = nowMs - since > silentMs && counterMovedMs > since
        return if (silent) Source.COUNTER else Source.HEALTH_SERVICES
    }

    /** The total of the day from Health Services. Always counted: a delivery proves it is registered and speaking. */
    fun onDaily(total: Long, nowMs: Long): Feed {
        if (!registered) registered(nowMs)
        lastDailyMs = nowMs
        counterAtDaily = counter
        return feed(Source.HEALTH_SERVICES, total)
    }

    /** The counter of the watch. Null while Health Services counts. */
    fun onCounter(total: Long, nowMs: Long): Feed? {
        if (counter >= 0 && total > counter) counterMovedMs = nowMs
        // A smaller total than the last one is a watch that started again: what the counter said before means nothing now.
        if (counter >= 0 && total < counter) counterAtDaily = -1
        counter = total
        return if (counting(nowMs) == Source.COUNTER) feed(Source.COUNTER, total) else null
    }

    private fun feed(source: Source, total: Long): Feed {
        val baseline = when {
            fedBy == source -> null
            source == Source.HEALTH_SERVICES -> -1L
            else -> counterAtDaily
        }
        fedBy = source
        return Feed(source, total, baseline)
    }

    companion object {
        /** Long enough for a delivery that comes in big batches, short enough that a silent source costs no more than an afternoon. */
        const val SILENT_MS = 2 * 60 * 60_000L
    }
}

/**
 * Counts what [feed] says. The first total of Health Services for a day is all of that day, so it counts from zero: the total of the
 * day before says nothing about it, and a day that starts with 9,500 steps because the watch was away all night would otherwise lose
 * the 9,000 it is compared with.
 */
fun DailySteps.count(feed: StepFeed.Feed, today: String, atMinute: Long = -1, atHour: Int = -1): DailySteps {
    val base = when {
        feed.source == StepFeed.Source.HEALTH_SERVICES && day != today -> copy(lastTotal = 0)
        feed.baseline != null -> copy(lastTotal = feed.baseline)
        // A total of the day that goes down is a correction by the system, not a watch that started again: the steps counted stay, and
        // what comes next counts from the lower total. (Only the counter of the watch resets to zero, and that is the other source.)
        feed.source == StepFeed.Source.HEALTH_SERVICES && feed.total < lastTotal -> copy(lastTotal = feed.total)
        else -> this
    }
    return base.update(today, feed.total, atMinute, atHour)
}
