package nl.markmaaktmedia.tandem.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.Route
import nl.markmaaktmedia.tandem.ui.components.ActionRow
import nl.markmaaktmedia.tandem.ui.components.SettingsGroup
import nl.markmaaktmedia.tandem.ui.components.SwitchRow
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import uniffi.tandem_core.TandemHealthKind

/**
 * Sleep and workouts, away from the settings of the watch. Without a watch this page has only the switch that turns it on, because
 * the switch has to be somewhere a person can find it.
 */
@Composable
internal fun HealthPage(onBack: () -> Unit, onOpen: (Route) -> Unit) {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    val watch by graph.watch.state.collectAsState()
    val shown by graph.prefs.healthShown.collectAsState(initial = false)
    val records by graph.host.healthRecords.collectAsState()
    val insights = rememberHealthInsights()

    SettingsPageFrame(stringResource(R.string.settings_cat_health), onBack) {
        Spacer(Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!watch.paired) {
                SettingsGroup {
                    SettingsTarget(FocusKeys.HealthShow, 0, 2) {
                        SwitchRow(
                            0, 2, TandemIcons.Sleep, stringResource(R.string.health_show), stringResource(R.string.health_show_sub), shown,
                            { on -> scope.launch { graph.prefs.setHealthShown(on) } },
                        )
                    }
                    ActionRow(1, 2, TandemIcons.Watch, stringResource(R.string.watch_pair), null, onClick = { onOpen(Route.PairWatch) })
                }
            }
            if (watch.paired || shown) {
                HealthAlert(insights)
                LatestNight(insights)
                HealthTrends(insights)
                EarlierNights()
                if (records.none { it.kind != TandemHealthKind.UNKNOWN }) {
                    Text(
                        stringResource(R.string.health_none),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }
}
