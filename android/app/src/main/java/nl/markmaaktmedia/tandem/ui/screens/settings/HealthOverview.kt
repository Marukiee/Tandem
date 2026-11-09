package nl.markmaaktmedia.tandem.ui.screens.settings

import android.text.format.DateUtils
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.data.TandemPrefs
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.components.bouncyClickable
import nl.markmaaktmedia.tandem.ui.theme.CardSquircle
import nl.markmaaktmedia.tandem.ui.theme.PillShape
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import uniffi.tandem_core.TandemHealthInsights

/**
 * Last night, large, at the top of Health: how long, the score, and the few numbers of the night as pills. In the colour of the theme with
 * soft rounds behind it, so the page opens on something friendly instead of a row of cards. A tap opens all of the night.
 */
@Composable
internal fun HealthHero(insights: TandemHealthInsights?, onOpen: () -> Unit) {
    val context = LocalContext.current
    val records by context.graph.host.healthRecords.collectAsState()
    val night = records.latestNight() ?: return
    val insight = insights?.latestNight?.takeIf { it.id == night.id }
    val scheme = MaterialTheme.colorScheme
    val date = DateUtils.formatDateTime(
        context, night.endMs.toLong(),
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL or DateUtils.FORMAT_NO_YEAR,
    )
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(36.dp)).background(scheme.primaryContainer).bouncyClickable(onClick = onOpen)) {
        // A clover leaning out of the corner: the playful side of Material You, quiet enough to leave the numbers in front.
        Box(
            Modifier.align(Alignment.BottomEnd).offset(x = 34.dp, y = 40.dp).size(150.dp).rotate(45f)
                .clip(CloverShape).background(scheme.onPrimaryContainer.copy(alpha = 0.06f)),
        )
        Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(30.dp).clip(CircleShape).background(scheme.onPrimaryContainer.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                            Icon(TandemIcons.Sleep, null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(17.dp))
                        }
                        Text(stringResource(R.string.watch_glance_night), style = MaterialTheme.typography.labelLarge, color = scheme.onPrimaryContainer)
                    }
                    BigNumber(duration(night.sleepMinutes.toInt()))
                    Text(date, style = MaterialTheme.typography.bodySmall, color = scheme.onPrimaryContainer.copy(alpha = 0.75f))
                }
                if (insight != null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        // The score on a cookie that rolls in once when the page opens, so the number reads as a badge, not a chart.
                        val spin = remember { Animatable(-50f) }
                        LaunchedEffect(Unit) { spin.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessVeryLow)) }
                        Box(Modifier.size(118.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.matchParentSize().graphicsLayer { rotationZ = spin.value }.clip(CookieShape).background(scheme.surface.copy(alpha = 0.6f)))
                            ScoreRing(insight.score.toInt(), 86.dp, tint = scheme.primary, track = scheme.onPrimaryContainer.copy(alpha = 0.12f))
                        }
                        Text(stringResource(R.string.health_score_title), style = MaterialTheme.typography.labelSmall, color = scheme.onPrimaryContainer)
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HeroPill(stringResource(R.string.health_stat_asleep), "${night.efficiencyPercent}%")
                HeroPill(stringResource(R.string.health_stage_awake), duration(night.awakeMinutes.toInt()))
                night.heartRateLow?.let { HeroPill(stringResource(R.string.health_stat_lowest), stringResource(R.string.health_resting_line, it.toInt())) }
            }
        }
    }
}

@Composable
private fun HeroPill(label: String, value: String) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.clip(PillShape).background(scheme.surface.copy(alpha = 0.6f)).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = scheme.onSurface)
    }
}

/**
 * A card for each part of Health with what matters most of it and a little picture, each opening the whole of that part. Only the parts
 * with something in them: a card that says nothing makes the page look broken.
 */
@Composable
internal fun HealthParts(insights: TandemHealthInsights?, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val watch by graph.watch.state.collectAsState()
    val records by graph.host.healthRecords.collectAsState()
    val goal by graph.prefs.stepGoal.collectAsState(initial = TandemPrefs.DEFAULT_STEP_GOAL)
    val scheme = MaterialTheme.colorScheme
    val week = insights?.week.orEmpty()

    // Sleep: the night on average this week, with the week as bars.
    val meanSleep = insights?.thisWeek?.meanSleepMinutes
    val latest = records.latestNight()
    if (meanSleep != null || latest != null) {
        PartCard(
            TandemIcons.Sleep, stringResource(R.string.health_sleep_title),
            duration((meanSleep ?: latest!!.sleepMinutes).toInt()),
            if (meanSleep != null) stringResource(R.string.health_mean_night) else dayName(context, latest!!.endMs.toLong()),
            scheme.secondaryContainer, scheme.onSecondaryContainer, { onOpen("sleep") },
        ) {
            if (week.isNotEmpty()) {
                WeekBars(week.map { it.sleepMinutes?.toFloat() }, week.map { it.startMs.toLong() }, null, {}, stringResource(R.string.health_sleep_title), height = 40.dp)
            }
        }
    }

    // Activity: the steps of today as a bar that fills toward the goal, and the active minutes.
    val steps = if (watch.paired) watch.stepsToday else week.lastOrNull()?.steps?.toLong()
    if (steps != null) {
        val share = (steps.toFloat() / goal.coerceAtLeast(1)).coerceIn(0f, 1f)
        PartCard(
            TandemIcons.Steps, stringResource(R.string.health_activity_title), number(steps),
            stringResource(R.string.health_steps_of_goal, number(goal.toLong())),
            scheme.tertiaryContainer, scheme.onTertiaryContainer, { onOpen("activity") },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                GoalBar(share, scheme.tertiary, scheme.onTertiaryContainer.copy(alpha = 0.12f))
                if (watch.paired) {
                    Text(
                        stringResource(R.string.health_active_today, watch.activeMinutesToday),
                        style = MaterialTheme.typography.labelSmall, color = scheme.onTertiaryContainer.copy(alpha = 0.8f),
                    )
                }
            }
        }
    }

    // The heart: the resting rate, with the heart rate of today as a line when there is one.
    val resting = insights?.resting
    val curve = watch.heartToday.takeIf { it.any { v -> v > 0 } } ?: insights?.latestDayHeart?.curve?.map { it.toInt() }
    if (resting != null || curve != null) {
        PartCard(
            TandemIcons.Heart, stringResource(R.string.health_heart_title),
            resting?.let { stringResource(R.string.health_resting_line, it.latest.toInt()) } ?: stringResource(R.string.health_resting_line, curve!!.filter { it > 0 }.last()),
            when {
                resting?.usual != null -> stringResource(R.string.health_usually, resting.usual!!.toInt())
                resting != null -> pluralStringResource(R.plurals.health_nights_more, resting.nightsNeeded.toInt(), resting.nightsNeeded.toInt())
                else -> stringResource(R.string.health_heart_today)
            },
            scheme.surfaceContainerHigh, scheme.onSurface, { onOpen("heart") },
        ) {
            if (curve != null) Sparkline(curve, scheme.primary)
        }
    }
}

/** A card of a part: its icon in a round, its name, the number large, a short word, and a little picture beside it. */
@Composable
private fun PartCard(
    icon: Painter,
    title: String,
    value: String,
    caption: String,
    container: Color,
    content: Color,
    onClick: () -> Unit,
    picture: @Composable () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().clip(CardSquircle).background(container).bouncyClickable(onClick = onClick).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(content.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = content, modifier = Modifier.size(20.dp))
            }
            Text(title, style = MaterialTheme.typography.titleMedium, color = content, modifier = Modifier.weight(1f))
            Icon(TandemIcons.ChevronRight, null, tint = content.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = content, maxLines = 1)
                Text(caption, style = MaterialTheme.typography.bodySmall, color = content.copy(alpha = 0.75f), maxLines = 2)
            }
            Box(Modifier.width(150.dp)) { picture() }
        }
    }
}

/** How far toward a goal, as a thick rounded bar. */
@Composable
private fun GoalBar(share: Float, fill: Color, track: Color) {
    Canvas(Modifier.fillMaxWidth().height(12.dp)) {
        val radius = CornerRadius(size.height / 2)
        drawRoundRect(track, cornerRadius = radius)
        drawRoundRect(fill, size = Size((size.width * share).coerceAtLeast(size.height), size.height), cornerRadius = radius)
    }
}

/** The heart rate of a day as a small line, gaps where the watch was off the wrist. */
@Composable
private fun Sparkline(curve: List<Int>, colour: Color) {
    val measured = curve.filter { it > 0 }
    if (measured.size < 2) return
    Canvas(Modifier.fillMaxWidth().height(44.dp)) {
        val low = measured.min() - 2f
        val high = measured.max() + 2f
        val line = Path()
        var before = -100
        curve.forEachIndexed { index, value ->
            if (value <= 0) return@forEachIndexed
            val x = index / (curve.size - 1f) * size.width
            val y = (1f - (value - low) / (high - low)) * size.height
            if (index - before > 6) line.moveTo(x, y) else line.lineTo(x, y)
            before = index
        }
        drawPath(line, colour, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/**
 * A circle with soft waves around its edge, after the shapes of Material You: a few deep lobes make a clover, many shallow ones a
 * cookie. Drawn here because those shapes are not in the version of Material that the app uses.
 */
private class WavyCircleShape(private val lobes: Int, private val depth: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val centreX = size.width / 2
        val centreY = size.height / 2
        val outer = minOf(centreX, centreY)
        val path = Path()
        val steps = 240
        for (step in 0..steps) {
            val angle = step * 2 * PI / steps - PI / 2
            val radius = outer * (1 - depth / 2 + depth / 2 * cos(lobes * angle)).toFloat()
            val x = centreX + radius * cos(angle).toFloat()
            val y = centreY + radius * sin(angle).toFloat()
            if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return Outline.Generic(path)
    }
}

private val CookieShape: Shape = WavyCircleShape(lobes = 9, depth = 0.09f)
private val CloverShape: Shape = WavyCircleShape(lobes = 4, depth = 0.32f)
