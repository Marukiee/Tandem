package nl.markmaaktmedia.tandem.ui.screens.settings

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.R
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import nl.markmaaktmedia.tandem.ui.components.SegmentedPillRow
import nl.markmaaktmedia.tandem.ui.components.ContentRow
import nl.markmaaktmedia.tandem.audio.AudioDelay
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.Route
import nl.markmaaktmedia.tandem.ui.components.ActionRow
import nl.markmaaktmedia.tandem.ui.components.InfoRow
import nl.markmaaktmedia.tandem.ui.components.PrimaryPillButton
import nl.markmaaktmedia.tandem.ui.components.SecondaryPillButton
import nl.markmaaktmedia.tandem.ui.components.SectionHeader
import nl.markmaaktmedia.tandem.ui.components.SettingsGroup
import nl.markmaaktmedia.tandem.ui.components.SwitchRow
import nl.markmaaktmedia.tandem.ui.components.TandemDialog
import nl.markmaaktmedia.tandem.ui.routeBounds
import nl.markmaaktmedia.tandem.ui.routeKey
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons

/** Devices and sharing: this phone, what it offers to share, and the music and sound that go to the other devices. */
@Composable
internal fun SharingPage(onBack: () -> Unit, onOpen: (Route) -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val prefs = graph.prefs
    val scope = rememberCoroutineScope()

    val deviceName by prefs.deviceName.collectAsState(initial = null)
    val screenshot by prefs.screenshotPrompt.collectAsState(initial = true)
    val bleMessages by prefs.bluetoothMessages.collectAsState(initial = true)
    val mediaShare by prefs.mediaShare.collectAsState(initial = true)
    val audioOutput by prefs.audioOutput.collectAsState(initial = true)
    var renaming by remember { mutableStateOf(false) }

    SettingsPageFrame(stringResource(R.string.settings_cat_sharing), onBack) {
        SectionHeader(stringResource(R.string.settings_this_phone))
        SettingsGroup {
            ActionRow(
                0, 2, TandemIcons.Phone, stringResource(R.string.settings_name), deviceName ?: graph.host.myName, { renaming = true },
                modifier = Modifier.settingsTarget(FocusKeys.PhoneName, 0, 2),
            )
            SettingsTarget(FocusKeys.PhoneId, 1, 2) {
                InfoRow(1, 2, TandemIcons.Key, stringResource(R.string.settings_id), graph.host.myId.take(10))
            }
        }

        SectionHeader(stringResource(R.string.settings_sharing))
        SettingsGroup {
            SettingsTarget(FocusKeys.Screenshot, 0, 4) {
                SwitchRow(0, 4, TandemIcons.Screenshot, stringResource(R.string.settings_screenshot), stringResource(R.string.settings_screenshot_sub), screenshot, { scope.launch { prefs.setScreenshotPrompt(it) } })
            }
            SettingsTarget(FocusKeys.Ble, 1, 4) {
                SwitchRow(1, 4, TandemIcons.Bluetooth, stringResource(R.string.settings_ble_messages), stringResource(R.string.settings_ble_messages_sub), bleMessages, { scope.launch { prefs.setBluetoothMessages(it) } })
            }
            ActionRow(
                2, 4, TandemIcons.Paste, stringResource(R.string.settings_clip_tile), stringResource(R.string.settings_clip_tile_sub),
                { nl.markmaaktmedia.tandem.share.ClipboardTileService.requestAdd(context) },
                modifier = Modifier.settingsTarget(FocusKeys.ClipTile, 2, 4),
            )
            ActionRow(
                3, 4, TandemIcons.Paste, stringResource(R.string.clip_history_title), stringResource(R.string.clip_history_row_sub),
                { onOpen(Route.ClipboardHistory) }, modifier = Modifier.routeBounds(routeKey(Route.ClipboardHistory)),
            )
        }

        SectionHeader(stringResource(R.string.settings_media))
        SettingsGroup {
            SettingsTarget(FocusKeys.MediaShare, 0, 4) {
                SwitchRow(0, 4, TandemIcons.Music, stringResource(R.string.settings_media_share), stringResource(R.string.settings_media_share_sub), mediaShare, { scope.launch { prefs.setMediaShare(it) } })
            }
            ActionRow(
                1, 4, TandemIcons.Devices, stringResource(R.string.settings_media_apps), stringResource(R.string.settings_media_apps_sub),
                { onOpen(Route.MediaApps) }, modifier = Modifier.routeBounds(routeKey(Route.MediaApps)),
            )
            SettingsTarget(FocusKeys.Speaker, 2, 4) {
                SwitchRow(2, 4, TandemIcons.VolumeUp, stringResource(R.string.settings_audio_output), stringResource(R.string.settings_audio_output_sub), audioOutput, { scope.launch { prefs.setAudioOutput(it) } })
            }
            val audioDelay by prefs.audioDelay.collectAsState(initial = 1)
            SettingsTarget(FocusKeys.SpeakerDelay, 3, 4) {
                ContentRow(3, 4, TandemIcons.VolumeUp, stringResource(R.string.settings_audio_delay)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.settings_audio_delay_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SegmentedPillRow(
                            options = AudioDelay.entries,
                            selected = AudioDelay.fromIndex(audioDelay),
                            label = { context.getString(when (it) { AudioDelay.LOW -> R.string.audio_delay_low; AudioDelay.NORMAL -> R.string.audio_delay_normal; AudioDelay.SMOOTH -> R.string.audio_delay_smooth }) },
                            onSelect = { scope.launch { prefs.setAudioDelay(it.ordinal) } },
                            modifier = Modifier.fillMaxWidth(),
                            equalWidth = true,
                        )
                    }
                }
            }
        }
    }

    if (renaming) {
        var text by remember { mutableStateOf(deviceName ?: graph.host.myName) }
        TandemDialog(
            title = stringResource(R.string.settings_name),
            onDismiss = { renaming = false },
            icon = TandemIcons.Phone,
            actions = {
                SecondaryPillButton(stringResource(R.string.action_cancel), { renaming = false })
                PrimaryPillButton(stringResource(R.string.action_save), {
                    val name = text.trim()
                    if (name.isNotEmpty()) scope.launch {
                        prefs.setDeviceName(name)
                        runCatching { graph.host.engine?.renameSelf(name) }
                    }
                    renaming = false
                })
            },
            content = {
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, keyboardOptions = KeyboardOptions.Default, shape = MaterialTheme.shapes.large)
            },
        )
    }
}
