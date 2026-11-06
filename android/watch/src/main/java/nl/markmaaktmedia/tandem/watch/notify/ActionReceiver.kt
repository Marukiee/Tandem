package nl.markmaaktmedia.tandem.watch.notify

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import nl.markmaaktmedia.tandem.watch.link.WatchLink
import nl.markmaaktmedia.tandem.watch.protocol.CallActionKind

/** Sends what is pressed in a notification back to the phone. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WatchLink.start(context)
        val key = intent.getStringExtra(EXTRA_KEY)
        when (intent.action) {
            ACTION_BUTTON -> {
                val reply = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(REPLY_KEY)?.toString()
                WatchLink.sendNotificationAction(key ?: return, intent.getStringExtra(EXTRA_BUTTON).orEmpty(), reply, false)
            }
            ACTION_DISMISS -> WatchLink.sendNotificationAction(key ?: return, "", null, true)
            ACTION_FOUND -> WatchLink.foundWatch()
            ACTION_CALL -> {
                val kind = CallActionKind.entries.getOrNull(intent.getIntExtra(EXTRA_CALL_ACTION, -1)) ?: return
                WatchLink.sendCallAction(kind)
            }
        }
    }

    companion object {
        const val REPLY_KEY = "reply"
        private const val ACTION_BUTTON = "nl.markmaaktmedia.tandem.watch.BUTTON"
        private const val ACTION_DISMISS = "nl.markmaaktmedia.tandem.watch.DISMISS"
        private const val ACTION_CALL = "nl.markmaaktmedia.tandem.watch.CALL"
        private const val ACTION_FOUND = "nl.markmaaktmedia.tandem.watch.FOUND"
        private const val EXTRA_KEY = "key"
        private const val EXTRA_BUTTON = "button"
        private const val EXTRA_CALL_ACTION = "call_action"

        /** A reply needs a mutable intent: the system has to add the typed text to it. */
        fun buttonIntent(context: Context, key: String, button: String, isReply: Boolean): PendingIntent {
            val intent = Intent(context, ActionReceiver::class.java).setAction(ACTION_BUTTON)
                .putExtra(EXTRA_KEY, key)
                .putExtra(EXTRA_BUTTON, button)
            return mutableBroadcast(context, requestCode(key, button), intent, isReply)
        }

        fun dismissIntent(context: Context, key: String): PendingIntent {
            val intent = Intent(context, ActionReceiver::class.java).setAction(ACTION_DISMISS).putExtra(EXTRA_KEY, key)
            return mutableBroadcast(context, requestCode(key, "dismiss"), intent, false)
        }

        fun foundIntent(context: Context): PendingIntent {
            val intent = Intent(context, ActionReceiver::class.java).setAction(ACTION_FOUND)
            return mutableBroadcast(context, requestCode("found"), intent, false)
        }

        fun callIntent(context: Context, kind: CallActionKind): PendingIntent {
            val intent = Intent(context, ActionReceiver::class.java).setAction(ACTION_CALL)
                .putExtra(EXTRA_CALL_ACTION, kind.ordinal)
            return mutableBroadcast(context, requestCode("call", kind.name), intent, false)
        }
    }
}
