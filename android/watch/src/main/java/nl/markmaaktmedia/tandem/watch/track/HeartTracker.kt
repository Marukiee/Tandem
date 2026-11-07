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
import nl.markmaaktmedia.tandem.watch.sleep.DayHeart
import nl.markmaaktmedia.tandem.watch.sleep.HeartSample
import nl.markmaaktmedia.tandem.watch.sleep.burstHeartRate
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Heart rate all day: half a minute in every five, because the light of the sensor is what costs the battery. It wakes with the wrist
 * movement [AutoSleep] gets anyway, so there is no alarm of its own and a watch off the wrist measures nothing. A recording measures
 * all the time and adds what it finds.
 */
object HeartTracker : SensorEventListener {
    private const val TAG = "TandemWatchHeart"

    /** The sensor hub measures and hands it all over at the end, so the processor sleeps while the light is on. */
    private const val MEASURE_US = 30 * 1_000_000
    private const val ENOUGH_MS = 25_000L
    private const val GIVE_UP_MS = 120_000L
    private const val SLOT_MS = 5 * 60_000L
    private const val SEND_EVERY_SLOTS = 3
    private const val KEEP_DAYS = 3L

    private val thread = HandlerThread("tandem-heart").apply { start() }
    private val handler = Handler(thread.looper)

    private var context: Context? = null
    private var sensors: SensorManager? = null
    private var day: DayHeart? = null
    private var dirty = false
    private var measuringSince = 0L
    private var finishing = false
    private var lastSlot = -1L
    private var slotsSinceSend = 0
    private val samples = ArrayList<HeartSample>()

    fun start(from: Context) {
        val app = from.applicationContext
        handler.post {
            if (context != null) return@post
            context = app
            sensors = app.getSystemService(SensorManager::class.java)
            load(LocalDate.now().toString())
            publish()
        }
    }

    fun stop() {
        handler.post {
            stopMeasuring()
            save()
        }
    }

    /** The processor is awake anyway, so this is the moment to see whether a measurement is due. */
    fun tick() {
        handler.post {
            val app = context ?: return@post
            if (measuringSince != 0L && SystemClock.elapsedRealtime() - measuringSince > GIVE_UP_MS) {
                Log.i(TAG, "Nothing from the sensor")
                stopMeasuring()
            }
            if (measuringSince != 0L || !TrackingStore(app).heartAllDay || Tracking.ui.value.active != null) return@post
            val slot = System.currentTimeMillis() / SLOT_MS
            if (slot == lastSlot) return@post
            lastSlot = slot
            measure()
        }
    }

    /** From a recording. Saved at the next [flush]: this comes many times a minute. */
    fun add(timeMs: Long, bpm: Int) {
        handler.post { addNow(timeMs, bpm) }
    }

    fun flush() {
        handler.post {
            if (!dirty) return@post
            save()
            publish()
            sendAfterSlot()
        }
    }

    fun setEnabled(from: Context, on: Boolean, tellPhone: Boolean) {
        val app = from.applicationContext
        TrackingStore(app).heartAllDay = on
        Tracking.publishHeartAllDay(on)
        if (tellPhone) WatchLink.sendHeartAllDay(on)
        handler.post { if (!on) stopMeasuring() }
        AutoSleep.refresh()
    }

    /** Gives a finished day to [use], on this thread. A day with nothing measured is just empty. */
    fun forDay(dayName: String, use: (DayHeart) -> Unit) {
        handler.post {
            save()
            use(if (day?.day == dayName) day!! else DayHeart.fromBytes(dayName, read(dayName)))
        }
    }

    /** Again after each connection: the phone only remembers what it heard. */
    fun sendToday() {
        handler.post { day?.takeIf { it.mean != null }?.let { WatchLink.reportHeart(it.curve()) } }
    }

    private fun measure() {
        val manager = sensors ?: return
        val sensor = manager.getDefaultSensor(Sensor.TYPE_HEART_RATE, true) ?: manager.getDefaultSensor(Sensor.TYPE_HEART_RATE) ?: return
        samples.clear()
        finishing = false
        measuringSince = SystemClock.elapsedRealtime()
        val listening = manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, MEASURE_US, handler)
        if (!listening) measuringSince = 0
        Log.i(TAG, "Measuring: $listening, wake-up sensor: ${sensor.isWakeUpSensor}")
    }

    private fun stopMeasuring() {
        sensors?.unregisterListener(this)
        measuringSince = 0
    }

    private val finish = Runnable {
        val value = burstHeartRate(samples)
        Log.i(TAG, "Measured $value from ${samples.size} values in ${SystemClock.elapsedRealtime() - measuringSince} ms")
        stopMeasuring()
        if (value != null) {
            val now = System.currentTimeMillis()
            addNow(now, value)
            AutoSleep.onHeart(now, value)
            save()
            publish()
            sendAfterSlot()
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (measuringSince == 0L) return
        samples += HeartSample(event.values[0].toInt(), event.accuracy)
        // A batch comes as one delivery. What is posted here runs after it, while the processor is still awake for it.
        if (!finishing && SystemClock.elapsedRealtime() - measuringSince >= ENOUGH_MS) {
            finishing = true
            handler.post(finish)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    private fun addNow(timeMs: Long, bpm: Int) {
        val moment = Instant.ofEpochMilli(timeMs).atZone(ZoneId.systemDefault())
        val name = moment.toLocalDate().toString()
        if (day?.day != name) {
            save()
            load(name)
        }
        day?.add(moment.hour * 60 + moment.minute, bpm)
        dirty = true
    }

    private fun sendAfterSlot() {
        if (++slotsSinceSend < SEND_EVERY_SLOTS) return
        slotsSinceSend = 0
        day?.let { WatchLink.reportHeart(it.curve()) }
    }

    private fun publish() {
        val today = day?.takeIf { it.day == LocalDate.now().toString() }
        Tracking.publishHeart(today?.latest, today?.low, today?.high, today?.curve().orEmpty())
    }

    private fun folder() = File(context!!.filesDir, "heart").apply { mkdirs() }

    private fun read(name: String): ByteArray? = runCatching { File(folder(), "$name.bin").readBytes() }.getOrNull()

    private fun load(name: String) {
        day = DayHeart.fromBytes(name, read(name))
        val oldest = LocalDate.now().minusDays(KEEP_DAYS).toString()
        // Dates in this form sort like text. Older days went to the phone in their day record.
        folder().listFiles()?.filter { it.name < oldest }?.forEach { it.delete() }
    }

    private fun save() {
        val current = day ?: return
        if (!dirty) return
        dirty = false
        runCatching { File(folder(), "${current.day}.bin").writeBytes(current.toBytes()) }
            .onFailure { Log.w(TAG, "Could not save the day", it) }
    }
}
