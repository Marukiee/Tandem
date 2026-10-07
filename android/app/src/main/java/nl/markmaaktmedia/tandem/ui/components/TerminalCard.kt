package nl.markmaaktmedia.tandem.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.theme.CardSquircle
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import uniffi.tandem_core.TandemDevice
import uniffi.tandem_core.TandemPlatform
import java.net.InetSocketAddress
import java.net.Socket

/** Whether a computer answers on the SSH port, at one of the addresses it was seen at. Null when it does not. */
suspend fun sshAddress(addresses: List<String>): String? = withContext(Dispatchers.IO) {
    for (address in addresses.take(4)) {
        val open = runCatching { Socket().use { it.connect(InetSocketAddress(address, 22), 1500); true } }.getOrDefault(false)
        if (open) return@withContext address
    }
    null
}

/**
 * Logging in to a computer from the phone, in a terminal of the app. It is on the page of every computer, and grey until the
 * computer answers on the SSH port, with what has to be done written under it.
 */
@Composable
fun TerminalCard(device: TandemDevice, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val engine = LocalContext.current.graph.host.engine
    var address by remember(device.id) { mutableStateOf<String?>(null) }
    var checked by remember(device.id) { mutableStateOf(false) }
    LaunchedEffect(device.id, device.online) {
        checked = false
        address = if (device.online) sshAddress(runCatching { engine?.deviceIps(device.id) }.getOrNull().orEmpty()) else null
        checked = true
    }
    val ready = device.online && address != null
    val title = stringResource(R.string.terminal_card_title)
    val reason = stringResource(
        when {
            !device.online -> R.string.terminal_why_offline
            device.platform == TandemPlatform.MAC_OS -> R.string.terminal_why_mac
            device.platform == TandemPlatform.WINDOWS -> R.string.terminal_why_windows
            else -> R.string.terminal_why_linux
        },
    )
    Column(modifier.fillMaxWidth().clip(CardSquircle).background(scheme.surfaceContainer)) {
        Row(
            Modifier.fillMaxWidth().bouncyClickable(enabled = ready, onClickLabel = title, onClick = onOpen).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            RowIcon(
                TandemIcons.Terminal,
                tint = if (ready) scheme.onSecondaryContainer else scheme.onSurfaceVariant.copy(alpha = 0.6f),
                container = if (ready) scheme.secondaryContainer else scheme.surfaceContainerHighest,
            )
            Column(Modifier.weight(1f).alpha(if (ready) 1f else 0.6f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.terminal_card_sub), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            if (ready) Icon(TandemIcons.ChevronRight, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        // What is missing, once it was looked for.
        if (checked && !ready) {
            Text(
                reason,
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            )
        }
    }
}
