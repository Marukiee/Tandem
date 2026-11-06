package nl.markmaaktmedia.tandem.watch.track

import android.content.Context
import android.util.Log
import java.io.File
import nl.markmaaktmedia.tandem.watch.protocol.Message
import nl.markmaaktmedia.tandem.watch.protocol.MessageCodec
import nl.markmaaktmedia.tandem.watch.protocol.StageRun
import nl.markmaaktmedia.tandem.watch.sleep.SleepSummary
import nl.markmaaktmedia.tandem.watch.sleep.SleepStage
import nl.markmaaktmedia.tandem.watch.sleep.WorkoutSummary

/**
 * Finished nights and workouts on their way to the phone. On disk, because the phone can be away for days and a watch restarts. A
 * record leaves only when the phone says it is safe; sent one at a time, so a long absence does not crowd out a ringing call.
 */
internal class HealthOutbox(context: Context) {
    private val dir = File(context.filesDir, "health-outbox").apply { mkdirs() }

    fun add(record: Message.HealthRecord) {
        val temp = File(dir, "${record.id}.tmp")
        temp.writeBytes(MessageCodec.encode(record))
        temp.renameTo(File(dir, "${record.id}$SUFFIX"))
        files().dropLast(MAX_WAITING).forEach { it.delete() }
    }

    fun oldest(): Message.HealthRecord? {
        for (file in files()) {
            val read = runCatching { MessageCodec.decode(file.readBytes()) as? Message.HealthRecord }.getOrNull()
            if (read != null) return read
            Log.w(TAG, "Dropping a record that cannot be read: ${file.name}")
            file.delete()
        }
        return null
    }

    fun done(id: String) {
        File(dir, "$id$SUFFIX").delete()
    }

    private fun files() = dir.listFiles { file -> file.name.endsWith(SUFFIX) }.orEmpty().sortedBy { it.lastModified() }

    private companion object {
        const val TAG = "TandemHealth"
        const val SUFFIX = ".rec"
        /** A night and a workout a day for half a year. */
        const val MAX_WAITING = 360
    }
}

internal fun SleepSummary.toRecord(id: String): Message.HealthRecord {
    val stages = stages
    return Message.HealthRecord(
        id = id,
        workout = false,
        startMs = onsetMs,
        endMs = wakeMs,
        sleepMinutes = sleepMinutes,
        awakeMinutes = wakeAfterOnsetMinutes,
        awakenings = awakenings,
        efficiencyPercent = efficiencyPercent,
        lightMinutes = stages?.light,
        deepMinutes = stages?.deep,
        remMinutes = stages?.rem,
        stages = if (stages == null) emptyList() else stageRuns().map { (stage, seconds) -> StageRun(stage.wireCode(), seconds) },
        heartRateMean = meanHeartRate,
        heartRateLow = lowestHeartRate,
        heartRateHigh = null,
        steps = 0,
    )
}

internal fun WorkoutSummary.toRecord(id: String) = Message.HealthRecord(
    id = id,
    workout = true,
    startMs = startMs,
    endMs = endMs,
    sleepMinutes = 0,
    awakeMinutes = 0,
    awakenings = 0,
    efficiencyPercent = 0,
    lightMinutes = null,
    deepMinutes = null,
    remMinutes = null,
    stages = emptyList(),
    heartRateMean = meanHeartRate,
    heartRateLow = null,
    heartRateHigh = highestHeartRate,
    steps = steps,
)

private fun SleepStage.wireCode() = when (this) {
    SleepStage.WAKE -> StageRun.WAKE
    SleepStage.LIGHT -> StageRun.LIGHT
    SleepStage.DEEP -> StageRun.DEEP
    SleepStage.REM -> StageRun.REM
}
