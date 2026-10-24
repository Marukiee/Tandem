package nl.markmaaktmedia.tandem.wifi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** "Not now" on the offer to take over a computer's Wi-Fi. */
class WifiActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == DECLINE) WifiShare.get(context).decline(intent.getStringExtra(EXTRA_SSID) ?: return)
    }

    companion object {
        const val DECLINE = "nl.markmaaktmedia.tandem.WIFI_DECLINE"
        const val EXTRA_SSID = "ssid"
    }
}
