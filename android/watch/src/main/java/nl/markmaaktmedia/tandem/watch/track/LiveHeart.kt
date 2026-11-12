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

/**
 * The heart rate now, for a phone that shows it: the sensor on all the time while the phone asks, and a value to the phone every couple
 * of seconds. The phone asks again every half minute while it shows it; without that this stops by itself, so the light of the sensor
 * never stays on for a phone that went away. Once a minute a value also goes into the day, as [HeartTracker] would have measured it.
 */
object LiveHeart : SensorEventListener {
    private const val TAG = "TandemWatchLiveHeart"
    private const val STOP_AFTER_MS = 75_000L
    private const val SEND_EVERY_MS = 2_000L
    private const val INTO_DAY_EVERY_MS = 60_000L

    private val thread = HandlerThread("tandem-live-heart").apply { start() }
    private val handler = Handler(thread.looper)

    private var sensors: SensorManager? = null
    private var listening = false
    private var lastSent = 0L
    private var lastIntoDay = 0L
    private val timeUp = Runnable { stop() }

    /** From the phone: [on] starts or keeps it going for a while yet, off stops it. */
    fun ask(from: Context, on: Boolean) {
        val app = from.applicationContext
        handler.post {
            handler.removeCallbacks(timeUp)
            if (!on) {
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
            stop()
        }
    }

    private fun start(app: Context) {
        val manager = app.getSystemService(SensorManager::class.java) ?: return
        // The kind that wakes the watch, where there is one: a watch asleep would hold the values back until it wakes.
        val sensor = manager.getDefaultSensor(Sensor.TYPE_HEART_RATE, true) ?: manager.getDefaultSensor(Sensor.TYPE_HEART_RATE) ?: return
        sensors = manager
        lastSent = 0L
        listening = manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, 0, handler)
        Log.i(TAG, "Live: $listening")
    }

    private fun stop() {
        if (!listening) return
        sensors?.unregisterListener(this)
        listening = false
        Log.i(TAG, "Live stopped")
        WatchLink.sendHeartNow(0)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!listening) return
        val bpm = event.values[0].toInt()
        if (bpm <= 0 || event.accuracy < SensorManager.SENSOR_STATUS_ACCURACY_LOW) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastSent >= SEND_EVERY_MS) {
            lastSent = now
            WatchLink.sendHeartNow(bpm)
        }
        if (now - lastIntoDay >= INTO_DAY_EVERY_MS) {
            lastIntoDay = now
            HeartTracker.add(System.currentTimeMillis(), bpm)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
}
