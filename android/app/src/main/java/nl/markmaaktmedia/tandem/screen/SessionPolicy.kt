package nl.markmaaktmedia.tandem.screen

import uniffi.tandem_core.TandemMediaEnd

/** What a session that ended means for the screen: stay, try again, or tell the person why not. */
enum class Outcome {
    /** The person at the Mac stopped it. */
    StoppedByMac,

    /** The Mac said no, or the person there did. */
    Denied,

    /** Another device is looking at the Mac. */
    Busy,

    /** The Mac cannot, for now: no permission to record the screen, or no such feature. */
    Unavailable,

    /** Nobody answered the question on the Mac. */
    NoAnswer,

    /** The link or the Mac went away. Worth trying again. */
    Retry,
}

object SessionPolicy {
    fun outcome(reason: TandemMediaEnd): Outcome = when (reason) {
        TandemMediaEnd.ENDED -> Outcome.StoppedByMac
        TandemMediaEnd.DECLINED, TandemMediaEnd.POLICY -> Outcome.Denied
        TandemMediaEnd.BUSY -> Outcome.Busy
        TandemMediaEnd.UNSUPPORTED, TandemMediaEnd.UNAVAILABLE -> Outcome.Unavailable
        TandemMediaEnd.TIMEOUT -> Outcome.NoAnswer
        TandemMediaEnd.PEER_GONE, TandemMediaEnd.ERROR, TandemMediaEnd.UNKNOWN_SESSION, TandemMediaEnd.REPLACED -> Outcome.Retry
    }

    /** How long to wait before the [attempt]th new try (the first is 1): quick at first, then calmer. */
    fun retryDelayMs(attempt: Int): Long = when {
        attempt <= 1 -> 600L
        attempt == 2 -> 1_500L
        attempt == 3 -> 3_000L
        attempt == 4 -> 6_000L
        else -> 10_000L
    }

    /** After this many tries in a row without a picture, the screen says the connection is lost. */
    const val MAX_ATTEMPTS = 8

    /**
     * The bitrate to ask for on the [attempt]th try. A link that dropped the session is not one to ask a lot of: each try
     * asks for less, down to a floor, and the Mac's own rate control takes it from there.
     */
    fun maxBitrate(base: Int, attempt: Int): Int {
        var value = base
        repeat((attempt - 1).coerceAtLeast(0)) { value = (value * 0.7f).toInt() }
        return value.coerceAtLeast(MIN_BITRATE)
    }

    const val MIN_BITRATE = 1_500_000
}
