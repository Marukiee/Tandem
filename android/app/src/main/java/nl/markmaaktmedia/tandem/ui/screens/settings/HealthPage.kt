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
                HealthDay(insights)
                HealthWeek(insights)
                EarlierNights()
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
                if (records.none { it.kind == TandemHealthKind.SLEEP || it.kind == TandemHealthKind.WORKOUT }) {
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
