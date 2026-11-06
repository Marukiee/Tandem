package nl.markmaaktmedia.tandem.ui.screens.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.text.format.DateUtils
import java.text.NumberFormat
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.TimeZone
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.data.TandemPrefs
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.components.ContentRow
import nl.markmaaktmedia.tandem.ui.components.RollingNumber
import nl.markmaaktmedia.tandem.ui.components.SectionHeader
import nl.markmaaktmedia.tandem.ui.components.SettingsGroup
import nl.markmaaktmedia.tandem.ui.components.SwitchRow
import nl.markmaaktmedia.tandem.ui.theme.CardSquircle
import nl.markmaaktmedia.tandem.ui.theme.PillShape
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import nl.markmaaktmedia.tandem.ui.theme.TandemMotion
import uniffi.tandem_core.TandemDevice
import uniffi.tandem_core.TandemHealthInsights
import uniffi.tandem_core.TandemHealthKind
import uniffi.tandem_core.TandemHealthRecord
import uniffi.tandem_core.TandemNightInsight
import uniffi.tandem_core.TandemSleepStage
import uniffi.tandem_core.TandemWeekSummary

private const val SHOWN = 30

/** With a watch, or for someone who turned it on. For everyone else this would be one more page that stays empty. */
@Composable
internal fun rememberHealthAvailable(): Boolean {
    val graph = LocalContext.current.graph
    val watch by graph.watch.state.collectAsState()
    val shown by graph.prefs.healthShown.collectAsState(initial = false)
    return watch.paired || shown
}

internal fun List<TandemHealthRecord>.latestNight() = firstOrNull { it.kind == TandemHealthKind.SLEEP }

/** What the nights say together. Asked again when nights come in: the sums are the core's, so every device gets the same answer. */
@Composable
internal fun rememberHealthInsights(): TandemHealthInsights? {
    val host = LocalContext.current.graph.host
    val records by host.healthRecords.collectAsState()
    return remember(records) { host.engine?.healthInsights(TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000) }
}

/** Only when two nights in a row say so, and in words that do not pretend to know more than a wrist can. */
@Composable
internal fun HealthAlert(insights: TandemHealthInsights?) {
    val above = insights?.alertAboveUsual ?: return
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(CardSquircle).background(scheme.tertiaryContainer).padding(20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(TandemIcons.Heart, null, tint = scheme.onTertiaryContainer)
        Text(stringResource(R.string.health_alert, above.toInt()), style = MaterialTheme.typography.bodyMedium, color = scheme.onTertiaryContainer)
    }
}

/** Steps come in a few times an hour, and only a watch counts them. A computer sees whole days. */
@Composable
internal fun HealthToday() {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    val watch by graph.watch.state.collectAsState()
    val goal by graph.prefs.stepGoal.collectAsState(initial = TandemPrefs.DEFAULT_STEP_GOAL)
    if (!watch.paired) return
    val scheme = MaterialTheme.colorScheme
    SectionHeader(stringResource(R.string.health_today), top = 12.dp, bottom = 0.dp)
    Column(
        Modifier.fillMaxWidth().clip(CardSquircle).background(scheme.surfaceContainer).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ScoreRing((watch.stepsToday * 100 / goal.coerceAtLeast(1)).coerceAtMost(100).toInt(), 68.dp)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.health_steps_goal, number(watch.stepsToday), number(goal.toLong())),
                    style = MaterialTheme.typography.titleMedium, color = scheme.onSurface,
                )
                Text(
                    stringResource(R.string.health_active_today, watch.activeMinutesToday),
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                )
            }
        }
    }
    val smartWake by graph.prefs.smartWake.collectAsState(initial = false)
    SettingsGroup {
        SettingsTarget(FocusKeys.HealthSmartWake, 0, 2) {
            SwitchRow(
                0, 2, TandemIcons.Schedule, stringResource(R.string.health_smart_wake), stringResource(R.string.health_smart_wake_sub), smartWake,
                { on -> scope.launch { graph.prefs.setSmartWake(on) } },
            )
        }
        PadSlider(
            1, 2, stringResource(R.string.health_goal), stringResource(R.string.health_goal_sub), goal, 2000..30000, 500,
            { number(it.toLong()) }, TandemIcons.Workout,
        ) { value -> scope.launch { graph.prefs.setStepGoal(value) } }
    }
}

private fun number(value: Long): String = NumberFormat.getIntegerInstance().format(value)

/** A share of something as a ring, the same way a battery is one: it grows to its value when it comes on screen. */
@Composable
private fun ScoreRing(score: Int, size: Dp) {
    val fill = remember { Animatable(0f) }
    LaunchedEffect(score) { fill.animateTo(score / 100f, TandemMotion.spatial()) }
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val tint = MaterialTheme.colorScheme.primary
    val numberSize = with(LocalDensity.current) { (size * 0.32f).toSp() }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = size.toPx() * 0.11f
            val inset = stroke / 2
            val arc = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
            drawArc(tint, -90f, 360f * fill.value, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        RollingNumber(score, style = MaterialTheme.typography.titleMedium.copy(fontSize = numberSize), fontWeight = FontWeight.Bold)
    }
}

/** The newest night, large: this is what the page gets opened for in the morning. Nothing at all while there is none. */
@Composable
internal fun LatestNight(insights: TandemHealthInsights?) {
    val context = LocalContext.current
    val records by context.graph.host.healthRecords.collectAsState()
    val night = records.latestNight() ?: return
    val insight = insights?.latestNight?.takeIf { it.id == night.id }
    val date = DateUtils.formatDateTime(
        context, night.endMs.toLong(),
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL or DateUtils.FORMAT_NO_YEAR,
    )
    val from = DateUtils.formatDateTime(context, night.startMs.toLong(), DateUtils.FORMAT_SHOW_TIME)
    val until = DateUtils.formatDateTime(context, night.endMs.toLong(), DateUtils.FORMAT_SHOW_TIME)
    val scheme = MaterialTheme.colorScheme

    SectionHeader(stringResource(R.string.health_sleep_title), top = 12.dp, bottom = 0.dp)
    Column(
        Modifier.fillMaxWidth().clip(CardSquircle).background(scheme.surfaceContainer).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (insight != null) {
                ScoreRing(insight.score.toInt(), 68.dp)
            } else {
                Box(Modifier.size(68.dp).clip(CircleShape).background(scheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(TandemIcons.Sleep, null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(28.dp))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(duration(night.sleepMinutes.toInt()), style = MaterialTheme.typography.headlineMedium, color = scheme.onSurface)
                Text(
                    "$date · ${stringResource(R.string.health_night_span, from, until)}",
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                )
            }
        }
        insight?.let { ScoreParts(it) }
        StageBar(night, 14.dp)
        StageLegends(night)
        insight?.let { WakeMoments(it) }
        Text(
            stringResource(R.string.health_efficiency, night.efficiencyPercent.toInt()),
            style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface,
        )
        HeartRateLine(night)
    }
    Text(
        stringResource(R.string.health_estimate),
        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

/** Why the score is what it is, so it is not one number to believe or not. */
@Composable
private fun ScoreParts(insight: TandemNightInsight) {
    val stages = insight.stagesScore
    Text(
        if (stages != null) stringResource(R.string.health_score_parts, insight.durationScore.toInt(), insight.continuityScore.toInt(), stages.toInt())
        else stringResource(R.string.health_score_parts_plain, insight.durationScore.toInt(), insight.continuityScore.toInt()),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The moments in the bar that people ask about: was I really awake then? */
@Composable
private fun WakeMoments(insight: TandemNightInsight) {
    val context = LocalContext.current
    if (insight.wakeMoments.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        insight.wakeMoments.forEach { moment ->
            Text(
                stringResource(
                    R.string.health_wake_moment,
                    DateUtils.formatDateTime(context, moment.startMs.toLong(), DateUtils.FORMAT_SHOW_TIME),
                    duration(moment.minutes.toInt()),
                ),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun clock(minutes: UInt): String =
    LocalTime.of((minutes.toInt() / 60) % 24, minutes.toInt() % 60).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

/** What only shows over more nights. Each row says how many it still needs, because an empty page would look broken. */
@Composable
internal fun HealthTrends(insights: TandemHealthInsights?) {
    if (insights == null || insights.latestNight == null) return
    SectionHeader(stringResource(R.string.health_trends), top = 16.dp, bottom = 0.dp)
    SettingsGroup {
        ContentRow(0, 3, TandemIcons.Heart, stringResource(R.string.health_resting)) { RestingLines(insights) }
        ContentRow(1, 3, TandemIcons.Schedule, stringResource(R.string.health_regular)) { RegularLines(insights) }
        ContentRow(2, 3, TandemIcons.Calendar, stringResource(R.string.health_week)) { WeekLines(insights.thisWeek, insights.lastWeek) }
    }
}

@Composable
private fun TrendLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun WaitingLine(needed: Int) {
    Text(
        pluralStringResource(R.plurals.health_nights_more, needed, needed),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun RestingLines(insights: TandemHealthInsights) {
    val resting = insights.resting
    if (resting == null) {
        WaitingLine(1)
        return
    }
    val usual = resting.usual
    if (usual != null) {
        TrendLine(stringResource(R.string.health_resting_usual, resting.latest.toInt(), usual.toInt()))
    } else {
        TrendLine(stringResource(R.string.health_resting_line, resting.latest.toInt()))
        WaitingLine(resting.nightsNeeded.toInt())
    }
}

@Composable
private fun RegularLines(insights: TandemHealthInsights) {
    val regular = insights.regularity
    if (regular == null) {
        WaitingLine(insights.regularityNightsNeeded.toInt())
        return
    }
    TrendLine(stringResource(R.string.health_regular_bed, clock(regular.bedtimeMinutes), duration(regular.bedtimeSpreadMinutes.toInt())))
    TrendLine(stringResource(R.string.health_regular_wake, clock(regular.wakeMinutes), duration(regular.wakeSpreadMinutes.toInt())))
}

@Composable
private fun WeekLines(week: TandemWeekSummary, before: TandemWeekSummary) {
    val sleep = week.meanSleepMinutes
    val score = week.meanScore
    val resting = week.meanResting
    if (sleep != null) {
        val was = before.meanSleepMinutes
        TrendLine(
            if (was != null) stringResource(R.string.health_week_sleep_before, duration(sleep.toInt()), duration(was.toInt()))
            else stringResource(R.string.health_week_sleep, duration(sleep.toInt())),
        )
    }
    if (score != null) {
        val was = before.meanScore
        TrendLine(
            if (was != null) stringResource(R.string.health_week_score_before, score.toInt(), was.toInt())
            else stringResource(R.string.health_week_score, score.toInt()),
        )
    }
    if (resting != null) {
        val was = before.meanResting
        TrendLine(
            if (was != null) stringResource(R.string.health_week_resting_before, resting.toInt(), was.toInt())
            else stringResource(R.string.health_week_resting, resting.toInt()),
        )
    }
    week.meanSteps?.let { steps ->
        val was = before.meanSteps
        TrendLine(
            if (was != null) stringResource(R.string.health_week_steps_before, number(steps.toLong()), number(was.toLong()))
            else stringResource(R.string.health_week_steps, number(steps.toLong())),
        )
        TrendLine(stringResource(R.string.health_week_active, week.activeMinutes.toInt()))
    }
    if (week.workouts > 0u) {
        TrendLine(pluralStringResource(R.plurals.health_week_workouts, week.workouts.toInt(), week.workouts.toInt(), duration(week.workoutMinutes.toInt())))
    }
    if (sleep == null && week.workouts == 0u && week.meanSteps == null) {
        Text(stringResource(R.string.health_week_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Everything else on this phone, the newest first. The list that grows, so it belongs at the end of a page. */
@Composable
internal fun EarlierNights() {
    val records by LocalContext.current.graph.host.healthRecords.collectAsState()
    val latest = records.latestNight()
    val shown = records.filter { (it.kind == TandemHealthKind.SLEEP || it.kind == TandemHealthKind.WORKOUT) && it !== latest }.take(SHOWN)
    if (shown.isEmpty()) return
    SectionHeader(stringResource(R.string.health_earlier), top = 16.dp, bottom = 0.dp)
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
}

@Composable
private fun SleepLines(record: TandemHealthRecord) {
    Text(
        stringResource(R.string.health_sleep_line, duration(record.sleepMinutes.toInt()), record.efficiencyPercent.toInt()),
        style = MaterialTheme.typography.bodyMedium,
    )
    StageBar(record, 10.dp)
    StageLegends(record)
    HeartRateLine(record)
}

@Composable
private fun StageBar(record: TandemHealthRecord, height: Dp) {
    if (record.stages.isEmpty()) return
    Row(Modifier.fillMaxWidth().height(height).clip(PillShape)) {
        record.stages.forEach { run ->
            Box(Modifier.weight(run.seconds.toFloat()).fillMaxHeight().background(stageColor(run.stage)))
        }
    }
}

@Composable
private fun StageLegends(record: TandemHealthRecord) {
    val light = record.lightMinutes
    val deep = record.deepMinutes
    val rem = record.remMinutes
    if (light == null || deep == null || rem == null) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        StageLegend(TandemSleepStage.LIGHT, stringResource(R.string.health_light, duration(light.toInt())))
        StageLegend(TandemSleepStage.DEEP, stringResource(R.string.health_deep, duration(deep.toInt())))
        StageLegend(TandemSleepStage.REM, stringResource(R.string.health_rem, duration(rem.toInt())))
        if (record.awakeMinutes > 0u) StageLegend(TandemSleepStage.WAKE, stringResource(R.string.health_awake, duration(record.awakeMinutes.toInt())))
    }
}

@Composable
private fun HeartRateLine(record: TandemHealthRecord) {
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
    // The moments people look for, so they stand out. Not a warning colour: being awake for a bit is nothing to worry about.
    TandemSleepStage.WAKE -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
    TandemSleepStage.UNKNOWN -> MaterialTheme.colorScheme.outlineVariant
}

@Composable
internal fun duration(minutes: Int): String =
    if (minutes >= 60) stringResource(R.string.health_duration_hm, minutes / 60, minutes % 60)
    else stringResource(R.string.health_duration_m, minutes)

/**
 * Whether [device] gets the nights and workouts: off until the person says so, because how someone slept is not for every computer
 * in a circle. Only shown on a phone that has any to give.
 */
@Composable
internal fun HealthDeviceSection(device: TandemDevice) {
    val graph = LocalContext.current.graph
    if ("health" !in device.caps || !rememberHealthAvailable()) return
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
