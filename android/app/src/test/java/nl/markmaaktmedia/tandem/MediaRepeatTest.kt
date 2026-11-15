package nl.markmaaktmedia.tandem

import nl.markmaaktmedia.tandem.media.SentPlayers
import nl.markmaaktmedia.tandem.media.isNewsAbout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.tandem_core.TandemMediaPlayer

class MediaRepeatTest {
    private fun player(position: ULong?, playing: Boolean = true, title: String = "Song") = TandemMediaPlayer(
        id = "app", app = "App", title = title, artist = "A", album = "B", playing = playing,
        positionMs = position, durationMs = 200_000uL, canPrev = true, canNext = true, canSeek = true, art = 1uL,
    )

    private val sent = SentPlayers(listOf(player(10_000uL)), at = 1_000)

    @Test fun nothingIsSentToAnEmptyHistory() = assertTrue(isNewsAbout(null, listOf(player(0uL)), 0))

    @Test fun aPositionWhereItWouldBeAnywayIsNothing() {
        assertFalse(isNewsAbout(sent, listOf(player(10_000uL)), 1_000))
        assertFalse(isNewsAbout(sent, listOf(player(40_500uL)), 31_000))
    }

    @Test fun aSeekIsNews() {
        assertTrue(isNewsAbout(sent, listOf(player(90_000uL)), 31_000))
        assertTrue(isNewsAbout(sent, listOf(player(1_000uL)), 31_000))
    }

    @Test fun aPauseAndANewTrackAreNews() {
        assertTrue(isNewsAbout(sent, listOf(player(10_000uL, playing = false)), 1_000))
        assertTrue(isNewsAbout(sent, listOf(player(0uL, title = "Other")), 1_000))
    }

    @Test fun aPausedPlayerDoesNotMove() {
        val paused = SentPlayers(listOf(player(10_000uL, playing = false)), at = 1_000)
        assertFalse(isNewsAbout(paused, listOf(player(10_000uL, playing = false)), 600_000))
        assertTrue(isNewsAbout(paused, listOf(player(15_000uL, playing = false)), 600_000))
    }

    @Test fun aPlayerThatComesOrGoesIsNews() {
        assertTrue(isNewsAbout(sent, emptyList(), 1_000))
        assertTrue(isNewsAbout(sent, listOf(player(10_000uL), player(0uL, title = "Two")), 1_000))
    }
}
