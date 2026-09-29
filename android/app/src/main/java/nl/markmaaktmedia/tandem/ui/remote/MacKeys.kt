package nl.markmaaktmedia.tandem.ui.remote

/** The modifier bits of a Key message. */
object Mods {
    const val SHIFT = 1
    const val CTRL = 2
    const val OPTION = 4
    const val COMMAND = 8
}

/** macOS virtual key codes, which is what the Mac side expects in Key messages. */
object MacKeys {
    const val RETURN: Short = 36
    const val TAB: Short = 48
    const val DELETE: Short = 51
    const val ESCAPE: Short = 53
    const val LEFT: Short = 123
    const val RIGHT: Short = 124
    const val DOWN: Short = 125
    const val UP: Short = 126

    /** A physical key and the modifiers a character needs on top of the armed ones. */
    data class Stroke(val code: Short, val mods: Int = 0)

    /**
     * The key that types [char] on an ANSI layout, or null for anything that has no
     * key of its own (accents, emoji, other scripts). Codes are positions, not letters,
     * so on another layout a shortcut lands on the key in the same place, which is how
     * a physical keyboard behaves too.
     */
    fun strokeFor(char: Char): Stroke? {
        if (char == '\n') return Stroke(RETURN)
        if (char in 'A'..'Z') return positions[char.lowercaseChar()]?.let { Stroke(it, Mods.SHIFT) }
        positions[char]?.let { return Stroke(it) }
        return SHIFTED.indexOf(char).takeIf { it >= 0 }?.let { Stroke(positions.getValue(SHIFTED_BASE[it]), Mods.SHIFT) }
    }

    private val positions: Map<Char, Short> = mapOf(
        'a' to 0, 's' to 1, 'd' to 2, 'f' to 3, 'h' to 4, 'g' to 5, 'z' to 6, 'x' to 7,
        'c' to 8, 'v' to 9, 'b' to 11, 'q' to 12, 'w' to 13, 'e' to 14, 'r' to 15,
        'y' to 16, 't' to 17, '1' to 18, '2' to 19, '3' to 20, '4' to 21, '6' to 22,
        '5' to 23, '=' to 24, '9' to 25, '7' to 26, '-' to 27, '8' to 28, '0' to 29,
        ']' to 30, 'o' to 31, 'u' to 32, '[' to 33, 'i' to 34, 'p' to 35, 'l' to 37,
        'j' to 38, '\'' to 39, 'k' to 40, ';' to 41, '\\' to 42, ',' to 43, '/' to 44,
        'n' to 45, 'm' to 46, '.' to 47, '`' to 50, ' ' to 49, '\t' to 48,
    ).mapValues { it.value.toShort() }

    // Symbols that sit on top of another key, and the key they sit on, in the same order.
    private const val SHIFTED = "!@#$%^&*()_+{}|:\"<>?~"
    private const val SHIFTED_BASE = "1234567890-=[]\\;',./`"
}

/** What changed between two versions of the text in the typing field. */
object TextDiff {
    data class Edit(val deleted: Int, val inserted: String)

    /**
     * The smallest edit that turns [old] into [new]: characters removed from the end
     * of the common start, then text added. Comparing the start rather than only the
     * length is what makes an autocorrect that replaces a word arrive as delete then
     * type, instead of being mistaken for typing.
     */
    fun between(old: String, new: String): Edit {
        var common = 0
        val limit = minOf(old.length, new.length)
        while (common < limit && old[common] == new[common]) common++
        // Never cut a surrogate pair in half, or an emoji is deleted as two keys.
        if (common > 0 && common < old.length && old[common].isLowSurrogate()) common--
        val deleted = old.codePointCount(common, old.length)
        return Edit(deleted, new.substring(common))
    }
}
