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

/**
 * Three soft pulses, again every minute, until the alarm goes off or someone looks at the watch. An alarm to the system, so do not
 * disturb lets it through, because that is on all night for someone who sleeps.
 */
internal object GentleWake {
    private const val EVERY_MS = 60_000L
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    fun start(context: Context, untilMs: Long) {
        if (running) return
        running = true
        val app = context.applicationContext
        val vibrator = app.getSystemService(VibratorManager::class.java).defaultVibrator
        val attributes = VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_ALARM).build()
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 180, 500, 180, 500, 180), intArrayOf(0, 90, 0, 120, 0, 150), -1)
        lateinit var seen: BroadcastReceiver
        val stop = {
            running = false
            handler.removeCallbacksAndMessages(null)
            runCatching { app.unregisterReceiver(seen) }
            vibrator.cancel()
        }
        seen = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                stop()
            }
        }
        app.registerReceiver(seen, IntentFilter(Intent.ACTION_SCREEN_ON))
        val pulse = object : Runnable {
            override fun run() {
                if (System.currentTimeMillis() >= untilMs) {
                    stop()
                    return
                }
                vibrator.vibrate(effect, attributes)
                handler.postDelayed(this, EVERY_MS)
            }
        }
        pulse.run()
    }
}
