package nl.markmaaktmedia.tandem.ui.components

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
        TandemSoundMode.VIBRATE -> TandemIcons.Vibration to stringResource(R.string.sound_mode_vibrate)
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

/**
 * Locks another device. An action, not a page: a button at the end of the row, as Keep awake above it has its switch. Grey while it
 * cannot, and a press says what is missing there.
 */
@Composable
fun LockRow(index: Int, total: Int, device: TandemDevice) {
    val context = LocalContext.current
    val host = context.graph.host
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    val locked = device.status.locked == true
    val ready = device.online && device.status.lock == true && !locked
    val whyNot = when {
        !device.online -> R.string.lock_why_offline
        device.status.lock == null -> R.string.lock_why_update
        else -> R.string.lock_why_access
    }
    GroupedRow(index, total) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            RowIcon(
                TandemIcons.Lock,
                tint = if (ready || locked) scheme.onSecondaryContainer else scheme.onSurfaceVariant.copy(alpha = 0.6f),
                container = if (ready || locked) scheme.secondaryContainer else scheme.surfaceContainerHighest,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.lock_row_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(if (locked) R.string.lock_row_locked else R.string.lock_row_sub),
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                )
            }
            // Pressed while it cannot, it still answers: with why.
            PrimaryPillButton(
                label = stringResource(if (locked) R.string.lock_button_locked else R.string.lock_button),
                icon = TandemIcons.Lock,
                enabled = ready,
                onClick = { scope.launch { runCatching { host.engine?.lock(device.id) } } },
                container = scheme.primary,
                content = scheme.onPrimary,
                modifier = if (ready || locked) Modifier else Modifier.clickable {
                    Toast.makeText(context, whyNot, Toast.LENGTH_SHORT).show()
                },
            )
        }
    }
}
