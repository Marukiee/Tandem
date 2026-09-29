package nl.markmaaktmedia.tandem.engine

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.graph

/** Buttons on notifications: accept or decline an offer, stop the ringing. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val host = context.graph.host
        val device = intent.getStringExtra(EXTRA_DEVICE)
        val offer = intent.getLongExtra(EXTRA_OFFER, 0)
        when (intent.action) {
            ACCEPT -> if (device != null) runCatching { host.engine?.acceptOffer(device, offer.toULong()) }
            DECLINE -> if (device != null) context.graph.scope.launch { runCatching { host.engine?.declineOffer(device, offer.toULong()) } }
            STOP_RING -> FindPhone.stop()
            SEND_SCREENSHOT -> {
                val uri = intent.getStringExtra(EXTRA_URI)?.let(android.net.Uri::parse)
                val targets = intent.getStringArrayExtra(EXTRA_TARGETS)?.toList().orEmpty()
                if (uri != null) {
                    val pending = goAsync()
                    context.graph.scope.launch {
                        try {
                            host.sendUris(listOf(uri), targets, uniffi.tandem_core.TandemShareOrigin.SCREENSHOT)
                        } finally {
                            pending.finish()
                        }
                    }
                }
                context.getSystemService(NotificationManager::class.java).cancel(nl.markmaaktmedia.tandem.share.ScreenshotWatcher.SCREENSHOT_ID)
            }
        }
        context.getSystemService(NotificationManager::class.java).cancel(offer.hashCode())
    }

    companion object {
        const val ACCEPT = "nl.markmaaktmedia.tandem.ACCEPT"
        const val DECLINE = "nl.markmaaktmedia.tandem.DECLINE"
        const val STOP_RING = "nl.markmaaktmedia.tandem.STOP_RING"
        const val SEND_SCREENSHOT = "nl.markmaaktmedia.tandem.SEND_SCREENSHOT"
        const val EXTRA_URI = "uri"
        const val EXTRA_TARGETS = "targets"
        const val EXTRA_DEVICE = "device"
        const val EXTRA_OFFER = "offer"
    }
}
