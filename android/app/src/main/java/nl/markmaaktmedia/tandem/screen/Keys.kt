package nl.markmaaktmedia.tandem.screen

/**
 * Key codes for the wire. A remote desktop sends USB HID usages on the Keyboard page, the same on every platform. The
 * on-screen keyboard of the trackpad screen speaks Mac virtual key codes, and a keyboard on the phone speaks Android's,
 * so both are turned into HID here.
 */
object Keys {
    /** The modifier bits of a Key message: shift 1, control 2, alt 4, meta 8, caps lock 16. */
    const val SHIFT = 1
    const val CTRL = 2
    const val ALT = 4
    const val META = 8

    /** HID usage to Mac virtual key code. The one table; the other directions are made from it. */
    private val hidToMac: Map<Int, Int> = buildMap {
        val letters = intArrayOf(0, 11, 8, 2, 14, 3, 5, 4, 34, 38, 40, 37, 46, 45, 31, 35, 12, 15, 1, 17, 32, 9, 13, 7, 16, 6)
        letters.forEachIndexed { index, code -> put(0x04 + index, code) }
        val digits = intArrayOf(18, 19, 20, 21, 23, 22, 26, 28, 25, 29)
        digits.forEachIndexed { index, code -> put(0x1E + index, code) }
        putAll(
            mapOf(
                0x28 to 36, 0x29 to 53, 0x2A to 51, 0x2B to 48, 0x2C to 49, 0x2D to 27, 0x2E to 24, 0x2F to 33, 0x30 to 30,
                0x31 to 42, 0x33 to 41, 0x34 to 39, 0x35 to 50, 0x36 to 43, 0x37 to 47, 0x38 to 44, 0x39 to 57,
                0x3A to 122, 0x3B to 120, 0x3C to 99, 0x3D to 118, 0x3E to 96, 0x3F to 97, 0x40 to 98, 0x41 to 100,
                0x42 to 101, 0x43 to 109, 0x44 to 103, 0x45 to 111,
                0x49 to 114, 0x4A to 115, 0x4B to 116, 0x4C to 117, 0x4D to 119, 0x4E to 121,
                0x4F to 124, 0x50 to 123, 0x51 to 125, 0x52 to 126,
            ),
        )
    }

    private val macToHid: Map<Int, Int> = hidToMac.entries.associate { it.value to it.key }

    /** The HID usage of a Mac virtual key code, or null for a key this table does not know. */
    fun hidForMac(code: Short): Int? = macToHid[code.toInt()]

    fun macForHid(usage: Int): Int? = hidToMac[usage]

    /**
     * The HID usage of an Android key code (the codes of KeyEvent), for a keyboard attached to the phone. Null for keys
     * that have no place on a Mac keyboard or that are only modifiers, which travel as flags.
     */
    fun hidForAndroid(keyCode: Int): Int? = when (keyCode) {
        in 29..54 -> 0x04 + (keyCode - 29) // A to Z
        in 8..16 -> 0x1E + (keyCode - 8) // 1 to 9
        7 -> 0x27 // 0
        66, 160 -> 0x28 // enter, keypad enter
        111 -> 0x29 // escape
        67 -> 0x2A // backspace
        61 -> 0x2B // tab
        62 -> 0x2C // space
        69 -> 0x2D // minus
        70 -> 0x2E // equals
        71 -> 0x2F // left bracket
        72 -> 0x30 // right bracket
        73 -> 0x31 // backslash
        74 -> 0x33 // semicolon
        75 -> 0x34 // apostrophe
        68 -> 0x35 // grave
        55 -> 0x36 // comma
        56 -> 0x37 // period
        76 -> 0x38 // slash
        115 -> 0x39 // caps lock
        in 131..142 -> 0x3A + (keyCode - 131) // F1 to F12
        124 -> 0x49 // insert
        122 -> 0x4A // home
        92 -> 0x4B // page up
        112 -> 0x4C // forward delete
        123 -> 0x4D // end
        93 -> 0x4E // page down
        22 -> 0x4F // right
        21 -> 0x50 // left
        20 -> 0x51 // down
        19 -> 0x52 // up
        else -> null
    }
}
