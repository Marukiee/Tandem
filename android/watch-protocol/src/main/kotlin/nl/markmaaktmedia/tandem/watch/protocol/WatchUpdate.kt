package nl.markmaaktmedia.tandem.watch.protocol

import java.security.MessageDigest

/**
 * The watch app goes along with the phone app: a phone with a newer version sends the watch the same version, in parts over the link,
 * and the watch installs it once it is not recording. What is on the watch survives an interruption: the next connect goes on from
 * where it stopped.
 */
object WatchUpdate {
    /** About a second of the link per part, so a notification or a call never waits long behind one. */
    const val PART_BYTES = 16 * 1024

    /** The watch has it all but records now: it installs as soon as the recording ends. */
    const val WAITING = 0
    const val INSTALLING = 1
    /** Android wants a tap on the watch for this one. */
    const val CONFIRM = 2
    const val FAILED = 3
    /** The watch has this version or a newer one already. */
    const val UP_TO_DATE = 4

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** The part to send for [want], or null when there is nothing to send: a wrong version, or the watch has it all. */
    fun partFor(want: Message.UpdateWant, code: Long, file: ByteArray): Message.UpdatePart? {
        if (want.code != code || want.offset < 0 || want.offset >= file.size) return null
        val start = want.offset.toInt()
        val end = minOf(file.size, start + PART_BYTES)
        return Message.UpdatePart(code, want.offset, file.copyOfRange(start, end))
    }
}
