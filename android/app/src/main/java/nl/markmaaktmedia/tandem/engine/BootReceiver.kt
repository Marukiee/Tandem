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
        val pending = goAsync()
        context.graph.scope.launch {
            try {
                if (context.graph.prefs.onboarded.first()) TandemService.start(context)
            } finally {
                pending.finish()
            }
        }
    }
}
