package nl.markmaaktmedia.tandem.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.data.TandemPrefs
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.components.ActionRow
import nl.markmaaktmedia.tandem.ui.components.NoteRow
import nl.markmaaktmedia.tandem.ui.components.PrimaryPillButton
import nl.markmaaktmedia.tandem.ui.components.SecondaryPillButton
import nl.markmaaktmedia.tandem.ui.components.SettingsGroup
import nl.markmaaktmedia.tandem.ui.components.TandemConfirmDialog
import nl.markmaaktmedia.tandem.ui.components.TandemDialog
import nl.markmaaktmedia.tandem.ui.components.TandemIconButton
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import nl.markmaaktmedia.tandem.watch.WatchAppState

/**
 * What is set for the watch itself, on a page of its own like a computer has: its name, the Tandem on it, and unpairing it. They were
 * on the page of the watch, between the things it does, where unpairing was one tap away.
 */
@Composable
internal fun WatchSettingsScreen(onBack: () -> Unit) {
    val watch = LocalContext.current.graph.watch
    val state by watch.state.collectAsState()
    val appUpdate by watch.appUpdate.collectAsState()
    var renaming by remember { mutableStateOf(false) }
    var confirmUnpair by remember { mutableStateOf(false) }
    val plain = stringResource(R.string.settings_cat_watch)
    val name = state.name ?: plain

    if (!state.paired) {
        // Unpaired while looking at it, here or from the watch.
        LaunchedEffect(Unit) { onBack() }
        return
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TandemIconButton(TandemIcons.Back, stringResource(R.string.action_back), onBack)
        }
        Text(name, style = MaterialTheme.typography.headlineMedium, maxLines = 2, modifier = Modifier.padding(horizontal = 8.dp))
        Text(
            stringResource(R.string.device_settings_title),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )

        Spacer(Modifier.height(4.dp))
        SettingsGroup {
            ActionRow(0, 2, TandemIcons.Watch, stringResource(R.string.watch_name_row), name, { renaming = true })
            // Says what it is doing, so a watch that restarts by itself during an update is no surprise. A failure is never the end
            // of it: a tap tries again.
            if (appUpdate is WatchAppState.Failed) {
                ActionRow(1, 2, TandemIcons.Update, stringResource(R.string.watch_app_title), watchAppText(appUpdate) + " " + stringResource(R.string.watch_app_retry), watch::retryAppUpdate)
            } else {
                NoteRow(1, 2, TandemIcons.Update, stringResource(R.string.watch_app_title), watchAppText(appUpdate))
            }
        }

        Spacer(Modifier.height(8.dp))
        SettingsGroup {
            ActionRow(0, 1, TandemIcons.Delete, stringResource(R.string.watch_unpair), stringResource(R.string.watch_unpair_sub), { confirmUnpair = true }, danger = true)
        }
        Spacer(Modifier.height(24.dp))
    }

    if (renaming) {
        var text by remember { mutableStateOf(name) }
        TandemDialog(
            title = stringResource(R.string.settings_name),
            onDismiss = { renaming = false },
            icon = TandemIcons.Watch,
            body = stringResource(R.string.watch_name_sub),
            actions = {
                SecondaryPillButton(stringResource(R.string.action_cancel), { renaming = false })
                PrimaryPillButton(stringResource(R.string.action_save), {
                    // The plain name is no name of its own: it then follows the language of each device.
                    watch.rename(text.trim().takeIf { it != plain }.orEmpty())
                    renaming = false
                })
            },
            content = {
                OutlinedTextField(value = text, onValueChange = { text = it.take(TandemPrefs.WATCH_NAME_MAX) }, singleLine = true, shape = MaterialTheme.shapes.large)
            },
        )
    }

    if (confirmUnpair) {
        TandemConfirmDialog(
            title = stringResource(R.string.watch_unpair_title, name),
            body = stringResource(R.string.watch_unpair_body),
            confirmLabel = stringResource(R.string.watch_unpair_confirm),
            cancelLabel = stringResource(R.string.action_cancel),
            destructive = true,
            onConfirm = {
                confirmUnpair = false
                watch.unpair()
            },
            onDismiss = { confirmUnpair = false },
        )
    }
}

@Composable
private fun watchAppText(state: WatchAppState): String = when (state) {
    WatchAppState.Unknown -> stringResource(R.string.watch_app_unknown)
    is WatchAppState.UpToDate -> stringResource(R.string.watch_app_up_to_date, state.name)
    is WatchAppState.OtherSigner -> stringResource(R.string.watch_app_other_signer, state.name)
    is WatchAppState.Fetching -> stringResource(R.string.watch_app_fetching, state.name)
    is WatchAppState.Sending -> stringResource(R.string.watch_app_sending, state.name, (state.progress * 100).toInt())
    is WatchAppState.Waiting -> stringResource(R.string.watch_app_waiting, state.name)
    is WatchAppState.Installing -> stringResource(R.string.watch_app_installing, state.name)
    is WatchAppState.Confirm -> stringResource(R.string.watch_app_confirm, state.name)
    is WatchAppState.Failed -> stringResource(R.string.watch_app_failed, state.name, state.reason)
}
