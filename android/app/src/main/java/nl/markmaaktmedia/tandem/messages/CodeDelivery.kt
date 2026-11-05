package nl.markmaaktmedia.tandem.messages

import kotlinx.coroutines.delay
import nl.markmaaktmedia.tandem.engine.EngineHost
import uniffi.tandem_core.TandemNotification

/**
 * Gets a code to the computers. It comes in by two ways, the notification and the message itself, and goes out once. A code is the one thing
 * that is worth waiting for: a phone in a pocket often has no connection at the moment the text arrives, so it is tried again while it can
 * still be used. It stays in memory only, and is never logged.
 */
internal object CodeDelivery {
    private const val ONCE_MS = 2 * 60_000L
    private const val USABLE_MS = 3 * 60_000L
    private const val RETRY_MS = 3_000L
    private val AGAIN_MS = listOf(10_000L, 30_000L, 75_000L, 120_000L)

    private val sent = HashMap<String, Long>()
    private var last = 0L

    /** True the first time [code] is seen, and marks it. */
    @Synchronized
    fun firstTime(code: String): Boolean {
        val now = System.currentTimeMillis()
        sent.values.removeAll { now - it > ONCE_MS }
        last = now
        return sent.put(code, now) == null
    }

    /** A code went out a moment ago, so a notification Android emptied at about the same time has nothing to add. */
    @Synchronized
    fun justSent(withinMs: Long): Boolean = System.currentTimeMillis() - last < withinMs

    /**
     * Sends until every device in [targets] has it, and a few more times after that: the core only knows that it wrote to the link, and a link
     * can be dead for a minute before anyone notices (a phone that was asleep, a computer that went away). The computers copy and show a code
     * once, however often it comes.
     */
    suspend fun deliver(host: EngineHost, targets: List<String>, notification: TandemNotification, onReached: () -> Unit = {}) {
        val start = System.currentTimeMillis()
        var left = targets
        var repeats = 0
        while (true) {
            val reached = runCatching { host.engine?.sendNotification(left, notification) }.getOrNull().orEmpty()
            if (reached.isNotEmpty()) onReached()
            left = left - reached.toSet()
            val age = System.currentTimeMillis() - start
            if (age >= USABLE_MS || (left.isEmpty() && repeats == AGAIN_MS.size)) return
            delay(RETRY_MS)
            if (repeats < AGAIN_MS.size && System.currentTimeMillis() - start >= AGAIN_MS[repeats]) {
                left = targets
                repeats++
            }
        }
    }
}
