package nl.markmaaktmedia.tandem.watch.track

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import nl.markmaaktmedia.tandem.watch.sleep.AutoSleepDecision
import nl.markmaaktmedia.tandem.watch.sleep.SleepAutoDetector
import java.util.Calendar

/**
 * Starts and stops the recording of a night by itself. Listens to a slow stream of movement that the sensor hub collects while the
 * processor sleeps, and only in the hours a night can start, so the rest of the day costs nothing.
 */
object AutoSleep : SensorEventListener {
    private const val TAG = "TandemWatchAutoSleep"
    private const val PERIOD_US = 200_000
    private const val BATCH_US = 4 * 60 * 1_000_000
    private const val MINUTE_MS = 60 * 1000L
    private const val BEDTIME_SETTING = "bedtime_mode"

    private val thread = HandlerThread("tandem-auto-sleep").apply { start() }
    private val handler = Handler(thread.looper)

    private var context: Context? = null
    private var sensors: SensorManager? = null
    private var detector = SleepAutoDetector()
    private var listening = false
    private var moving = false
    private var worn = true
    private var charging = false
    private var started = false
    private var clockOffsetMs = 0L
    private var recordingAutomatic = false

    /** Bedtime mode on the watch says more than lying still does, so a night it started also ends with it. */
    private var recordingBedtime = false
    private var bedtimeOn = false

    /** Stopped by hand during bedtime mode: that holds until the next time the mode goes on. */
    private var bedtimeDismissed = false

    private val bedtimeSetting = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = updateBedtime()
    }

    private var alarmSet = false
    private val windowAlarm = AlarmManager.OnAlarmListener {
        alarmSet = false
        update()
    }

    private val phoneState = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> handler.post { detector.onInteraction() }
                Intent.ACTION_BATTERY_CHANGED -> {
                    val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                    handler.post {
                        if (plugged != charging) {
                            charging = plugged
                            detector.setCharging(plugged)
                            updateMovement()
                            updateBedtime()
                        }
                    }
                }
                NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED -> handler.post { updateBedtime() }
            }
        }
    }

    fun start(from: Context) {
        val app = from.applicationContext
        handler.post {
            // Again on purpose: this also comes right after someone gave the permission a recording needs.
            if (started) {
                update()
                return@post
            }
            started = true
            context = app
            sensors = app.getSystemService(SensorManager::class.java)
            app.registerReceiver(phoneState, IntentFilter(Intent.ACTION_SCREEN_ON))
            app.registerReceiver(phoneState, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            app.registerReceiver(phoneState, IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED), Context.RECEIVER_EXPORTED)
            app.contentResolver.registerContentObserver(Settings.Global.getUriFor(BEDTIME_SETTING), false, bedtimeSetting)
            TrackingService.finishLeftover(app)
            update()
        }
    }

    fun stop() {
        handler.post {
            if (!started) return@post
            started = false
            stopListening()
            cancelAlarm()
            runCatching { context?.unregisterReceiver(phoneState) }
            context?.contentResolver?.unregisterContentObserver(bedtimeSetting)
        }
    }

    fun setEnabled(from: Context, on: Boolean) {
        val app = from.applicationContext
        TrackingStore(app).autoSleep = on
        Tracking.publishAutoSleep(on)
        handler.post {
            if (!on && recordingAutomatic) TrackingService.stop(app)
            if (!on) {
                recordingAutomatic = false
                detector.reset()
            }
            update()
        }
    }

    fun onStoppedByHand() {
        handler.post {
            detector.onStoppedByHand(System.currentTimeMillis())
            recordingAutomatic = false
            recordingBedtime = false
            bedtimeDismissed = bedtimeOn
            update()
        }
    }

    private fun update() {
        val app = context ?: return
        val enabled = TrackingStore(app).autoSleep && TrackingService.canRun(app)
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val inWindow = SleepAutoDetector.inWindow(hour)
        detector.setAllowed(inWindow)
        // A night that is being recorded goes on after the window closes.
        if (enabled && (inWindow || recordingAutomatic)) startListening() else stopListening()
        updateMovement()
        cancelAlarm()
        if (enabled) {
            val at = SystemClock.elapsedRealtime() + SleepAutoDetector.minutesToWindowChange(hour, calendar.get(Calendar.MINUTE)) * MINUTE_MS
            app.getSystemService(AlarmManager::class.java)
                .set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, "tandem:auto-sleep", windowAlarm, handler)
            alarmSet = true
        }
        updateBedtime()
    }

    private fun bedtimeNow(app: Context): Boolean {
        // Wear's own setting. It is not public API, so a later version may close it; bedtime mode also silences the watch, and that stays visible.
        val setting = runCatching { Settings.Global.getInt(app.contentResolver, BEDTIME_SETTING) == 1 }.getOrNull()
        return setting ?: (app.getSystemService(NotificationManager::class.java).currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL)
    }

    private fun updateBedtime() {
        val app = context ?: return
        val on = bedtimeNow(app)
        if (on != bedtimeOn) {
            bedtimeOn = on
            bedtimeDismissed = false
            Log.i(TAG, "Bedtime mode: $on")
            TrackingService.note(if (on) "bedtime_on" else "bedtime_off")
        }
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val allowed = TrackingStore(app).autoSleep && TrackingService.canRun(app) && SleepAutoDetector.inWindow(hour)
        when {
            recordingBedtime && (!on || charging || !SleepAutoDetector.inWindow(hour)) -> {
                TrackingService.stop(app)
                recordingBedtime = false
                recordingAutomatic = false
                Log.i(TAG, "Bedtime is over")
            }
            on && allowed && !charging && !bedtimeDismissed && Tracking.ui.value.active == null -> {
                recordingBedtime = TrackingService.startAutomatic(app, System.currentTimeMillis())
                recordingAutomatic = recordingBedtime
                Log.i(TAG, "Bedtime started a night: $recordingBedtime")
            }
        }
    }

    private fun cancelAlarm() {
        if (!alarmSet) return
        alarmSet = false
        context?.getSystemService(AlarmManager::class.java)?.cancel(windowAlarm)
    }

    private fun startListening() {
        if (listening) return
        val manager = sensors ?: return
        clockOffsetMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        manager.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT, true)
            ?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, handler) }
        listening = true
        Log.i(TAG, "Listening for a night")
    }

    private fun stopListening() {
        if (!listening) return
        sensors?.unregisterListener(this)
        listening = false
        moving = false
        Log.i(TAG, "Not listening")
    }

    /** A watch on the charger or the nightstand can not start a night, so it need not wake up every few minutes to find that out. */
    private fun updateMovement() {
        val manager = sensors ?: return
        val accel = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true) ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        val wanted = listening && (recordingAutomatic || (worn && !charging))
        if (wanted == moving) return
        moving = wanted
        if (wanted) manager.registerListener(this, accel, PERIOD_US, BATCH_US, handler) else manager.unregisterListener(this, accel)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT -> {
                worn = event.values[0] > 0.5f
                detector.setWorn(worn)
                updateMovement()
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val time = clockOffsetMs + event.timestamp / 1_000_000
                val g = SensorManager.GRAVITY_EARTH
                detector.onSample(time, event.values[0] / g, event.values[1] / g, event.values[2] / g)?.let(::act)
            }
        }
    }

    private fun act(decision: AutoSleepDecision) {
        val app = context ?: return
        when (decision) {
            is AutoSleepDecision.Start -> {
                // A recording someone started themselves is theirs to stop. It does keep what this would have done, to judge it by later.
                if (Tracking.ui.value.active != null) {
                    TrackingService.note("auto_start")
                    return
                }
                recordingAutomatic = TrackingService.startAutomatic(app, decision.stillSinceMs)
                Log.i(TAG, "A night started: $recordingAutomatic")
            }
            AutoSleepDecision.Stop -> {
                TrackingService.note("auto_stop")
                if (recordingBedtime) return
                if (recordingAutomatic) TrackingService.stop(app)
                recordingAutomatic = false
                Log.i(TAG, "The night ended")
                update()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
}
