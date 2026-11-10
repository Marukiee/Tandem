package nl.markmaaktmedia.tandem.watch.track

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import nl.markmaaktmedia.tandem.watch.sleep.DailySteps
import nl.markmaaktmedia.tandem.watch.sleep.NapGuess
import nl.markmaaktmedia.tandem.watch.sleep.SleepSummary
import nl.markmaaktmedia.tandem.watch.sleep.StageMinutes
import nl.markmaaktmedia.tandem.watch.sleep.WorkoutSummary

enum class TrackingKind { SLEEP, WORKOUT }

data class LastSleep(
    val found: Boolean,
    val minutes: Int,
    val efficiencyPercent: Int,
    /** Null when the heart rate was missing too much to tell them apart. */
    val stages: StageMinutes? = null,
)

data class LastWorkout(val minutes: Int, val meanHeartRate: Int)

data class LastNap(val startMs: Long, val minutes: Int)

data class TrackingUi(
    val active: TrackingKind? = null,
    val startedAtMs: Long = 0,
    val lastSleep: LastSleep? = null,
    val lastWorkout: LastWorkout? = null,
    val stepsToday: Long = 0,
    val activeMinutesToday: Int = 0,
    val stepGoal: Int = DEFAULT_STEP_GOAL,
    val autoSleep: Boolean = true,
    val heartAllDay: Boolean = true,
    val heartNow: Int? = null,
    val heartLow: Int? = null,
    val heartHigh: Int? = null,
    /** Today per five minutes from midnight on, 0 where nothing was measured. */
    val heartCurve: List<Int> = emptyList(),
    val lastNap: LastNap? = null,
)

/** Where the research on steps and health stops finding much more per step. The person can pick their own on the phone. */
const val DEFAULT_STEP_GOAL = 8000

object Tracking {
    private val _ui = MutableStateFlow(TrackingUi())
    val ui: StateFlow<TrackingUi> = _ui.asStateFlow()
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val store = TrackingStore(context)
        _ui.update {
            it.copy(
                lastSleep = store.loadSleep(),
                lastWorkout = store.loadWorkout(),
                stepsToday = store.loadSteps().todayOrZero(),
                activeMinutesToday = store.loadSteps().activeTodayOrZero(),
                stepGoal = store.stepGoal,
                autoSleep = store.autoSleep,
                heartAllDay = store.heartAllDay,
                lastNap = store.loadNap(),
            )
        }
    }

    fun publishActive(kind: TrackingKind?, startedAtMs: Long) = _ui.update { it.copy(active = kind, startedAtMs = startedAtMs) }
    fun publishSleep(sleep: LastSleep) = _ui.update { it.copy(lastSleep = sleep) }
    fun publishWorkout(workout: LastWorkout) = _ui.update { it.copy(lastWorkout = workout) }
    fun publishSteps(steps: Long, activeMinutes: Int) = _ui.update { it.copy(stepsToday = steps, activeMinutesToday = activeMinutes) }
    fun publishStepGoal(steps: Int) = _ui.update { it.copy(stepGoal = steps) }
    fun publishAutoSleep(on: Boolean) = _ui.update { it.copy(autoSleep = on) }
    fun publishHeartAllDay(on: Boolean) = _ui.update { it.copy(heartAllDay = on) }
    fun publishNap(nap: LastNap) = _ui.update { it.copy(lastNap = nap) }
    fun publishHeart(now: Int?, low: Int?, high: Int?, curve: List<Int>) =
        _ui.update { it.copy(heartNow = now, heartLow = low, heartHigh = high, heartCurve = curve) }
}

class TrackingStore(context: Context) {
    private val prefs = context.getSharedPreferences("tracking", Context.MODE_PRIVATE)

    var autoSleep: Boolean
        get() = prefs.getBoolean("auto_sleep", true)
        set(value) = prefs.edit().putBoolean("auto_sleep", value).apply()

    var heartAllDay: Boolean
        get() = prefs.getBoolean("heart_all_day", true)
        set(value) = prefs.edit().putBoolean("heart_all_day", value).apply()

    /** The mean heart rate of the last night, to judge an afternoon by. Null until there has been a night. */
    val sleepingHeart: Int?
        get() = prefs.getInt("sleeping_heart", -1).takeIf { it > 0 }

    /** What the question on the wrist is about, so an answer still counts after the process restarted. */
    var pendingNap: NapGuess?
        get() = if (!prefs.contains("nap_guess_start")) null else NapGuess(
            startMs = prefs.getLong("nap_guess_start", 0),
            endMs = prefs.getLong("nap_guess_end", 0),
            sleepMinutes = prefs.getInt("nap_guess_sleep", 0),
            awakeMinutes = prefs.getInt("nap_guess_awake", 0),
            meanHeartRate = prefs.getInt("nap_guess_mean", -1).takeIf { it > 0 },
            lowestHeartRate = prefs.getInt("nap_guess_low", -1).takeIf { it > 0 },
        )
        set(value) {
            val edit = prefs.edit()
            if (value == null) {
                listOf("start", "end", "sleep", "awake", "mean", "low").forEach { edit.remove("nap_guess_$it") }
            } else {
                edit.putLong("nap_guess_start", value.startMs)
                    .putLong("nap_guess_end", value.endMs)
                    .putInt("nap_guess_sleep", value.sleepMinutes)
                    .putInt("nap_guess_awake", value.awakeMinutes)
                    .putInt("nap_guess_mean", value.meanHeartRate ?: -1)
                    .putInt("nap_guess_low", value.lowestHeartRate ?: -1)
            }
            edit.apply()
        }

    fun saveNap(startMs: Long, minutes: Int): LastNap {
        prefs.edit().putLong("nap_start", startMs).putInt("nap_minutes", minutes).apply()
        return LastNap(startMs, minutes)
    }

    fun loadNap(): LastNap? =
        if (!prefs.contains("nap_start")) null else LastNap(prefs.getLong("nap_start", 0), prefs.getInt("nap_minutes", 0))

    /** The file of the recording under way, so a next start knows when one never finished. */
    var unfinished: String?
        get() = prefs.getString("unfinished", null)
        set(value) = prefs.edit().putString("unfinished", value).apply()

    fun loadSteps() = DailySteps(
        day = prefs.getString("steps_day", "").orEmpty(),
        steps = prefs.getLong("steps_count", 0),
        lastTotal = prefs.getLong("steps_total", -1),
        activeMinutes = prefs.getInt("steps_active", 0),
        minute = prefs.getLong("steps_minute", -1),
        stepsInMinute = prefs.getLong("steps_in_minute", 0),
        hourly = prefs.getString("steps_hourly", "").orEmpty().split(',').mapNotNull { it.toIntOrNull() },
    )

    /** The next alarm to wake someone before, 0 for none. Kept here so a night on a watch without its phone still has it. */
    var smartWakeAlarmMs: Long
        get() = prefs.getLong("smart_wake_alarm", 0)
        set(value) = prefs.edit().putLong("smart_wake_alarm", value).apply()

    /** How strong waking gently gets at its top, in per cent of the motor, as chosen on the phone. */
    var smartWakeStrength: Int
        get() = prefs.getInt("smart_wake_strength", nl.markmaaktmedia.tandem.watch.protocol.Message.SmartWakeAlarm.DEFAULT_STRENGTH)
        set(value) = prefs.edit().putInt("smart_wake_strength", value).apply()

    var stepGoal: Int
        get() = prefs.getInt("step_goal", DEFAULT_STEP_GOAL)
        set(value) = prefs.edit().putInt("step_goal", value).apply()

    fun saveSteps(state: DailySteps) {
        prefs.edit()
            .putString("steps_day", state.day)
            .putLong("steps_count", state.steps)
            .putLong("steps_total", state.lastTotal)
            .putInt("steps_active", state.activeMinutes)
            .putLong("steps_minute", state.minute)
            .putLong("steps_in_minute", state.stepsInMinute)
            .putString("steps_hourly", state.hourly.joinToString(","))
            .apply()
    }

    fun saveSleep(summary: SleepSummary?): LastSleep {
        val last = LastSleep(summary != null, summary?.sleepMinutes ?: 0, summary?.efficiencyPercent ?: 0, summary?.stages)
        prefs.edit()
            .putBoolean("sleep_found", last.found)
            .putInt("sleep_minutes", last.minutes)
            .putInt("sleep_efficiency", last.efficiencyPercent)
            .putInt("sleep_light", last.stages?.light ?: -1)
            .putInt("sleep_deep", last.stages?.deep ?: -1)
            .putInt("sleep_rem", last.stages?.rem ?: -1)
            .apply()
        summary?.meanHeartRate?.let { prefs.edit().putInt("sleeping_heart", it).apply() }
        return last
    }

    fun loadSleep(): LastSleep? {
        if (!prefs.contains("sleep_found")) return null
        val light = prefs.getInt("sleep_light", -1)
        return LastSleep(
            prefs.getBoolean("sleep_found", false),
            prefs.getInt("sleep_minutes", 0),
            prefs.getInt("sleep_efficiency", 0),
            if (light < 0) null else StageMinutes(light, prefs.getInt("sleep_deep", 0), prefs.getInt("sleep_rem", 0)),
        )
    }

    fun saveWorkout(summary: WorkoutSummary?): LastWorkout {
        val last = LastWorkout(summary?.minutes ?: 0, summary?.meanHeartRate ?: 0)
        prefs.edit().putInt("workout_minutes", last.minutes).putInt("workout_heart_rate", last.meanHeartRate).apply()
        return last
    }

    fun loadWorkout(): LastWorkout? {
        if (!prefs.contains("workout_minutes")) return null
        return LastWorkout(prefs.getInt("workout_minutes", 0), prefs.getInt("workout_heart_rate", 0))
    }
}
