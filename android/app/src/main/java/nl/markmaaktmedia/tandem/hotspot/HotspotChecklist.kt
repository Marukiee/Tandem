package nl.markmaaktmedia.tandem.hotspot

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.engine.Permissions
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.components.ActionRow
import nl.markmaaktmedia.tandem.ui.components.rememberPermissionRequests
import nl.markmaaktmedia.tandem.ui.components.rememberPermissionStatus
import nl.markmaaktmedia.tandem.ui.theme.LocalTandemExtraColors
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import uniffi.tandem_core.TandemPlatform

/** How many rows [HotspotChecklistRows] draws. */
const val HOTSPOT_CHECKLIST_ROWS = 5

/**
 * What the hotspot needs, one line each with a check when it is in place and a button when not,
 * so it is clear at a glance whether sharing the hotspot can work.
 */
@Composable
fun ColumnScope.HotspotChecklistRows() {
    val context = LocalContext.current
    val module = HotspotModule.get(context)
    val status = rememberPermissionStatus()
    val requests = rememberPermissionRequests(status)
    val shizuku by module.shizuku.state.collectAsState()
    val devices by context.graph.host.devices.collectAsState()

    LaunchedEffect(Unit) {
        while (true) {
            module.shizuku.refresh()
            delay(2_000)
        }
    }

    val macPaired = devices.any { it.platform == TandemPlatform.MAC_OS }
    val automatic = shizuku == ShizukuState.Ready

    Row(0, TandemIcons.Bluetooth, R.string.perm_bluetooth, if (status.bluetooth) R.string.check_ok else R.string.check_bluetooth_missing, status.bluetooth) { requests.bluetooth() }
    Row(1, TandemIcons.Notifications, R.string.perm_notifications, if (status.notifications) R.string.check_ok else R.string.check_notifications_missing, status.notifications) { requests.notifications() }
    Row(2, TandemIcons.Battery, R.string.perm_battery, if (status.battery) R.string.check_ok else R.string.check_battery_missing, status.battery) { Permissions.openBatterySettings(context) }
    // Turning it on by hand is a fine way to work, so it is neutral rather than a failure.
    Row(
        3, TandemIcons.Hotspot, R.string.check_method,
        if (automatic) R.string.check_method_shizuku else R.string.check_method_manual, automatic, neutral = !automatic,
    ) {
        when (shizuku) {
            ShizukuState.NeedsPermission -> module.shizuku.requestPermission()
            ShizukuState.NotRunning, ShizukuState.Denied -> module.shizuku.launchShizuku()
            else -> Unit
        }
    }
    Row(4, TandemIcons.Laptop, R.string.check_mac, if (macPaired) R.string.check_ok else R.string.check_mac_missing, macPaired) {}
}

@Composable
private fun ColumnScope.Row(
    index: Int,
    icon: androidx.compose.ui.graphics.painter.Painter,
    title: Int,
    detail: Int,
    ok: Boolean,
    neutral: Boolean = false,
    onClick: () -> Unit,
) {
    ActionRow(
        index, HOTSPOT_CHECKLIST_ROWS, icon, stringResource(title), stringResource(detail),
        onClick = { if (!ok) onClick() },
        trailing = {
            when {
                ok -> Icon(TandemIcons.Check, null, tint = LocalTandemExtraColors.current.online, modifier = Modifier.size(24.dp))
                neutral -> Icon(TandemIcons.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                else -> Text(stringResource(R.string.action_allow), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        },
    )
}
