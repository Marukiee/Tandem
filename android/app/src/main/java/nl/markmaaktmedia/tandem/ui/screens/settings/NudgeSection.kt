package nl.markmaaktmedia.tandem.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.components.SettingsGroup
import nl.markmaaktmedia.tandem.ui.components.SwitchRow
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import uniffi.tandem_core.TandemDevice
import uniffi.tandem_core.TandemPlatform

/** Whether `tandem notify` on this computer reaches this phone. Only a computer has that command. */
@Composable
internal fun NudgeDeviceSection(device: TandemDevice) {
    if (device.platform == TandemPlatform.ANDROID) return
    val prefs = LocalContext.current.graph.prefs
    val scope = rememberCoroutineScope()
    val muted by prefs.nudgeMuted.collectAsState(initial = emptySet())
    SettingsGroup {
        SwitchRow(
            0, 1, TandemIcons.Notifications, stringResource(R.string.setting_nudge), stringResource(R.string.setting_nudge_sub), device.id !in muted,
            { on -> scope.launch { prefs.setNudgeAllowed(device.id, on) } },
        )
    }
}
