package nl.markmaaktmedia.tandem.watch.track

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import nl.markmaaktmedia.tandem.watch.sleep.SmartWake
import nl.markmaaktmedia.tandem.watch.sleep.WakeSwell

/**
 * One buzz without a break from a few minutes before the alarm, swelling and easing as [WakeSwell] draws it. It ends when someone looks
 * at the watch, moves after the alarm, or the recording of the night ends; only someone who still lies still after the alarm gets it
 * stronger, up to full, for at most [WakeSwell.MAX_AFTER_MS]. An alarm to the system, so do not disturb lets it through, because that is
 * on all night for someone who sleeps.
 */
internal object GentleWake {
    /** The minutes before the alarm in half a minute, to feel what a morning will be like. */
    private const val TRY_MS = 30_000L
    private val handler = Handler(Looper.getMainLooper())
    private val attributes = VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_ALARM).build()
    @Volatile private var stopNow: (() -> Unit)? = null

    val running: Boolean get() = stopNow != null

    private fun effect(fromMs: Long, windowMs: Long, afterMs: Long = 0): VibrationEffect {
        val (timings, amplitudes) = WakeSwell.waveform(fromMs, windowMs, afterMs)
        return VibrationEffect.createWaveform(timings, amplitudes, -1)
    }

    /** The morning sped up to half a minute. A second try stops the first. */
    fun tryOnce(context: Context) {
        val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
        vibrator.cancel()
        vibrator.vibrate(effect(0, TRY_MS), attributes)
    }

    fun start(context: Context, untilMs: Long) {
        if (running) return
        val app = context.applicationContext
        val vibrator = app.getSystemService(VibratorManager::class.java).defaultVibrator
        lateinit var seen: BroadcastReceiver
        val stop = {
            if (stopNow != null) {
                stopNow = null
                handler.removeCallbacksAndMessages(null)
                runCatching { app.unregisterReceiver(seen) }
                vibrator.cancel()
            }
        }
        seen = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                stop()
            }
        }
        val now = System.currentTimeMillis()
        if (now >= untilMs + WakeSwell.MAX_AFTER_MS) return
        stopNow = stop
        app.registerReceiver(seen, IntentFilter(Intent.ACTION_SCREEN_ON))
        // One waveform for the rest of the window and the time after the alarm: pieces one after the other left a small break at each seam.
        vibrator.vibrate(effect(now - (untilMs - SmartWake.WINDOW_MS), SmartWake.WINDOW_MS, WakeSwell.MAX_AFTER_MS), attributes)
        handler.postDelayed({ stop() }, untilMs + WakeSwell.MAX_AFTER_MS - now)
    }

    /** Someone moved after the alarm, or the night was ended: they are awake. From any thread. */
    fun awake() {
        handler.post { stopNow?.invoke() }
    }
}
