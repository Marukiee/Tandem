package nl.markmaaktmedia.tandem.watch.track

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import nl.markmaaktmedia.tandem.watch.link.WatchLink
import nl.markmaaktmedia.tandem.watch.protocol.LiveHeartLimit

/**
 * The heart rate now, for a phone that shows it: the sensor on all the time while the phone asks, and a value to the phone every couple
 * of seconds. The phone asks again every half minute while it shows it; without that this stops by itself, so the light of the sensor
 * never stays on for a phone that went away. Once a minute a value also goes into the day, as [HeartTracker] would have measured it.
 * A phone that never stops asking (a page behind other windows) gets [LiveHeartLimit.MAX_RUN_MS] at a time, then a rest: this is the
 * costliest thing the watch does.
 */
object LiveHeart : SensorEventListener {
    private const val TAG = "TandemWatchLiveHeart"
    private const val STOP_AFTER_MS = 75_000L
    private const val SEND_EVERY_MS = 2_000L
    private const val INTO_DAY_EVERY_MS = 60_000L

    /** The sensor hub keeps the values of this long and hands them over together, so the processor wakes once for them and not for each. */
    private const val BATCH_US = 2_000_000

    private val thread = HandlerThread("tandem-live-heart").apply { start() }
    private val handler = Handler(thread.looper)

    private var sensors: SensorManager? = null
    private var listening = false
    private var lastSent = 0L
    private var lastIntoDay = 0L
    private var latest = 0
    private var sendPosted = false
    private val limit = LiveHeartLimit()
    private val timeUp = Runnable {
        limit.stopped()
        stop()
    }

    /** From the phone: [on] starts or keeps it going for a while yet, off stops it. */
    fun ask(from: Context, on: Boolean) {
        val app = from.applicationContext
        handler.post {
            handler.removeCallbacks(timeUp)
            if (!on) {
                limit.stopped()
                stop()
                return@post
            }
            if (!limit.ask(SystemClock.elapsedRealtime())) {
                // Resting: whoever shows the number is not watching it any more, or it would not still be asking.
                stop()
                return@post
            }
            if (!listening) start(app)
            handler.postDelayed(timeUp, STOP_AFTER_MS)
        }
    }

    /** The phone is gone: nobody to show it to. */
    fun linkDown() {
        handler.post {
            handler.removeCallbacks(timeUp)
            limit.stopped()
            stop()
        }
    }

    private fun start(app: Context) {
        val manager = app.getSystemService(SensorManager::class.java) ?: return
        // The kind that wakes the watch, where there is one: a watch asleep would hold the values back until it wakes.
        val sensor = manager.getDefaultSensor(Sensor.TYPE_HEART_RATE, true) ?: manager.getDefaultSensor(Sensor.TYPE_HEART_RATE) ?: return
        sensors = manager
        lastSent = 0L
        listening = manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, BATCH_US, handler)
        Log.i(TAG, "Live: $listening")
    }

    private fun stop() {
        if (!listening) return
        sensors?.unregisterListener(this)
        listening = false
        sendPosted = false
        Log.i(TAG, "Live stopped")
        WatchLink.sendHeartNow(0)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!listening) return
        val bpm = event.values[0].toInt()
        if (bpm <= 0 || event.accuracy < SensorManager.SENSOR_STATUS_ACCURACY_LOW) return
        latest = bpm
        // A batch comes as one delivery, oldest value first. What is posted here runs after it, and sends the newest.
        if (!sendPosted) {
            sendPosted = true
            handler.post(sendLatest)
        }
    }

    private val sendLatest = Runnable {
        sendPosted = false
        if (!listening) return@Runnable
        val now = SystemClock.elapsedRealtime()
        if (now - lastSent >= SEND_EVERY_MS) {
            lastSent = now
            WatchLink.sendHeartNow(latest)
        }
        if (now - lastIntoDay >= INTO_DAY_EVERY_MS) {
            lastIntoDay = now
            HeartTracker.add(System.currentTimeMillis(), latest)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
}
