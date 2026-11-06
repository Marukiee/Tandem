package nl.markmaaktmedia.tandem.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import android.text.format.DateUtils
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.components.ContentRow
import nl.markmaaktmedia.tandem.ui.components.SectionHeader
import nl.markmaaktmedia.tandem.ui.components.SettingsGroup
import nl.markmaaktmedia.tandem.ui.components.SwitchRow
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import uniffi.tandem_core.TandemDevice
import uniffi.tandem_core.TandemHealthKind
import uniffi.tandem_core.TandemHealthRecord
import uniffi.tandem_core.TandemSleepStage

private const val SHOWN = 30

/** The nights and workouts on this phone, the newest first. Nothing at all while there are none. */
@Composable
internal fun HealthHistory() {
    val records by LocalContext.current.graph.host.healthRecords.collectAsState()
    val shown = records.filter { it.kind != TandemHealthKind.UNKNOWN }.take(SHOWN)
    if (shown.isEmpty()) return
    SectionHeader(stringResource(R.string.health_share), top = 16.dp, bottom = 0.dp)
    SettingsGroup {
        shown.forEachIndexed { index, record ->
            ContentRow(
                index, shown.size, if (record.kind == TandemHealthKind.WORKOUT) TandemIcons.Workout else TandemIcons.Sleep,
                // A night that starts before midnight belongs to the morning it ends in.
                DateUtils.formatDateTime(
                    LocalContext.current, (if (record.kind == TandemHealthKind.WORKOUT) record.startMs else record.endMs).toLong(),
                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL or DateUtils.FORMAT_NO_YEAR,
                ),
            ) {
                if (record.kind == TandemHealthKind.WORKOUT) WorkoutLines(record) else SleepLines(record)
            }
        }
    }
    Text(
        stringResource(R.string.health_estimate),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun SleepLines(record: TandemHealthRecord) {
    Text(
        stringResource(R.string.health_sleep_line, duration(record.sleepMinutes.toInt()), record.efficiencyPercent.toInt()),
        style = MaterialTheme.typography.bodyMedium,
    )
    if (record.stages.isNotEmpty()) {
        Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
            record.stages.forEach { run ->
                Box(Modifier.weight(run.seconds.toFloat()).fillMaxHeight().background(stageColor(run.stage)))
            }
        }
    }
    val light = record.lightMinutes
    val deep = record.deepMinutes
    val rem = record.remMinutes
    if (light != null && deep != null && rem != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StageLegend(TandemSleepStage.LIGHT, stringResource(R.string.health_light, duration(light.toInt())))
            StageLegend(TandemSleepStage.DEEP, stringResource(R.string.health_deep, duration(deep.toInt())))
            StageLegend(TandemSleepStage.REM, stringResource(R.string.health_rem, duration(rem.toInt())))
        }
    }
    val mean = record.heartRateMean
    val low = record.heartRateLow
    if (mean != null && low != null) {
        Text(
            stringResource(R.string.health_heart_night, mean.toInt(), low.toInt()),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WorkoutLines(record: TandemHealthRecord) {
    val minutes = ((record.endMs.toLong() - record.startMs.toLong()) / 60_000).toInt()
    Text(duration(minutes), style = MaterialTheme.typography.bodyMedium)
    val mean = record.heartRateMean
    val high = record.heartRateHigh
    if (mean != null && high != null) {
        Text(
            stringResource(R.string.health_heart_workout, mean.toInt(), high.toInt()),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (record.steps > 0u) {
        Text(
            stringResource(R.string.health_steps, record.steps.toLong()),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StageLegend(stage: TandemSleepStage, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(stageColor(stage)))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun stageColor(stage: TandemSleepStage): Color = when (stage) {
    TandemSleepStage.DEEP -> MaterialTheme.colorScheme.primary
    TandemSleepStage.LIGHT -> MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
    TandemSleepStage.REM -> MaterialTheme.colorScheme.tertiary
    TandemSleepStage.WAKE, TandemSleepStage.UNKNOWN -> MaterialTheme.colorScheme.outlineVariant
}

@Composable
private fun duration(minutes: Int): String =
    if (minutes >= 60) stringResource(R.string.health_duration_hm, minutes / 60, minutes % 60)
    else stringResource(R.string.health_duration_m, minutes)

/**
 * Whether [device] gets the nights and workouts: off until the person says so, because how someone slept is not for every computer
 * in a circle. Only shown on a phone that has any to give.
 */
@Composable
internal fun HealthDeviceSection(device: TandemDevice) {
    val graph = LocalContext.current.graph
    val watch by graph.watch.state.collectAsState()
    val records by graph.host.healthRecords.collectAsState()
    if ("health" !in device.caps || !(watch.paired || records.isNotEmpty())) return
    val engine = graph.host.engine
    val scope = rememberCoroutineScope()
    var allowed by remember(device.id) { mutableStateOf(engine?.healthAllowed(device.id) ?: false) }
    SettingsGroup {
        SwitchRow(
            0, 1, TandemIcons.Sleep, stringResource(R.string.health_share), stringResource(R.string.health_share_sub), allowed,
            { on ->
                allowed = on
                scope.launch { runCatching { engine?.setHealthAllowed(device.id, on) } }
            },
        )
    }
}
