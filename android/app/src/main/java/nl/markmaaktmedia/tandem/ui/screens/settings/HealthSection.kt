package nl.markmaaktmedia.tandem.ui.screens.settings

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.TimeZone
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.data.TandemPrefs
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.components.GroupedRow
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
import uniffi.tandem_core.TandemWeekDay

private const val SHOWN = 30
private const val USUAL_NIGHTS = 7
private const val REGULAR_NIGHTS = 5
private val ChartWidth = 164.dp

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

private fun number(value: Long): String = NumberFormat.getIntegerInstance().format(value)

/** The digits large and the units small, so the number is what the eye finds first. */
@Composable
private fun BigNumber(text: String, modifier: Modifier = Modifier) {
    val type = MaterialTheme.typography
    val big = type.headlineLarge.toSpanStyle()
    val small = SpanStyle(fontSize = type.titleSmall.fontSize, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(
        buildAnnotatedString {
            text.forEach { char ->
                withStyle(if (char.isDigit() || char in ".,:") big else small) { append(char) }
            }
        },
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        modifier = modifier,
    )
}

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

/** A title that says it can open, and the words behind it. Only for what a person could really wonder about. */
@Composable
private fun TitleWithMore(title: String, open: Boolean, hasMore: Boolean, modifier: Modifier = Modifier) {
    val turned by animateFloatAsState(if (open) 180f else 0f, TandemMotion.spatial(), label = "healthMore")
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        if (hasMore) {
            Icon(TandemIcons.ChevronDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp).rotate(turned))
        }
    }
}

@Composable
private fun MoreText(open: Boolean, text: String?) {
    AnimatedVisibility(
        open && text != null,
        enter = expandVertically(TandemMotion.sizeSpring()) + fadeIn(TandemMotion.fadeSpec()),
        exit = shrinkVertically(TandemMotion.sizeSpring()) + fadeOut(TandemMotion.fadeSpec()),
    ) {
        Text(
            text.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/** A card of the page, whose whole surface opens the explanation when it has one. */
@Composable
private fun HealthCard(index: Int, total: Int, more: String? = null, content: @Composable ColumnScope.(open: Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    GroupedRow(index, total, onClick = more?.let { { open = !open } }) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            content(open)
            MoreText(open, more)
        }
    }
}

/**
 * The shape of most of this page: what it is, the number, and the week beside it. Tapping a day shows that day in the same place, so no
 * number needs a label of its own on the chart.
 */
@Composable
private fun WeekCard(
    index: Int,
    total: Int,
    title: String,
    number: String,
    caption: String,
    week: List<TandemWeekDay>,
    dayNumbers: List<String?>,
    more: String? = null,
    chart: @Composable (selected: Int?, select: (Int?) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf<Int?>(null) }
    val pick = selected
    val shownNumber = if (pick != null) dayNumbers.getOrNull(pick) ?: "-" else number
    val shownCaption = if (pick != null) dayName(context, week[pick].startMs.toLong()) else caption
    HealthCard(index, total, more) { open ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TitleWithMore(title, open, more != null)
                Crossfade(shownNumber, animationSpec = TandemMotion.fadeSpec(), label = "healthNumber") { BigNumber(it) }
                Crossfade(shownCaption, animationSpec = TandemMotion.fadeSpec(), label = "healthCaption") {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Box(Modifier.width(ChartWidth)) { chart(selected) { selected = it } }
        }
    }
}

private fun dayName(context: android.content.Context, ms: Long): String = DateUtils.formatDateTime(
    context, ms + DateUtils.DAY_IN_MILLIS / 2,
    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL or DateUtils.FORMAT_NO_YEAR,
)

/** The newest night, large: this is what the page gets opened for in the morning. Nothing at all while there is none. */
@Composable
internal fun LatestNight(insights: TandemHealthInsights?) {
    val context = LocalContext.current
    val records by context.graph.host.healthRecords.collectAsState()
    val night = records.latestNight() ?: return
    val insight = insights?.latestNight?.takeIf { it.id == night.id }
    val scheme = MaterialTheme.colorScheme
    val date = DateUtils.formatDateTime(
        context, night.endMs.toLong(),
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL or DateUtils.FORMAT_NO_YEAR,
    )
    val hasStages = night.stages.isNotEmpty()
    val parts = 1 + (if (hasStages) 1 else 0) + (if (insight != null) 1 else 0)

    SectionHeader(stringResource(R.string.health_sleep_title), top = 12.dp, bottom = 0.dp)
    SettingsGroup {
        HealthCard(0, parts) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BigNumber(duration(night.sleepMinutes.toInt()))
                    Text(date, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
                if (insight != null) {
                    ScoreRing(insight.score.toInt(), 72.dp)
                } else {
                    Box(Modifier.size(72.dp).clip(CircleShape).background(scheme.primaryContainer), contentAlignment = Alignment.Center) {
                        Icon(TandemIcons.Sleep, null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(28.dp))
                    }
                }
            }
            NightFacts(night, Modifier.padding(top = 16.dp))
        }
        if (hasStages) {
            HealthCard(1, parts, more = stringResource(R.string.health_more_stages)) { open ->
                TitleWithMore(stringResource(R.string.health_timeline), open, hasMore = true, Modifier.padding(bottom = 8.dp))
                SleepTimeline(night)
                StageTiles(night, Modifier.padding(top = 14.dp))
                insight?.let { WakeMoments(it, Modifier.padding(top = 10.dp)) }
            }
        }
        if (insight != null) {
            HealthCard(parts - 1, parts, more = stringResource(R.string.health_more_score)) { open ->
                TitleWithMore(stringResource(R.string.health_score_title), open, hasMore = true, Modifier.padding(bottom = 12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ScorePart(stringResource(R.string.health_part_length), insight.durationScore.toInt())
                    ScorePart(stringResource(R.string.health_part_calm), insight.continuityScore.toInt())
                    insight.stagesScore?.let { ScorePart(stringResource(R.string.health_part_stages), it.toInt()) }
                }
            }
        }
    }
}

/** The few numbers of a night that need no chart. */
@Composable
private fun NightFacts(night: TandemHealthRecord, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth()) {
        Fact(stringResource(R.string.health_stat_asleep), "${night.efficiencyPercent}%", Modifier.weight(1f))
        Fact(stringResource(R.string.health_stage_awake), duration(night.awakeMinutes.toInt()), Modifier.weight(1f))
        night.heartRateMean?.let { Fact(stringResource(R.string.health_stat_heart), "$it", Modifier.weight(1f)) }
        night.heartRateLow?.let { Fact(stringResource(R.string.health_stat_lowest), "$it", Modifier.weight(1f)) }
    }
}

@Composable
private fun Fact(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(value, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}

/** How long each stage lasted, as the colour key of the timeline above it. */
@Composable
private fun StageTiles(record: TandemHealthRecord, modifier: Modifier = Modifier) {
    val light = record.lightMinutes ?: return
    val deep = record.deepMinutes ?: return
    val rem = record.remMinutes ?: return
    Row(modifier.fillMaxWidth()) {
        StageTile(TandemSleepStage.LIGHT, stringResource(R.string.health_stage_light), duration(light.toInt()), Modifier.weight(1f))
        StageTile(TandemSleepStage.DEEP, stringResource(R.string.health_stage_deep), duration(deep.toInt()), Modifier.weight(1f))
        StageTile(TandemSleepStage.REM, stringResource(R.string.health_stage_rem), duration(rem.toInt()), Modifier.weight(1f))
        StageTile(TandemSleepStage.WAKE, stringResource(R.string.health_stage_awake), duration(record.awakeMinutes.toInt()), Modifier.weight(1f))
    }
}

@Composable
private fun StageTile(stage: TandemSleepStage, name: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(stageColor(stage)))
            Text(name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Text(value, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}

/** The moments in the chart that people ask about: was I really awake then? */
@Composable
private fun WakeMoments(insight: TandemNightInsight, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    if (insight.wakeMoments.isEmpty()) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
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

/**
 * Today, as far as a watch can say. Steps come in a few times an hour and only a watch counts them, so today's bar comes from the watch
 * and the days before it from the records.
 */
@Composable
internal fun HealthDay(insights: TandemHealthInsights?) {
    val graph = LocalContext.current.graph
    val watch by graph.watch.state.collectAsState()
    val goal by graph.prefs.stepGoal.collectAsState(initial = TandemPrefs.DEFAULT_STEP_GOAL)
    val week = insights?.week.orEmpty()
    if (week.isEmpty()) return
    val days = week.map { it.startMs.toLong() }
    val steps = week.mapIndexed { index, day ->
        if (index == week.lastIndex && watch.paired) watch.stepsToday else day.steps?.toLong()
    }
    val active = week.mapIndexed { index, day ->
        if (index == week.lastIndex && watch.paired) watch.activeMinutesToday else day.activeMinutes?.toInt()
    }
    val hasSteps = watch.paired || steps.any { it != null }
    val todayHeart = watch.heartToday.takeIf { curve -> curve.any { it > 0 } }
    val lastDay = insights?.latestDayHeart
    val naps = insights?.naps.orEmpty()
    val cards = (if (hasSteps) 2 else 0) + (if (todayHeart != null || lastDay != null) 1 else 0) + (if (naps.isNotEmpty()) 1 else 0)
    if (cards == 0) return

    SectionHeader(stringResource(R.string.health_today), top = 16.dp, bottom = 0.dp)
    SettingsGroup {
        var at = 0
        if (hasSteps) {
            val today = steps.last() ?: 0L
            val left = goal - today
            WeekCard(
                at++, cards, stringResource(R.string.health_steps_title), number(today),
                if (left > 0) stringResource(R.string.health_steps_left, number(left)) else stringResource(R.string.health_steps_done),
                week, steps.map { step -> step?.let(::number) },
            ) { selected, select ->
                WeekBars(
                    steps.map { it?.toFloat() }, days, selected, select,
                    stringResource(R.string.health_steps_title), goal = goal.toFloat(),
                )
            }
            val sum = active.sumOf { it ?: 0 }
            WeekCard(
                at++, cards, stringResource(R.string.health_active_title), stringResource(R.string.health_minutes, active.last() ?: 0),
                stringResource(R.string.health_active_week, sum),
                week, active.map { minutes -> minutes?.let { stringResource(R.string.health_minutes, it) } },
                more = stringResource(R.string.health_more_active),
            ) { selected, select ->
                WeekBars(active.map { it?.toFloat() }, days, selected, select, stringResource(R.string.health_active_title))
            }
        }
        if (todayHeart != null) {
            HeartCard(at++, cards, stringResource(R.string.health_heart_today), todayHeart)
        } else if (lastDay != null) {
            HeartCard(
                at++, cards,
                stringResource(R.string.health_heart_on_day, dayName(LocalContext.current, lastDay.startMs.toLong())),
                lastDay.curve.map { it.toInt() },
            )
        }
        if (naps.isNotEmpty()) NapsCard(at, cards, naps)
    }
}

@Composable
private fun HeartCard(index: Int, total: Int, title: String, curve: List<Int>) {
    val measured = curve.filter { it > 0 }
    HealthCard(index, total, more = stringResource(R.string.health_more_heart)) { open ->
        TitleWithMore(title, open, hasMore = true, Modifier.padding(bottom = 6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigNumber(stringResource(R.string.health_resting_line, measured.last()))
            Text(
                stringResource(R.string.health_heart_range, measured.min(), measured.max()),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        DayHeartLine(curve, title, Modifier.fillMaxWidth().padding(top = 12.dp))
    }
}

@Composable
private fun NapsCard(index: Int, total: Int, naps: List<uniffi.tandem_core.TandemNap>) {
    val context = LocalContext.current
    HealthCard(index, total, more = stringResource(R.string.health_more_naps)) { open ->
        TitleWithMore(stringResource(R.string.health_naps), open, hasMore = true, Modifier.padding(bottom = 6.dp))
        BigNumber(duration(naps.sumOf { it.sleepMinutes.toInt() }))
        Text(
            pluralStringResource(R.plurals.health_naps_week, naps.size, naps.size),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        naps.forEach { nap ->
            Text(
                stringResource(
                    R.string.health_nap_line,
                    DateUtils.formatDateTime(context, nap.startMs.toLong(), DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL),
                    duration(nap.sleepMinutes.toInt()),
                ),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private fun clock(minutes: UInt): String =
    LocalTime.of((minutes.toInt() / 60) % 24, minutes.toInt() % 60).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

/**
 * What only shows over more nights. Each card says how far it has come, because an empty page would look broken. The nights this week
 * are next to the week before, because a number alone does not say whether it is going well.
 */
@Composable
internal fun HealthWeek(insights: TandemHealthInsights?) {
    if (insights == null || insights.latestNight == null) return
    val week = insights.week
    if (week.isEmpty()) return
    val days = week.map { it.startMs.toLong() }
    val thisWeek = insights.thisWeek
    val before = insights.lastWeek
    SectionHeader(stringResource(R.string.health_week), top = 16.dp, bottom = 0.dp)
    val sleep = thisWeek.meanSleepMinutes
    val score = thisWeek.meanScore
    val workouts = thisWeek.workouts.toInt()
    val total = listOf(sleep != null, score != null, insights.resting != null, true, workouts > 0).count { it }
    SettingsGroup {
        var at = 0
        if (sleep != null) {
            val was = before.meanSleepMinutes
            WeekCard(
                at++, total, stringResource(R.string.health_sleep_title), duration(sleep.toInt()),
                if (was != null) stringResource(R.string.health_mean_night_before, duration(was.toInt())) else stringResource(R.string.health_mean_night),
                week, week.map { day -> day.sleepMinutes?.let { duration(it.toInt()) } },
            ) { selected, select ->
                WeekBars(week.map { it.sleepMinutes?.toFloat() }, days, selected, select, stringResource(R.string.health_sleep_title))
            }
        }
        if (score != null) {
            val was = before.meanScore
            WeekCard(
                at++, total, stringResource(R.string.health_score_title), "$score",
                if (was != null) stringResource(R.string.health_mean_score_before, was.toInt()) else stringResource(R.string.health_mean_score),
                week, week.map { day -> day.score?.toString() },
            ) { selected, select ->
                WeekBars(week.map { it.score?.toFloat() }, days, selected, select, stringResource(R.string.health_score_title))
            }
        }
        if (insights.resting != null) RestingCard(at++, total, insights, days)
        RegularityCard(at++, total, insights, days)
        if (workouts > 0) {
            HealthCard(at, total) {
                Text(stringResource(R.string.health_workouts), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                BigNumber(duration(thisWeek.workoutMinutes.toInt()), Modifier.padding(top = 6.dp))
                Text(
                    pluralStringResource(R.plurals.health_workouts_week, workouts, workouts),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun RestingCard(index: Int, total: Int, insights: TandemHealthInsights, days: List<Long>) {
    val resting = insights.resting ?: return
    val week = insights.week
    val usual = resting.usual
    WeekCard(
        index, total, stringResource(R.string.health_resting), stringResource(R.string.health_resting_line, resting.latest.toInt()),
        if (usual != null) stringResource(R.string.health_usually, usual.toInt())
        else pluralStringResource(R.plurals.health_nights_more, resting.nightsNeeded.toInt(), resting.nightsNeeded.toInt()),
        week, week.map { day -> day.resting?.let { stringResource(R.string.health_resting_line, it.toInt()) } },
        more = stringResource(R.string.health_more_resting),
    ) { selected, select ->
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            WeekLine(week.map { it.resting?.toFloat() }, days, selected, select, stringResource(R.string.health_resting), usual = usual?.toFloat())
            if (usual == null) NightsToGo(resting.nightsNeeded.toInt(), USUAL_NIGHTS)
        }
    }
}

@Composable
private fun RegularityCard(index: Int, total: Int, insights: TandemHealthInsights, days: List<Long>) {
    val regular = insights.regularity
    val week = insights.week
    HealthCard(index, total, more = stringResource(R.string.health_more_regular)) { open ->
        TitleWithMore(stringResource(R.string.health_regular), open, hasMore = true, Modifier.padding(bottom = 6.dp))
        if (regular != null) {
            Text(
                stringResource(R.string.health_regular_bed, clock(regular.bedtimeMinutes), duration(regular.bedtimeSpreadMinutes.toInt())),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                stringResource(R.string.health_regular_wake, clock(regular.wakeMinutes), duration(regular.wakeSpreadMinutes.toInt())),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
            )
        } else {
            val needed = insights.regularityNightsNeeded.toInt()
            Text(
                pluralStringResource(R.plurals.health_nights_more, needed, needed),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            NightsToGo(needed, REGULAR_NIGHTS)
        }
        SleepWindows(week.map { it.bedMinutes }, week.map { it.wakeMinutes }, days, stringResource(R.string.health_regular), Modifier.padding(top = 14.dp))
    }
}

/** Everything else on this phone, the newest first. The list that grows, so it belongs at the end of a page. A night opens where it is. */
@Composable
internal fun EarlierNights() {
    val records by LocalContext.current.graph.host.healthRecords.collectAsState()
    val latest = records.latestNight()
    val shown = records.filter { (it.kind == TandemHealthKind.SLEEP || it.kind == TandemHealthKind.WORKOUT) && it !== latest }.take(SHOWN)
    if (shown.isEmpty()) return
    SectionHeader(stringResource(R.string.health_earlier), top = 16.dp, bottom = 0.dp)
    SettingsGroup {
        shown.forEachIndexed { index, record ->
            if (record.kind == TandemHealthKind.WORKOUT) WorkoutRow(index, shown.size, record) else NightRow(index, shown.size, record)
        }
    }
}

@Composable
private fun NightRow(index: Int, total: Int, record: TandemHealthRecord) {
    val context = LocalContext.current
    val canOpen = record.stages.isNotEmpty()
    var open by remember { mutableStateOf(false) }
    GroupedRow(index, total, onClick = if (canOpen) ({ open = !open }) else null) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                // A night that starts before midnight belongs to the morning it ends in.
                TitleWithMore(dayName(context, record.endMs.toLong() - DateUtils.DAY_IN_MILLIS / 2), open, canOpen)
                Text(
                    stringResource(R.string.health_sleep_row, duration(record.sleepMinutes.toInt()), record.efficiencyPercent.toInt()),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!open) StageBar(record, 8.dp)
            AnimatedVisibility(
                open,
                enter = expandVertically(TandemMotion.sizeSpring()) + fadeIn(TandemMotion.fadeSpec()),
                exit = shrinkVertically(TandemMotion.sizeSpring()) + fadeOut(TandemMotion.fadeSpec()),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SleepTimeline(record)
                    StageTiles(record)
                    NightFacts(record)
                }
            }
        }
    }
}

@Composable
private fun WorkoutRow(index: Int, total: Int, record: TandemHealthRecord) {
    val context = LocalContext.current
    GroupedRow(index, total) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(TandemIcons.Workout, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Text(dayName(context, record.startMs.toLong() - DateUtils.DAY_IN_MILLIS / 2), style = MaterialTheme.typography.titleSmall)
            }
            Row(Modifier.fillMaxWidth()) {
                val minutes = ((record.endMs.toLong() - record.startMs.toLong()) / 60_000).toInt()
                Fact(stringResource(R.string.health_workout_length), duration(minutes), Modifier.weight(1f))
                record.heartRateMean?.let { Fact(stringResource(R.string.health_stat_heart), "$it", Modifier.weight(1f)) }
                record.heartRateHigh?.let { Fact(stringResource(R.string.health_stat_highest), "$it", Modifier.weight(1f)) }
                if (record.steps > 0u) Fact(stringResource(R.string.health_steps_title), number(record.steps.toLong()), Modifier.weight(1f))
            }
        }
    }
}

/** What a person chooses here, below what is measured: it is set once and then looked past. */
@Composable
internal fun HealthChoices() {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    val watch by graph.watch.state.collectAsState()
    if (!watch.paired) return
    val goal by graph.prefs.stepGoal.collectAsState(initial = TandemPrefs.DEFAULT_STEP_GOAL)
    val smartWake by graph.prefs.smartWake.collectAsState(initial = false)
    val heartAllDay by graph.prefs.heartAllDay.collectAsState(initial = true)
    SectionHeader(stringResource(R.string.section_preferences), top = 16.dp, bottom = 0.dp)
    SettingsGroup {
        SettingsTarget(FocusKeys.HealthSmartWake, 0, 3) {
            SwitchRow(
                0, 3, TandemIcons.Schedule, stringResource(R.string.health_smart_wake), stringResource(R.string.health_smart_wake_sub), smartWake,
                { on -> scope.launch { graph.prefs.setSmartWake(on) } },
            )
        }
        SettingsTarget(FocusKeys.HealthHeartAllDay, 1, 3) {
            SwitchRow(
                1, 3, TandemIcons.Heart, stringResource(R.string.health_heart_all_day), stringResource(R.string.health_heart_all_day_sub), heartAllDay,
                { on -> scope.launch { graph.prefs.setHeartAllDay(on) } },
            )
        }
        PadSlider(
            2, 3, stringResource(R.string.health_goal), stringResource(R.string.health_goal_sub), goal, 2000..30000, 500,
            { number(it.toLong()) }, TandemIcons.Workout,
        ) { value -> scope.launch { graph.prefs.setStepGoal(value) } }
    }
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
internal fun stageColor(stage: TandemSleepStage): Color = when (stage) {
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
