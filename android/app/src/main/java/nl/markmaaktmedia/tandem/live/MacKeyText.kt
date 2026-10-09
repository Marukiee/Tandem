package nl.markmaaktmedia.tandem.live

/**
 * The keys of a Mac keyboard that become text on this phone, by their virtual key code (the code that the shared mouse and keyboard of
 * a Mac sends). The layout is the US one: the code is a place on the keyboard, and the Mac does not say what is printed on it.
 */
object MacKeyText {
    private val plain = mapOf(
        0 to "a", 11 to "b", 8 to "c", 2 to "d", 14 to "e", 3 to "f", 5 to "g", 4 to "h", 34 to "i", 38 to "j", 40 to "k", 37 to "l",
        46 to "m", 45 to "n", 31 to "o", 35 to "p", 12 to "q", 15 to "r", 1 to "s", 17 to "t", 32 to "u", 9 to "v", 13 to "w", 7 to "x",
        16 to "y", 6 to "z",
        18 to "1", 19 to "2", 20 to "3", 21 to "4", 23 to "5", 22 to "6", 26 to "7", 28 to "8", 25 to "9", 29 to "0",
        27 to "-", 24 to "=", 33 to "[", 30 to "]", 42 to "\\", 41 to ";", 39 to "'", 43 to ",", 47 to ".", 44 to "/", 50 to "`",
        49 to " ", 36 to "\n",
    )
    private val shifted = mapOf(
        18 to "!", 19 to "@", 20 to "#", 21 to "$", 23 to "%", 22 to "^", 26 to "&", 28 to "*", 25 to "(", 29 to ")",
        27 to "_", 24 to "+", 33 to "{", 30 to "}", 42 to "|", 41 to ":", 39 to "\"", 43 to "<", 47 to ">", 44 to "?", 50 to "~",
    )

    const val ESCAPE = 53
    const val DELETE = 51

    /** The text a key types, or null when it types nothing. Shift gives capitals and the signs above the digits. */
    fun text(code: Int, shift: Boolean): String? {
        val base = plain[code] ?: return null
        if (!shift) return base
        return shifted[code] ?: base.uppercase()
    }
}
