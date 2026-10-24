package nl.markmaaktmedia.tandem.live

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import uniffi.tandem_core.TandemMediaInput
import java.io.File

/**
 * Debug builds only. Plays a scroll of a trackpad into [RemoteInput] the way the network hands it on (steps at 120 a second, in bursts),
 * and writes how the finger moved: how long a piece took from start to start, and how far the finger went in each. The accessibility
 * service has to be on.
 *
 *     adb shell am broadcast -n nl.markmaaktmedia.tandem.debug/nl.markmaaktmedia.tandem.live.ScrollSelfCheck --ei burst 3
 *     adb exec-out run-as nl.markmaaktmedia.tandem.debug cat files/scroll-selfcheck.txt
 */
class ScrollSelfCheck : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val burst = intent.getIntExtra("burst", 3).coerceAtLeast(1)
        val step = intent.getIntExtra("step", -6)
        val millis = intent.getIntExtra("millis", 1500)
        val input = RemoteInput(context.applicationContext)
        val pieces = ArrayList<Piece>()
        RemoteInput.trace = { at, x, y, length -> synchronized(pieces) { pieces += Piece(at, x, y, length) } }
        val main = Handler(Looper.getMainLooper())
        val start = SystemClock.uptimeMillis()
        val every = 1000L / 120 * burst
        input.handle(TandemMediaInput.PointerAbs(0.5f, 0.5f))
        var sent = 0L
        while (sent < millis) {
            main.postAtTime({ repeat(burst) { input.handle(TandemMediaInput.Scroll(0.toShort(), step.toShort())) } }, start + sent)
            sent += every
        }
        main.postAtTime({
            RemoteInput.trace = null
            File(context.filesDir, "scroll-selfcheck.txt").writeText(report(pieces))
        }, start + millis + 800)
    }

    private class Piece(val at: Long, val x: Float, val y: Float, val length: Long)

    private fun report(pieces: List<Piece>): String {
        if (pieces.size < 3) return "pieces: ${pieces.size} (is the accessibility service on?)\n"
        val cycles = pieces.zipWithNext { a, b -> b.at - a.at }.filter { it < 200 }
        val moves = pieces.zipWithNext { a, b -> kotlin.math.hypot(b.x - a.x, b.y - a.y) }
        fun stats(name: String, values: List<Float>): String {
            val sorted = values.sorted()
            val mean = values.average()
            return "%s: n=%d mean=%.1f p10=%.1f p50=%.1f p90=%.1f max=%.1f\n".format(
                name, values.size, mean, sorted[sorted.size / 10], sorted[sorted.size / 2], sorted[sorted.size * 9 / 10], sorted.last(),
            )
        }
        return stats("cycle ms", cycles.map { it.toFloat() }) + stats("move px", moves) +
            stats("piece ms", pieces.drop(1).map { it.length.toFloat() }) +
            "the finger moves %.0f%% of the time\n".format(pieces.drop(1).sumOf { it.length } * 100.0 / cycles.sum())
    }
}
