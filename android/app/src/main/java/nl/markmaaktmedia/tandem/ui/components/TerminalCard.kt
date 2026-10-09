package nl.markmaaktmedia.tandem.ui.components

import android.content.Context
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import uniffi.tandem_core.TandemDevice
import uniffi.tandem_core.TandemPlatform
import java.net.InetSocketAddress
import java.net.Socket

/** Whether a computer answers on the SSH port, at one of the addresses it was seen at. Null when it does not. */
suspend fun sshAddress(addresses: List<String>, port: Int = 22): String? = coroutineScope {
    // All at once, so one that does not answer costs a second and a half and not a second and a half each.
    addresses.take(4)
        .map { address ->
            async(Dispatchers.IO) {
                address to runCatching { Socket().use { it.connect(InetSocketAddress(address, port), 1500); true } }.getOrDefault(false)
            }
        }
        .awaitAll()
        .firstOrNull { it.second }
        ?.first
}

/** What was found the last time a computer was asked about SSH, so the row of the terminal shows the answer at once and not after a moment. */
private object SshAnswers {
    private val found = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    fun last(id: String): Boolean? = found[id]
    fun remember(id: String, open: Boolean) { found[id] = open }
}

/**
 * Logging in to a computer from the phone, in a terminal of the app: a row of the group "Control" on the page of a computer. Grey
 * with the reason when the computer does not answer on the SSH port. Until the first answer it is taken to be there, and it is
 * looked at in the background: a row that starts grey and lights up a moment later reads as broken.
 */
@Composable
fun TerminalRow(index: Int, total: Int, device: TandemDevice, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val engine = context.graph.host.engine
    var open by remember(device.id) { mutableStateOf(SshAnswers.last(device.id) ?: true) }
    var checked by remember(device.id) { mutableStateOf(SshAnswers.last(device.id) != null) }
    LaunchedEffect(device.id, device.online) {
        if (!device.online) return@LaunchedEffect
        val port = context.getSharedPreferences("ssh", Context.MODE_PRIVATE).getInt("port.${device.id}", 22)
        val answer = sshAddress(runCatching { engine?.deviceIps(device.id) }.getOrNull().orEmpty(), port) != null
        SshAnswers.remember(device.id, answer)
        open = answer
        checked = true
    }
    val ready = device.online && open
    val title = stringResource(R.string.terminal_card_title)
    val reason = stringResource(
        when {
            !device.online -> R.string.terminal_why_offline
            device.platform == TandemPlatform.MAC_OS -> R.string.terminal_why_mac
            device.platform == TandemPlatform.WINDOWS -> R.string.terminal_why_windows
            else -> R.string.terminal_why_linux
        },
    )
    GroupedRow(index, total, modifier = modifier, onClick = if (ready) onOpen else null) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
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
        // What is missing, once it was looked for (or when the computer is not there at all).
        if (!ready && (checked || !device.online)) {
            Text(
                reason,
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            )
        }
    }
}
