package nl.markmaaktmedia.tandem.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.Route
import nl.markmaaktmedia.tandem.ui.components.ContentRow
import nl.markmaaktmedia.tandem.ui.components.InfoRow
import nl.markmaaktmedia.tandem.ui.components.PillSpinner
import nl.markmaaktmedia.tandem.ui.components.SectionHeader
import nl.markmaaktmedia.tandem.ui.components.SettingsGroup
import nl.markmaaktmedia.tandem.ui.components.SwitchRow
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import uniffi.tandem_core.TandemQsKind

/** Quick Share without Google services: the switch, how it works, and the devices that were found nearby. */
@Composable
internal fun QuickSharePage(onBack: () -> Unit, onOpen: (Route) -> Unit = {}) {
    val host = LocalContext.current.graph.quickShare
    val enabled by host.enabled.collectAsState()
    val peers by host.peers.collectAsState()
    val problem by host.problem.collectAsState()
    val status = nl.markmaaktmedia.tandem.ui.components.rememberPermissionStatus()

    SettingsPageFrame(stringResource(R.string.quickshare_title), onBack) {
        Spacer(Modifier.height(16.dp))
        SettingsGroup {
            SwitchRow(
                0, 1, TandemIcons.Send, stringResource(R.string.quickshare_toggle_title), stringResource(R.string.quickshare_toggle_sub), enabled, { host.setEnabled(it) },
                blocked = if (status.notifications) null else stringResource(R.string.perm_needs_notifications_quickshare), onBlocked = { onOpen(Route.Access) },
            )
        }
        problem?.let {
            Text(
                stringResource(R.string.quickshare_problem, it),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        SectionHeader(stringResource(R.string.quickshare_how_title))
        SettingsGroup {
            ContentRow(0, 3, TandemIcons.Download, stringResource(R.string.quickshare_how_receive)) {}
            ContentRow(1, 3, TandemIcons.Send, stringResource(R.string.quickshare_how_send)) {}
            ContentRow(2, 3, TandemIcons.Folder, stringResource(R.string.quickshare_how_files)) {}
        }

        if (enabled) {
            SectionHeader(stringResource(R.string.quickshare_nearby))
            if (peers.isEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PillSpinner(size = 24.dp)
                    Text(stringResource(R.string.quickshare_searching), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                SettingsGroup {
                    peers.forEachIndexed { index, peer ->
                        InfoRow(
                            index, peers.size,
                            if (peer.kind == TandemQsKind.PHONE) TandemIcons.Phone else TandemIcons.Desktop,
                            peer.name, "",
                        )
                    }
                }
            }
        }
    }
}
