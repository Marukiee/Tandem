package nl.markmaaktmedia.tandem.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * Debug builds only. Puts made up batteries in the file of the battery widget and draws it again, to see a change of state (the charger,
 * something that starts or stops playing) on a home screen without other devices:
 *
 *     adb shell am broadcast -n nl.markmaaktmedia.tandem.debug/nl.markmaaktmedia.tandem.widget.WidgetSelfCheck --es json '<what encode writes>'
 */
class WidgetSelfCheck : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val json = intent.getStringExtra("json") ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                File(context.filesDir, "battery-widget.json").writeText(json)
                BatteryWidgetStore.redraw(context)
            } finally {
                pending.finish()
            }
        }
    }
}
