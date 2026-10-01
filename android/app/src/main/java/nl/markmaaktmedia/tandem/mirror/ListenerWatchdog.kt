package nl.markmaaktmedia.tandem.mirror

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.engine.Permissions

/**
 * Android binds the notification listener by itself, but after an update it sometimes does not, and then access shows
 * as allowed while nothing is mirrored. This looks every minute while Tandem runs and asks for the listener again, the
 * gentle way first and the harder way if that did not help.
 */
class ListenerWatchdog(private val context: Context, private val scope: CoroutineScope) {
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            // Right after the process starts the system is usually busy binding the listener itself.
            delay(SETTLE_MS)
            while (isActive) {
                if (needsNudge()) {
                    Permissions.rebindListener(context)
                    delay(CHECK_MS)
                    if (needsNudge()) Permissions.restartListener(context)
                }
                delay(EVERY_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun needsNudge() = Permissions.notificationAccess(context) && !MirrorListener.connected.value

    private companion object {
        const val SETTLE_MS = 6_000L
        const val CHECK_MS = 5_000L
        const val EVERY_MS = 60_000L
    }
}
