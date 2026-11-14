package nl.markmaaktmedia.tandem.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.graph

/** Starts Tandem again after a restart or an update, once the person has set it up. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // The receiver is exported for the system, so any other app can send it anything.
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        context.graph.scope.launch {
            try {
                // After an update or a boot Android may refuse a foreground service start, and that must not crash the app.
                if (context.graph.prefs.onboarded.first()) runCatching { TandemService.start(context) }
            } finally {
                pending.finish()
            }
        }
    }
}
