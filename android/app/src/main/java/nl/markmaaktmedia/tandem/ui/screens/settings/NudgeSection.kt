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

/** Whether the nudge row is shown. Only a computer has the `tandem notify` command. */
internal fun nudgeDeviceShown(device: TandemDevice): Boolean = device.platform != TandemPlatform.ANDROID

/** Whether `tandem notify` on this computer reaches this phone. */
@Composable
internal fun NudgeDeviceRow(device: TandemDevice, index: Int, count: Int) {
    val prefs = LocalContext.current.graph.prefs
    val scope = rememberCoroutineScope()
    val muted by prefs.nudgeMuted.collectAsState(initial = emptySet())
    SwitchRow(
        index, count, TandemIcons.Notifications, stringResource(R.string.setting_nudge), stringResource(R.string.setting_nudge_sub), device.id !in muted,
        { on -> scope.launch { prefs.setNudgeAllowed(device.id, on) } },
    )
}
