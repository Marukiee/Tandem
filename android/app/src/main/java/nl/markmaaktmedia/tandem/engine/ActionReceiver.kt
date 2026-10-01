package nl.markmaaktmedia.tandem.engine

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.share.ScreenshotWatcher

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
            STOP_AUDIO -> {
                context.graph.audio.stop(tell = true)
                return
            }
            SEND_SCREENSHOT -> {
                val uri = intent.getStringExtra(EXTRA_URI)?.let(android.net.Uri::parse)
                val targets = intent.getStringArrayExtra(EXTRA_TARGETS)?.toList().orEmpty()
                if (uri != null) {
                    val pending = goAsync()
                    val label = targets.singleOrNull()?.let { host.device(it)?.name }
                        ?: context.getString(R.string.screenshot_n_devices, targets.size)
                    // The notification stays and changes into a status line: tapping "Send" must
                    // never look like nothing happened.
                    ScreenshotWatcher.showStatus(context, context.getString(R.string.screenshot_sending, label), sending = true)
                    context.graph.scope.launch {
                        try {
                            val sent = host.sendUris(listOf(uri), targets, uniffi.tandem_core.TandemShareOrigin.SCREENSHOT)
                            if (sent > 0) {
                                ScreenshotWatcher.showStatus(context, context.getString(R.string.screenshot_sent, label))
                            } else {
                                ScreenshotWatcher.showStatus(context, context.getString(R.string.screenshot_failed), context.getString(R.string.screenshot_failed_text))
                            }
                        } catch (e: Exception) {
                            ScreenshotWatcher.showStatus(context, context.getString(R.string.screenshot_failed), e.message)
                        } finally {
                            pending.finish()
                        }
                    }
                }
                return
            }
        }
        context.getSystemService(NotificationManager::class.java).cancel(offer.hashCode())
    }

    companion object {
        const val ACCEPT = "nl.markmaaktmedia.tandem.ACCEPT"
        const val DECLINE = "nl.markmaaktmedia.tandem.DECLINE"
        const val STOP_RING = "nl.markmaaktmedia.tandem.STOP_RING"
        const val STOP_AUDIO = "nl.markmaaktmedia.tandem.STOP_AUDIO"
        const val SEND_SCREENSHOT = "nl.markmaaktmedia.tandem.SEND_SCREENSHOT"
        const val EXTRA_URI = "uri"
        const val EXTRA_TARGETS = "targets"
        const val EXTRA_DEVICE = "device"
        const val EXTRA_OFFER = "offer"
    }
}
