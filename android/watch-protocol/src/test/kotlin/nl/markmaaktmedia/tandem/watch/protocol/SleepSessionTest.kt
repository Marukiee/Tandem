package nl.markmaaktmedia.tandem.watch.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import nl.markmaaktmedia.tandem.watch.protocol.SleepSession.Step

class SleepSessionTest {
    private val session = SleepSession()

    @Test
    fun bedtimeModeSilencesButDoesNotPause() {
        assertEquals(listOf(Step.QUIET_ON), session.onState(on = true, asleep = false))
        assertTrue(session.recording)
        assertFalse(session.asleep)
    }

    @Test
    fun fallingAsleepPausesOnce() {
        session.onState(on = true, asleep = false)
        assertEquals(listOf(Step.FELL_ASLEEP), session.onState(on = true, asleep = true))
        assertTrue(session.asleep)
        // The watch says it again after every reconnect.
        assertEquals(emptyList(), session.onState(on = true, asleep = true))
        assertEquals(emptyList(), session.onState(on = true, asleep = true))
    }

    @Test
    fun theSameBedtimeStateAgainDoesNothing() {
        session.onState(on = true, asleep = false)
        assertEquals(emptyList(), session.onState(on = true, asleep = false))
        assertEquals(emptyList(), session.onState(on = true, asleep = false))
    }

    @Test
    fun theWholeNightInOrder() {
        val seen = buildList {
            addAll(session.onState(on = true, asleep = false))
            addAll(session.onState(on = true, asleep = false))
            addAll(session.onState(on = true, asleep = true))
            addAll(session.onState(on = true, asleep = true))
            addAll(session.onState(on = false, asleep = false))
        }
        assertEquals(listOf(Step.QUIET_ON, Step.FELL_ASLEEP, Step.QUIET_OFF, Step.WOKE_UP), seen)
        assertFalse(session.recording)
    }

    @Test
    fun aWatchFromBeforeTheFlagMeansAsleepAtOnce() {
        val old = Message.SleepMode(true)
        assertTrue(old.asleep)
        assertEquals(listOf(Step.QUIET_ON, Step.FELL_ASLEEP), session.onState(old.on, old.asleep))
        assertEquals(emptyList(), session.onState(old.on, old.asleep))
    }

    @Test
    fun aNightThatEndsWithoutSleepNeverPaused() {
        val seen = session.onState(on = true, asleep = false) + session.onState(on = false, asleep = false)
        assertFalse(seen.contains(Step.FELL_ASLEEP))
        assertEquals(listOf(Step.QUIET_ON, Step.QUIET_OFF, Step.WOKE_UP), seen)
    }

    @Test
    fun anAwakeWatchGetsTheEndAgainWithoutHarm() {
        // Each reconnect repeats "off". Quiet off and the morning question are both safe to repeat.
        assertEquals(listOf(Step.QUIET_OFF, Step.WOKE_UP), session.onState(on = false, asleep = false))
        assertEquals(listOf(Step.QUIET_OFF, Step.WOKE_UP), session.onState(on = false, asleep = false))
        assertFalse(session.recording)
    }

    @Test
    fun wakingInTheNightAndFallingAsleepAgainPausesAgain() {
        session.onState(on = true, asleep = false)
        assertEquals(listOf(Step.FELL_ASLEEP), session.onState(on = true, asleep = true))
        assertEquals(emptyList(), session.onState(on = true, asleep = false))
        assertFalse(session.asleep)
        assertTrue(session.recording)
        assertEquals(listOf(Step.FELL_ASLEEP), session.onState(on = true, asleep = true))
    }

    @Test
    fun aPhoneThatMissedTheStartStillPausesWhenTheWatchSaysAsleep() {
        assertEquals(listOf(Step.QUIET_ON, Step.FELL_ASLEEP), session.onState(on = true, asleep = true))
    }

    @Test
    fun theFlagTravelsAndAnOldPhoneReadsTheFirstByteOnly() {
        for (message in listOf(Message.SleepMode(true, false), Message.SleepMode(true, true), Message.SleepMode(false, false))) {
            assertEquals(message, MessageCodec.decode(MessageCodec.encode(message)))
        }
        // What a watch from before the flag sends: one byte after the id.
        val old = MessageCodec.encode(Message.SleepMode(true, true)).let { it.copyOf(it.size - 1) }
        assertEquals(Message.SleepMode(true, true), MessageCodec.decode(old))
        val oldOff = MessageCodec.encode(Message.SleepMode(false, false)).let { it.copyOf(it.size - 1) }
        assertEquals(Message.SleepMode(false, false), MessageCodec.decode(oldOff))
        // And the new bytes start like the old ones, so a phone that reads one boolean still gets the right answer.
        assertEquals(1, MessageCodec.encode(Message.SleepMode(true, false))[1].toInt())
    }
}

class SleepReportTest {
    private val report = SleepReport()

    @Test
    fun nothingIsRecordedAtFirst() {
        assertEquals(Message.SleepMode(false, false), report.current())
    }

    @Test
    fun bedtimeModeReportsANightThatIsNotAsleepYet() {
        assertEquals(Message.SleepMode(true, false), report.begin(asleep = false))
        // After a reconnect the phone hears the same.
        assertEquals(Message.SleepMode(true, false), report.current())
    }

    @Test
    fun fallingAsleepIsReportedOnceAndStaysAfterAReconnect() {
        report.begin(asleep = false)
        assertEquals(Message.SleepMode(true, true), report.asleep(true))
        assertNull(report.asleep(true))
        assertEquals(Message.SleepMode(true, true), report.current())
    }

    @Test
    fun aNightStartedByHandOrByTheDetectorIsAsleepAtOnce() {
        assertEquals(Message.SleepMode(true, true), report.begin(asleep = true))
    }

    @Test
    fun theDetectorHasNothingToSayWithoutANight() {
        assertNull(report.asleep(true))
        assertNull(report.asleep(false))
        assertEquals(Message.SleepMode(false, false), report.current())
    }

    @Test
    fun wakingInTheNightIsReportedAndSoIsTheNextSleep() {
        report.begin(asleep = false)
        report.asleep(true)
        assertEquals(Message.SleepMode(true, false), report.asleep(false))
        assertEquals(Message.SleepMode(true, true), report.asleep(true))
    }

    @Test
    fun theEndOfANightForgetsEverything() {
        report.begin(asleep = false)
        report.asleep(true)
        assertEquals(Message.SleepMode(false, false), report.end())
        assertEquals(Message.SleepMode(true, false), report.begin(asleep = false))
    }
}
