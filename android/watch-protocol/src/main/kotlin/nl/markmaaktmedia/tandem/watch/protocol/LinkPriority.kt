package nl.markmaaktmedia.tandem.watch.protocol

/**
 * How often the watch and the phone exchange a packet while nothing is sent. A link that stays up all day costs most in these empty
 * exchanges: every 30 to 50 ms on the balanced setting, every 100 to 125 ms on low power. So the link rests on low power, and is balanced
 * where someone could be waiting for it: the screen is on, or a transfer is under way. The pointer asks for the fastest on its own.
 *
 * What it costs: a message that arrives while the screen is off is seen up to a tenth of a second later, and the first moments of a
 * transfer go at the slow rate until [BURST_CHUNKS] show that it is one. A link that keeps dropping while it rests is put back to
 * balanced for a while, because then the saving is not worth it. Pure logic; the client does the asking and says what the stack did.
 */
class LinkPriorityRules {
    enum class Level { HIGH, BALANCED, LOW_POWER }

    /** What the link was last asked for. A new link starts on the balanced setting of the stack. */
    var applied: Level = Level.BALANCED
        private set

    private var screenOn = false
    private var fast = false
    private var activeUntil = 0L
    private var bannedUntil = 0L
    private val recent = LongArray(BURST_CHUNKS)
    private var recentCount = 0
    private val drops = ArrayDeque<Long>()

    /** A connection came up: its first moments are busy with the setup, the state of the phone and its lists. */
    fun linkUp(nowMs: Long) {
        applied = Level.BALANCED
        recentCount = 0
        activeUntil = nowMs + AFTER_UP_MS
    }

    /** The connection ended without the watch ending it. A link that dies while resting is what [DROPS_BEFORE_BAN] counts. */
    fun linkDown(nowMs: Long, restingWhenDropped: Boolean = applied == Level.LOW_POWER) {
        if (restingWhenDropped) {
            drops.addLast(nowMs)
            while (drops.isNotEmpty() && nowMs - drops.first() > DROP_WINDOW_MS) drops.removeFirst()
            if (drops.size >= DROPS_BEFORE_BAN) {
                bannedUntil = nowMs + BAN_MS
                drops.clear()
            }
        }
        applied = Level.BALANCED
    }

    /** One chunk went over the link, in either direction. A few close together are a transfer, one on its own is a message. */
    fun traffic(nowMs: Long) {
        if (recentCount == BURST_CHUNKS) {
            System.arraycopy(recent, 1, recent, 0, BURST_CHUNKS - 1)
            recentCount--
        }
        recent[recentCount++] = nowMs
        if (recentCount == BURST_CHUNKS && nowMs - recent[0] <= BURST_WINDOW_MS) activeUntil = maxOf(activeUntil, nowMs + SETTLE_MS)
    }

    fun screen(on: Boolean, nowMs: Long) {
        screenOn = on
        // Someone who just put their wrist down may still be waiting for what they pressed.
        if (!on) activeUntil = maxOf(activeUntil, nowMs + SETTLE_MS)
    }

    fun fast(on: Boolean) {
        fast = on
    }

    fun wanted(nowMs: Long): Level = when {
        fast -> Level.HIGH
        screenOn || nowMs < activeUntil || nowMs < bannedUntil -> Level.BALANCED
        else -> Level.LOW_POWER
    }

    /** The level to ask the link for now, or null when it already has it. */
    fun change(nowMs: Long): Level? {
        val level = wanted(nowMs)
        return level.takeIf { it != applied }
    }

    /** The stack took the request. */
    fun applied(level: Level) {
        applied = level
    }

    /** When to look again because time passing could change [wanted], or null when only an event could. */
    fun checkInMs(nowMs: Long): Long? = when {
        fast || screenOn || applied != Level.BALANCED -> null
        nowMs < activeUntil -> activeUntil - nowMs
        nowMs < bannedUntil -> bannedUntil - nowMs
        else -> 0L
    }

    companion object {
        /** Quiet this long, and the screen off, before the link rests. */
        const val SETTLE_MS = 15_000L
        const val AFTER_UP_MS = 30_000L
        const val BURST_CHUNKS = 4
        const val BURST_WINDOW_MS = 2_000L
        const val DROPS_BEFORE_BAN = 3
        const val DROP_WINDOW_MS = 60 * 60_000L
        const val BAN_MS = 12 * 60 * 60_000L
    }
}
