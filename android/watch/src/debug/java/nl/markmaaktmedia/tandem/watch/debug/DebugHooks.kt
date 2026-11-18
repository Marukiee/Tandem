package nl.markmaaktmedia.tandem.watch.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import nl.markmaaktmedia.tandem.watch.track.HeartTracker
import nl.markmaaktmedia.tandem.watch.track.LiveHeart

/**
 * Only in debug builds: asks for what the phone would ask for over the link, so the battery work can be measured on an emulator, which
 * has no Bluetooth. `adb shell am broadcast -a nl.markmaaktmedia.tandem.watch.debug.LIVE_HEART_ON -n <package>/.debug.DebugHooks`.
 */
class DebugHooks : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "nl.markmaaktmedia.tandem.watch.debug.LIVE_HEART_ON" -> LiveHeart.ask(context, true)
            "nl.markmaaktmedia.tandem.watch.debug.LIVE_HEART_OFF" -> LiveHeart.ask(context, false)
            "nl.markmaaktmedia.tandem.watch.debug.HEART_TICK" -> HeartTracker.tick()
        }
    }
}
