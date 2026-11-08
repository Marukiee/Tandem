package nl.markmaaktmedia.tandem.watch.track

import java.lang.ref.WeakReference
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import nl.markmaaktmedia.tandem.watch.R
import nl.markmaaktmedia.tandem.watch.link.WatchLink
import nl.markmaaktmedia.tandem.watch.sleep.ACCEL_UNITS_PER_G
import nl.markmaaktmedia.tandem.watch.sleep.AwakeAfterAlarm
import nl.markmaaktmedia.tandem.watch.sleep.RawLogWriter
import nl.markmaaktmedia.tandem.watch.sleep.MARK_OFF
import nl.markmaaktmedia.tandem.watch.sleep.MARK_WORN
import nl.markmaaktmedia.tandem.watch.sleep.NapAnalyzer
import nl.markmaaktmedia.tandem.watch.sleep.RawRecord
import nl.markmaaktmedia.tandem.watch.sleep.SleepAnalyzer
import nl.markmaaktmedia.tandem.watch.sleep.SleepAutoDetector
import nl.markmaaktmedia.tandem.watch.sleep.SmartWake
import nl.markmaaktmedia.tandem.watch.sleep.WorkoutAnalyzer
import nl.markmaaktmedia.tandem.watch.sleep.readRawLog
import nl.markmaaktmedia.tandem.watch.ui.MainActivity
import nl.markmaaktmedia.tandem.watch.update.SelfUpdate
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records raw sensor data to a file and analyses it when stopped. Raw on purpose: when the sleep analysis gets
 * better, old nights can be analysed again. Samples are batched in the sensor hub so the processor can stay asleep.
 */
class TrackingService : Service(), SensorEventListener {
    private val thread = HandlerThread("tandem-watch-sensors").apply { start() }
    private val handler = Handler(thread.looper)
    private lateinit var sensors: SensorManager

    private var kind: TrackingKind? = null
    private var file: File? = null
    private var output: BufferedOutputStream? = null
    private var writer: RawLogWriter? = null
    private var clockOffsetMs = 0L
    private var automatic = false
    private var stopping = false

    private val flushLoop = object : Runnable {
        override fun run() {
            if (kind == TrackingKind.SLEEP) followAlarm()
            // At most a minute of data is lost if the battery dies.
            writer?.flush()
            HeartTracker.flush()
            handler.postDelayed(this, FLUSH_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = WeakReference(this)
        sensors = getSystemService(SensorManager::class.java)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val requested = TrackingKind.entries.getOrNull(intent.getIntExtra(EXTRA_KIND, -1)) ?: return START_NOT_STICKY
                begin(requested, intent.getLongExtra(EXTRA_STILL_SINCE, 0))
            }
            ACTION_STOP -> finish()
            // Restarted by the system after being killed: the file is intact but the recording stopped there.
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun begin(requested: TrackingKind, stillSince: Long) {
        // While a stop finishes, a new recording would be ended by the stopSelf at its end.
        if (kind != null || stopping) return
        automatic = stillSince != 0L
        try {
            startForeground(NOTIFICATION_ID, buildNotification(requested), ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot record without permission: ${e.message}")
            stopSelf()
            return
        }
        kind = requested
        handler.post {
            pruneOldFiles()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
            val target = File(rawDir(this), "${requested.name.lowercase(Locale.ROOT)}-$stamp.tws")
            TrackingStore(this).unfinished = target.name
            val stream = BufferedOutputStream(FileOutputStream(target), BUFFER_BYTES)
            file = target
            output = stream
            val log = RawLogWriter(stream, writeHeader = true)
            writer = log
            val now = System.currentTimeMillis()
            clockOffsetMs = now - SystemClock.elapsedRealtime()
            log.write(RawRecord.Mark(now, requested.name))
            if (stillSince != 0L) log.write(RawRecord.Mark(stillSince, SleepAutoDetector.STILL_SINCE))
            register(requested)
            shortBatches = false
            if (requested == TrackingKind.SLEEP) followAlarm()
            handler.postDelayed(flushLoop, FLUSH_MS)
            Tracking.publishActive(requested, now)
            if (requested == TrackingKind.SLEEP) WatchLink.setSleepMode(true)
        }
    }

    private var smartWake: SmartWake? = null

    /** From the start of waking gently: whether the wrist moved after the alarm. On the sensor thread, like the samples it reads. */
    private var awakeAfterAlarm: AwakeAfterAlarm? = null
    private var shortBatches = false

    private fun register(requested: TrackingKind, batchUs: Int = if (requested == TrackingKind.SLEEP) SLEEP_BATCH_US else WORKOUT_BATCH_US) {
        wakeUpOrPlain(Sensor.TYPE_ACCELEROMETER)?.let { sensors.registerListener(this, it, ACCEL_PERIOD_US, batchUs, handler) }
        wakeUpOrPlain(Sensor.TYPE_HEART_RATE)
            ?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, batchUs, handler) }
        wakeUpOrPlain(Sensor.TYPE_STEP_COUNTER)
            ?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, batchUs, handler) }
        if (requested == TrackingKind.SLEEP) {
            // A watch on the nightstand lies perfectly still too.
            wakeUpOrPlain(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT)
                ?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, handler) }
            // The one permission that may be refused: the night is simply logged without it.
            if (checkSelfPermission(SKIN_TEMPERATURE_PERMISSION) == PackageManager.PERMISSION_GRANTED) sensors.getSensorList(Sensor.TYPE_ALL)
                .filter { it.stringType == SKIN_TEMPERATURE_TYPE }
                .let { found -> found.firstOrNull { it.isWakeUpSensor } ?: found.firstOrNull() }
                ?.let { sensors.registerListener(this, it, SKIN_PERIOD_US, batchUs, handler) }
        }
    }

    /** A wake-up sensor delivers its batch by waking the processor. An ordinary one drops samples while it sleeps. */
    private fun wakeUpOrPlain(type: Int) = sensors.getDefaultSensor(type, true) ?: sensors.getDefaultSensor(type)

    override fun onSensorChanged(event: SensorEvent) {
        val log = writer ?: return
        val time = clockOffsetMs + event.timestamp / 1_000_000
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                log.write(RawRecord.Accel(time, toUnits(event.values[0]), toUnits(event.values[1]), toUnits(event.values[2])))
                considerWaking(time)
                awakeAfterAlarm?.let { awake ->
                    val g = SensorManager.GRAVITY_EARTH
                    if (awake.add(time, event.values[0] / g, event.values[1] / g, event.values[2] / g)) {
                        awakeAfterAlarm = null
                        GentleWake.awake()
                    }
                }
            }
            Sensor.TYPE_HEART_RATE -> {
                log.write(RawRecord.HeartRate(time, event.values[0].toInt(), event.accuracy))
                // Accuracy zero means no contact, so it says nothing about the heart.
                if (event.accuracy > 0) HeartTracker.add(time, event.values[0].toInt())
            }
            Sensor.TYPE_STEP_COUNTER -> log.write(RawRecord.Steps(time, event.values[0].toLong()))
            Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT -> log.write(RawRecord.Mark(time, if (event.values[0] > 0.5f) MARK_WORN else MARK_OFF))
            else -> if (event.sensor.stringType == SKIN_TEMPERATURE_TYPE) {
                log.write(RawRecord.SkinTemperature(time, (event.values[0] * 100).toInt()))
            }
        }
    }

    /** Again during the night: a phone that moves its alarm should not have to wait for the next night. */
    private fun followAlarm() {
        val alarm = TrackingStore(this).smartWakeAlarmMs
        if (smartWake?.alarmMs == alarm) return
        smartWake = alarm.takeIf { it > System.currentTimeMillis() }?.let { SmartWake(it) }
    }

    /**
     * Stays with the night it started in. The batches get shorter before it counts: a whole long batch fits between now and the moment,
     * and waiting for one could mean missing it.
     */
    private fun considerWaking(time: Long) {
        val wake = smartWake ?: return
        if (time < wake.windowStartMs - SMART_WAKE_PREPARE_MS) return
        if (!shortBatches) {
            shortBatches = true
            sensors.unregisterListener(this)
            register(TrackingKind.SLEEP, SMART_WAKE_BATCH_US)
        }
        if (wake.isTime(time)) {
            GentleWake.start(this, wake.alarmMs)
            // Moving after the alarm is waking up: only someone who still lies still gets it stronger.
            awakeAfterAlarm = AwakeAfterAlarm(wake.alarmMs)
        }
    }

    private fun toUnits(metersPerSecond: Float): Short =
        (metersPerSecond / SensorManager.GRAVITY_EARTH * ACCEL_UNITS_PER_G)
            .toInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    private fun finish() {
        if (stopping) return
        val finishing = kind ?: run {
            stopSelf()
            return
        }
        stopping = true
        kind = null
        // Ask for what is still in the sensor hub, and give it a moment to arrive before closing the file.
        sensors.flush(this)
        handler.postDelayed({
            sensors.unregisterListener(this)
            handler.removeCallbacks(flushLoop)
            runCatching { output?.close() }
            writer = null
            output = null
            HeartTracker.flush()
            file?.let { analyzeRecording(this, it, finishing, automatic) }
            TrackingStore(this).unfinished = null
            if (finishing == TrackingKind.SLEEP) WatchLink.setSleepMode(false)
            Tracking.publishActive(null, 0)
            stopping = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }, FLUSH_WAIT_MS)
    }

    private fun pruneOldFiles() {
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        rawDir(this).listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        // Recordings moved to the external folder, so nothing else looks here any more.
        File(filesDir, "raw").takeIf { it != rawDir(this) }?.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    private fun buildNotification(requested: TrackingKind): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_tracking), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val title = if (requested == TrackingKind.SLEEP) R.string.tracking_sleep else R.string.tracking_workout
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(if (requested == TrackingKind.SLEEP) R.drawable.sym_bedtime else R.drawable.sym_monitoring)
            .setContentTitle(getString(title))
            .setContentIntent(open)
            .setOngoing(true)
            .setLocalOnly(true)
            .build()
    }

    override fun onDestroy() {
        sensors.unregisterListener(this)
        // The night is over, so whoever slept it is awake.
        if (GentleWake.running) GentleWake.awake()
        runCatching { output?.close() }
        thread.quitSafely()
        if (running.get() === this) running = WeakReference(null)
        // An update of the app waits for a recording to end, because it restarts the app.
        SelfUpdate.idle(applicationContext)
        super.onDestroy()
    }

    companion object {
        private var running = WeakReference<TrackingService>(null)

        /** A night or a workout is being recorded now. */
        val recording: Boolean get() = running.get() != null

        /** Puts something that happened in the recording under way, to look at when a night is judged afterwards. */
        fun note(label: String) {
            val service = running.get() ?: return
            service.handler.post { service.writer?.write(RawRecord.Mark(System.currentTimeMillis(), label)) }
        }

        private const val TAG = "TandemWatchTracking"
        private const val ACTION_START = "nl.markmaaktmedia.tandem.watch.TRACK_START"
        private const val ACTION_STOP = "nl.markmaaktmedia.tandem.watch.TRACK_STOP"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_STILL_SINCE = "still_since"
        private const val SKIN_TEMPERATURE_TYPE = "com.google.sensor.skin_temperature"
        private const val SKIN_TEMPERATURE_PERMISSION = "android.permission.health.READ_SKIN_TEMPERATURE"
        private const val SKIN_PERIOD_US = 30 * 1_000_000
        private const val CHANNEL = "tracking"
        private const val NOTIFICATION_ID = 300

        /** 25 Hz: enough for wrist movement, and about 5 MB for a night. */
        private const val ACCEL_PERIOD_US = 40_000
        private const val SLEEP_BATCH_US = 5 * 60 * 1_000_000
        private const val SMART_WAKE_BATCH_US = 30 * 1_000_000
        private const val SMART_WAKE_PREPARE_MS = 6 * 60_000L
        private const val WORKOUT_BATCH_US = 10 * 1_000_000
        private const val FLUSH_MS = 60_000L
        private const val FLUSH_WAIT_MS = 1_500L
        private const val BUFFER_BYTES = 64 * 1024
        private const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000

        /** Where the health pages start calling sleep a night. Under it, it says nothing about how that night was. */
        private const val MIN_NIGHT_MINUTES = 180

        // In the app's external folder because that is the one place a person can pull a night from over USB with a release build.
        // No other app can read it, the same as the private folder.
        fun rawDir(context: Context) = (context.getExternalFilesDir("raw") ?: File(context.filesDir, "raw")).apply { mkdirs() }

        private fun analyzeRecording(context: Context, target: File, finished: TrackingKind, automatic: Boolean) {
            val store = TrackingStore(context)
            when (finished) {
                TrackingKind.SLEEP -> {
                    val summary = runCatching { target.inputStream().use { SleepAnalyzer.analyze(it) } }
                        .onFailure { Log.w(TAG, "Could not read a night", it) }
                        .getOrNull()
                    // Nobody asked for this recording, so when it turned out not to be a night it never happened.
                    if (automatic && summary == null) {
                        target.delete()
                        return
                    }
                    // Started by hand: they said they would sleep, and the night model needs an hour to say anything.
                    val nap = if (summary != null) summary.takeIf { it.sleepMinutes < MIN_NIGHT_MINUTES } else {
                        runCatching { target.inputStream().use { NapAnalyzer.analyze(it) } }
                            .onFailure { Log.w(TAG, "Could not read a nap", it) }
                            .getOrNull()
                    }
                    if (nap != null) {
                        Tracking.publishNap(store.saveNap(nap.onsetMs, nap.sleepMinutes))
                        WatchLink.sendHealth(context, nap.toRecord(target.nameWithoutExtension))
                        return
                    }
                    Tracking.publishSleep(store.saveSleep(summary))
                    summary?.let { WatchLink.sendHealth(context, it.toRecord(target.nameWithoutExtension)) }
                }
                TrackingKind.WORKOUT -> {
                    val records = runCatching { target.inputStream().use(::readRawLog) }.getOrDefault(emptyList())
                    val summary = WorkoutAnalyzer.analyze(records)
                    Tracking.publishWorkout(store.saveWorkout(summary))
                    summary?.let { WatchLink.sendHealth(context, it.toRecord(target.nameWithoutExtension)) }
                }
            }
        }

        /** A recording that ended with the process or the battery was never analysed. Call before a new one can start. */
        fun finishLeftover(context: Context) {
            // The link service can restart while a night is being recorded: that file is not left over, it is still being written.
            if (Tracking.ui.value.active != null) return
            val store = TrackingStore(context)
            val name = store.unfinished ?: return
            store.unfinished = null
            val target = File(rawDir(context), name)
            if (!target.exists()) return
            val finished = if (name.startsWith("workout")) TrackingKind.WORKOUT else TrackingKind.SLEEP
            // Whether it started by itself is not remembered: a short leftover is not worth a place on the screen anyway.
            analyzeRecording(context, target, finished, automatic = true)
        }

        /** A health service may only run with one of these; starting without would crash the app. */
        fun canRun(context: Context) = listOf(
            Manifest.permission.ACTIVITY_RECOGNITION,
            Manifest.permission.BODY_SENSORS,
            "android.permission.health.READ_HEART_RATE",
        ).any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

        fun start(context: Context, kind: TrackingKind) {
            context.startForegroundService(
                Intent(context, TrackingService::class.java).setAction(ACTION_START).putExtra(EXTRA_KIND, kind.ordinal),
            )
        }

        /** False when Android refuses: a service like this may not always start without the person at the watch. */
        fun startAutomatic(context: Context, stillSinceMs: Long): Boolean = canRun(context) && runCatching {
            context.startForegroundService(
                Intent(context, TrackingService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_KIND, TrackingKind.SLEEP.ordinal)
                    .putExtra(EXTRA_STILL_SINCE, stillSinceMs),
            )
        }.onFailure { Log.w(TAG, "Could not start the recording of a night", it) }.isSuccess

        fun stop(context: Context) {
            context.startService(Intent(context, TrackingService::class.java).setAction(ACTION_STOP))
        }
    }
}
