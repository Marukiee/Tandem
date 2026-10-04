package nl.markmaaktmedia.tandem.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.Route
import nl.markmaaktmedia.tandem.ui.components.ActionRow
import nl.markmaaktmedia.tandem.ui.components.SettingsGroup
import nl.markmaaktmedia.tandem.ui.components.SwitchRow
import nl.markmaaktmedia.tandem.ui.routeBounds
import nl.markmaaktmedia.tandem.ui.routeKey
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons

/** Notifications and calls: what this phone shows on the other devices. */
@Composable
internal fun NotificationsPage(onBack: () -> Unit, onOpen: (Route) -> Unit) {
    val prefs = LocalContext.current.graph.prefs
    val scope = rememberCoroutineScope()
    val mirror by prefs.mirrorNotifications.collectAsState(initial = true)
    val copyCodes by prefs.copyCodes.collectAsState(initial = true)
    val calls by prefs.callMirror.collectAsState(initial = true)

    SettingsPageFrame(stringResource(R.string.settings_notifications), onBack) {
        Spacer(Modifier.height(16.dp))
        SettingsGroup {
            SettingsTarget(FocusKeys.Mirror, 0, 4) {
                SwitchRow(0, 4, TandemIcons.Notifications, stringResource(R.string.settings_mirror), stringResource(R.string.settings_mirror_sub), mirror, { scope.launch { prefs.setMirrorNotifications(it) } })
            }
            ActionRow(
                1, 4, TandemIcons.Devices, stringResource(R.string.settings_mirror_apps), stringResource(R.string.settings_mirror_apps_sub),
                { onOpen(Route.MirrorApps) }, modifier = Modifier.routeBounds(routeKey(Route.MirrorApps)),
            )
            SettingsTarget(FocusKeys.Codes, 2, 4) {
                SwitchRow(2, 4, TandemIcons.Key, stringResource(R.string.settings_codes), stringResource(R.string.settings_codes_sub), copyCodes, { scope.launch { prefs.setCopyCodes(it) } })
            }
            SettingsTarget(FocusKeys.Calls, 3, 4) {
                SwitchRow(3, 4, TandemIcons.Call, stringResource(R.string.settings_calls), stringResource(R.string.settings_calls_sub), calls, { scope.launch { prefs.setCallMirror(it) } })
            }
        }
    }
}
