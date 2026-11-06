package nl.markmaaktmedia.tandem.watch

import android.content.Context
import android.health.connect.HealthConnectException
import android.health.connect.HealthConnectManager
import android.health.connect.InsertRecordsResponse
import android.health.connect.ReadRecordsRequestUsingFilters
import android.health.connect.ReadRecordsResponse
import android.health.connect.TimeInstantRangeFilter
import android.health.connect.datatypes.ExerciseSessionRecord
import android.health.connect.datatypes.ExerciseSessionType
import android.health.connect.datatypes.Metadata
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
    val WRITE_PERMISSIONS = arrayOf(
        "android.permission.health.WRITE_SLEEP",
        "android.permission.health.WRITE_STEPS",
        "android.permission.health.WRITE_EXERCISE",
    )

    /** More than half of a night in common: it is the same night, seen by two apps. */
    private const val SAME_NIGHT_SHARE = 0.5

    /** A night that another app measured too, for comparing. */
    data class SameNight(val app: String, val mine: TandemHealthRecord, val other: TandemHealthRecord)

    /** What to keep of what Health Connect has, and which nights of this device another app measured as well. */
    data class Found(val fresh: List<TandemHealthRecord>, val compared: List<SameNight>)

    private const val DAYS_BACK = 365L
    private const val PAGE = 1000
    private const val PREFIX = "hc-"

    fun available(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && context.getSystemService(Context.HEALTHCONNECT_SERVICE) != null

    /**
     * What Health Connect has of the last year. What this app wrote there itself stays out, and so does a night or a day this device
     * measured itself: two sources for one night would count it twice.
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    suspend fun read(context: Context, have: List<TandemHealthRecord>): Found {
        val manager = context.getSystemService(HealthConnectManager::class.java)
        val until = Instant.now()
        val from = until.minus(DAYS_BACK, ChronoUnit.DAYS)
        fun <T : Record> others(records: List<T>) = records.filter { it.metadata.dataOrigin.packageName != context.packageName }
        val mineOnly = have.filterNot { it.id.startsWith(PREFIX) }
        val compared = mutableListOf<SameNight>()
        val nights = others(all(manager, SleepSessionRecord::class.java, from, until)).mapNotNull { record ->
            val other = night(record)
            val same = mineOnly.firstOrNull { it.kind == TandemHealthKind.SLEEP && overlap(it, other) >= SAME_NIGHT_SHARE }
            if (same != null) compared += SameNight(record.metadata.dataOrigin.packageName, same, other)
            other.takeIf { same == null }
        }
        val workouts = others(all(manager, ExerciseSessionRecord::class.java, from, until)).map(::workout)
            .filterNot { other -> mineOnly.any { it.kind == TandemHealthKind.WORKOUT && overlap(it, other) >= SAME_NIGHT_SHARE } }
        val stepDays = days(others(all(manager, StepsRecord::class.java, from, until)))
            .filterNot { other -> mineOnly.any { it.kind == TandemHealthKind.DAY && it.startMs == other.startMs } }
        return Found(nights + workouts + stepDays, compared)
    }

    private fun overlap(a: TandemHealthRecord, b: TandemHealthRecord): Double {
        val shared = minOf(a.endMs, b.endMs).toLong() - maxOf(a.startMs, b.startMs).toLong()
        val shortest = minOf(a.endMs - a.startMs, b.endMs - b.startMs).toLong()
        return if (shortest <= 0) 0.0 else shared.toDouble() / shortest
    }

    /**
     * Nights, workouts and days of steps to Health Connect, so other apps see the same numbers as this one. The id of the record goes
     * along, so writing one twice replaces it. What came from Health Connect itself stays out.
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    suspend fun write(context: Context, records: List<TandemHealthRecord>) {
        val fresh = records.filterNot { it.id.startsWith(PREFIX) }.mapNotNull(::toRecord)
        if (fresh.isEmpty()) return
        val manager = context.getSystemService(HealthConnectManager::class.java)
        suspendCoroutine { done ->
            manager.insertRecords(
                fresh,
                Runnable::run,
                object : OutcomeReceiver<InsertRecordsResponse, HealthConnectException> {
                    override fun onResult(result: InsertRecordsResponse) = done.resume(Unit)
                    override fun onError(error: HealthConnectException) = done.resumeWithException(error)
                },
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun toRecord(record: TandemHealthRecord): Record? {
        val metadata = Metadata.Builder().setClientRecordId(record.id).build()
        val start = Instant.ofEpochMilli(record.startMs.toLong())
        val end = Instant.ofEpochMilli(record.endMs.toLong())
        if (!end.isAfter(start)) return null
        return when (record.kind) {
            TandemHealthKind.SLEEP -> {
                var at = start
                val stages = record.stages.mapNotNull { run ->
                    val until = minOf(at.plusSeconds(run.seconds.toLong()), end)
                    val stage = if (until.isAfter(at)) SleepSessionRecord.Stage(at, until, typeOf(run.stage)) else null
                    at = until
                    stage
                }
                SleepSessionRecord.Builder(metadata, start, end).setStages(stages).build()
            }
            TandemHealthKind.WORKOUT -> ExerciseSessionRecord.Builder(metadata, start, end, ExerciseSessionType.EXERCISE_SESSION_TYPE_OTHER_WORKOUT).build()
            TandemHealthKind.DAY -> if (record.steps > 0u) StepsRecord.Builder(metadata, start, end, record.steps.toLong()).build() else null
            TandemHealthKind.UNKNOWN -> null
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun typeOf(stage: TandemSleepStage): Int = when (stage) {
        TandemSleepStage.WAKE -> SleepSessionRecord.StageType.STAGE_TYPE_AWAKE
        TandemSleepStage.LIGHT -> SleepSessionRecord.StageType.STAGE_TYPE_SLEEPING_LIGHT
        TandemSleepStage.DEEP -> SleepSessionRecord.StageType.STAGE_TYPE_SLEEPING_DEEP
        TandemSleepStage.REM -> SleepSessionRecord.StageType.STAGE_TYPE_SLEEPING_REM
        TandemSleepStage.UNKNOWN -> SleepSessionRecord.StageType.STAGE_TYPE_UNKNOWN
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
        return empty("$PREFIX${record.metadata.id}", TandemHealthKind.SLEEP, record.startTime, record.endTime).copy(
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
        empty("$PREFIX${record.metadata.id}", TandemHealthKind.WORKOUT, record.startTime, record.endTime)

    /** Health Connect keeps steps in pieces of any size. Here a day is the unit, as it is for the watch. */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun days(steps: List<StepsRecord>): List<TandemHealthRecord> {
        val zone = ZoneId.systemDefault()
        return steps.groupBy { it.startTime.atZone(zone).toLocalDate() }.map { (day, pieces) ->
            val start = day.atStartOfDay(zone)
            empty("${PREFIX}day-$day", TandemHealthKind.DAY, start.toInstant(), start.plusDays(1).toInstant()).copy(steps = pieces.sumOf { it.count }.toULong())
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
