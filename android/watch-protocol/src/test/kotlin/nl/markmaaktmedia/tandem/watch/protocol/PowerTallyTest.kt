package nl.markmaaktmedia.tandem.watch.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PowerTallyTest {
    @Test
    fun countsAndMessagesAddUp() {
        val tally = PowerTally()
        tally.count(Tick.BLE_IN)
        tally.count(Tick.BLE_IN)
        tally.count(Tick.BYTES_IN, 240)
        tally.message(inbound = true, kind = "Ping")
        tally.message(inbound = true, kind = "Ping")
        tally.message(inbound = false, kind = "Pong")
        val done = tally.cut(1_000)
        assertEquals(2L, done.counts[Tick.BLE_IN])
        assertEquals(240L, done.counts[Tick.BYTES_IN])
        assertEquals(mapOf("Ping" to 2), done.messagesIn)
        assertEquals(mapOf("Pong" to 1), done.messagesOut)
        // A period starts empty.
        val next = tally.cut(2_000)
        assertTrue(next.counts.isEmpty())
        assertTrue(next.messagesIn.isEmpty())
    }

    @Test
    fun aSpanCountsFromOpenToClose() {
        val tally = PowerTally()
        tally.open(Span.ACCEL, 1_000)
        tally.close(Span.ACCEL, 4_000)
        assertEquals(3_000L, tally.cut(5_000).spansMs[Span.ACCEL])
    }

    @Test
    fun openingTwiceKeepsTheFirstMomentAndClosingTwiceCountsOnce() {
        val tally = PowerTally()
        tally.open(Span.LINK, 1_000)
        tally.open(Span.LINK, 3_000)
        tally.close(Span.LINK, 4_000)
        tally.close(Span.LINK, 9_000)
        assertEquals(3_000L, tally.cut(10_000).spansMs[Span.LINK])
    }

    @Test
    fun aSpanThatIsStillOpenCountsUpToTheCutAndGoesOnInTheNextPeriod() {
        val tally = PowerTally()
        tally.open(Span.SCREEN, 1_000)
        val first = tally.cut(4_000)
        assertEquals(3_000L, first.spansMs[Span.SCREEN])
        assertTrue(tally.isOpen(Span.SCREEN))
        tally.close(Span.SCREEN, 6_500)
        assertEquals(2_500L, tally.cut(7_000).spansMs[Span.SCREEN])
    }

    @Test
    fun peekDoesNotEndThePeriod() {
        val tally = PowerTally()
        tally.open(Span.WORN, 0)
        tally.count(Tick.ALARM)
        assertEquals(5_000L, tally.peek(5_000).spansMs[Span.WORN])
        assertEquals(1L, tally.peek(6_000).counts[Tick.ALARM])
        assertEquals(7_000L, tally.cut(7_000).spansMs[Span.WORN])
    }

    @Test
    fun aClockThatWentBackwardsDoesNotCountNegativeTime() {
        val tally = PowerTally()
        tally.open(Span.LINK, 5_000)
        tally.close(Span.LINK, 4_000)
        assertTrue(tally.cut(6_000).spansMs.isEmpty())
    }

    @Test
    fun setOpensAndCloses() {
        val tally = PowerTally()
        tally.set(Span.CHARGING, true, 0)
        assertTrue(tally.isOpen(Span.CHARGING))
        tally.set(Span.CHARGING, false, 1_000)
        assertFalse(tally.isOpen(Span.CHARGING))
    }

    @Test
    fun aRecordSurvivesItsLine() {
        val tally = PowerTally()
        tally.count(Tick.ACCEL_BATCH, 15)
        tally.count(Tick.BYTES_OUT, 1234)
        tally.open(Span.LINK, 0)
        tally.open(Span.SCAN_LOW, 0)
        tally.close(Span.SCAN_LOW, 600_000)
        tally.message(inbound = true, kind = "NotificationRemoved")
        tally.message(inbound = false, kind = "WatchStatus")
        val record = PowerRecord(1_000, 3_601_000, 80, 78, 250_000, 243_000, tally.cut(3_601_000))
        val line = record.toJson()
        assertFalse(line.contains('\n'))
        val back = PowerRecord.parse(line)!!
        assertEquals(record.startMs, back.startMs)
        assertEquals(record.endMs, back.endMs)
        assertEquals(80, back.levelStart)
        assertEquals(78, back.levelEnd)
        assertEquals(250_000L, back.chargeStartUah)
        assertEquals(243_000L, back.chargeEndUah)
        assertEquals(15L, back.count(Tick.ACCEL_BATCH))
        assertEquals(1234L, back.count(Tick.BYTES_OUT))
        assertEquals(3601L, back.spanSeconds(Span.LINK))
        assertEquals(600L, back.spanSeconds(Span.SCAN_LOW))
        assertEquals(mapOf("NotificationRemoved" to 1), back.tally.messagesIn)
        assertEquals(mapOf("WatchStatus" to 1), back.tally.messagesOut)
    }

    @Test
    fun aLineLeavesOutWhatIsZeroAndWhatIsUnknown() {
        val line = PowerRecord(0, 3_600_000, null, null, null, null, Tally(emptyMap(), emptyMap(), emptyMap(), emptyMap())).toJson()
        assertEquals("{\"t0\":0,\"t1\":3600000}", line)
        val back = PowerRecord.parse(line)!!
        assertNull(back.levelStart)
        assertNull(back.chargeEndUah)
    }

    @Test
    fun anHourOfCountersFitsInAShortLine() {
        val tally = PowerTally()
        for (tick in Tick.entries) tally.count(tick, 99_999)
        for (span in Span.entries) {
            tally.open(span, 0)
            tally.close(span, 3_599_000)
        }
        for (kind in listOf("Notification", "NotificationRemoved", "MediaPlayers", "MediaArt", "Ping", "Pong", "HeartToday", "WatchStatus")) {
            tally.message(inbound = true, kind = kind)
            tally.message(inbound = false, kind = kind)
        }
        val line = PowerRecord(0, 3_600_000, 100, 99, 300_000, 299_000, tally.cut(3_600_000)).toJson()
        // A day of this is under 10 kB, so writing one line an hour is nothing for the flash.
        assertTrue(line.length < 1_100, "was ${line.length}")
    }

    @Test
    fun linesThatAreNotOursAreSkipped() {
        assertNull(PowerRecord.parse(""))
        assertNull(PowerRecord.parse("{"))
        assertNull(PowerRecord.parse("{\"t0\":1}"))
        assertNull(PowerRecord.parse("{\"t0\":1,\"t1\":2} extra"))
        assertNull(PowerRecord.parse("{\"t0\":1,\"t1\":\"x\"}"))
        assertNull(PowerRecord.parse("[1,2]"))
    }

    @Test
    fun unknownKeysAreKeptOutAndNewOnesDoNotBreakTheLine() {
        val back = PowerRecord.parse("{\"t0\":0,\"t1\":3600000,\"n\":{\"accel_batch\":3,\"later\":9},\"s\":{\"link\":5,\"later\":1},\"extra\":4}")!!
        assertEquals(3L, back.count(Tick.ACCEL_BATCH))
        assertEquals(5L, back.spanSeconds(Span.LINK))
    }

    @Test
    fun wakesCountOnlyWhatWakesTheProcessor() {
        val tally = PowerTally()
        tally.count(Tick.ACCEL_BATCH, 15)
        tally.count(Tick.BLE_IN, 20)
        tally.count(Tick.SCREEN_ON, 3)
        tally.count(Tick.BYTES_IN, 5_000)
        tally.count(Tick.BLE_OUT, 40)
        val record = PowerRecord(0, 3_600_000, null, null, null, null, tally.cut(3_600_000))
        assertEquals(38L, record.wakes)
    }

    @Test
    fun drainIsWhatTheBatteryLostAndNothingWhileItCharged() {
        val awake = PowerTally().cut(0)
        assertEquals(7_000L, PowerRecord(0, 3_600_000, 80, 78, 250_000, 243_000, awake).drainUah)
        assertEquals(2.0, PowerRecord(0, 3_600_000, 80, 78, 250_000, 243_000, awake).percentPerHour)
        // Up is the charger, and unknown is unknown.
        assertNull(PowerRecord(0, 3_600_000, 80, 85, 250_000, 260_000, awake).drainUah)
        assertNull(PowerRecord(0, 3_600_000, 80, 85, 250_000, 260_000, awake).percentPerHour)
        assertNull(PowerRecord(0, 3_600_000, null, null, null, null, awake).drainUah)
        // Half the time on the charger says nothing about what the watch costs on the wrist.
        val tally = PowerTally()
        tally.open(Span.CHARGING, 0)
        val charged = PowerRecord(0, 3_600_000, 80, 80, 250_000, 250_000, tally.cut(3_000_000))
        assertNull(charged.drainUah)
        assertNull(charged.percentPerHour)
    }

    @Test
    fun percentPerHourScalesAShortStretch() {
        val record = PowerRecord(0, 1_800_000, 80, 79, null, null, PowerTally().cut(0))
        assertEquals(2.0, record.percentPerHour)
    }
}

class PowerSummaryTest {
    private fun record(start: Long, hours: Int, from: Long?, to: Long?, build: PowerTally.() -> Unit = {}): PowerRecord {
        val tally = PowerTally()
        tally.build()
        val end = start + hours * PowerClock.HOUR_MS
        return PowerRecord(start, end, null, null, from, to, tally.cut(end / 1000))
    }

    @Test
    fun totalsAddUpAndDrainIsPerHourOverTheStretchesItIsKnownFor() {
        val a = record(0, 1, 250_000, 243_000) {
            count(Tick.ACCEL_BATCH, 15)
            message(true, "Ping")
        }
        val b = record(PowerClock.HOUR_MS, 1, 243_000, 239_000) {
            count(Tick.BLE_IN, 5)
            message(true, "Ping")
            message(false, "WatchStatus")
        }
        // The battery was not read for this one, so it adds to the time but not to the drain.
        val c = record(2 * PowerClock.HOUR_MS, 1, null, null)
        val summary = summarize(listOf(a, b, c))
        assertEquals(3 * 3600L, summary.seconds)
        assertEquals(11_000L, summary.drainUah)
        assertEquals(2 * 3600L, summary.drainSeconds)
        assertEquals(5_500L, summary.uahPerHour)
        assertEquals(20L, summary.wakes)
        assertEquals(mapOf("Ping" to 2), summary.messagesIn)
        assertEquals(mapOf("WatchStatus" to 1), summary.messagesOut)
    }

    @Test
    fun noBatteryReadingsMeansNoRate() {
        assertNull(summarize(listOf(record(0, 1, null, null))).uahPerHour)
        assertNull(summarize(emptyList()).uahPerHour)
    }

    @Test
    fun aStretchOnTheChargerIsLeftOutOfTheDrain() {
        val charging = record(0, 1, 250_000, 280_000)
        val summary = summarize(listOf(charging))
        assertEquals(0L, summary.drainUah)
        assertNull(summary.uahPerHour)
    }
}

class PowerClockTest {
    private val hour = PowerClock.HOUR_MS

    @Test
    fun aStretchEndsWhereTheLocalHourChanges() {
        val offset = 3_600_000L
        // 10:59:59 local and 11:00:00 local, with a clock an hour ahead of UTC.
        val before = 10 * hour - offset + hour - 1
        val after = 11 * hour - offset
        assertFalse(PowerClock.due(before - 60_000, before, offset))
        assertTrue(PowerClock.due(before, after, offset))
    }

    @Test
    fun aHalfHourOffsetStillCutsOnTheLocalHour() {
        val offset = 5 * hour + hour / 2
        val noon = 12 * hour - offset
        assertFalse(PowerClock.due(noon, noon + hour - 1, offset))
        assertTrue(PowerClock.due(noon, noon + hour, offset))
    }

    @Test
    fun aLongGapIsDueToo() {
        assertTrue(PowerClock.due(0, 5 * hour + 123, 0))
    }

    @Test
    fun beforeTheEpochStillFloorsCorrectly() {
        assertFalse(PowerClock.due(-hour + 1, -1, 0))
        assertTrue(PowerClock.due(-1, 0, 0))
    }
}

class DeliveryCounterTest {
    @Test
    fun valuesCloseTogetherAreOneDelivery() {
        val counter = DeliveryCounter()
        assertTrue(counter.onValue(10_000))
        assertFalse(counter.onValue(10_004))
        assertFalse(counter.onValue(10_010))
        assertTrue(counter.onValue(250_000))
        assertFalse(counter.onValue(250_003))
    }

    @Test
    fun aSlowStreamIsADeliveryEach() {
        val counter = DeliveryCounter()
        assertTrue(counter.onValue(0))
        assertTrue(counter.onValue(1_000))
        assertTrue(counter.onValue(2_500))
    }
}
