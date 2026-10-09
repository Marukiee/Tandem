package nl.markmaaktmedia.tandem

import nl.markmaaktmedia.tandem.engine.TransferItem
import nl.markmaaktmedia.tandem.ui.components.FileKind
import nl.markmaaktmedia.tandem.ui.components.TransferDirection
import nl.markmaaktmedia.tandem.ui.components.TransferFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferFilterTest {
    private fun item(name: String, peer: String = "mac", incoming: Boolean = true, state: TransferItem.State = TransferItem.State.Done) =
        TransferItem(id = name + peer, peer = peer, name = name, done = 1, total = 1, incoming = incoming, state = state)

    private val items = listOf(
        item("holiday.JPG"),
        item("clip.mp4", peer = "linux", incoming = false),
        item("notes.pdf", state = TransferItem.State.Failed),
        item("backup.zip", peer = "linux"),
        item("README"),
    )

    @Test
    fun a_file_kind_comes_from_the_end_of_its_name_whatever_the_case() {
        assertEquals(FileKind.Pictures, FileKind.of("holiday.JPG"))
        assertEquals(FileKind.Videos, FileKind.of("clip.mp4"))
        assertEquals(FileKind.Documents, FileKind.of("notes.pdf"))
        assertEquals(FileKind.Archives, FileKind.of("backup.zip"))
        assertEquals(FileKind.Other, FileKind.of("README"))
    }

    @Test
    fun nothing_chosen_lets_everything_through() {
        val filter = TransferFilter()
        assertFalse(filter.active)
        assertEquals(items, items.filter(filter::matches))
    }

    @Test
    fun the_choices_narrow_down_together() {
        assertEquals(listOf("holiday.JPG"), items.filter(TransferFilter(kind = FileKind.Pictures)::matches).map { it.name })
        assertEquals(listOf("clip.mp4", "backup.zip"), items.filter(TransferFilter(peer = "linux")::matches).map { it.name })
        assertEquals(listOf("clip.mp4"), items.filter(TransferFilter(direction = TransferDirection.Sent)::matches).map { it.name })
        assertEquals(listOf("notes.pdf"), items.filter(TransferFilter(failedOnly = true)::matches).map { it.name })
        val both = TransferFilter(peer = "linux", direction = TransferDirection.Received)
        assertTrue(both.active)
        assertEquals(listOf("backup.zip"), items.filter(both::matches).map { it.name })
    }
}
