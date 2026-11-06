package nl.markmaaktmedia.tandem.watch.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayerRulesTest {
    private fun session(id: String, playing: Boolean = false, usable: Boolean = true, at: Long = 0, song: String = id) =
        SessionView(id, song, playing, usable, at)

    private fun player(id: String, playing: Boolean = false) = MediaPlayer(id, id, "title", "artist", "album", playing, 0, 0)

    @Test
    fun aPausedPlayerStaysInFrontOfOneTheSystemListsFirst() {
        val order = PlayerRules.order(listOf(session("news"), session("music", at = 500)))
        assertEquals(listOf("music", "news"), order)
    }

    @Test
    fun whatPlaysGoesInFrontOfWhatPausedLater() {
        val order = PlayerRules.order(listOf(session("music", at = 900), session("video", playing = true, at = 500)))
        assertEquals(listOf("video", "music"), order)
    }

    @Test
    fun ofTwoThatPlayTheLastOneStartedIsInFront() {
        val order = PlayerRules.order(listOf(session("first", playing = true, at = 100), session("second", playing = true, at = 200)))
        assertEquals(listOf("second", "first"), order)
    }

    @Test
    fun onATieTheSystemsOrderCounts() {
        assertEquals(listOf("first", "second"), PlayerRules.order(listOf(session("first"), session("second"))))
    }

    @Test
    fun aStoppedLeftoverStaysOut() {
        assertEquals(listOf("music"), PlayerRules.order(listOf(session("old", usable = false), session("music"))))
    }

    @Test
    fun aPlayerStoppedFromTheWatchStays() {
        assertEquals(listOf("music"), PlayerRules.order(listOf(session("music", usable = false, at = 300))))
    }

    @Test
    fun aPlayerAndTheSpeakerItPlaysOnAreOne() {
        val order = PlayerRules.order(
            listOf(session("speaker", playing = true, at = 100, song = "x"), session("player", playing = true, at = 200, song = "x")),
        )
        assertEquals(listOf("player"), order)
    }

    @Test
    fun sessionsWithoutASongAreNotTakenForOne() {
        val order = PlayerRules.order(listOf(session("one", song = ""), session("two", song = "")))
        assertEquals(listOf("one", "two"), order)
    }

    @Test
    fun onlySoManyFit() {
        val order = PlayerRules.order((1..5).map { session("p$it", at = it.toLong()) })
        assertEquals(listOf("p5", "p4", "p3"), order)
    }

    @Test
    fun theChosenPlayerIsTheOneShown() {
        val players = listOf(player("a"), player("b"))
        assertEquals("b", PlayerRules.shown(players, "b")?.id)
    }

    @Test
    fun withoutAChoiceTheFirstIsShown() {
        val players = listOf(player("a"), player("b"))
        assertEquals("a", PlayerRules.shown(players, null)?.id)
        assertEquals("a", PlayerRules.shown(players, "gone")?.id)
    }

    @Test
    fun aChoiceSurvivesAPause() {
        val before = listOf(player("a", playing = true), player("b"))
        val now = listOf(player("a"), player("b"))
        assertEquals("a", PlayerRules.keptPick("a", before, now))
    }

    @Test
    fun aChoiceEndsWhenThatPlayerIsGone() {
        assertNull(PlayerRules.keptPick("a", listOf(player("a")), listOf(player("b"))))
    }

    @Test
    fun aChoiceEndsWhenSomethingElseStartsPlaying() {
        val before = listOf(player("a"), player("b"))
        val now = listOf(player("b", playing = true), player("a"))
        assertNull(PlayerRules.keptPick("a", before, now))
    }

    @Test
    fun aChoiceSurvivesWhatWasAlreadyPlaying() {
        val before = listOf(player("b", playing = true), player("a"))
        assertEquals("a", PlayerRules.keptPick("a", before, before))
    }

    @Test
    fun aPressCountsAtOnceAndKeepsItsPlaceInTheTrack() {
        val playing = player("music", playing = true).copy(positionMs = 10_000, durationMs = 60_000)
        val expected = PlayerRules.pressed(listOf(playing), "music", sinceShownMs = 5_000, nowMs = 100)!!
        val shown = PlayerRules.withExpected(listOf(playing), expected).single()
        assertEquals(false, shown.playing)
        assertEquals(15_000, shown.positionMs)
    }

    @Test
    fun anOldUpdateDoesNotFlipTheButtonBack() {
        val before = player("music", playing = true)
        val expected = PlayerRules.pressed(listOf(before), "music", 0, nowMs = 100)
        assertEquals(expected, PlayerRules.keptExpected(expected, listOf(before), nowMs = 200))
    }

    @Test
    fun thePhoneAgreeingEndsTheExpectation() {
        val expected = PlayerRules.pressed(listOf(player("music", playing = true)), "music", 0, nowMs = 100)
        assertNull(PlayerRules.keptExpected(expected, listOf(player("music", playing = false)), nowMs = 200))
    }

    @Test
    fun aPressThatGotLostShowsTheTruthAgain() {
        val before = player("music", playing = true)
        val expected = PlayerRules.pressed(listOf(before), "music", 0, nowMs = 100)
        assertNull(PlayerRules.keptExpected(expected, listOf(before), nowMs = 100 + PlayerRules.EXPECT_MS))
    }

    @Test
    fun aPlayerThatLeftTakesTheExpectationWithIt() {
        val expected = PlayerRules.pressed(listOf(player("music")), "music", 0, nowMs = 100)
        assertNull(PlayerRules.keptExpected(expected, emptyList(), nowMs = 200))
    }
}
