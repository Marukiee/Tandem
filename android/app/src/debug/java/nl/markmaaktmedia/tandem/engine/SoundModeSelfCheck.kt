package nl.markmaaktmedia.tandem.engine

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import java.io.File
import kotlin.concurrent.thread
import uniffi.tandem_core.TandemSoundMode

/**
 * Debug builds only. Switches the sound mode as another device does with the button on the page of this phone, and writes what
 * Android then says: the mode Tandem reads, the ringer and Do Not Disturb.
 *
 *     adb shell cmd notification allow_dnd nl.markmaaktmedia.tandem.debug
 *     adb shell am broadcast -n nl.markmaaktmedia.tandem.debug/nl.markmaaktmedia.tandem.engine.SoundModeSelfCheck --es mode dnd
 *     adb exec-out run-as nl.markmaaktmedia.tandem.debug cat files/sound-selfcheck.txt
 */
class SoundModeSelfCheck : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val mode = when (intent.getStringExtra("mode")) {
            "vibrate" -> TandemSoundMode.VIBRATE
            "silent" -> TandemSoundMode.SILENT
            "dnd" -> TandemSoundMode.DO_NOT_DISTURB
            else -> TandemSoundMode.SOUND
        }
        val pending = goAsync()
        thread(name = "sound-selfcheck") {
            try {
                PhoneSoundMode.set(context, mode)
                // The system applies a mode on its own thread.
                Thread.sleep(1500)
                val ringer = when (context.getSystemService(AudioManager::class.java).ringerMode) {
                    AudioManager.RINGER_MODE_SILENT -> "silent"
                    AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                    else -> "normal"
                }
                val filter = context.getSystemService(NotificationManager::class.java).currentInterruptionFilter
                val line = "asked $mode, reads ${PhoneSoundMode.read(context)}, ringer $ringer, filter $filter"
                File(context.filesDir, "sound-selfcheck.txt").writeText(line)
            } finally {
                pending.finish()
            }
        }
    }
}
