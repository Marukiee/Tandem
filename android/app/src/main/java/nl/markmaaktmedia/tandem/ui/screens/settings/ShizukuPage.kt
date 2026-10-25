package nl.markmaaktmedia.tandem.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.hotspot.HotspotModule
import nl.markmaaktmedia.tandem.hotspot.ShizukuState
import nl.markmaaktmedia.tandem.live.ProjectionGrant
import nl.markmaaktmedia.tandem.ui.Route
import nl.markmaaktmedia.tandem.ui.components.ActionRow
import nl.markmaaktmedia.tandem.ui.components.SectionHeader
import nl.markmaaktmedia.tandem.ui.components.SettingsGroup
import nl.markmaaktmedia.tandem.ui.components.SwitchRow
import nl.markmaaktmedia.tandem.ui.theme.LocalTandemExtraColors
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons

/** What Shizuku is doing on this phone, and what Tandem does with it. */
@Composable
internal fun ShizukuPage(onBack: () -> Unit, onOpen: (Route) -> Unit) {
    nl.markmaaktmedia.tandem.ui.screens.SetupScreen(onBack, onOpen, nl.markmaaktmedia.tandem.ui.screens.SetupSection.Shizuku)
}

/** What Shizuku is for here and how far it is, on the page Setup. */
@Composable
internal fun ColumnScope.ShizukuContent(onOpen: (Route) -> Unit) {
    val context = LocalContext.current
    val shizuku = HotspotModule.get(context).shizuku
    val state by shizuku.state.collectAsState()
    val projection by ProjectionGrant.state.collectAsState()
    var uid by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()

    // Shizuku sends no word when it is started or allowed in its own app, so the page looks again while it is open.
    LaunchedEffect(Unit) {
        while (true) {
            shizuku.refresh()
            uid = shizuku.serverUid()
            ProjectionGrant.allowed(context)
            delay(2_000)
        }
    }
    LaunchedEffect(state) {
        if (state == ShizukuState.Ready) ProjectionGrant.ensure(context)
    }

    val ready = state == ShizukuState.Ready
    val clipOn by context.graph.prefs.clipboardShizuku.collectAsState(initial = true)
    val clipWatching by nl.markmaaktmedia.tandem.hotspot.ShizukuClipboard.watching.collectAsState()
    val clipProblem by nl.markmaaktmedia.tandem.hotspot.ShizukuClipboard.problem.collectAsState()
    // The next step for Shizuku itself, from its own row and from a use that waits for it.
    val nextStep: () -> Unit = {
        when (state) {
            ShizukuState.NeedsPermission -> shizuku.requestPermission()
            ShizukuState.NotRunning, ShizukuState.Denied, ShizukuState.Ready -> shizuku.launchShizuku()
            ShizukuState.NotInstalled, ShizukuState.TooOld -> context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
    val done: @Composable () -> Unit = {
        Icon(TandemIcons.Check, null, tint = LocalTandemExtraColors.current.online, modifier = Modifier.size(24.dp))
    }

    Text(
        stringResource(R.string.shizuku_intro),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
    )

    SectionHeader(stringResource(R.string.shizuku_section_state))
    SettingsGroup {
        val (title, sub) = when (state) {
            ShizukuState.NotInstalled -> R.string.shizuku_state_not_installed to R.string.shizuku_state_not_installed_sub
            ShizukuState.NotRunning -> R.string.shizuku_state_not_running to R.string.shizuku_state_not_running_sub
            ShizukuState.NeedsPermission -> R.string.shizuku_state_needs_permission to R.string.shizuku_state_needs_permission_sub
            ShizukuState.Denied -> R.string.shizuku_state_denied to R.string.shizuku_state_denied_sub
            ShizukuState.TooOld -> R.string.shizuku_state_too_old to R.string.shizuku_state_too_old_sub
            ShizukuState.Ready -> (if (uid == 0) R.string.shizuku_state_root else R.string.shizuku_state_shell) to R.string.shizuku_state_ready_sub
        }
        SettingsTarget(FocusKeys.ShizukuStatus, 0, 1) {
            ActionRow(
                0, 1, TandemIcons.Terminal, stringResource(title), stringResource(sub),
                onClick = nextStep,
                trailing = if (ready) done else null,
            )
        }
    }

    SectionHeader(stringResource(R.string.shizuku_section_uses))
    SettingsGroup {
        SettingsTarget(FocusKeys.ShizukuHotspot, 0, 4) {
            ActionRow(
                0, 4, TandemIcons.Hotspot, stringResource(R.string.shizuku_use_hotspot),
                stringResource(if (ready) R.string.shizuku_use_hotspot_on else R.string.shizuku_use_hotspot_off),
                onClick = { onOpen(Route.Hotspot) },
                trailing = if (ready) done else null,
            )
        }
        SettingsTarget(FocusKeys.ShizukuProjection, 1, 4) {
            ActionRow(
                1, 4, TandemIcons.VolumeUp, stringResource(R.string.shizuku_use_projection),
                stringResource(
                    when {
                        projection -> R.string.shizuku_use_projection_on
                        ready -> R.string.shizuku_use_projection_set
                        else -> R.string.shizuku_use_projection_off
                    },
                ),
                onClick = {
                    when {
                        projection -> Unit
                        ready -> scope.launch { ProjectionGrant.ensure(context) }
                        else -> nextStep()
                    }
                },
                trailing = if (projection) done else null,
            )
        }
        SettingsTarget(FocusKeys.ShizukuClipboard, 2, 4) {
            SwitchRow(
                2, 4, TandemIcons.Paste, stringResource(R.string.shizuku_use_clipboard),
                clipProblem ?: stringResource(if (clipWatching) R.string.shizuku_use_clipboard_on else R.string.shizuku_use_clipboard_sub),
                clipOn,
                { on -> scope.launch { context.graph.prefs.setClipboardShizuku(on) } },
                blocked = if (ready) null else stringResource(R.string.shizuku_use_clipboard_off),
                onBlocked = nextStep,
            )
        }
        // Android tells about every read of the clipboard by another app, so also about each copy the shell hears for Tandem.
        ActionRow(
            3, 4, TandemIcons.Shield, stringResource(R.string.shizuku_clipboard_message),
            stringResource(R.string.shizuku_clipboard_message_sub),
            onClick = {
                runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_PRIVACY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            },
        )
    }
}
