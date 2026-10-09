package nl.markmaaktmedia.tandem.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import uniffi.tandem_core.TandemDevice
import uniffi.tandem_core.TandemPlatform

/**
 * Seeing and controlling a computer from the phone, as a row of the group "Control" on the page of the computer. It is always there,
 * and grey until it can work, with what is missing written under it: a button that is not there tells nobody what to do.
 */
@Composable
fun ControlComputerRow(index: Int, total: Int, device: TandemDevice, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val mac = device.platform == TandemPlatform.MAC_OS
    val hosts = "screen.host" in device.caps
    val controls = "screen.control" in device.caps
    val ready = device.online && hosts
    val title = stringResource(if (mac) R.string.tile_control_mac else R.string.tile_control_computer)
    val subtitle = stringResource(
        when {
            ready -> R.string.control_sub_ready
            !device.online -> R.string.control_sub_offline
            mac -> R.string.control_sub_setup
            else -> R.string.control_sub_unavailable
        },
    )
    GroupedRow(index, total, modifier = modifier, onClick = if (ready) onOpen else null) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            RowIcon(
                TandemIcons.Desktop,
                tint = if (ready) scheme.onSecondaryContainer else scheme.onSurfaceVariant.copy(alpha = 0.6f),
                container = if (ready) scheme.secondaryContainer else scheme.surfaceContainerHighest,
            )
            Column(Modifier.weight(1f).alpha(if (ready) 1f else 0.6f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            if (ready) Icon(TandemIcons.ChevronRight, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        // What is missing, only while the computer is reachable: offline there is nothing to set up yet.
        if (device.online && mac && !hosts) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.control_setup_title), style = MaterialTheme.typography.labelLarge, color = scheme.primary)
                listOf(R.string.control_setup_1, R.string.control_setup_2, R.string.control_setup_3, R.string.control_setup_4).forEachIndexed { index, text ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("${index + 1}", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant, modifier = Modifier.width(14.dp))
                        Text(stringResource(text), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                }
            }
        } else if (ready && !controls) {
            Text(
                stringResource(if (mac) R.string.control_no_access else R.string.control_no_access_computer),
                style = MaterialTheme.typography.bodySmall, color = scheme.error,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            )
        }
    }
}

/** One row of the group "Control" on the page of a device: grey and without a chevron while it cannot be used. */
@Composable
fun ControlRow(
    index: Int,
    total: Int,
    icon: androidx.compose.ui.graphics.painter.Painter,
    title: String,
    subtitle: String,
    ready: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    GroupedRow(index, total, modifier = modifier, onClick = if (ready) onClick else null) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            RowIcon(
                icon,
                tint = if (ready) scheme.onSecondaryContainer else scheme.onSurfaceVariant.copy(alpha = 0.6f),
                container = if (ready) scheme.secondaryContainer else scheme.surfaceContainerHighest,
            )
            Column(Modifier.weight(1f).alpha(if (ready) 1f else 0.6f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            if (ready) Icon(TandemIcons.ChevronRight, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}
