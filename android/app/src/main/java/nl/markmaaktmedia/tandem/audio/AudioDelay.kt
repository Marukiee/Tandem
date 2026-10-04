package nl.markmaaktmedia.tandem.audio

/**
 * How much sound the phone keeps waiting before the speaker. Waiting less is closer to live and gives up some steadiness:
 * a late packet then has less time to arrive and is heard as a short hiccup. Waiting more rides out a slow network and
 * is heard as a later sound. The sound itself is the same in all three, nothing is compressed.
 */
enum class AudioDelay(
    /** What is written to the speaker before it starts. */
    val prebufferMs: Long,
    /** What the speaker holds at most. */
    val trackMs: Int,
    /** Above this much waiting, the oldest sound is dropped down to [keepMs]. */
    val maxMs: Int,
    val keepMs: Int,
) {
    LOW(prebufferMs = 30, trackMs = 100, maxMs = 150, keepMs = 60),
    NORMAL(prebufferMs = 90, trackMs = 200, maxMs = 320, keepMs = 120),
    SMOOTH(prebufferMs = 200, trackMs = 400, maxMs = 600, keepMs = 260),
    ;

    companion object {
        val Default = NORMAL
        fun fromIndex(index: Int): AudioDelay = entries.getOrElse(index) { Default }
    }
}
