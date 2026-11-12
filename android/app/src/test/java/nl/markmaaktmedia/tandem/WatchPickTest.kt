package nl.markmaaktmedia.tandem

import nl.markmaaktmedia.tandem.ui.screens.WatchHolder
import nl.markmaaktmedia.tandem.ui.screens.WatchPick
import nl.markmaaktmedia.tandem.ui.screens.pickWatch
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchPickTest {
    @Test fun theWatchConnectedHereWinsFromThePhoneItLeft() {
        assertEquals(WatchPick.Local, pickWatch(localPaired = true, localReady = true, others = listOf(WatchHolder("oppo", false))))
    }

    @Test fun theWatchConnectedToAnotherPhoneWinsFromTheOneThatLostIt() {
        assertEquals(WatchPick.Remote("oppo"), pickWatch(localPaired = true, localReady = false, others = listOf(WatchHolder("oppo", true))))
    }

    @Test fun withoutAWatchHereTheOneOfAnotherPhoneShowsEvenWhenAway() {
        assertEquals(WatchPick.Remote("oppo"), pickWatch(localPaired = false, localReady = false, others = listOf(WatchHolder("oppo", false))))
    }

    @Test fun whenNoneIsConnectedTheOneOfThisPhoneStays() {
        assertEquals(WatchPick.Local, pickWatch(localPaired = true, localReady = false, others = listOf(WatchHolder("oppo", false))))
    }

    @Test fun theConnectedOneIsPickedFromSeveralPhones() {
        val others = listOf(WatchHolder("xperia", false), WatchHolder("oppo", true))
        assertEquals(WatchPick.Remote("oppo"), pickWatch(localPaired = false, localReady = false, others = others))
    }

    @Test fun noWatchAnywhereIsNoCard() {
        assertEquals(WatchPick.None, pickWatch(localPaired = false, localReady = false, others = emptyList()))
    }
}
