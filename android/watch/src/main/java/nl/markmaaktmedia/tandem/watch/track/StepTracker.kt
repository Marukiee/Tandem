package nl.markmaaktmedia.tandem.watch.track

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import nl.markmaaktmedia.tandem.watch.sleep.DailySteps
import java.time.LocalDate

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
        state = state.update(LocalDate.now().toString(), event.values[0].toLong())
        store.saveSteps(state)
        Tracking.publishSteps(state.steps)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    private companion object {
        const val BATCH_US = 5 * 60 * 1_000_000
    }
}

internal fun DailySteps.todayOrZero(): Long = if (day == LocalDate.now().toString()) steps else 0
