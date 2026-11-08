package nl.markmaaktmedia.tandem.live

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/**
 * Debug builds only. Types as the keyboard of a computer does with the shared mouse, through the same calls of the service,
 * one character at a time. Put the cursor in a field first.
 *
 *     adb shell "am broadcast -n nl.markmaaktmedia.tandem.debug/nl.markmaaktmedia.tandem.live.TypeSelfCheck --es text 'hello world' --ei backspace 2"
 *
 * The quotes matter: without them adb splits the text at its spaces.
 */
class TypeSelfCheck : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val service = TandemAccessibilityService.instance ?: return
        val text = intent.getStringExtra("text").orEmpty()
        val main = Handler(Looper.getMainLooper())
        var delay = 0L
        for (ch in text) {
            main.postDelayed({ service.type(ch.toString()) }, delay)
            delay += 60
        }
        if (intent.getIntExtra("backspace", 0) > 0) repeat(intent.getIntExtra("backspace", 0)) {
            main.postDelayed({ service.backspace() }, delay)
            delay += 60
        }
        if (intent.getBooleanExtra("enter", false)) main.postDelayed({ service.type("\n") }, delay)
    }
}
