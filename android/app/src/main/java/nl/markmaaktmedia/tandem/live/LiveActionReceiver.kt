package nl.markmaaktmedia.tandem.live

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import nl.markmaaktmedia.tandem.graph
import uniffi.tandem_core.TandemMediaEnd

/** The buttons on the notifications of sharing: Stop on the one that stays, Deny on the question. */
class LiveActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val live = context.graph.live
        when (intent.action) {
            STOP -> live.stopAll()
            DENY -> live.deny(intent.getLongExtra(EXTRA_SESSION, 0).toULong(), TandemMediaEnd.DECLINED)
        }
    }

    companion object {
        const val STOP = "nl.markmaaktmedia.tandem.LIVE_STOP_BUTTON"
        const val DENY = "nl.markmaaktmedia.tandem.LIVE_DENY_BUTTON"
        const val EXTRA_SESSION = "session"
    }
}
