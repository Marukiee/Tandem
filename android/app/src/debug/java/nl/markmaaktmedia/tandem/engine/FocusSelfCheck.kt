package nl.markmaaktmedia.tandem.engine

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.io.File
import kotlin.concurrent.thread

/**
 * Debug builds only. Proves Tandem's own mode without another device: turns it on or off as a Focus from another device would, and
 * writes what Android then says about Do Not Disturb.
 *
 *     adb shell cmd notification allow_dnd nl.markmaaktmedia.tandem.debug
 *     adb shell am broadcast -n nl.markmaaktmedia.tandem.debug/nl.markmaaktmedia.tandem.engine.FocusSelfCheck --ez on true
 *     adb exec-out run-as nl.markmaaktmedia.tandem.debug cat files/focus-selfcheck.txt
 */
class FocusSelfCheck : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val on = intent.getBooleanExtra("on", true)
        val pending = goAsync()
        thread(name = "focus-selfcheck") {
            try {
                val manager = context.getSystemService(NotificationManager::class.java)
                val lines = mutableListOf("access ${FocusMode.canUse(context)}", "filter before ${manager.currentInterruptionFilter}")
                FocusMode.turn(context, on, "Self check", "Work")
                // The system applies the mode on its own thread.
                Thread.sleep(1500)
                lines += "filter after ${manager.currentInterruptionFilter}"
                val id = FocusMode.store(context).getString("rule", null)
                lines += "rule $id ${id?.let { manager.getAutomaticZenRule(it)?.name }}"
                if (id != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) lines += "state ${manager.getAutomaticZenRuleState(id)}"
                File(context.filesDir, "focus-selfcheck.txt").writeText(lines.joinToString("\n"))
                Log.i("FocusSelfCheck", lines.joinToString(", "))
            } finally {
                pending.finish()
            }
        }
    }
}
