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
import nl.markmaaktmedia.tandem.watch.sleep.StepFeed
import nl.markmaaktmedia.tandem.watch.sleep.count
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import nl.markmaaktmedia.tandem.watch.power.PowerLog
import nl.markmaaktmedia.tandem.watch.protocol.Tick
import nl.markmaaktmedia.tandem.watch.protocol.DeliveryCounter

/**
 * Steps all day. Health Services counts them for everyone and tells the total of the day in batches ([onDaily]); the step counter of the
 * watch stays registered next to it, which costs next to nothing because it never wakes the processor, and only counts when Health
 * Services is not there, has no permission, or says nothing for hours while the counter moves (see [StepFeed]).
 *
 * All state lives on one thread: the system calls from other threads.
 */
class StepTracker private constructor(private val context: Context) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val store = TrackingStore(context)
    private val handler = Handler(HandlerThread("tandem-steps").apply { start() }.looper)
    private var state = store.loadSteps()
    private val feed = StepFeed(
        when (store.stepSource) {
            "hs" -> StepFeed.Source.HEALTH_SERVICES
            "counter" -> StepFeed.Source.COUNTER
            else -> null
        },
    )
    private var listening = false
    private val counterDeliveries = DeliveryCounter()
    private var lastReportMs = 0L
    private var reportPosted = false

    /** One total of the day from Health Services: [bootMs] is when it was taken, on the clock the step counter also uses. */
    class DailyTotal(val total: Long, val day: String, val hour: Int, val bootMs: Long) {
        val bootMinute: Long get() = bootMs / 60_000
    }

    fun start() {
        handler.post {
            if (listening) return@post
            if (context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) return@post
            val sensor = sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return@post
            listening = sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, BATCH_US, handler)
        }
    }

    fun stop() {
        handler.post {
            sensors.unregisterListener(this)
            listening = false
        }
    }

    /** Health Services took what was asked of it, or it did not or it took the permission back: then the counter counts. */
    fun healthServices(on: Boolean) {
        handler.post {
            if (on) feed.registered(SystemClock.elapsedRealtime()) else feed.unregistered()
            Log.i(TAG, "Health Services counts the steps: $on, counting now: ${feed.counting(SystemClock.elapsedRealtime())}")
        }
    }

    /** Totals of the day from Health Services, in the order they were taken. */
    fun onDaily(totals: List<DailyTotal>) {
        handler.post {
            PowerLog.count(Tick.PASSIVE_STEPS)
            // Many come together, within the same minute: in the order they were taken, or a lower total after a higher one reads as a restart.
            for (point in totals.sortedBy { it.bootMs }) {
                count(feed.onDaily(point.total, SystemClock.elapsedRealtime()), point.day, point.bootMinute, point.hour)
            }
            Log.i(TAG, "From the system: ${totals.size} totals, ${state.steps} steps on ${state.day}, ${state.activeMinutes} active minutes")
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (counterDeliveries.onValue(SystemClock.elapsedRealtime())) PowerLog.count(Tick.STEP_BATCH)
        val fed = feed.onCounter(event.values[0].toLong(), SystemClock.elapsedRealtime()) ?: return
        // The counter counts because the system does not: it may have lost the registration, or the permission may be given again.
        PassiveHealth.reregisterIfDue(context)
        // The clock of the event and not the one of now: a batch arrives minutes after the steps were taken.
        val takenMs = System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - event.timestamp) / NANOS_PER_MILLI
        val hour = Instant.ofEpochMilli(takenMs).atZone(ZoneId.systemDefault()).hour
        count(fed, LocalDate.now().toString(), event.timestamp / NANOS_PER_MINUTE, hour)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    private fun count(fed: StepFeed.Feed, today: String, atMinute: Long, atHour: Int) {
        val before = state
        state = state.count(fed, today, atMinute, atHour)
        store.saveSteps(state)
        store.stepSource = when (feed.fedBy) {
            StepFeed.Source.HEALTH_SERVICES -> "hs"
            StepFeed.Source.COUNTER -> "counter"
            null -> ""
        }
        Tracking.publishSteps(state.steps, state.activeMinutes)
        report()
        if (before.day.isNotEmpty() && before.day != today) {
            HeartTracker.forDay(before.day) { heart -> WatchLink.sendHealth(context, before.toDayRecord(heart)) }
        }
    }

    /** At most once in five minutes, the way the old batches came: deliveries can be more often, and every message wakes the radio. */
    private fun report() {
        val now = SystemClock.elapsedRealtime()
        val wait = REPORT_EVERY_MS - (now - lastReportMs)
        if (wait <= 0) {
            lastReportMs = now
            send()
        } else if (!reportPosted) {
            reportPosted = true
            handler.postDelayed({
                reportPosted = false
                lastReportMs = SystemClock.elapsedRealtime()
                send()
            }, wait)
        }
    }

    private fun send() = WatchLink.reportSteps(state.steps, state.activeMinutes, state.hourly)

    companion object {
        private const val TAG = "TandemWatchSteps"
        private const val BATCH_US = 5 * 60 * 1_000_000
        private const val REPORT_EVERY_MS = 5 * 60_000L
        private const val NANOS_PER_MINUTE = 60_000_000_000L
        private const val NANOS_PER_MILLI = 1_000_000L

        @Volatile
        private var instance: StepTracker? = null

        /** One for the process: the link service and the service Health Services starts share what was counted. */
        fun shared(context: Context): StepTracker =
            instance ?: synchronized(this) { instance ?: StepTracker(context.applicationContext).also { instance = it } }
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
