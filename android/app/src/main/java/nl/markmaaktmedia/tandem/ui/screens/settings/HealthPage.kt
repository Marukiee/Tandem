package nl.markmaaktmedia.tandem.ui.screens.settings

import android.os.Build
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import nl.markmaaktmedia.tandem.ui.components.TandemIconButton
import androidx.compose.ui.Alignment
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.Route
import nl.markmaaktmedia.tandem.ui.components.ActionRow
import nl.markmaaktmedia.tandem.ui.components.ContentRow
import nl.markmaaktmedia.tandem.ui.components.SectionHeader
import nl.markmaaktmedia.tandem.ui.components.SettingsGroup
import nl.markmaaktmedia.tandem.ui.components.SwitchRow
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import nl.markmaaktmedia.tandem.watch.HealthConnectImport
import uniffi.tandem_core.TandemHealthKind
import java.util.TimeZone

/**
 * Health at a glance: last night large, and a card for sleep, activity and the heart with the numbers that matter most, each opening the
 * whole of it. It was one long page with everything on it, the choices and the data at the bottom; those now have a page of their own,
 * behind the gear. Without a watch this page has only the switch that turns it on, because the switch has to be somewhere a person can
 * find it.
 */
@Composable
internal fun HealthPage(onBack: () -> Unit, onOpen: (Route) -> Unit) {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    val watch by graph.watch.state.collectAsState()
    val shown by graph.prefs.healthShown.collectAsState(initial = false)
    val records by graph.host.healthRecords.collectAsState()
    val insights = rememberHealthInsights()

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TandemIconButton(TandemIcons.Back, stringResource(R.string.action_back), onBack)
            Spacer(Modifier.weight(1f))
            TandemIconButton(TandemIcons.Settings, stringResource(R.string.health_settings_title), { onOpen(Route.HealthSettings) })
        }
        Text(stringResource(R.string.settings_cat_health), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 8.dp))
        if (!watch.paired && !shown) {
            SettingsGroup {
                SwitchRow(
                    0, 2, TandemIcons.Sleep, stringResource(R.string.health_show), stringResource(R.string.health_show_sub), shown,
                    { on -> scope.launch { graph.prefs.setHealthShown(on) } },
                )
                ActionRow(1, 2, TandemIcons.Watch, stringResource(R.string.watch_pair), null, onClick = { onOpen(Route.PairWatch) })
            }
        } else {
            HealthAlert(insights)
            HealthHero(insights) { onOpen(Route.HealthDetail("sleep")) }
            HealthParts(insights) { onOpen(Route.HealthDetail(it)) }
            if (records.none { it.kind == TandemHealthKind.SLEEP || it.kind == TandemHealthKind.WORKOUT } && !watch.paired) {
                Text(
                    stringResource(R.string.health_none),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** One part of Health in full: the nights, the activity or the heart, with the week next to today and the earlier ones under it. */
@Composable
internal fun HealthDetailScreen(part: String, onBack: () -> Unit) {
    val insights = rememberHealthInsights()
    val goal by LocalContext.current.graph.prefs.stepGoal.collectAsState(initial = nl.markmaaktmedia.tandem.data.TandemPrefs.DEFAULT_STEP_GOAL)
    val title = stringResource(
        when (part) {
            "sleep" -> R.string.health_sleep_title
            "activity" -> R.string.health_activity_title
            else -> R.string.health_heart_title
        },
    )
    SettingsPageFrame(title, onBack) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (part) {
                "sleep" -> {
                    LatestNight(insights)
                    SleepWeek(insights)
                    MonthCalendar(CalendarKind.SLEEP)
                    Naps(insights)
                    EarlierNights()
                }
                "activity" -> {
                    ActivityToday(insights)
                    ActivityWeek(insights)
                    MonthCalendar(CalendarKind.ACTIVITY, goal)
                    EarlierWorkouts()
                }
                else -> {
                    HealthAlert(insights)
                    HeartToday(insights)
                    HeartWeek(insights)
                    MonthCalendar(CalendarKind.HEART)
                }
            }
        }
    }
}

/**
 * What is chosen for Health, which computers get it, and its data in and out of Health Connect. They were at the bottom of the page with
 * what was measured, where they pushed it further down every time and were hard to find.
 */
@Composable
internal fun HealthSettingsScreen(onBack: () -> Unit, onOpen: (Route) -> Unit) {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    val watch by graph.watch.state.collectAsState()
    val shown by graph.prefs.healthShown.collectAsState(initial = false)
    val context = LocalContext.current

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val csv = graph.host.engine?.healthExportCsv(TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000).orEmpty()
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(csv.toByteArray()) }
                }.isSuccess
            }
            Toast.makeText(context, if (saved) R.string.health_export_done else R.string.health_export_failed, Toast.LENGTH_SHORT).show()
        }
    }

    var compared by remember { mutableStateOf<List<HealthConnectImport.SameNight>>(emptyList()) }
    val writing by graph.prefs.healthConnectWrite.collectAsState(initial = false)

    fun writeAll() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        scope.launch {
            val written = withContext(Dispatchers.IO) {
                runCatching { HealthConnectImport.write(context, graph.host.engine?.healthRecords().orEmpty()) }.isSuccess
            }
            if (!written) Toast.makeText(context, R.string.health_write_failed, Toast.LENGTH_SHORT).show()
        }
    }

    val writeAccess = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val allowed = granted.values.any { it }
        scope.launch { graph.prefs.setHealthConnectWrite(allowed) }
        if (allowed) writeAll() else Toast.makeText(context, R.string.health_import_denied, Toast.LENGTH_SHORT).show()
    }

    fun runImport() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        scope.launch {
            val count = withContext(Dispatchers.IO) {
                runCatching {
                    val found = HealthConnectImport.read(context, graph.host.engine?.healthRecords().orEmpty())
                    graph.host.engine?.addHealthRecords(found.fresh)
                    compared = found.compared
                    found.fresh.size
                }.getOrNull()
            }
            if (count == null) {
                Toast.makeText(context, R.string.health_import_failed, Toast.LENGTH_SHORT).show()
            } else {
                graph.host.refreshHealth()
                Toast.makeText(context, context.getString(R.string.health_import_done, count), Toast.LENGTH_SHORT).show()
            }
        }
    }

    val importAccess = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) runImport() else Toast.makeText(context, R.string.health_import_denied, Toast.LENGTH_SHORT).show()
    }

    SettingsPageFrame(stringResource(R.string.health_settings_title), onBack) {
        Spacer(Modifier.height(8.dp))
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
            HealthChoices()
            HealthSharing()
            SectionHeader(stringResource(R.string.health_your_data), top = 16.dp, bottom = 0.dp)
            SettingsGroup {
                val canImport = HealthConnectImport.available(context)
                ActionRow(
                    0, if (canImport) 3 else 1, TandemIcons.Download, stringResource(R.string.health_export), stringResource(R.string.health_export_sub),
                    onClick = { export.launch("tandem-health.csv") },
                )
                if (canImport) {
                    ActionRow(
                        1, 3, TandemIcons.Heart, stringResource(R.string.health_import), stringResource(R.string.health_import_sub),
                        onClick = { importAccess.launch(HealthConnectImport.PERMISSIONS) },
                    )
                    SwitchRow(
                        2, 3, TandemIcons.Heart, stringResource(R.string.health_write), stringResource(R.string.health_write_sub), writing,
                        { on -> if (on) writeAccess.launch(HealthConnectImport.WRITE_PERMISSIONS) else scope.launch { graph.prefs.setHealthConnectWrite(false) } },
                    )
                }
            }
            HealthCompared(compared)
        }
    }
}

/**
 * The same nights as another app measured them: the fairest test there is without a sleep lab. Not a verdict on who is right, because
 * neither is a lab. Only shown right after an import, and nothing of it is kept.
 */
@Composable
private fun HealthCompared(nights: List<HealthConnectImport.SameNight>) {
    if (nights.isEmpty()) return
    val shown = nights.sortedByDescending { it.mine.startMs }.take(14)
    val gap = shown.map { it.mine.sleepMinutes.toInt() - it.other.sleepMinutes.toInt() }
    SectionHeader(stringResource(R.string.health_compared, shown.first().app), top = 16.dp, bottom = 0.dp)
    SettingsGroup {
        ContentRow(0, 1, TandemIcons.Sleep, stringResource(R.string.health_compared_sum, shown.size, duration(gap.map { kotlin.math.abs(it) }.average().toInt()))) {
            shown.forEach { night ->
                Text(
                    stringResource(
                        R.string.health_compared_line,
                        DateUtils.formatDateTime(LocalContext.current, night.mine.endMs.toLong(), DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL or DateUtils.FORMAT_NO_YEAR),
                        duration(night.mine.sleepMinutes.toInt()),
                        duration(night.other.sleepMinutes.toInt()),
                    ),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
