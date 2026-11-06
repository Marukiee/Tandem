package nl.markmaaktmedia.tandem.watch.protocol

object NotificationRules {
    const val MAX_TITLE = 200
    const val MAX_TEXT = 1000
    const val MAX_BUTTONS = 3
    const val MAX_SNAPSHOT = 20
    const val FRESH_MS = 10 * 60_000L

    private val OTP_CONTEXT = Regex(
        "(code|otp|verif|inlog|login|wachtwoord|password|token|tan|pin)",
        RegexOption.IGNORE_CASE,
    )
    private val OTP_DIGITS = Regex("(?<![\\d.,/-])\\d{4,8}(?![\\d.,/-])")

    /** Finds a one-time code. It needs a nearby word like "code", so ordinary numbers are not hidden. */
    fun findOtp(title: String, text: String): String? {
        val all = "$title $text"
        if (!OTP_CONTEXT.containsMatchIn(all)) return null
        return OTP_DIGITS.find(all)?.value
    }

    fun clip(text: String, max: Int): String = when {
        text.length <= max -> text
        // Do not cut a surrogate pair in half: that produces invalid text.
        text[max - 1].isHighSurrogate() -> text.take(max - 1) + "…"
        else -> text.take(max) + "…"
    }

    /**
     * What a resync sends. The list is bounded with the newest first, because at a low MTU a long resync takes
     * seconds. Only the newest unseen notification may buzz, so a reconnect never makes the wrist buzz twenty times.
     */
    fun sync(current: List<NotificationMsg>, alreadyAlerted: (String) -> Boolean, now: Long): List<Message> {
        val newest = current.sortedByDescending { it.ts }.take(MAX_SNAPSHOT)
        val buzz = newest.firstOrNull { !it.silent && !alreadyAlerted(it.key) && now - it.ts < FRESH_MS }
        return listOf<Message>(Message.NotificationSnapshot(newest.map { it.key })) +
            newest.map { Message.Notification(it.copy(silent = it !== buzz)) }
    }

    /** Replaces a code with dots, so the notification still says what it is about. */
    fun maskOtp(text: String, otp: String): String = text.replace(otp, "\u2022".repeat(otp.length))
}

/**
 * Outgoing messages with room for a call to overtake. Messages are sealed only when they leave this queue:
 * a counter sealed earlier would arrive out of order after overtaking, and the receiver would reject it.
 */
class OutboundQueue(private val capacity: Int = 64) {
    private val urgent = ArrayDeque<Message>()
    private val normal = ArrayDeque<Message>()

    val isEmpty: Boolean get() = urgent.isEmpty() && normal.isEmpty()

    fun offer(message: Message) {
        when (message) {
            is Message.Call -> {
                // Only the latest call state means anything.
                urgent.removeAll { it is Message.Call }
                urgent.addLast(message)
            }
            is Message.CallAction -> urgent.addLast(message)
            is Message.MediaArt -> {
                // Skipping through an album: only the cover of where it ended up is worth the airtime.
                normal.removeAll { it is Message.MediaArt }
                normal.addLast(message)
            }
            else -> {
                // The next resync repairs anything dropped here, so a stuck link cannot grow this without limit.
                if (normal.size >= capacity) normal.removeFirst()
                normal.addLast(message)
            }
        }
    }

    fun poll(): Message? = urgent.removeFirstOrNull() ?: normal.removeFirstOrNull()

    fun clear() {
        urgent.clear()
        normal.clear()
    }
}
