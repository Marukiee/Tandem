package nl.markmaaktmedia.tandem.ui.screens.settings

import kotlin.math.roundToInt
import nl.markmaaktmedia.tandem.watch.protocol.Message
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
import androidx.compose.foundation.clickable
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.text.style.TextOverflow
import java.text.NumberFormat
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.TimeZone
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.R
import uniffi.tandem_core.TandemQualityMeasure
import uniffi.tandem_core.TandemQualityKind
import nl.markmaaktmedia.tandem.ui.theme.LocalTandemExtraColors
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.runtime.mutableIntStateOf
import nl.markmaaktmedia.tandem.ui.components.ActionRow
import nl.markmaaktmedia.tandem.ui.components.PrimaryPillButton
import nl.markmaaktmedia.tandem.ui.components.SecondaryPillButton
import nl.markmaaktmedia.tandem.ui.components.TandemDialog
import nl.markmaaktmedia.tandem.ui.components.TandemIconButton
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
import uniffi.tandem_core.TandemPlatform
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

/** A device a watch can be with: it gets the nights and workouts of this phone without a switch, as the core decides. */
private fun TandemDevice.isPhone() = platform == TandemPlatform.ANDROID || platform == TandemPlatform.IOS

/**
 * A night is sleep of at least three hours, as the core counts it; shorter sleep is a nap. The newest sleep of any length made a watch
 * that lay still on a table for half an hour show as last night.
 */
internal fun TandemHealthRecord.isNight() = kind == TandemHealthKind.SLEEP && sleepMinutes >= NIGHT_MINUTES

private const val NIGHT_MINUTES = 180u

internal fun List<TandemHealthRecord>.latestNight() = firstOrNull { it.isNight() }

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

internal fun number(value: Long): String = NumberFormat.getIntegerInstance().format(value)

/** The digits large and the units small, so the number is what the eye finds first. */
@Composable
internal fun BigNumber(text: String, modifier: Modifier = Modifier) {
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

/**
 * A share of something as a ring that grows to its value when it comes on screen. The ends stay apart, with the track after a gap, as
 * Material 3 draws progress: at 97 a ring that almost closed looked like a mistake.
 */
@Composable
internal fun ScoreRing(score: Int, size: Dp, tint: Color = MaterialTheme.colorScheme.primary, track: Color = MaterialTheme.colorScheme.surfaceContainerHighest) {
    val fill = remember { Animatable(0f) }
    LaunchedEffect(score) { fill.animateTo(score / 100f, TandemMotion.spatial()) }
    val numberSize = with(LocalDensity.current) { (size * 0.32f).toSp() }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = size.toPx() * 0.11f
            val inset = stroke / 2
            val arc = Size(this.size.width - stroke, this.size.height - stroke)
            // The round caps reach past the ends of an arc: the gap counts them, and leaves a sliver on top of that. More than a
            // sliver made 99 look far from full.
            val radius = arc.width / 2
            val cap = Math.toDegrees((stroke / 2 / radius).toDouble()).toFloat()
            // A full score is a closed ring: the sliver says how much is missing, and at 100 nothing is.
            val gap = if (score >= 100) 0f else cap * 2 + 4f
            val sweep = (360f - gap) * fill.value
            if (sweep > 0.5f) drawArc(tint, -90f, sweep, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
            val rest = 360f - sweep - gap * (if (sweep > 0.5f) 2 else 1)
            if (rest > 0.5f) {
                drawArc(track, -90f + sweep + (if (sweep > 0.5f) gap else gap / 2), rest, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        RollingNumber(score, style = MaterialTheme.typography.titleMedium.copy(fontSize = numberSize), fontWeight = FontWeight.Bold)
    }
}

/** A title that says it can open, and the words behind it: only for a night in the list, which opens where it is. */
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

/** A title, and for what a person could wonder about a question mark that opens the words behind it. */
@Composable
internal fun CardTitle(title: String, info: String?, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, modifier = Modifier.weight(1f))
        if (info != null) InfoButton(title, info)
    }
}

/**
 * A quiet info sign in the corner of a card that opens what is behind a number in a window. The words unfolded under the card before,
 * behind an arrow that looked like it would show more of the chart; a filled round beside the title drew the eye away from the numbers.
 * The touch target is larger than the sign, and its edge lines the sign up with the edge of the card's content.
 */
@Composable
internal fun InfoButton(title: String, info: String) {
    var open by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.offset(x = 8.dp).size(36.dp).clip(CircleShape).clickable(onClickLabel = stringResource(R.string.health_info)) { open = true },
        contentAlignment = Alignment.Center,
    ) {
        Icon(TandemIcons.Info, stringResource(R.string.health_info), tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
    if (open) {
        TandemDialog(
            title = title,
            onDismiss = { open = false },
            icon = TandemIcons.Info,
            body = info,
            actions = { PrimaryPillButton(stringResource(R.string.generic_ok), { open = false }) },
        )
    }
}

/** A card of a health page. */
@Composable
internal fun HealthCard(index: Int, total: Int, content: @Composable ColumnScope.() -> Unit) {
    GroupedRow(index, total) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) { content() }
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
    HealthCard(index, total) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CardTitle(title, more)
                Crossfade(shownNumber, animationSpec = TandemMotion.fadeSpec(), label = "healthNumber") { BigNumber(it) }
                Crossfade(shownCaption, animationSpec = TandemMotion.fadeSpec(), label = "healthCaption") {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Box(Modifier.width(ChartWidth)) { chart(selected) { selected = it } }
        }
    }
}

internal fun dayName(context: android.content.Context, ms: Long): String = DateUtils.formatDateTime(
    context, ms + DateUtils.DAY_IN_MILLIS / 2,
    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL or DateUtils.FORMAT_NO_YEAR,
)

/**
 * The newest night in full: how long and how well, its stages, its parts the way Google's Sleep Score shows them, and its score.
 * Nothing at all while there is none.
 */
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
    val quality = insight?.quality.orEmpty()
    val parts = 1 + (if (hasStages) 1 else 0) + (if (quality.isNotEmpty()) 1 else 0) + (if (insight != null) 1 else 0)

    SectionHeader(stringResource(R.string.watch_glance_night), top = 12.dp, bottom = 0.dp)
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
            HealthCard(1, parts) {
                CardTitle(stringResource(R.string.health_timeline), stringResource(R.string.health_more_stages), Modifier.padding(bottom = 8.dp))
                SleepTimeline(night)
                StageTiles(night, Modifier.padding(top = 14.dp))
                insight?.let { WakeMoments(it, Modifier.padding(top = 10.dp)) }
            }
        }
        if (quality.isNotEmpty()) {
            HealthCard(if (hasStages) 2 else 1, parts) {
                CardTitle(stringResource(R.string.health_quality_title), stringResource(R.string.health_quality_more), Modifier.padding(bottom = 12.dp))
                SleepQuality(quality)
            }
        }
        if (insight != null) {
            HealthCard(parts - 1, parts) {
                CardTitle(stringResource(R.string.health_score_title), stringResource(R.string.health_more_score), Modifier.padding(bottom = 12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ScorePart(stringResource(R.string.health_part_length), insight.durationScore.toInt())
                    ScorePart(stringResource(R.string.health_part_calm), insight.continuityScore.toInt())
                    insight.stagesScore?.let { ScorePart(stringResource(R.string.health_part_stages), it.toInt()) }
                }
            }
        }
    }
}

/**
 * The parts of the quality of a night as Google's Sleep Score shows them: each a bar with the range most adults fall in as a dashed box,
 * the night as a dot, and a word for whether it falls in that range. Tells at a glance what went well and what did not.
 */
@Composable
private fun SleepQuality(quality: List<TandemQualityMeasure>) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        quality.forEach { QualityRow(it) }
        Row(
            Modifier.padding(top = 4.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            Canvas(Modifier.size(width = 18.dp, height = 10.dp)) {
                drawRoundRect(
                    scheme.onSurfaceVariant, cornerRadius = CornerRadius(size.height / 2),
                    style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))),
                )
            }
            Text(stringResource(R.string.health_q_typical), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
    }
}

/** Amber for a part outside its range: a sign to look at, not an alarm, so not the colour of an error. */
private val QualityAmber = Color(0xFFE3A008)

@Composable
private fun QualityRow(measure: TandemQualityMeasure) {
    val scheme = MaterialTheme.colorScheme
    val low = measure.typicalLow.toInt()
    val high = measure.typicalHigh.toInt()
    val value = measure.value.toInt()
    val inRange = value in low..high
    val colour = if (inRange) LocalTandemExtraColors.current.charging else QualityAmber
    val name = stringResource(
        when (measure.kind) {
            TandemQualityKind.TO_SOUND_SLEEP -> R.string.health_q_to_sound
            TandemQualityKind.SOUND_SLEEP -> R.string.health_q_sound
            TandemQualityKind.RESTLESSNESS -> R.string.health_q_restless
            TandemQualityKind.INTERRUPTIONS -> R.string.health_q_interruptions
            TandemQualityKind.FULL_AWAKENINGS -> R.string.health_q_awakenings
        },
    )
    val shown = if (measure.kind == TandemQualityKind.FULL_AWAKENINGS) pluralStringResource(R.plurals.health_q_moments, value, value) else duration(value)
    val badge = stringResource(
        when {
            value > high -> R.string.health_q_above
            value < low -> R.string.health_q_below
            else -> R.string.health_q_in
        },
    )
    val track = scheme.surfaceContainerHighest
    val ink = scheme.onSurface
    val ring = scheme.surfaceContainerHigh
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(scheme.surfaceContainerHigh).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                buildAnnotatedString {
                    append(name)
                    withStyle(SpanStyle(color = scheme.onSurfaceVariant)) { append(" \u00B7 $shown") }
                },
                style = MaterialTheme.typography.bodyMedium, color = ink, modifier = Modifier.weight(1f),
            )
            Text(
                badge, style = MaterialTheme.typography.labelMedium, color = ink, maxLines = 1,
                modifier = Modifier.clip(PillShape).background(colour.copy(alpha = 0.25f)).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        Canvas(Modifier.fillMaxWidth().height(24.dp)) {
            val bar = 14.dp.toPx()
            val top = (size.height - bar) / 2
            val max = measure.scaleMax.toFloat().coerceAtLeast(1f)
            fun x(at: Int) = (at / max).coerceIn(0f, 1f) * size.width
            drawRoundRect(track, Offset(0f, top), Size(size.width, bar), CornerRadius(bar / 2))
            val end = x(value).coerceIn(bar, size.width)
            drawRoundRect(colour, Offset(0f, top), Size(end, bar), CornerRadius(bar / 2))
            // The range most adults fall in, as a dashed box a little taller than the bar, like Google's.
            val left = x(low)
            val right = x(high).coerceAtLeast(left + bar)
            drawRoundRect(
                ink, Offset(left + 1f, 1f), Size(right - left - 2f, size.height - 2f), CornerRadius((size.height - 2f) / 2),
                style = Stroke(1.2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
            )
            val dot = Offset((end - bar / 2).coerceIn(bar / 2, size.width - bar / 2), size.height / 2)
            drawCircle(ring, 10.dp.toPx(), dot)
            drawCircle(ink, 7.5.dp.toPx(), dot)
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
 * Steps and active minutes of today next to the week. Steps come in a few times an hour and only a watch counts them, so today's bar
 * comes from the watch and the days before it from the records.
 */
@Composable
internal fun ActivityToday(insights: TandemHealthInsights?) {
    val graph = LocalContext.current.graph
    val watch by graph.watch.state.collectAsState()
    val goal by graph.prefs.stepGoal.collectAsState(initial = TandemPrefs.DEFAULT_STEP_GOAL)
    if (insights == null) return
    var offset by rememberSaveable { mutableIntStateOf(0) }
    val shown = rememberWeek(insights, offset)
    val week = shown?.week.orEmpty()
    WeekPager(stringResource(R.string.health_today), offset, { offset = it }, week)
    if (shown == null || week.isEmpty()) return
    val days = week.map { it.startMs.toLong() }
    // Today is live from the watch; a week further back has only whole days.
    val live = shown.now && watch.paired
    val steps = week.mapIndexed { index, day ->
        if (index == week.lastIndex && live) watch.stepsToday else day.steps?.toLong()
    }
    val active = week.mapIndexed { index, day ->
        if (index == week.lastIndex && live) watch.activeMinutesToday else day.activeMinutes?.toInt()
    }
    if (!live && steps.none { it != null }) return
    val shownDay = if (shown.now) week.lastIndex else steps.indexOfLast { it != null }.coerceAtLeast(0)
    val context = LocalContext.current
    SettingsGroup {
        val today = steps[shownDay] ?: 0L
        val left = goal - today
        val caption = if (!shown.now) dayName(context, week[shownDay].startMs.toLong())
        else if (left > 0) stringResource(R.string.health_steps_left, number(left)) else stringResource(R.string.health_steps_done)
        // Today by the hour, in place of the week: live from the watch, or without it the last whole day the core knows. A week
        // further back keeps its days.
        val lastDay = insights.latestDaySteps
        when {
            shown.now && live && watch.stepsByHour.size == 24 -> StepsByHourCard(0, 2, watch.stepsByHour, today, caption)
            shown.now && !live && lastDay != null ->
                StepsByHourCard(0, 2, lastDay.curve.map { it.toInt() }, lastDay.curve.sumOf { it.toLong() }, dayName(context, lastDay.startMs.toLong()))
            else -> WeekCard(
                0, 2, stringResource(R.string.health_steps_title), number(today), caption,
                week, steps.map { step -> step?.let(::number) },
            ) { selected, select ->
                WeekBars(steps.map { it?.toFloat() }, days, selected, select, stringResource(R.string.health_steps_title), goal = goal.toFloat())
            }
        }
        val sum = active.sumOf { it ?: 0 }
        WeekCard(
            1, 2, stringResource(R.string.health_active_title), stringResource(R.string.health_minutes, active[shownDay] ?: 0),
            if (shown.now) stringResource(R.string.health_active_week, sum) else stringResource(R.string.health_active_that_week, sum),
            week, active.map { minutes -> minutes?.let { stringResource(R.string.health_minutes, it) } },
            more = stringResource(R.string.health_more_active),
        ) { selected, select ->
            WeekBars(active.map { it?.toFloat() }, days, selected, select, stringResource(R.string.health_active_title))
        }
    }
}

/** The heart rate of today, to slide over and zoom into; on a computer the last whole day, because only the phone hears the watch during it. */
@Composable
internal fun HeartToday(insights: TandemHealthInsights?) {
    val context = LocalContext.current
    val watch by context.graph.watch.state.collectAsState()
    val todayHeart = watch.heartToday.takeIf { curve -> curve.any { it > 0 } }
    val lastDay = insights?.latestDayHeart
    if (todayHeart == null && lastDay == null) return
    SectionHeader(stringResource(R.string.health_today), top = 12.dp, bottom = 0.dp)
    SettingsGroup {
        if (todayHeart != null) {
            HeartCard(0, 1, stringResource(R.string.health_heart_today), todayHeart)
        } else if (lastDay != null) {
            HeartCard(0, 1, stringResource(R.string.health_heart_on_day, dayName(context, lastDay.startMs.toLong())), lastDay.curve.map { it.toInt() })
        }
    }
}

/** The naps of the last week, which a night of sleep does not count. */
@Composable
internal fun Naps(insights: TandemHealthInsights?) {
    val naps = insights?.naps.orEmpty()
    if (naps.isEmpty()) return
    SectionHeader(stringResource(R.string.health_naps), top = 16.dp, bottom = 0.dp)
    SettingsGroup { NapsCard(0, 1, naps) }
}

@Composable
private fun HeartCard(index: Int, total: Int, title: String, curve: List<Int>) {
    val measured = curve.filter { it > 0 }
    HealthCard(index, total) {
        CardTitle(title, stringResource(R.string.health_more_heart), Modifier.padding(bottom = 6.dp))
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
    HealthCard(index, total) {
        CardTitle(stringResource(R.string.health_naps), stringResource(R.string.health_more_naps), Modifier.padding(bottom = 6.dp))
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
 * The nights of this week next to the week before, because a number alone does not say whether it is going well, and how regular they
 * are. Each card says how far it has come, because an empty page would look broken.
 */
@Composable
internal fun SleepWeek(insights: TandemHealthInsights?) {
    if (insights == null || insights.latestNight == null) return
    var offset by rememberSaveable { mutableIntStateOf(0) }
    val shown = rememberWeek(insights, offset)
    val week = shown?.week.orEmpty()
    WeekPager(stringResource(R.string.health_week), offset, { offset = it }, week)
    if (shown == null || week.isEmpty()) return
    val days = week.map { it.startMs.toLong() }
    val before = shown.before
    val sleep = shown.summary.meanSleepMinutes
    val score = shown.summary.meanScore
    // How regular the nights are is about the last nights, so it belongs to this week only.
    val total = listOf(sleep != null, score != null, shown.now).count { it }
    if (total == 0) return
    SettingsGroup {
        var at = 0
        if (sleep != null) {
            val was = before?.meanSleepMinutes
            WeekCard(
                at++, total, stringResource(R.string.health_sleep_title), duration(sleep.toInt()),
                if (was != null) stringResource(R.string.health_mean_night_before, duration(was.toInt())) else stringResource(R.string.health_mean_night),
                week, week.map { day -> day.sleepMinutes?.let { duration(it.toInt()) } },
            ) { selected, select ->
                WeekBars(week.map { it.sleepMinutes?.toFloat() }, days, selected, select, stringResource(R.string.health_sleep_title))
            }
        }
        if (score != null) {
            val was = before?.meanScore
            WeekCard(
                at++, total, stringResource(R.string.health_score_title), "$score",
                if (was != null) stringResource(R.string.health_mean_score_before, was.toInt()) else stringResource(R.string.health_mean_score),
                week, week.map { day -> day.score?.toString() },
            ) { selected, select ->
                WeekBars(week.map { it.score?.toFloat() }, days, selected, select, stringResource(R.string.health_score_title))
            }
        }
        if (shown.now) RegularityCard(at, total, insights, days)
    }
}

/** The resting heart rate of the week, against what is usual for this person once there are enough nights. */
@Composable
internal fun HeartWeek(insights: TandemHealthInsights?) {
    if (insights?.resting == null || insights.week.isEmpty()) return
    var offset by rememberSaveable { mutableIntStateOf(0) }
    val shown = rememberWeek(insights, offset)
    WeekPager(stringResource(R.string.health_week), offset, { offset = it }, shown?.week.orEmpty())
    if (shown == null || shown.week.isEmpty()) return
    SettingsGroup { RestingCard(0, 1, insights, shown) }
}

/** The workouts of this week. */
@Composable
internal fun ActivityWeek(insights: TandemHealthInsights?) {
    val thisWeek = insights?.thisWeek ?: return
    val workouts = thisWeek.workouts.toInt()
    if (workouts == 0) return
    SectionHeader(stringResource(R.string.health_week), top = 16.dp, bottom = 0.dp)
    SettingsGroup {
        HealthCard(0, 1) {
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

@Composable
private fun RestingCard(index: Int, total: Int, insights: TandemHealthInsights, shown: ShownWeek) {
    val resting = insights.resting ?: return
    val week = shown.week
    val days = week.map { it.startMs.toLong() }
    val usual = resting.usual
    // A week further back shows its own mean, not the latest night.
    val mean = shown.summary.meanResting
    WeekCard(
        index, total, stringResource(R.string.health_resting),
        if (shown.now) stringResource(R.string.health_resting_line, resting.latest.toInt())
        else mean?.let { stringResource(R.string.health_resting_line, it.toInt()) } ?: "-",
        if (!shown.now) stringResource(R.string.health_mean_score)
        else if (usual != null) stringResource(R.string.health_usually, usual.toInt())
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
    HealthCard(index, total) {
        CardTitle(stringResource(R.string.health_regular), stringResource(R.string.health_more_regular), Modifier.padding(bottom = 6.dp))
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

/** The earlier nights on this phone, the newest first. The list that grows, so it belongs at the end of a page. A night opens where it is. */
@Composable
internal fun EarlierNights() {
    val records by LocalContext.current.graph.host.healthRecords.collectAsState()
    val latest = records.latestNight()
    val shown = records.filter { it.isNight() && it !== latest }.take(SHOWN)
    if (shown.isEmpty()) return
    SectionHeader(stringResource(R.string.health_earlier), top = 16.dp, bottom = 0.dp)
    SettingsGroup { shown.forEachIndexed { index, record -> NightRow(index, shown.size, record) } }
}

/** The workouts on this phone, the newest first. */
@Composable
internal fun EarlierWorkouts() {
    val records by LocalContext.current.graph.host.healthRecords.collectAsState()
    val shown = records.filter { it.kind == TandemHealthKind.WORKOUT }.take(SHOWN)
    if (shown.isEmpty()) return
    SectionHeader(stringResource(R.string.health_workouts), top = 16.dp, bottom = 0.dp)
    SettingsGroup { shown.forEachIndexed { index, record -> WorkoutRow(index, shown.size, record) } }
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

/**
 * Which computers get the nights and workouts, all on the health page: the switch of each was only on the settings page of that computer,
 * where nobody looking at a Mac with an empty health page would think to look. Still off until the person turns it on. Another phone
 * always gets them (the watch can move to it), so it has no switch here.
 */
@Composable
internal fun HealthSharing() {
    val graph = LocalContext.current.graph
    val devices by graph.host.devices.collectAsState()
    val computers = devices.filter { "health" in it.caps && !it.isPhone() }
    if (computers.isEmpty() || !rememberHealthAvailable()) return
    val engine = graph.host.engine
    val scope = rememberCoroutineScope()
    SectionHeader(stringResource(R.string.health_share_title), top = 16.dp, bottom = 0.dp)
    SettingsGroup {
        computers.forEachIndexed { index, device ->
            var allowed by remember(device.id) { mutableStateOf(engine?.healthAllowed(device.id) ?: false) }
            SwitchRow(
                index, computers.size, TandemIcons.Laptop, device.name, stringResource(R.string.health_share_row_sub), allowed,
                { on ->
                    allowed = on
                    scope.launch { runCatching { engine?.setHealthAllowed(device.id, on) } }
                },
            )
        }
    }
}

/** What a person chooses here, below what is measured: it is set once and then looked past. */
@Composable
internal fun HealthChoices() {
    val watch by LocalContext.current.graph.watch.state.collectAsState()
    if (!watch.paired) return
    val smartWake by LocalContext.current.graph.prefs.smartWake.collectAsState(initial = false)
    SectionHeader(stringResource(R.string.section_preferences), top = 16.dp, bottom = 0.dp)
    SettingsGroup {
        // The strength only while waking gently is on: a slider for something that is off says nothing.
        val total = if (smartWake) 4 else 3
        var at = 0
        SmartWakeRow(at++, total, searchTarget = true)
        if (smartWake) WakeStrengthRow(at++, total)
        HeartAllDayRow(at++, total, searchTarget = true)
        StepGoalRow(at, total)
    }
}

/** Waking gently, at [index] in a group of [total]. On the health page and on the page of the watch; search leads to the one on the health page. */
@Composable
internal fun SmartWakeRow(index: Int, total: Int, searchTarget: Boolean = false) {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    val smartWake by graph.prefs.smartWake.collectAsState(initial = false)
    val row = @Composable {
        SwitchRow(
            index, total, TandemIcons.Schedule, stringResource(R.string.health_smart_wake), stringResource(R.string.health_smart_wake_sub), smartWake,
            { on -> scope.launch { graph.prefs.setSmartWake(on) } },
        )
    }
    if (searchTarget) SettingsTarget(FocusKeys.HealthSmartWake, index, total, row) else row()
}

/**
 * How strong waking gently gets at its top, in steps of ten per cent of the watch's motor. It changes as the slider moves, and the
 * watch gets it with the next alarm; the try in the developer options buzzes at it too.
 */
@Composable
private fun WakeStrengthRow(index: Int, total: Int) {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    val saved by graph.prefs.wakeStrength.collectAsState(initial = Message.SmartWakeAlarm.DEFAULT_STRENGTH)
    var moving by remember { mutableStateOf<Float?>(null) }
    val shown = moving ?: saved.toFloat()
    val scheme = MaterialTheme.colorScheme
    GroupedRow(index, total) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.health_wake_strength), style = MaterialTheme.typography.titleSmall, color = scheme.onSurface, modifier = Modifier.weight(1f))
                Text("${shown.roundToInt()}%", style = MaterialTheme.typography.titleSmall, color = scheme.primary)
            }
            androidx.compose.material3.Slider(
                value = shown,
                onValueChange = { moving = it },
                onValueChangeFinished = {
                    val chosen = (moving ?: return@Slider).roundToInt()
                    moving = null
                    scope.launch { graph.prefs.setWakeStrength(chosen) }
                },
                valueRange = Message.SmartWakeAlarm.MIN_STRENGTH.toFloat()..Message.SmartWakeAlarm.MAX_STRENGTH.toFloat(),
                steps = (Message.SmartWakeAlarm.MAX_STRENGTH - Message.SmartWakeAlarm.MIN_STRENGTH) / 10 - 1,
            )
            Row {
                Text(stringResource(R.string.health_wake_soft), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.health_wake_strong), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
        }
    }
}

/** The heart rate all day, like [SmartWakeRow]. */
@Composable
internal fun HeartAllDayRow(index: Int, total: Int, searchTarget: Boolean = false) {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    val heartAllDay by graph.prefs.heartAllDay.collectAsState(initial = true)
    val row = @Composable {
        SwitchRow(
            index, total, TandemIcons.Heart, stringResource(R.string.health_heart_all_day), stringResource(R.string.health_heart_all_day_sub), heartAllDay,
            { on -> scope.launch { graph.prefs.setHeartAllDay(on) } },
        )
    }
    if (searchTarget) SettingsTarget(FocusKeys.HealthHeartAllDay, index, total, row) else row()
}

/**
 * The step goal as a row that opens a small window with a minus and a plus, saved only with Save. It was a slider on the page, which a
 * thumb scrolling past moved by thousands without meaning to.
 */
@Composable
private fun StepGoalRow(index: Int, total: Int) {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    val goal by graph.prefs.stepGoal.collectAsState(initial = TandemPrefs.DEFAULT_STEP_GOAL)
    var editing by remember { mutableStateOf(false) }
    ActionRow(index, total, TandemIcons.Steps, stringResource(R.string.health_goal), stringResource(R.string.health_goal_row, number(goal.toLong())), { editing = true })
    if (!editing) return
    var draft by remember { mutableIntStateOf(goal) }
    TandemDialog(
        title = stringResource(R.string.health_goal),
        onDismiss = { editing = false },
        icon = TandemIcons.Steps,
        body = stringResource(R.string.health_goal_sub),
        actions = {
            SecondaryPillButton(stringResource(R.string.action_cancel), { editing = false })
            PrimaryPillButton(stringResource(R.string.action_save), {
                scope.launch { graph.prefs.setStepGoal(draft) }
                editing = false
            })
        },
        content = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                TandemIconButton(
                    TandemIcons.Remove, stringResource(R.string.health_goal_less),
                    { draft = (draft - TandemPrefs.STEP_GOAL_STEP).coerceAtLeast(TandemPrefs.STEP_GOAL_MIN) },
                    background = MaterialTheme.colorScheme.surfaceContainerHighest, size = 52,
                )
                Text(number(draft.toLong()), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                TandemIconButton(
                    TandemIcons.Add, stringResource(R.string.health_goal_more),
                    { draft = (draft + TandemPrefs.STEP_GOAL_STEP).coerceAtMost(TandemPrefs.STEP_GOAL_MAX) },
                    background = MaterialTheme.colorScheme.surfaceContainerHighest, size = 52,
                )
            }
        },
    )
}

/**
 * The way to everything the watch measures, on the page of the watch: what it is called, what is in it, and the last numbers as tiles.
 * It was a row near the bottom called after only a part of it, which was easy to miss.
 */
@Composable
internal fun WatchHealthCard(onOpen: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clip(CardSquircle).background(scheme.surfaceContainer)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(scheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(TandemIcons.Heart, null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(24.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.settings_cat_health), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.health_open_sub), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            Icon(TandemIcons.ChevronRight, null, tint = scheme.onSurfaceVariant)
        }
        WatchGlance(onOpen, Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp))
    }
}

/**
 * What the watch measured last, as three numbers: the night, today's steps as the watch counts them, and the resting heart rate. A tap
 * opens the health page. Nothing while there is nothing to show.
 */
@Composable
internal fun WatchGlance(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val graph = context.graph
    val records by graph.host.healthRecords.collectAsState()
    val watch by graph.watch.state.collectAsState()
    val insights = rememberHealthInsights()
    val night = records.latestNight()
    val score = insights?.latestNight?.takeIf { it.id == night?.id }?.score
    val resting = insights?.resting
    val steps = watch.stepsToday.takeIf { watch.paired }
    // The heart rate now, while these tiles are on screen and the watch is here. Asked again every half minute: the watch stops by itself.
    val now = watch.heartNow.takeIf { watch.ready }
    if (watch.ready) {
        val lifecycle = LocalLifecycleOwner.current
        LaunchedEffect(lifecycle) {
            lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                try {
                    while (true) {
                        graph.watch.liveHeart(true)
                        kotlinx.coroutines.delay(30_000)
                    }
                } finally {
                    graph.watch.liveHeart(false)
                }
            }
        }
    }
    if (night == null && steps == null && resting == null && now == null) return
    // Three tiles of their own, each in its own colour of the theme, so the eye finds each number by its colour as well as its place.
    // All as tall as the tallest, so a caption on two lines does not make one stick out.
    val scheme = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (night != null) {
            Glance(
                TandemIcons.Sleep, stringResource(R.string.watch_glance_night), duration(night.sleepMinutes.toInt()),
                score?.let { stringResource(R.string.watch_glance_score, it.toInt()) } ?: dayName(context, night.endMs.toLong()),
                scheme.secondaryContainer, scheme.onSecondaryContainer, onOpen, Modifier.weight(1f).fillMaxHeight(),
            )
        }
        if (steps != null) {
            Glance(
                TandemIcons.Steps, stringResource(R.string.health_steps_title), number(steps), stringResource(R.string.health_today),
                scheme.primaryContainer, scheme.onPrimaryContainer, onOpen, Modifier.weight(1f).fillMaxHeight(),
            )
        }
        if (now != null) {
            // Live: the number now, with the heart beating, and the resting rate under it.
            Glance(
                TandemIcons.Heart, stringResource(R.string.watch_glance_now), stringResource(R.string.health_resting_line, now),
                resting?.let { stringResource(R.string.watch_glance_resting_value, it.latest.toInt()) } ?: stringResource(R.string.watch_glance_live),
                scheme.tertiaryContainer, scheme.onTertiaryContainer, onOpen, Modifier.weight(1f).fillMaxHeight(), beating = true,
            )
        } else if (resting != null) {
            Glance(
                TandemIcons.Heart, stringResource(R.string.watch_glance_resting), stringResource(R.string.health_resting_line, resting.latest.toInt()),
                resting.usual?.let { stringResource(R.string.health_usually, it.toInt()) }
                    ?: pluralStringResource(R.plurals.watch_glance_nights_left, resting.nightsNeeded.toInt(), resting.nightsNeeded.toInt()),
                scheme.tertiaryContainer, scheme.onTertiaryContainer, onOpen, Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

/** A tile: the icon in a round of its own, the number large, what it is and a short word under it, on two lines when it needs them. */
@Composable
private fun Glance(
    icon: androidx.compose.ui.graphics.painter.Painter,
    label: String,
    value: String,
    caption: String,
    container: Color,
    content: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    beating: Boolean = false,
) {
    Column(
        modifier.clip(CardSquircle).background(container).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // A live number beats: two quick swells and a rest, about once a second.
        val beat = if (beating) {
            val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "heartBeat")
            pulse.animateFloat(
                1f, 1f,
                androidx.compose.animation.core.infiniteRepeatable(
                    androidx.compose.animation.core.keyframes {
                        durationMillis = 1000
                        1.18f at 120
                        1f at 240
                        1.12f at 360
                        1f at 520
                    },
                ),
                label = "heartScale",
            ).value
        } else 1f
        Box(Modifier.size(34.dp).clip(CircleShape).background(content.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = content, modifier = Modifier.size(20.dp).graphicsLayer { scaleX = beat; scaleY = beat })
        }
        Spacer(Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = content, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelMedium, color = content, maxLines = 2)
        Text(caption, style = MaterialTheme.typography.bodySmall, color = content.copy(alpha = 0.75f), maxLines = 2)
    }
}

@Composable
internal fun StageBar(record: TandemHealthRecord, height: Dp) {
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
 * in a circle. Only shown on a phone that has any to give, and not for another phone, which always gets them.
 */
@Composable
internal fun HealthDeviceSection(device: TandemDevice) {
    val graph = LocalContext.current.graph
    if ("health" !in device.caps || device.isPhone() || !rememberHealthAvailable()) return
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
