package nl.markmaaktmedia.tandem.watch.protocol

import kotlin.math.abs
import kotlin.reflect.KClass

/**
 * What the phone sends to the watch only when the watch would learn something from it. Every message that arrives hands the watch's
 * processor a job, and a watch that sleeps most of the day pays for each of them: the phone's battery changing a tenth of a degree,
 * a computer's round trip time moving, or an app posting the same text again are no news on a wrist.
 *
 * It remembers what the watch will have once the queue is empty, so it lives as long as one link: the watch asks for everything after
 * each connect, and [reset] makes that ask start from nothing.
 */
class SendGate {
    private class Seen(val message: Message, val atMs: Long)

    /** The last of each kind of state that went to the queue. */
    private val state = HashMap<KClass<out Message>, Seen>()

    /** The notifications the watch has, by key, as they were last sent. */
    private val posted = HashMap<String, Seen>()

    /** True when [message] is worth sending, and then counts as what the watch will have. */
    fun admit(message: Message, nowMs: Long): Boolean = when (message) {
        is Message.Notification -> admitNotification(message, nowMs)
        // A removal of something the watch never got (a timer, a service, an app that is left out) is no news either.
        is Message.NotificationRemoved -> posted.remove(message.key) != null
        is Message.MediaPlayers -> admitState(message, nowMs) { before, now, elapsed ->
            PlayerRules.unchanged((before as Message.MediaPlayers).players, now.players, elapsed)
        }
        is Message.DeviceBatteries, is Message.PointerTargets, is Message.StepGoal, is Message.HeartAllDay,
        is Message.SmartWakeAlarm, is Message.Ringing,
        -> admitState(message, nowMs) { before, now, _ -> before == now }
        else -> true
    }

    /** The queue let go of [message] before it left, so the watch does not have it and the same one has to go again. */
    fun dropped(message: Message) {
        if (message is Message.Notification) posted.remove(message.notification.key) else state.remove(message::class)
    }

    /** A new link: the watch knows nothing and asks for everything. */
    fun reset() {
        state.clear()
        posted.clear()
    }

    private fun <T : Message> admitState(message: T, nowMs: Long, same: (Message, T, Long) -> Boolean): Boolean {
        val before = state[message::class]
        if (before != null && same(before.message, message, nowMs - before.atMs)) return false
        state[message::class] = Seen(message, nowMs)
        return true
    }

    private fun admitNotification(message: Message.Notification, nowMs: Long): Boolean {
        val now = message.notification
        val before = (posted[now.key]?.message as? Message.Notification)?.notification
        if (before != null && sameForWatch(before, now)) return false
        // What is compared next time is the last one that went out, so a progress bar that creeps up still shows every few steps.
        posted[now.key] = Seen(message, nowMs)
        return true
    }

    private fun sameForWatch(before: NotificationMsg, now: NotificationMsg): Boolean =
        before.copy(ts = 0, silent = false, progress = null) == now.copy(ts = 0, silent = false, progress = null) &&
            // One that did not buzz may be the first to; the other way round it only repeats.
            !(before.silent && !now.silent) &&
            // Posted again later with the same words is a new message, and the time on its card should say so.
            abs(now.ts - before.ts) < REPOST_MS &&
            sameProgress(before.progress, now.progress)

    private fun sameProgress(before: Int?, now: Int?): Boolean = when {
        before == null || now == null -> before == now
        // The end of a download is the one step that is never skipped.
        else -> abs(now - before) < PROGRESS_STEP && (now < 100 || before == 100)
    }

    private companion object {
        const val REPOST_MS = 2 * 60_000L
        const val PROGRESS_STEP = 10
    }
}
