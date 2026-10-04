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
import nl.markmaaktmedia.tandem.ui.components.SwitchRow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
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
    val graph = LocalContext.current.graph
    val updater = graph.updater
    val scope = androidx.compose.runtime.rememberCoroutineScope()

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
            ActionRow(0, 3, TandemIcons.Update, stringResource(R.string.dev_update), stringResource(R.string.dev_update_sub), { scope.launch { graph.prefs.setDismissedUpdate(""); updater.showPreview() } })
            val context = LocalContext.current
            val fake by nl.markmaaktmedia.tandem.media.FakePlayer.running.collectAsState()
            SwitchRow(
                1, 3, TandemIcons.Music, stringResource(R.string.dev_player), stringResource(R.string.dev_player_sub), fake,
                { if (it) nl.markmaaktmedia.tandem.media.FakePlayer.start(context) else nl.markmaaktmedia.tandem.media.FakePlayer.stop() },
            )
            ActionRow(2, 3, TandemIcons.VolumeUp, stringResource(R.string.dev_tone), stringResource(R.string.dev_tone_sub), { graph.audio.playTestTone() })
        }

        // The hotspot counts mobile data per day and per month. These put numbers in it so the lists can be seen and tried,
        // and take them out again.
        SectionHeader(stringResource(R.string.dev_hotspot))
        SettingsGroup {
            val context = LocalContext.current
            val prefs = nl.markmaaktmedia.tandem.hotspot.HotspotModule.get(context).prefs
            ActionRow(
                0, 4, TandemIcons.Hotspot, stringResource(R.string.dev_hotspot_today), stringResource(R.string.dev_hotspot_today_sub),
                { scope.launch { prefs.addTestUsage(nl.markmaaktmedia.tandem.hotspot.HotspotUsage.dayKey(System.currentTimeMillis()), 500L * 1024 * 1024) } },
            )
            ActionRow(
                1, 4, TandemIcons.Hotspot, stringResource(R.string.dev_hotspot_months), stringResource(R.string.dev_hotspot_months_sub),
                {
                    scope.launch {
                        // About a gigabyte and a half on a few days of each of the last five months, a different amount each time.
                        val now = java.time.LocalDate.now()
                        for (back in 1..5L) for (offset in listOf(3L, 11L, 19L)) {
                            val day = now.minusMonths(back).withDayOfMonth(minOf(28, (offset + back).toInt()))
                            prefs.addTestUsage(day.toString(), (180L + back * 40 + offset * 9) * 1024 * 1024)
                        }
                    }
                },
            )
            ActionRow(2, 4, TandemIcons.Hotspot, stringResource(R.string.dev_hotspot_open), stringResource(R.string.dev_hotspot_open_sub), { onOpen(Route.Hotspot) })
            ActionRow(3, 4, TandemIcons.Delete, stringResource(R.string.dev_hotspot_clear), stringResource(R.string.dev_hotspot_clear_sub), { scope.launch { prefs.resetDataUsed() } })
        }
    }
}
