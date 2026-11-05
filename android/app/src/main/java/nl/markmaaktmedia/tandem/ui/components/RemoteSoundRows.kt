package nl.markmaaktmedia.tandem.ui.components

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import uniffi.tandem_core.TandemDevice
import uniffi.tandem_core.TandemSoundMode

/** How another phone sounds, and one press to the next: sound, vibrate, do not disturb. Without access to the last one it is skipped, and said why. */
@Composable
fun SoundModeRow(index: Int, total: Int, device: TandemDevice) {
    val context = LocalContext.current
    val host = context.graph.host
    val scope = rememberCoroutineScope()
    val mode = device.status.sound
    val canDnd = device.status.soundDnd == true
    val (icon, title) = when (mode) {
        TandemSoundMode.VIBRATE -> TandemIcons.VolumeDown to stringResource(R.string.sound_mode_vibrate)
        TandemSoundMode.SILENT -> TandemIcons.VolumeOff to stringResource(R.string.sound_mode_silent)
        TandemSoundMode.DO_NOT_DISTURB -> TandemIcons.Dnd to stringResource(R.string.status_dnd)
        else -> TandemIcons.VolumeUp to stringResource(R.string.sound_mode_sound)
    }
    fun say(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    ControlRow(
        index, total, icon, title, stringResource(R.string.sound_mode_sub), ready = true,
        onClick = press@{
            if (mode == null) return@press say(context.getString(R.string.sound_mode_update, device.name))
            if (mode == TandemSoundMode.DO_NOT_DISTURB && !canDnd) {
                scope.launch { runCatching { host.engine?.openSettingsOn(device.id, "dnd-access") } }
                return@press say(context.getString(R.string.sound_mode_access, device.name))
            }
            val next = when (mode) {
                TandemSoundMode.SOUND -> TandemSoundMode.VIBRATE
                TandemSoundMode.DO_NOT_DISTURB -> TandemSoundMode.SOUND
                else -> if (canDnd) TandemSoundMode.DO_NOT_DISTURB else TandemSoundMode.SOUND
            }
            if (mode != TandemSoundMode.SOUND && next == TandemSoundMode.SOUND && !canDnd) {
                say(context.getString(R.string.sound_mode_access, device.name))
            }
            scope.launch { runCatching { host.engine?.setSoundMode(device.id, next) } }
        },
    )
}

/** Locks another device. Grey while it cannot, and a press says what is missing there. */
@Composable
fun LockRow(index: Int, total: Int, device: TandemDevice) {
    val host = LocalContext.current.graph.host
    val scope = rememberCoroutineScope()
    val locked = device.status.locked == true
    ControlRow(
        index, total, TandemIcons.Lock, stringResource(R.string.lock_row_title),
        stringResource(if (locked) R.string.lock_row_locked else R.string.lock_row_sub),
        ready = device.online && device.status.lock == true,
        onClick = { scope.launch { runCatching { host.engine?.lock(device.id) } } },
        whyNot = when {
            !device.online -> R.string.lock_why_offline
            device.status.lock == null -> R.string.lock_why_update
            else -> R.string.lock_why_access
        },
    )
}
