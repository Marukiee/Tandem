package nl.markmaaktmedia.tandem.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.Route
import nl.markmaaktmedia.tandem.ui.components.ActionRow
import nl.markmaaktmedia.tandem.ui.components.SectionHeader
import nl.markmaaktmedia.tandem.ui.components.SettingsGroup
import nl.markmaaktmedia.tandem.ui.components.TandemConfirmDialog
import nl.markmaaktmedia.tandem.ui.routeBounds
import nl.markmaaktmedia.tandem.ui.routeKey
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons

private const val RepositoryUrl = "https://github.com/Marukiee/Tandem"

/** About and developer: what is new, where the code is, the developer screens, and starting over. */
@Composable
internal fun AboutPage(onBack: () -> Unit, onOpen: (Route) -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    var confirmReset by remember { mutableStateOf(false) }

    SettingsPageFrame(stringResource(R.string.settings_cat_about), onBack) {
        SectionHeader(stringResource(R.string.settings_about))
        SettingsGroup {
            ActionRow(
                0, 1, TandemIcons.OpenInNew, stringResource(R.string.settings_github), stringResource(R.string.settings_license),
                { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RepositoryUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                modifier = Modifier.settingsTarget(FocusKeys.Source, 0, 1),
            )
        }

        SectionHeader(stringResource(R.string.dev_section))
        SettingsGroup {
            ActionRow(
                0, 1, TandemIcons.Info, stringResource(R.string.dev_title), stringResource(R.string.dev_sub),
                { onOpen(Route.Developer) }, modifier = Modifier.routeBounds(routeKey(Route.Developer)),
            )
        }

        // Alone at the bottom: the one thing on these pages that cannot be undone.
        Spacer(Modifier.height(24.dp))
        SettingsGroup {
            ActionRow(
                0, 1, TandemIcons.Restart, stringResource(R.string.settings_reset), stringResource(R.string.settings_reset_sub),
                { confirmReset = true }, danger = true, modifier = Modifier.settingsTarget(FocusKeys.Reset, 0, 1),
            )
        }
    }

    if (confirmReset) {
        TandemConfirmDialog(
            title = stringResource(R.string.settings_reset),
            body = stringResource(R.string.settings_reset_body),
            confirmLabel = stringResource(R.string.settings_reset_confirm),
            cancelLabel = stringResource(R.string.action_cancel),
            destructive = true,
            onConfirm = {
                graph.host.stop()
                nl.markmaaktmedia.tandem.engine.TandemService.stop(context)
                (context.getSystemService(android.app.ActivityManager::class.java)).clearApplicationUserData()
            },
            onDismiss = { confirmReset = false },
        )
    }
}
