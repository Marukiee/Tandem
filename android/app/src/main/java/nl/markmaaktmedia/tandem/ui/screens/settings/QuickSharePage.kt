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
import nl.markmaaktmedia.tandem.ui.components.ActionRow
import nl.markmaaktmedia.tandem.ui.components.DropdownRow
import nl.markmaaktmedia.tandem.ui.components.ContentRow
import nl.markmaaktmedia.tandem.ui.components.InfoRow
import nl.markmaaktmedia.tandem.ui.components.NoteRow
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

    val minutes by host.visibleMinutes.collectAsState()
    val tileOpens by host.tileOpens.collectAsState()
    val context = LocalContext.current
    val named = peers.filter { it.name.isNotBlank() }
    val unnamed = peers.size - named.size

    SettingsPageFrame(stringResource(R.string.quickshare_title), onBack) {
        Spacer(Modifier.height(16.dp))
        SettingsGroup {
            SwitchRow(
                0, 1, TandemIcons.QuickShare, stringResource(R.string.quickshare_toggle_title), stringResource(R.string.quickshare_toggle_sub), enabled, { host.setEnabled(it) },
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

        SectionHeader(stringResource(R.string.quickshare_visibility))
        SettingsGroup {
            InfoRow(0, 2, TandemIcons.Phone, stringResource(R.string.quickshare_receive_as), context.graph.host.myName.ifBlank { "Tandem" })
            DropdownRow(
                1, 2, TandemIcons.QuickShare, stringResource(R.string.settings_quickshare_visible), stringResource(R.string.settings_quickshare_visible_sub),
                context.getString(when (minutes) { 60 -> R.string.quickshare_visible_hour; 10 -> R.string.quickshare_visible_10; else -> R.string.quickshare_visible_always }),
                listOf(0 to context.getString(R.string.quickshare_visible_always), 60 to context.getString(R.string.quickshare_visible_hour), 10 to context.getString(R.string.quickshare_visible_10)),
                { host.setVisibleMinutes(it) },
            )
        }

        SectionHeader(stringResource(R.string.quickshare_quick_settings))
        SettingsGroup {
            ActionRow(
                0, 3, TandemIcons.QuickShare, stringResource(R.string.quickshare_open_sheet), stringResource(R.string.quickshare_open_sheet_sub),
                { context.startActivity(android.content.Intent(context, nl.markmaaktmedia.tandem.quickshare.QuickShareSheetActivity::class.java)) },
            )
            ActionRow(
                1, 3, TandemIcons.QuickShare, stringResource(R.string.settings_quickshare_tile), stringResource(R.string.settings_quickshare_tile_sub),
                { nl.markmaaktmedia.tandem.quickshare.QuickShareTileService.requestAdd(context) },
            )
            DropdownRow(
                2, 3, TandemIcons.QuickShare, stringResource(R.string.settings_quickshare_tap), stringResource(R.string.settings_quickshare_tap_sub),
                context.getString(if (tileOpens) R.string.quickshare_tap_open else R.string.quickshare_tap_toggle),
                listOf(true to context.getString(R.string.quickshare_tap_open), false to context.getString(R.string.quickshare_tap_toggle)),
                { host.setTileOpens(it) },
            )
        }

        SectionHeader(stringResource(R.string.quickshare_how_title))
        SettingsGroup {
            NoteRow(0, 3, TandemIcons.Download, stringResource(R.string.quickshare_how_receive_title), stringResource(R.string.quickshare_how_receive))
            NoteRow(1, 3, TandemIcons.QuickShare, stringResource(R.string.quickshare_how_send_title), stringResource(R.string.quickshare_how_send))
            NoteRow(2, 3, TandemIcons.Folder, stringResource(R.string.quickshare_how_files_title), stringResource(R.string.quickshare_how_files))
        }

        if (enabled) {
            SectionHeader(stringResource(R.string.quickshare_nearby))
            if (named.isEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PillSpinner(size = 24.dp)
                    Text(stringResource(R.string.quickshare_searching), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                SettingsGroup {
                    named.forEachIndexed { index, peer ->
                        InfoRow(
                            index, named.size,
                            if (peer.kind == TandemQsKind.PHONE) TandemIcons.Phone else TandemIcons.Desktop,
                            peer.name, context.getString(if (peer.kind == TandemQsKind.PHONE) R.string.quickshare_type_phone else R.string.quickshare_type_computer),
                        )
                    }
                }
            }
            if (unnamed > 0) {
                Text(
                    androidx.compose.ui.res.pluralStringResource(R.plurals.quickshare_unnamed, unnamed, unnamed),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}
