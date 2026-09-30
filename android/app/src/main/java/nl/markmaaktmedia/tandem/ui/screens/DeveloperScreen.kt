package nl.markmaaktmedia.tandem.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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
import nl.markmaaktmedia.tandem.ui.components.TandemIconButton
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons

/**
 * Screens to look at and press through without any of it counting: the onboarding runs on
 * demo state, the update banner is a fake release, and nothing is asked of Android or saved.
 */
@Composable
fun DeveloperScreen(onBack: () -> Unit, onOpen: (Route) -> Unit) {
    val updater = LocalContext.current.graph.updater

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TandemIconButton(TandemIcons.Back, stringResource(R.string.action_back), onBack)
        }
        Text(stringResource(R.string.dev_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 8.dp))
        Text(
            stringResource(R.string.dev_intro),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        )

        SectionHeader(stringResource(R.string.dev_screens))
        SettingsGroup {
            ActionRow(0, 3, TandemIcons.Devices, stringResource(R.string.dev_onboarding), stringResource(R.string.dev_onboarding_sub), { onOpen(Route.OnboardingPreview) })
            ActionRow(1, 3, TandemIcons.QrScan, stringResource(R.string.dev_pairing), stringResource(R.string.dev_pairing_sub), { onOpen(Route.Pair) })
            ActionRow(2, 3, TandemIcons.Shield, stringResource(R.string.dev_access), stringResource(R.string.dev_access_sub), { onOpen(Route.Access) })
        }

        SectionHeader(stringResource(R.string.dev_pieces))
        SettingsGroup {
            ActionRow(0, 1, TandemIcons.Update, stringResource(R.string.dev_update), stringResource(R.string.dev_update_sub), { updater.showPreview() })
        }
    }
}
