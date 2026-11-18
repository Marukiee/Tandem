package nl.markmaaktmedia.tandem.watch.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SendGateTest {
    private val gate = SendGate()

    private fun player(playing: Boolean = true, position: Long = 0, title: String = "Song") =
        MediaPlayer("app", "App", title, "Artist", "Album", playing, position, 200_000, "cover")

    private fun note(
        key: String = "k1",
        text: String = "Hello",
        ts: Long = 1_000,
        silent: Boolean = false,
        progress: Int? = null,
    ) = Message.Notification(
        NotificationMsg(key, "app", "App", "Title", text, null, ts, false, silent, emptyList(), null, progress),
    )

    @Test
    fun theSameStateGoesOnlyOnce() {
        val batteries = Message.DeviceBatteries(listOf(DeviceBattery("", "Phone", 80, false, DeviceBattery.PHONE)))
        assertTrue(gate.admit(batteries, 0))
        repeat(50) { assertFalse(gate.admit(batteries.copy(), it * 1_000L)) }
        assertTrue(gate.admit(Message.DeviceBatteries(listOf(DeviceBattery("", "Phone", 79, false, DeviceBattery.PHONE))), 60_000))
    }

    @Test
    fun targetsGoAgainWhenOneComesOnline() {
        val off = Message.PointerTargets(true, listOf(PointerTarget("mac", "Mac", false, true)))
        val on = Message.PointerTargets(true, listOf(PointerTarget("mac", "Mac", true, true)))
        assertTrue(gate.admit(off, 0))
        assertFalse(gate.admit(off, 1))
        assertTrue(gate.admit(on, 2))
        assertTrue(gate.admit(off, 3))
    }

    @Test
    fun aNewLinkStartsFromNothing() {
        val goal = Message.StepGoal(8_000)
        assertTrue(gate.admit(goal, 0))
        assertFalse(gate.admit(goal, 1))
        gate.reset()
        assertTrue(gate.admit(goal, 2))
    }

    @Test
    fun theWatchLearnsOfTheGoalWhenItChanges() {
        assertTrue(gate.admit(Message.StepGoal(8_000), 0))
        assertTrue(gate.admit(Message.StepGoal(10_000), 1))
        assertTrue(gate.admit(Message.StepGoal(8_000), 2))
    }

    @Test
    fun aPlayingSongThatIsOnTimeIsNotSentAgain() {
        assertTrue(gate.admit(Message.MediaPlayers(listOf(player(position = 10_000))), 0))
        // The watch counts along by itself: 5 seconds later the player says 15 seconds.
        assertFalse(gate.admit(Message.MediaPlayers(listOf(player(position = 15_000))), 5_000))
    }

    @Test
    fun aJumpInTheSongIsSent() {
        assertTrue(gate.admit(Message.MediaPlayers(listOf(player(position = 10_000))), 0))
        assertTrue(gate.admit(Message.MediaPlayers(listOf(player(position = 90_000))), 5_000))
    }

    @Test
    fun aPauseOrANewSongIsSent() {
        assertTrue(gate.admit(Message.MediaPlayers(listOf(player(position = 10_000))), 0))
        assertTrue(gate.admit(Message.MediaPlayers(listOf(player(playing = false, position = 15_000))), 5_000))
        assertTrue(gate.admit(Message.MediaPlayers(listOf(player(playing = false, position = 15_000, title = "Other"))), 6_000))
    }

    @Test
    fun aPausedSongThatStandsStillIsNotSentAgain() {
        assertTrue(gate.admit(Message.MediaPlayers(listOf(player(playing = false, position = 10_000))), 0))
        assertFalse(gate.admit(Message.MediaPlayers(listOf(player(playing = false, position = 10_000))), 600_000))
        // Paused, the position does not run on: a different one is somebody dragging the bar.
        assertTrue(gate.admit(Message.MediaPlayers(listOf(player(playing = false, position = 50_000))), 601_000))
    }

    @Test
    fun theSameNotificationPostedAgainIsNotSentAgain() {
        assertTrue(gate.admit(note(), 0))
        assertFalse(gate.admit(note(ts = 5_000, silent = true), 5_000))
        assertFalse(gate.admit(note(ts = 20_000, silent = true), 20_000))
    }

    @Test
    fun newWordsAreSent() {
        assertTrue(gate.admit(note(), 0))
        assertTrue(gate.admit(note(text = "Hello again", ts = 2_000, silent = true), 2_000))
    }

    @Test
    fun theSameWordsMuchLaterAreANewMessage() {
        assertTrue(gate.admit(note(ts = 1_000), 0))
        assertTrue(gate.admit(note(ts = 1_000 + 3 * 60_000, silent = true), 3 * 60_000))
    }

    @Test
    fun aNotificationThatMayBuzzNowIsSentAfterASilentOne() {
        assertTrue(gate.admit(note(silent = true), 0))
        assertTrue(gate.admit(note(silent = false, ts = 2_000), 2_000))
    }

    @Test
    fun aProgressBarIsSentInSteps() {
        assertTrue(gate.admit(note(progress = 10), 0))
        for (percent in 11..19) assertFalse(gate.admit(note(progress = percent, silent = true), 1_000L * percent))
        assertTrue(gate.admit(note(progress = 20, silent = true), 20_000))
        assertTrue(gate.admit(note(progress = 100, silent = true), 30_000))
        assertFalse(gate.admit(note(progress = 100, silent = true), 31_000))
    }

    @Test
    fun aBarThatAppearsOrGoesIsSent() {
        assertTrue(gate.admit(note(), 0))
        assertTrue(gate.admit(note(progress = 5, silent = true), 1_000))
        assertTrue(gate.admit(note(progress = null, silent = true), 2_000))
    }

    @Test
    fun aRemovalOfWhatTheWatchNeverGotIsNotSent() {
        assertFalse(gate.admit(Message.NotificationRemoved("timer"), 0))
        assertTrue(gate.admit(note(key = "chat"), 1))
        assertTrue(gate.admit(Message.NotificationRemoved("chat"), 2))
        assertFalse(gate.admit(Message.NotificationRemoved("chat"), 3))
    }

    @Test
    fun aNotificationThatCameBackAfterARemovalIsNew() {
        assertTrue(gate.admit(note(), 0))
        assertTrue(gate.admit(Message.NotificationRemoved("k1"), 1))
        assertTrue(gate.admit(note(ts = 1_500), 2))
    }

    @Test
    fun whatTheQueueDroppedGoesAgain() {
        val goal = Message.StepGoal(8_000)
        assertTrue(gate.admit(goal, 0))
        gate.dropped(goal)
        assertTrue(gate.admit(goal, 1))
        val first = note()
        assertTrue(gate.admit(first, 2))
        gate.dropped(first)
        assertTrue(gate.admit(note(ts = 3_000, silent = true), 3_000))
    }

    @Test
    fun everythingElseGoesThrough() {
        assertTrue(gate.admit(Message.Ping(1), 0))
        assertTrue(gate.admit(Message.Ping(1), 1))
        assertTrue(gate.admit(Message.FindDevice(true), 2))
        assertTrue(gate.admit(Message.FindDevice(true), 3))
    }

    @Test
    fun anHourOfNoiseCostsTheWatchAFewMessages() {
        // What one hour looked like from the phone's side: a devices refresh a minute (the round trip time moves each time, but the
        // lists made of it do not), a battery broadcast every half minute, an app that posts its progress bar every second for a few
        // minutes, and the same chat notification posted a few times.
        val queue = OutboundQueue(onDrop = gate::dropped)
        var offered = 0
        var sent = 0

        fun send(message: Message, at: Long) {
            offered++
            if (gate.admit(message, at)) {
                queue.offer(message)
                sent++
            }
        }

        val targets = Message.PointerTargets(true, listOf(PointerTarget("mac", "Mac", true, true)))
        val others = listOf(DeviceBattery("mac", "Mac", 61, true, DeviceBattery.COMPUTER))
        val players = listOf(player(playing = false))
        for (minute in 0 until 60) {
            val at = minute * 60_000L
            send(targets, at)
            send(Message.DeviceBatteries(listOf(DeviceBattery("", "Phone", 80, false, DeviceBattery.PHONE)) + others), at)
            send(Message.MediaPlayers(players), at)
            send(Message.DeviceBatteries(listOf(DeviceBattery("", "Phone", 80, false, DeviceBattery.PHONE)) + others), at + 30_000)
        }
        for (second in 0 until 300) send(note(key = "download", progress = second / 3, ts = 1_000 + second * 1_000L, silent = true), 600_000L + second * 1_000L)
        repeat(4) { send(note(key = "chat", ts = 1_000 + it * 10_000L, silent = it > 0), 2_000_000L + it * 10_000L) }

        assertEquals(60 * 4 + 300 + 4, offered)
        // The three states once, the download at 0, 10 and so on up to 90 per cent, and the chat message once.
        assertEquals(3 + 10 + 1, sent)
    }
}
