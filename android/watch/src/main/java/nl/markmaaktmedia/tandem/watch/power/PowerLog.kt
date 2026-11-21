package nl.markmaaktmedia.tandem.watch.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import nl.markmaaktmedia.tandem.watch.link.WatchLink
import nl.markmaaktmedia.tandem.watch.protocol.PowerClock
import nl.markmaaktmedia.tandem.watch.protocol.PowerRecord
import nl.markmaaktmedia.tandem.watch.protocol.PowerTally
import nl.markmaaktmedia.tandem.watch.protocol.Span
import nl.markmaaktmedia.tandem.watch.protocol.Tick
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicLong

/**
 * Where a day on the wrist went: per hour what Tandem did (wake moments, sensor time, Bluetooth, the screen) next to what the battery
 * lost, so a day can be read afterwards. The phone has the same kind of log for its traffic.
 *
 * It costs next to nothing by design. Everything is added to in memory, with no timer, alarm or wake lock of its own: a stretch of the
 * log is closed by the first thing that happens after the hour changed, and that is something that had woken the watch already. One short
 * line is written per hour, to a file per day in the folder `adb pull` reaches (and to the log, as `TandemWatchPower`).
 *
 * A stretch that was open when the process was killed is lost, and the next one starts where the app did.
 */
object PowerLog {
    private const val TAG = "TandemWatchPower"
    private const val KEEP_DAYS = 14

    private val tally = PowerTally()

    /** Start of the stretch that is being counted, on the wall clock. Zero until [start]. */
    private val stretchStart = AtomicLong(0)
    private var started = false

    @Volatile
    private var context: Context? = null

    @Volatile
    private var baseline = Battery(null, null)

    /** Reports come many times a second at the busiest, so whether the hour changed is looked at once in a while. */
    private val nextCheckMs = AtomicLong(0)
    private val io by lazy { Handler(HandlerThread("tandem-power").apply { start() }.looper) }

    private class Battery(val level: Int?, val chargeUah: Long?)

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val on = intent.action == Intent.ACTION_SCREEN_ON
            set(Span.SCREEN, on)
            if (on) count(Tick.SCREEN_ON)
            WatchLink.onScreen(on)
        }
    }

    @Synchronized
    fun start(from: Context) {
        if (started) return
        started = true
        val app = from.applicationContext
        context = app
        baseline = battery(app)
        val interactive = app.getSystemService(PowerManager::class.java)?.isInteractive == true
        set(Span.SCREEN, interactive)
        WatchLink.onScreen(interactive)
        val plugged = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        set(Span.CHARGING, plugged != 0)
        app.registerReceiver(screen, IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) })
        stretchStart.set(System.currentTimeMillis())
    }

    fun count(tick: Tick, n: Long = 1) {
        tally.count(tick, n)
        rollIfDue()
    }

    /** A message of this kind went over the link. Counted by kind, so an hour with many shows what they were. */
    fun message(inbound: Boolean, kind: String) {
        tally.message(inbound, kind)
    }

    fun open(span: Span) = tally.open(span, SystemClock.elapsedRealtime())

    fun close(span: Span) = tally.close(span, SystemClock.elapsedRealtime())

    fun set(span: Span, on: Boolean) = tally.set(span, on, SystemClock.elapsedRealtime())

    /** The watch went on or off the charger. */
    fun charging(on: Boolean) {
        set(Span.CHARGING, on)
        rollIfDue()
    }

    /** Ends the stretch that is going now, for a service that stops. */
    fun flush() {
        val last = stretchStart.get()
        if (last == 0L) return
        val now = System.currentTimeMillis()
        if (stretchStart.compareAndSet(last, now)) close(last, now)
    }

    /** The stretch that is going now, not yet in the file, for the page that shows the log. Null before [start]. */
    fun current(): PowerRecord? {
        val app = context ?: return null
        val from = stretchStart.get().takeIf { it != 0L } ?: return null
        val now = System.currentTimeMillis()
        val battery = battery(app)
        return PowerRecord(from, now, baseline.level, battery.level, baseline.chargeUah, battery.chargeUah, tally.peek(SystemClock.elapsedRealtime()))
    }

    /** The lines of the last [hours] hours, oldest first. Reads files: not for the main thread. */
    fun recent(from: Context, hours: Int = 24): List<PowerRecord> {
        val since = System.currentTimeMillis() - hours * PowerClock.HOUR_MS
        val files = folder(from).listFiles { f -> f.name.startsWith(PREFIX) && f.name.endsWith(SUFFIX) }?.sortedBy { it.name }?.takeLast(3).orEmpty()
        return files.flatMap { file -> runCatching { file.readLines() }.getOrDefault(emptyList()) }
            .mapNotNull(PowerRecord::parse)
            .filter { it.endMs >= since }
            .sortedBy { it.startMs }
    }

    private fun rollIfDue() {
        val last = stretchStart.get()
        if (last == 0L) return
        val now = System.currentTimeMillis()
        if (now < nextCheckMs.get()) return
        nextCheckMs.set(now + CHECK_EVERY_MS)
        if (!PowerClock.due(last, now, TimeZone.getDefault().getOffset(now).toLong())) return
        // Many threads report here; whichever gets the stretch closes it.
        if (stretchStart.compareAndSet(last, now)) close(last, now)
    }

    private fun close(startMs: Long, endMs: Long) {
        val app = context ?: return
        val before = baseline
        val after = battery(app)
        baseline = after
        val record = PowerRecord(startMs, endMs, before.level, after.level, before.chargeUah, after.chargeUah, tally.cut(SystemClock.elapsedRealtime()))
        Log.i(TAG, summary(record))
        io.post { write(app, record) }
    }

    private fun summary(r: PowerRecord): String =
        "${r.seconds / 60} min, battery ${r.levelStart}% to ${r.levelEnd}%, ${r.wakes} wake moments, link ${r.spanSeconds(Span.LINK)} s, " +
            "scanning ${r.spanSeconds(Span.SCAN_FAST) + r.spanSeconds(Span.SCAN_BALANCED) + r.spanSeconds(Span.SCAN_LOW)} s, " +
            "screen ${r.spanSeconds(Span.SCREEN)} s, ${r.count(Tick.BLE_IN)} chunks in, ${r.count(Tick.BLE_OUT)} out"

    private fun battery(app: Context): Battery {
        val manager = app.getSystemService(BatteryManager::class.java) ?: return Battery(null, null)
        val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
        // The charge counter is Long.MIN_VALUE where a watch does not have one, and zero where it has not read it yet.
        val charge = manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).takeIf { it > 0 }
        return Battery(level, charge)
    }

    private fun folder(app: Context) = (app.getExternalFilesDir("power") ?: File(app.filesDir, "power")).apply { mkdirs() }

    private fun write(app: Context, record: PowerRecord) {
        runCatching {
            val dir = folder(app)
            val day = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(record.endMs))
            File(dir, "$PREFIX$day$SUFFIX").appendText(record.toJson() + "\n")
            val oldest = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(System.currentTimeMillis() - KEEP_DAYS * 24 * PowerClock.HOUR_MS))
            // Dates in this form sort like text.
            dir.listFiles()?.filter { it.name.startsWith(PREFIX) && it.name < "$PREFIX$oldest$SUFFIX" }?.forEach { it.delete() }
        }.onFailure { Log.w(TAG, "Could not write the power log", it) }
    }

    private const val CHECK_EVERY_MS = 30_000L
    private const val PREFIX = "power-"
    private const val SUFFIX = ".jsonl"
}
