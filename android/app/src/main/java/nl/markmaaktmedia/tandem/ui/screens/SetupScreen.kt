package nl.markmaaktmedia.tandem.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.ui.Route
import nl.markmaaktmedia.tandem.ui.components.ActionRow
import nl.markmaaktmedia.tandem.ui.components.SettingsGroup
import nl.markmaaktmedia.tandem.ui.screens.settings.SettingsCategory
import nl.markmaaktmedia.tandem.ui.screens.settings.SettingsPageFrame
import nl.markmaaktmedia.tandem.ui.screens.settings.rememberCategorySummaries
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons

/**
 * What Tandem needs on this phone: the tour, and the permissions, Shizuku and what other devices may do with the files, each a page of
 * its own behind a row as in the overview of the settings (asked 2026-10-26). A link to one of them (a switch that needs a permission, a
 * search) opens that page itself.
 */
@Composable
fun SetupScreen(onBack: () -> Unit, onOpen: (Route) -> Unit) {
    val summaries = rememberCategorySummaries()
    SettingsPageFrame(stringResource(R.string.setup_title), onBack) {
        // The tour first: it says per thing what it needs, and the pages below are where it is given.
        Spacer(Modifier.height(16.dp))
        SettingsGroup {
            ActionRow(0, 1, TandemIcons.Idea, stringResource(R.string.tour_title), stringResource(R.string.tour_row_sub), onClick = { onOpen(Route.Tour) })
        }
        Spacer(Modifier.height(12.dp))
        val parts = listOf(SettingsCategory.Permissions, SettingsCategory.Shizuku, SettingsCategory.Files)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            parts.forEachIndexed { index, part ->
                CategoryRow(index, parts.size, part, summaries[part].orEmpty(), onClick = { onOpen(part.route) }, modifier = Modifier)
            }
        }
    }
}
