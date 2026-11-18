package nl.markmaaktmedia.tandem.watch.protocol

/**
 * What the phone does with the sleep state the watch reports. A night is recorded from the moment bedtime mode goes on, but the
 * person is only asleep when the watch has seen them fall asleep: pausing the music at bedtime mode would cut it off for someone
 * who is lying there listening. Pure logic, so the order of events can be tested without a phone.
 */
class SleepSession {
    enum class Step {
        /** Silence the phone, if the person wants that. */
        QUIET_ON,
        QUIET_OFF,

        /** Pause what plays and lock the computers, if the person wants that. */
        FELL_ASLEEP,
        WOKE_UP,
    }

    private enum class Phase { AWAKE, BEDTIME, ASLEEP }

    @Volatile
    private var phase = Phase.AWAKE

    /** A night is being recorded, asleep or not. */
    val recording: Boolean get() = phase != Phase.AWAKE

    /** The watch has seen the person fall asleep, and has not seen them wake up since. */
    val asleep: Boolean get() = phase == Phase.ASLEEP

    /** What to do now that the watch says [on] and [asleep]. The same state again, as after a reconnect, gives nothing. */
    fun onState(on: Boolean, asleep: Boolean): List<Step> {
        if (!on) {
            // Every time, as before: it is cheap, and a phone that missed the end of a night still gets to ask its morning question.
            phase = Phase.AWAKE
            return listOf(Step.QUIET_OFF, Step.WOKE_UP)
        }
        return when (phase) {
            Phase.AWAKE -> {
                phase = if (asleep) Phase.ASLEEP else Phase.BEDTIME
                if (asleep) listOf(Step.QUIET_ON, Step.FELL_ASLEEP) else listOf(Step.QUIET_ON)
            }
            Phase.BEDTIME -> if (asleep) {
                phase = Phase.ASLEEP
                listOf(Step.FELL_ASLEEP)
            } else {
                emptyList()
            }
            // Awake for a while in the night: the next time they fall asleep is a new fall, and what they put on in between pauses again.
            Phase.ASLEEP -> {
                if (!asleep) phase = Phase.BEDTIME
                emptyList()
            }
        }
    }
}

/**
 * The watch's side of [SleepSession]: what to tell the phone about the night being recorded. Bedtime mode starts a night before
 * anyone is asleep, so the report starts with [Message.SleepMode.asleep] false and flips when the detector says they fell asleep.
 */
class SleepReport {
    private var on = false
    private var asleep = false

    /** The night as the phone should know it now; what to send again after each reconnect. */
    fun current() = Message.SleepMode(on, asleep)

    /** A night is being recorded. Started by hand, or by the detector that has just seen them fall asleep, it is asleep at once. */
    fun begin(asleep: Boolean): Message.SleepMode {
        on = true
        this.asleep = asleep
        return current()
    }

    fun end(): Message.SleepMode {
        on = false
        asleep = false
        return current()
    }

    /** The detector saw them fall asleep or wake up. Null when it changes nothing for the phone: no night, or no change. */
    fun asleep(value: Boolean): Message.SleepMode? {
        if (!on || asleep == value) return null
        asleep = value
        return current()
    }
}
