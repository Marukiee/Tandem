package nl.markmaaktmedia.tandem.watch

import android.content.Context
import android.health.connect.HealthConnectException
import android.health.connect.HealthConnectManager
import android.health.connect.ReadRecordsRequestUsingFilters
import android.health.connect.ReadRecordsResponse
import android.health.connect.TimeInstantRangeFilter
import android.health.connect.datatypes.ExerciseSessionRecord
import android.health.connect.datatypes.Record
import android.health.connect.datatypes.SleepSessionRecord
import android.health.connect.datatypes.StepsRecord
import android.os.Build
import android.os.OutcomeReceiver
import androidx.annotation.RequiresApi
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import uniffi.tandem_core.TandemHealthKind
import uniffi.tandem_core.TandemHealthRecord
import uniffi.tandem_core.TandemSleepStage
import uniffi.tandem_core.TandemStageRun

/**
 * Brings nights, steps and workouts in from Health Connect, which is part of Android itself from version 14. For someone who comes
 * from another watch or app, or has none. The ids come from Health Connect, so importing twice does not count anything twice.
 */
object HealthConnectImport {
    val PERMISSIONS = arrayOf(
        "android.permission.health.READ_SLEEP",
        "android.permission.health.READ_STEPS",
        "android.permission.health.READ_EXERCISE",
    )

    private const val DAYS_BACK = 365L
    private const val PAGE = 1000

    fun available(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && context.getSystemService(Context.HEALTHCONNECT_SERVICE) != null

    /** What Health Connect has of the last year, as records this device can keep. */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    suspend fun read(context: Context): List<TandemHealthRecord> {
        val manager = context.getSystemService(HealthConnectManager::class.java)
        val until = Instant.now()
        val from = until.minus(DAYS_BACK, ChronoUnit.DAYS)
        val nights = all(manager, SleepSessionRecord::class.java, from, until).map(::night)
        val workouts = all(manager, ExerciseSessionRecord::class.java, from, until).map(::workout)
        return nights + workouts + days(all(manager, StepsRecord::class.java, from, until))
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private suspend fun <T : Record> all(manager: HealthConnectManager, type: Class<T>, from: Instant, until: Instant): List<T> {
        val range = TimeInstantRangeFilter.Builder().setStartTime(from).setEndTime(until).build()
        val request = ReadRecordsRequestUsingFilters.Builder(type).setTimeRangeFilter(range).setPageSize(PAGE).build()
        return suspendCoroutine { done ->
            manager.readRecords(
                request,
                Runnable::run,
                object : OutcomeReceiver<ReadRecordsResponse<T>, HealthConnectException> {
                    override fun onResult(result: ReadRecordsResponse<T>) = done.resume(result.records)
                    override fun onError(error: HealthConnectException) = done.resumeWithException(error)
                },
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun night(record: SleepSessionRecord): TandemHealthRecord {
        val runs = record.stages.sortedBy { it.startTime }.map { stage ->
            TandemStageRun(stageOf(stage.type), (stage.endTime.epochSecond - stage.startTime.epochSecond).coerceAtLeast(0).toUInt())
        }
        fun minutes(stage: TandemSleepStage) = runs.filter { it.stage == stage }.sumOf { it.seconds.toLong() }.div(60).toUInt()
        val total = ((record.endTime.epochSecond - record.startTime.epochSecond) / 60).coerceAtLeast(0).toUInt()
        val awake = minutes(TandemSleepStage.WAKE)
        val known = runs.any { it.stage == TandemSleepStage.LIGHT || it.stage == TandemSleepStage.DEEP || it.stage == TandemSleepStage.REM }
        val asleep = total - awake.coerceAtMost(total)
        return empty("hc-${record.metadata.id}", TandemHealthKind.SLEEP, record.startTime, record.endTime).copy(
            sleepMinutes = asleep,
            awakeMinutes = awake,
            awakenings = runs.count { it.stage == TandemSleepStage.WAKE }.toUInt(),
            efficiencyPercent = if (total > 0u) asleep * 100u / total else 0u,
            lightMinutes = if (known) minutes(TandemSleepStage.LIGHT) else null,
            deepMinutes = if (known) minutes(TandemSleepStage.DEEP) else null,
            remMinutes = if (known) minutes(TandemSleepStage.REM) else null,
            stages = if (known) runs else emptyList(),
        )
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun stageOf(type: Int): TandemSleepStage = when (type) {
        SleepSessionRecord.StageType.STAGE_TYPE_AWAKE,
        SleepSessionRecord.StageType.STAGE_TYPE_AWAKE_IN_BED,
        SleepSessionRecord.StageType.STAGE_TYPE_AWAKE_OUT_OF_BED,
        -> TandemSleepStage.WAKE
        SleepSessionRecord.StageType.STAGE_TYPE_SLEEPING_LIGHT -> TandemSleepStage.LIGHT
        SleepSessionRecord.StageType.STAGE_TYPE_SLEEPING_DEEP -> TandemSleepStage.DEEP
        SleepSessionRecord.StageType.STAGE_TYPE_SLEEPING_REM -> TandemSleepStage.REM
        else -> TandemSleepStage.UNKNOWN
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun workout(record: ExerciseSessionRecord): TandemHealthRecord =
        empty("hc-${record.metadata.id}", TandemHealthKind.WORKOUT, record.startTime, record.endTime)

    /** Health Connect keeps steps in pieces of any size. Here a day is the unit, as it is for the watch. */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun days(steps: List<StepsRecord>): List<TandemHealthRecord> {
        val zone = ZoneId.systemDefault()
        return steps.groupBy { it.startTime.atZone(zone).toLocalDate() }.map { (day, pieces) ->
            val start = day.atStartOfDay(zone)
            empty("hc-day-$day", TandemHealthKind.DAY, start.toInstant(), start.plusDays(1).toInstant()).copy(steps = pieces.sumOf { it.count }.toULong())
        }
    }

    private fun empty(id: String, kind: TandemHealthKind, start: Instant, end: Instant) = TandemHealthRecord(
        id = id,
        kind = kind,
        startMs = start.toEpochMilli().toULong(),
        endMs = end.toEpochMilli().toULong(),
        sleepMinutes = 0u,
        awakeMinutes = 0u,
        awakenings = 0u,
        efficiencyPercent = 0u,
        lightMinutes = null,
        deepMinutes = null,
        remMinutes = null,
        stages = emptyList(),
        heartRateMean = null,
        heartRateLow = null,
        heartRateHigh = null,
        steps = 0u,
        activeMinutes = 0u,
    )
}
