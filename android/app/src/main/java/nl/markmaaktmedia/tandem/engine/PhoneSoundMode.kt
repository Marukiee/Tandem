package nl.markmaaktmedia.tandem.engine

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import uniffi.tandem_core.TandemSoundMode

/** How this phone sounds, for the button on the page of this phone on another device. */
object PhoneSoundMode {
    fun read(context: Context): TandemSoundMode {
        val filter = context.getSystemService(NotificationManager::class.java).currentInterruptionFilter
        if (filter != NotificationManager.INTERRUPTION_FILTER_ALL && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN) {
            return TandemSoundMode.DO_NOT_DISTURB
        }
        return when (context.getSystemService(AudioManager::class.java).ringerMode) {
            AudioManager.RINGER_MODE_VIBRATE -> TandemSoundMode.VIBRATE
            AudioManager.RINGER_MODE_SILENT -> TandemSoundMode.SILENT
            else -> TandemSoundMode.SOUND
        }
    }

    /**
     * Another device switches it. Do Not Disturb needs the access the person gives in the system settings; that device knows from the
     * status when it is missing and says so there. The ringer comes last, because since Android 15 only that ends a Do Not Disturb the
     * person turned on themselves: the filter only reaches what Tandem turned on.
     */
    fun set(context: Context, mode: TandemSoundMode) {
        val notifications = context.getSystemService(NotificationManager::class.java)
        val audio = context.getSystemService(AudioManager::class.java)
        val access = FocusMode.canUse(context)
        runCatching {
            if (mode == TandemSoundMode.DO_NOT_DISTURB) {
                if (!access) return
                notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                // Do Not Disturb is the quietest step, so the ringer is off too: it stayed on vibrate when the step before was Vibrate.
                audio.ringerMode = AudioManager.RINGER_MODE_SILENT
                return
            }
            if (access && read(context) == TandemSoundMode.DO_NOT_DISTURB) {
                notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                FocusMode.off(context)
            }
            audio.ringerMode = when (mode) {
                TandemSoundMode.VIBRATE -> AudioManager.RINGER_MODE_VIBRATE
                TandemSoundMode.SILENT -> AudioManager.RINGER_MODE_SILENT
                else -> AudioManager.RINGER_MODE_NORMAL
            }
        }
    }
}
