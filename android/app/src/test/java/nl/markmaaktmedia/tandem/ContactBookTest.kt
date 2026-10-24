package nl.markmaaktmedia.tandem

import nl.markmaaktmedia.tandem.messages.ContactBook
import nl.markmaaktmedia.tandem.messages.ContactBook.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactBookTest {
    private fun row(contact: Long, number: String, mobile: Boolean = true, default: Boolean = false, name: String = "Anna") =
        Row(contact, name, number, if (mobile) "Mobile" else "Work", mobile, default)

    @Test
    fun numbersOfOnePersonGoTogetherAndAPageStopsAtAPerson() {
        val rows = sequenceOf(row(3, "+31 6 1111 1111"), row(3, "0611111111"), row(3, "+31201234567", mobile = false), row(5, "+31622222222"), row(8, "+31633333333"))
        val (page, more) = ContactBook.page(rows, 2)
        assertEquals(listOf(3uL, 5uL), page.map { it.id })
        assertTrue(more)
        // The same number with and without the country code is one.
        assertEquals(2, page[0].numbers.size)
        val (all, done) = ContactBook.page(rows, 10)
        assertEquals(3, all.size)
        assertFalse(done)
    }

    @Test
    fun theNumberToCallWithoutAskingIsTheDefaultOrTheOnlyMobile() {
        val onlyMobile = ContactBook.page(sequenceOf(row(1, "+31611111111"), row(1, "+31201111111", mobile = false)), 10).first[0]
        assertEquals(listOf(true, false), onlyMobile.numbers.map { it.preferred })

        val twoMobiles = ContactBook.page(sequenceOf(row(1, "+31611111111"), row(1, "+31622222222")), 10).first[0]
        assertTrue(twoMobiles.numbers.none { it.preferred })

        val chosen = ContactBook.page(sequenceOf(row(1, "+31611111111"), row(1, "+31622222222", default = true)), 10).first[0]
        assertEquals(listOf(false, true), chosen.numbers.map { it.preferred })
    }

    @Test
    fun aPersonWithoutANameIsCalledByTheirNumberAndOneWithoutANumberIsLeftOut() {
        val (page, _) = ContactBook.page(sequenceOf(row(1, " "), row(2, "+31611111111", name = "")), 10)
        assertEquals(listOf("+31611111111"), page.map { it.name })
    }
}
