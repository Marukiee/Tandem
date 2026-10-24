package nl.markmaaktmedia.tandem.live

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.sqrt

/**
 * Notices that the phone was picked up and put down again: a while of movement, then a couple of seconds of lying still. That is the moment
 * the computer it is a second screen for may find its side again (see `side.rs` in the core). A phone that is only touched, tapped or
 * turned a little does not count, and nothing is said while it is still being moved.
 */
class MoveWatcher(context: Context, private val onMoved: () -> Unit) : SensorEventListener {
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val linear = accelerometer?.type == Sensor.TYPE_LINEAR_ACCELERATION
    private var gravity = floatArrayOf(0f, 0f, 9.81f)
    private var movingSince = 0L
    private var lastMove = 0L
    private var moved = false

    fun start() {
        accelerometer?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() {
        sensors.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = SystemClock.elapsedRealtime()
        val (x, y, z) = if (linear) {
            Triple(event.values[0], event.values[1], event.values[2])
        } else {
            // Without a sensor that leaves out gravity, a slow filter follows gravity and the rest is the movement.
            for (i in 0..2) gravity[i] = 0.9f * gravity[i] + 0.1f * event.values[i]
            Triple(event.values[0] - gravity[0], event.values[1] - gravity[1], event.values[2] - gravity[2])
        }
        val strength = sqrt(x * x + y * y + z * z)
        if (strength > MOVING) {
            if (lastMove == 0L || now - lastMove > STILL_MS) movingSince = now
            lastMove = now
            // Carried, not tapped: the movement went on for a while.
            if (now - movingSince > MOVE_MS) moved = true
        } else if (moved && now - lastMove > STILL_MS) {
            moved = false
            movingSince = 0L
            onMoved()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        /** Metres a second squared, without gravity: more than the hand that taps the screen, less than a phone that is carried. */
        const val MOVING = 1.2f
        const val MOVE_MS = 600L
        const val STILL_MS = 2000L
    }
}
