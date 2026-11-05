package nl.markmaaktmedia.tandem.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
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
    val context = LocalContext.current
    val prefs = context.graph.prefs
    val scope = rememberCoroutineScope()
    val mirror by prefs.mirrorNotifications.collectAsState(initial = true)
    val copyCodes by prefs.copyCodes.collectAsState(initial = true)
    val calls by prefs.callMirror.collectAsState(initial = true)
    val battery by prefs.batteryAlerts.collectAsState(initial = true)
    val status = nl.markmaaktmedia.tandem.ui.components.rememberPermissionStatus()
    // Reading texts or Shizuku's app-op: given outside this page, so looked at again while it is open.
    var seesCodes by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            seesCodes = nl.markmaaktmedia.tandem.messages.CodeGrant.canSeeCodes(context)
            delay(2_000)
        }
    }

    SettingsPageFrame(stringResource(R.string.settings_notifications), onBack) {
        Spacer(Modifier.height(16.dp))
        SettingsGroup {
            SettingsTarget(FocusKeys.Mirror, 0, 5) {
                SwitchRow(
                    0, 5, TandemIcons.Notifications, stringResource(R.string.settings_mirror), stringResource(R.string.settings_mirror_sub), mirror, { scope.launch { prefs.setMirrorNotifications(it) } },
                    blocked = if (status.notificationAccess) null else stringResource(R.string.perm_needs_listener), onBlocked = { onOpen(Route.Access) },
                )
            }
            ActionRow(
                1, 5, TandemIcons.Devices, stringResource(R.string.settings_mirror_apps), stringResource(R.string.settings_mirror_apps_sub),
                { onOpen(Route.MirrorApps) }, modifier = Modifier.routeBounds(routeKey(Route.MirrorApps)),
            )
            SettingsTarget(FocusKeys.Codes, 2, 5) {
                SwitchRow(
                    2, 5, TandemIcons.Key, stringResource(R.string.settings_codes), stringResource(R.string.settings_codes_sub), copyCodes, { scope.launch { prefs.setCopyCodes(it) } },
                    blocked = when {
                        !status.notificationAccess -> stringResource(R.string.perm_needs_listener)
                        !seesCodes -> stringResource(R.string.settings_codes_hidden)
                        else -> null
                    },
                    onBlocked = {
                        if (!status.notificationAccess) onOpen(Route.Access)
                        else context.startActivity(nl.markmaaktmedia.tandem.messages.MessagesAccessActivity.intent(context, null))
                    },
                )
            }
            SettingsTarget(FocusKeys.Calls, 3, 5) {
                SwitchRow(
                    3, 5, TandemIcons.Call, stringResource(R.string.settings_calls), stringResource(R.string.settings_calls_sub), calls, { scope.launch { prefs.setCallMirror(it) } },
                    blocked = if (status.phone != nl.markmaaktmedia.tandem.ui.components.PermissionLevel.Off) null else stringResource(R.string.perm_needs_phone), onBlocked = { onOpen(Route.Access) },
                )
            }
            SwitchRow(
                4, 5, TandemIcons.Battery, stringResource(R.string.settings_battery_alerts), stringResource(R.string.settings_battery_alerts_sub), battery,
                { scope.launch { prefs.setBatteryAlerts(it) } },
            )
        }
        FindSoundGroup()
        FocusGroup()
    }
}
