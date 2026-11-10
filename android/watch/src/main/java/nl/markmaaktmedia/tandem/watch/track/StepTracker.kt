package nl.markmaaktmedia.tandem.watch.track

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import nl.markmaaktmedia.tandem.watch.link.WatchLink
import nl.markmaaktmedia.tandem.watch.protocol.Message
import nl.markmaaktmedia.tandem.watch.sleep.DailySteps
import nl.markmaaktmedia.tandem.watch.sleep.DayHeart
import android.os.SystemClock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Steps all day. The sensor batches in hardware, so this costs next to nothing. */
class StepTracker(private val context: Context) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val store = TrackingStore(context)
    private var state = store.loadSteps()

    fun start() {
        if (context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) return
        val sensor = sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return
        sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, BATCH_US)
    }

    fun stop() {
        sensors.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val before = state
        val today = LocalDate.now().toString()
        // The clock of the event and not the one of now: a batch arrives minutes after the steps were taken.
        val takenMs = System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - event.timestamp) / NANOS_PER_MILLI
        val hour = Instant.ofEpochMilli(takenMs).atZone(ZoneId.systemDefault()).hour
        state = state.update(today, event.values[0].toLong(), event.timestamp / NANOS_PER_MINUTE, hour)
        store.saveSteps(state)
        Tracking.publishSteps(state.steps, state.activeMinutes)
        WatchLink.reportSteps(state.steps, state.activeMinutes, state.hourly)
        if (before.day.isNotEmpty() && before.day != today) {
            HeartTracker.forDay(before.day) { heart -> WatchLink.sendHealth(context, before.toDayRecord(heart)) }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    private companion object {
        const val BATCH_US = 5 * 60 * 1_000_000
        const val NANOS_PER_MINUTE = 60_000_000_000L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

internal fun DailySteps.todayOrZero(): Long = if (day == LocalDate.now().toString()) steps else 0

internal fun DailySteps.activeTodayOrZero(): Int = if (day == LocalDate.now().toString()) activeMinutes else 0

/** A finished day. Its id is the date, so a day that is sent twice is still one day. */
private fun DailySteps.toDayRecord(heart: DayHeart): Message.HealthRecord {
    val start = LocalDate.parse(day).atStartOfDay(ZoneId.systemDefault())
    return Message.HealthRecord(
        id = "day-$day",
        workout = false,
        startMs = start.toInstant().toEpochMilli(),
        endMs = start.plusDays(1).toInstant().toEpochMilli(),
        sleepMinutes = 0,
        awakeMinutes = 0,
        awakenings = 0,
        efficiencyPercent = 0,
        lightMinutes = null,
        deepMinutes = null,
        remMinutes = null,
        stages = emptyList(),
        heartRateMean = heart.mean,
        heartRateLow = heart.low,
        heartRateHigh = heart.high,
        steps = steps,
        day = true,
        activeMinutes = activeMinutes,
        heartCurve = if (heart.mean == null) emptyList() else heart.curve(),
        stepCurve = hourly,
    )
}
