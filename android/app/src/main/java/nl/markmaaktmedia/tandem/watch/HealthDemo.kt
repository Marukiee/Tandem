package nl.markmaaktmedia.tandem.watch

import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random
import uniffi.tandem_core.TandemHealthKind
import uniffi.tandem_core.TandemHealthRecord
import uniffi.tandem_core.TandemSleepStage
import uniffi.tandem_core.TandemStageRun

/**
 * Six weeks of made-up nights, days and workouts, so the Health pages can be seen and tried on an emulator or a phone without a
 * watch. Only offered in builds for testing: it writes into the real list of records, which is shared with computers.
 */
object HealthDemo {
    fun records(zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): List<TandemHealthRecord> {
        val random = Random(7)
        val records = mutableListOf<TandemHealthRecord>()
        // Six weeks, so a week further back and the month before can be seen in the calendar too.
        for (back in 44 downTo 0) {
            val day = today.minusDays(back.toLong())
            records += night(day, zone, random)
            if (back > 0) records += day(day.minusDays(1), zone, random, whole = true)
            if (back % 3 == 1) records += workout(day.minusDays(1), zone, random)
        }
        records += day(today, zone, random, whole = false)
        return records
    }

    private fun night(morning: LocalDate, zone: ZoneId, random: Random): TandemHealthRecord {
        val start = morning.minusDays(1).atTime(22, 50).atZone(zone).toInstant().toEpochMilli() + random.nextLong(0, 50) * 60_000
        val runs = mutableListOf(TandemStageRun(TandemSleepStage.WAKE, (12 + random.nextInt(14)).toUInt() * 60u))
        // Cycles of about an hour and a half, with deep sleep early in the night and more dreaming toward the morning.
        for (cycle in 0 until 5) {
            runs += TandemStageRun(TandemSleepStage.LIGHT, (25 + random.nextInt(15)).toUInt() * 60u)
            runs += TandemStageRun(TandemSleepStage.DEEP, (30 - cycle * 6 + random.nextInt(6)).coerceAtLeast(4).toUInt() * 60u)
            runs += TandemStageRun(TandemSleepStage.LIGHT, (10 + random.nextInt(10)).toUInt() * 60u)
            runs += TandemStageRun(TandemSleepStage.REM, (10 + cycle * 6 + random.nextInt(8)).toUInt() * 60u)
            if (random.nextInt(3) == 0) runs += TandemStageRun(TandemSleepStage.WAKE, (2 + random.nextInt(8)).toUInt() * 60u)
        }
        val minutes = { stage: TandemSleepStage -> runs.filter { it.stage == stage }.sumOf { it.seconds.toInt() } / 60 }
        val light = minutes(TandemSleepStage.LIGHT)
        val deep = minutes(TandemSleepStage.DEEP)
        val rem = minutes(TandemSleepStage.REM)
        val awake = minutes(TandemSleepStage.WAKE)
        val sleep = light + deep + rem
        val low = 50 + random.nextInt(7)
        return TandemHealthRecord(
            id = "demo-night-$morning",
            kind = TandemHealthKind.SLEEP,
            startMs = start.toULong(),
            endMs = (start + (sleep + awake) * 60_000L).toULong(),
            sleepMinutes = sleep.toUInt(),
            awakeMinutes = awake.toUInt(),
            awakenings = runs.drop(1).count { it.stage == TandemSleepStage.WAKE }.toUInt(),
            efficiencyPercent = (100 * sleep / (sleep + awake)).toUInt(),
            lightMinutes = light.toUInt(),
            deepMinutes = deep.toUInt(),
            remMinutes = rem.toUInt(),
            stages = runs,
            heartRateMean = (low + 7).toUInt(),
            heartRateLow = low.toUInt(),
            heartRateHigh = (low + 25).toUInt(),
            steps = 0u,
            activeMinutes = 0u,
            heartCurve = emptyList(),
        )
    }

    private fun day(date: LocalDate, zone: ZoneId, random: Random, whole: Boolean): TandemHealthRecord {
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val now = java.time.LocalTime.now(zone)
        val slots = if (whole) 288 else (now.toSecondOfDay() / 300).coerceAtLeast(1)
        // Lower at night, higher in the day, with a walk at noon; zero where the watch was off the wrist in the morning.
        val curve = List(slots) { slot ->
            val hour = slot / 12.0
            when {
                hour in 7.3..7.8 -> 0
                hour < 7 || hour > 23 -> 52 + random.nextInt(6)
                hour in 12.5..13.2 -> 105 + random.nextInt(20)
                else -> 66 + random.nextInt(14)
            }.toUInt()
        }
        val share = if (whole) 1.0 else slots / 288.0
        return TandemHealthRecord(
            id = "demo-day-$date",
            kind = TandemHealthKind.DAY,
            startMs = start.toULong(),
            endMs = (start + 86_400_000L).toULong(),
            sleepMinutes = 0u, awakeMinutes = 0u, awakenings = 0u, efficiencyPercent = 0u,
            lightMinutes = null, deepMinutes = null, remMinutes = null, stages = emptyList(),
            heartRateMean = curve.filter { it > 0u }.map { it.toInt() }.average().toInt().toUInt(), heartRateLow = null, heartRateHigh = null,
            steps = ((4_000 + random.nextInt(9_000)) * share).toLong().toULong(),
            activeMinutes = ((10 + random.nextInt(50)) * share).toInt().toUInt(),
            heartCurve = curve,
            // The steps spread over the waking hours, with walks in the morning, at noon and after work.
            stepCurve = List(24) { hour -> if (hour < 7 || hour > 22) 0 else (if (hour in listOf(8, 12, 18)) 3 else 1) }.let { weights ->
                val steps = ((4_000 + random.nextInt(9_000)) * share).toLong()
                weights.map { w -> (steps * w / weights.sum()).toInt().toUInt() }
            },
        )
    }

    private fun workout(date: LocalDate, zone: ZoneId, random: Random): TandemHealthRecord {
        val start = date.atTime(18, 0).atZone(zone).toInstant().toEpochMilli()
        val minutes = 25 + random.nextInt(35)
        return TandemHealthRecord(
            id = "demo-workout-$date",
            kind = TandemHealthKind.WORKOUT,
            startMs = start.toULong(),
            endMs = (start + minutes * 60_000L).toULong(),
            sleepMinutes = 0u, awakeMinutes = 0u, awakenings = 0u, efficiencyPercent = 0u,
            lightMinutes = null, deepMinutes = null, remMinutes = null, stages = emptyList(),
            heartRateMean = (125 + random.nextInt(20)).toUInt(),
            heartRateLow = 95u,
            heartRateHigh = (160 + random.nextInt(20)).toUInt(),
            steps = (minutes * 140).toULong(),
            activeMinutes = minutes.toUInt(),
            heartCurve = emptyList(),
        )
    }
}
