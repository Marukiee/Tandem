package nl.markmaaktmedia.tandem.messages

import uniffi.tandem_core.TandemPhoneContact
import uniffi.tandem_core.TandemPhoneNumber

/**
 * The whole list of contacts for the search of a computer (Spotlight, GNOME), made from the rows of numbers that Android gives: one
 * person per contact with all their numbers, in pages by contact id. Kept apart from Android so it can be tested on its own.
 */
object ContactBook {
    /** One number as Android has it, the label already in words. */
    data class Row(val contact: Long, val name: String, val number: String, val label: String, val mobile: Boolean, val default: Boolean)

    /**
     * The people in [rows] (sorted by contact id, only ids above the cursor) up to [limit], and whether there are more. The number to
     * call without asking is the default one of the person, or else their only mobile number; with two mobiles and no default the
     * computer asks.
     */
    fun page(rows: Sequence<Row>, limit: Int): Pair<List<TandemPhoneContact>, Boolean> {
        val people = mutableListOf<TandemPhoneContact>()
        var current: MutableList<Row>? = null
        var more = false
        fun close() {
            val group = current ?: return
            person(group)?.let { people += it }
            current = null
        }
        for (row in rows) {
            if (row.number.isBlank()) continue
            if (current?.first()?.contact != row.contact) {
                close()
                if (people.size >= limit) {
                    more = true
                    break
                }
                current = mutableListOf()
            }
            current!! += row
        }
        if (!more) close()
        return people to more
    }

    private fun person(rows: List<Row>): TandemPhoneContact? {
        // The same number written twice (with and without the country code) is one number.
        // The last nine digits, as SmsReader matches numbers; not its own function, whose class reaches into Android when loaded.
        val unique = rows.distinctBy { row -> row.number.filter { it.isDigit() }.takeLast(9) }.take(MAX_NUMBERS)
        if (unique.isEmpty()) return null
        val chosen = unique.firstOrNull { it.default } ?: unique.filter { it.mobile }.singleOrNull()
        val name = rows.first().name.ifBlank { unique.first().number }
        return TandemPhoneContact(
            id = rows.first().contact.toULong(),
            name = name,
            numbers = unique.map { TandemPhoneNumber(number = it.number.trim(), label = it.label, preferred = it === chosen) },
        )
    }

    private const val MAX_NUMBERS = 10
}
