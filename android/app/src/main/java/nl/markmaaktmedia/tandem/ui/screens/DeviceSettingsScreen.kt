package nl.markmaaktmedia.tandem.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.live.LiveDeviceSection
import nl.markmaaktmedia.tandem.ui.components.ActionRow
import nl.markmaaktmedia.tandem.ui.components.SectionHeader
import nl.markmaaktmedia.tandem.ui.components.SettingsGroup
import nl.markmaaktmedia.tandem.ui.components.SwitchRow
import nl.markmaaktmedia.tandem.ui.components.TandemConfirmDialog
import nl.markmaaktmedia.tandem.ui.components.TandemIconButton
import nl.markmaaktmedia.tandem.ui.theme.LocalTandemExtraColors
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons

/**
 * What is set for one device: what is shared with it, what it may ask of this phone, and removing it. They were at the
 * foot of the page of the device, where they pushed away what the device does now.
 */
@Composable
fun DeviceSettingsScreen(id: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val host = context.graph.host
    val devices by host.devices.collectAsState()
    val device = devices.firstOrNull { it.id == id }
    val scope = rememberCoroutineScope()
    var confirmRemove by remember { mutableStateOf(false) }

    if (device == null) {
        // Removed while looking at it.
        LaunchedEffect(Unit) { onBack() }
        return
    }

    fun set(clipboard: Boolean? = null, autoAccept: Boolean? = null, notifications: Boolean? = null) {
        host.engine?.setDeviceSettings(
            device.id,
            clipboard ?: device.clipboardEnabled,
            autoAccept ?: device.autoAccept,
            notifications ?: device.notificationsEnabled,
        )
        host.refreshDevices()
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TandemIconButton(TandemIcons.Back, stringResource(R.string.action_back), onBack)
        }
        Text(device.name, style = MaterialTheme.typography.headlineMedium, maxLines = 2, modifier = Modifier.padding(horizontal = 8.dp))
        Text(
            stringResource(R.string.device_settings_title),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )

        SectionHeader(stringResource(R.string.section_preferences), top = 12.dp, bottom = 0.dp)
        SettingsGroup {
            SwitchRow(0, 3, TandemIcons.Paste, stringResource(R.string.setting_clipboard), stringResource(R.string.setting_clipboard_sub), device.clipboardEnabled, { set(clipboard = it) })
            SwitchRow(1, 3, TandemIcons.Notifications, stringResource(R.string.setting_notifications_from), stringResource(R.string.setting_notifications_from_sub), device.notificationsEnabled, { set(notifications = it) })
            SwitchRow(2, 3, TandemIcons.Download, stringResource(R.string.setting_auto_accept), stringResource(R.string.setting_auto_accept_sub), device.autoAccept, { set(autoAccept = it) })
        }

        // What this device may ask of this phone: its screen, its camera, and clicking and typing.
        LiveDeviceSection(device, permissions = true)

        if (device.vouchedByRemoved) {
            Text(
                stringResource(R.string.warning_vouched),
                style = MaterialTheme.typography.bodySmall, color = LocalTandemExtraColors.current.urgent,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        SettingsGroup {
            ActionRow(
                0, 1, TandemIcons.Delete, stringResource(R.string.action_remove_device), stringResource(R.string.action_remove_device_sub),
                { confirmRemove = true }, danger = true,
            )
        }
        Spacer(Modifier.height(24.dp))
    }

    if (confirmRemove) {
        TandemConfirmDialog(
            title = stringResource(R.string.remove_title, device.name),
            body = stringResource(R.string.remove_body),
            confirmLabel = stringResource(R.string.remove_confirm),
            cancelLabel = stringResource(R.string.action_cancel),
            destructive = true,
            onConfirm = { scope.launch { runCatching { host.engine?.removeDevice(id) }; onBack() } },
            onDismiss = { confirmRemove = false },
        )
    }
}
