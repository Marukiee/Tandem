package nl.markmaaktmedia.tandem.ui.screens.settings

import androidx.compose.foundation.Canvas
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import nl.markmaaktmedia.tandem.ui.components.bouncyClickable
import nl.markmaaktmedia.tandem.ui.theme.TandemMotion
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.components.SectionHeader
import nl.markmaaktmedia.tandem.ui.components.SettingsGroup
import nl.markmaaktmedia.tandem.ui.components.TandemIconButton
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import uniffi.tandem_core.TandemHealthInsights
import uniffi.tandem_core.TandemHealthRange
import uniffi.tandem_core.TandemWeekDay
import uniffi.tandem_core.TandemWeekSummary

private const val DAY_MS = 86_400_000L

/** The week a page shows: this one, with last week to compare, or one further back without it. */
internal class ShownWeek(val week: List<TandemWeekDay>, val summary: TandemWeekSummary, val before: TandemWeekSummary?, val now: Boolean)

/**
 * A run of days that ends with the day of [untilMs], from the core over every record this phone keeps, not only the newest. Asked again
 * when records come in. Null while it is on its way.
 */
@Composable
internal fun rememberRange(untilMs: Long, count: Int): TandemHealthRange? {
    val host = LocalContext.current.graph.host
    val records by host.healthRecords.collectAsState()
    val range by produceState<TandemHealthRange?>(null, untilMs / DAY_MS, count, records) {
        value = withContext(Dispatchers.IO) {
            host.engine?.healthRange(untilMs.toULong(), count.toUInt(), TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000)
        }
    }
    return range
}

/** This week from the insights, or the week [offset] weeks back from the core. Null while that one is on its way. */
@Composable
internal fun rememberWeek(insights: TandemHealthInsights, offset: Int): ShownWeek? {
    val range = rememberRange(System.currentTimeMillis() - offset * 7 * DAY_MS, 7)
    if (offset == 0) return ShownWeek(insights.week, insights.thisWeek, insights.lastWeek, true)
    return range?.let { ShownWeek(it.days, it.summary, null, false) }
}

private val ShortDate = DateTimeFormatter.ofPattern("d MMM")

private fun shortDate(day: TandemWeekDay): String =
    Instant.ofEpochMilli(day.startMs.toLong() + DAY_MS / 2).atZone(ZoneId.systemDefault()).format(ShortDate.withLocale(Locale.getDefault()))

/** The heading of a week with a way back and forward, so older weeks are looked at the same way as this one. */
@Composable
internal fun WeekPager(title: String, offset: Int, onOffset: (Int) -> Unit, week: List<TandemWeekDay>) {
    val label = if (offset == 0 || week.isEmpty()) title else stringResource(R.string.health_night_span, shortDate(week.first()), shortDate(week.last()))
    SectionHeader(label, top = 16.dp, bottom = 0.dp, trailing = { Pager(offset, onOffset, R.string.health_week_before, R.string.health_week_after) })
}

@Composable
private fun Pager(offset: Int, onOffset: (Int) -> Unit, back: Int, on: Int) {
    Row {
        TandemIconButton(TandemIcons.ChevronRight, stringResource(back), { onOffset(offset + 1) }, Modifier.rotate(180f), size = 40)
        TandemIconButton(
            TandemIcons.ChevronRight, stringResource(on), { if (offset > 0) onOffset(offset - 1) },
            Modifier.alpha(if (offset > 0) 1f else 0.3f), size = 40,
        )
    }
}

/**
 * A month as heat tiles, Monday first: for sleep and activity the stronger the colour the closer to the goal, for the heart warm on days
 * above the usual of that month and cool below it. The picked day stands large above the grid; a tap picks another.
 */
@Composable
internal fun MonthCalendar(kind: CalendarKind, goal: Int? = null) {
    var back by rememberSaveable { mutableIntStateOf(0) }
    val zone = ZoneId.systemDefault()
    val thisMonth = YearMonth.now(zone)
    val shownMonth = thisMonth.minusMonths(back.toLong())
    val scheme = MaterialTheme.colorScheme
    SettingsGroup {
        HealthCard(0, 1) {
            CardTitle(stringResource(R.string.health_calendar), stringResource(kind.info))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    shownMonth.atDay(1).format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault())).replaceFirstChar { it.titlecase(Locale.getDefault()) },
                    style = MaterialTheme.typography.titleSmall, color = scheme.onSurface, modifier = Modifier.weight(1f),
                )
                Pager(back, { back = it }, R.string.health_month_before, R.string.health_month_after)
            }
            AnimatedContent(
                targetState = back,
                transitionSpec = {
                    // Further back comes in from the left, as the button that asked for it points.
                    val older = targetState > initialState
                    (slideInHorizontally(TandemMotion.spatial()) { if (older) -it / 5 else it / 5 } + fadeIn(TandemMotion.fadeSpec()))
                        .togetherWith(slideOutHorizontally(TandemMotion.spatial()) { if (older) it / 5 else -it / 5 } + fadeOut(TandemMotion.fadeSpec()))
                        .using(SizeTransform(clip = false))
                },
                label = "calendarMonth",
            ) { shown ->
                // Each month asks for its own days, so a month on its way out never draws the days of the one coming in.
                MonthTiles(kind, goal, thisMonth.minusMonths(shown.toLong()))
            }
        }
    }
}

internal enum class CalendarKind(val info: Int) {
    SLEEP(R.string.health_more_calendar_sleep),
    ACTIVITY(R.string.health_more_calendar_activity),
    HEART(R.string.health_more_calendar_heart),
}

@Composable
private fun MonthTiles(kind: CalendarKind, goal: Int?, month: YearMonth) {
    val zone = ZoneId.systemDefault()
    val length = month.lengthOfMonth()
    val range = rememberRange(month.atDay(length).atTime(12, 0).atZone(zone).toInstant().toEpochMilli(), length)
    // The grid comes from the month itself. Values only from a range of this month: the one before stays until the new one is in.
    val days = range?.days?.takeIf { days ->
        days.size == length && Instant.ofEpochMilli(days.first().startMs.toLong() + DAY_MS / 2).atZone(zone).toLocalDate() == month.atDay(1)
    }
    val values: List<Long?> = List(length) { index ->
        val day = days?.get(index) ?: return@List null
        when (kind) {
            CalendarKind.SLEEP -> day.sleepMinutes?.toLong()
            CalendarKind.ACTIVITY -> day.steps?.toLong()
            CalendarKind.HEART -> day.heartMean?.toLong()
        }
    }
    val target = when (kind) {
        CalendarKind.SLEEP -> CalendarHeat.SLEEP_GOAL
        CalendarKind.ACTIVITY -> goal?.toLong()
        CalendarKind.HEART -> null
    }
    val levels = if (kind == CalendarKind.HEART) CalendarHeat.heart(values) else CalendarHeat.shares(values, target)
    val usual = CalendarHeat.usual(values)
    val today = LocalDate.now(zone)
    val todayIndex = if (YearMonth.from(today) == month) today.dayOfMonth - 1 else null
    var picked by rememberSaveable(month) { mutableStateOf<Int?>(null) }
    val shown = picked ?: CalendarHeat.defaultPick(values, todayIndex)
    val scheme = MaterialTheme.colorScheme
    val texts = values.map { value -> value?.let { calendarValue(kind, it) } }
    val noValue = stringResource(R.string.health_calendar_none)

    Column {
        AnimatedContent(
            targetState = shown,
            transitionSpec = { fadeIn(TandemMotion.fadeSpec()).togetherWith(fadeOut(TandemMotion.fadeSpec())) },
            contentAlignment = Alignment.TopStart,
            label = "calendarDay",
            modifier = Modifier.padding(top = 4.dp),
        ) { index ->
            Column(Modifier.fillMaxWidth()) {
                if (index == null) {
                    Text(
                        stringResource(R.string.health_calendar_empty), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                } else {
                    val value = values[index]
                    if (value != null) BigNumber(texts[index]!!)
                    else Text(noValue, style = MaterialTheme.typography.titleMedium, color = scheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
                    val note = calendarNote(kind, value, usual, target)
                    Text(
                        listOfNotNull(longDate(month.atDay(index + 1)), note).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1,
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
            for (index in 0 until 7) {
                Text(
                    DayOfWeek.of(index + 1).getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                )
            }
        }
        val lead = month.atDay(1).dayOfWeek.value - DayOfWeek.MONDAY.value
        val cells: List<Int?> = List(lead) { null } + List(length) { it }
        Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(TILE_GAP)) {
            cells.chunked(7).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                    row.forEach { index ->
                        Box(Modifier.weight(1f).aspectRatio(1f)) {
                            if (index != null) {
                                val date = month.atDay(index + 1)
                                HeatTile(
                                    number = index + 1,
                                    level = levels[index],
                                    heart = kind == CalendarKind.HEART,
                                    met = CalendarHeat.met(values[index], target),
                                    today = index == todayIndex,
                                    picked = shown == index,
                                    later = date.isAfter(today),
                                    description = "${longDate(date)}, ${texts[index] ?: noValue}",
                                    onClick = { picked = if (picked == index) null else index },
                                )
                            }
                        }
                    }
                    repeat(7 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        CalendarLegend(kind, usual, Modifier.padding(top = 14.dp))
    }
}

private val TILE_GAP = 5.dp

private fun contrast(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}
private val TileShape = RoundedCornerShape(12.dp)

@Composable
private fun HeatTile(number: Int, level: Float?, heart: Boolean, met: Boolean, today: Boolean, picked: Boolean, later: Boolean, description: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    if (later) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("$number", style = MaterialTheme.typography.labelLarge, color = scheme.onSurface.copy(alpha = 0.35f))
        }
        return
    }
    val strength = kotlin.math.abs(level ?: 0f).coerceIn(0f, 1f)
    val warm = heart && (level ?: 0f) > 0f
    val hue = if (warm) scheme.error else scheme.primary
    val neutral = scheme.surfaceContainerHighest
    val tile = if (level == null) neutral else hue.copy(alpha = strength).compositeOver(neutral)
    val fill by animateColorAsState(tile, TandemMotion.colourSpec(), label = "tileFill")
    // The number takes whichever reads better on the tile as it is: a fixed threshold left dark digits on mid tones in dark mode.
    val onHue = if (warm) scheme.onError else scheme.onPrimary
    val ink by animateColorAsState(
        when {
            level == null -> scheme.onSurfaceVariant
            contrast(onHue, tile) > contrast(scheme.onSurface, tile) * 1.25f -> onHue
            else -> scheme.onSurface
        },
        TandemMotion.colourSpec(), label = "tileInk",
    )
    val scale by animateFloatAsState(if (picked) 1.06f else 1f, TandemMotion.spatial(), label = "tilePicked")
    val ring by animateColorAsState(
        when {
            picked -> scheme.onSurface
            today -> scheme.primary
            else -> Color.Transparent
        },
        TandemMotion.colourSpec(), label = "tileRing",
    )
    Box(
        Modifier.fillMaxSize()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(TileShape)
            .background(fill)
            .border(if (picked) 2.dp else 1.5.dp, ring, TileShape)
            .bouncyClickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "$number", style = MaterialTheme.typography.labelLarge, color = ink,
            fontWeight = if (today || met) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun CalendarLegend(kind: CalendarKind, usual: Long?, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val caption = MaterialTheme.typography.labelSmall
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (kind == CalendarKind.HEART) {
            Text(stringResource(R.string.health_calendar_lower), style = caption, color = scheme.onSurfaceVariant)
            Box(
                Modifier.width(56.dp).height(8.dp).clip(CircleShape)
                    .background(Brush.horizontalGradient(listOf(scheme.primary, scheme.surfaceContainerHighest, scheme.error))),
            )
            Text(stringResource(R.string.health_calendar_higher), style = caption, color = scheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            if (usual != null) {
                Text(stringResource(R.string.health_calendar_usual, stringResource(R.string.health_resting_line, usual.toInt())), style = caption, color = scheme.onSurfaceVariant)
            }
        } else {
            Text(stringResource(R.string.health_calendar_less), style = caption, color = scheme.onSurfaceVariant)
            listOf(0.15f, 0.43f, 0.72f, 1f).forEach { alpha ->
                Box(Modifier.size(12.dp).clip(RoundedCornerShape(4.dp)).background(scheme.surfaceContainerHighest).background(scheme.primary.copy(alpha = alpha)))
            }
            Text(stringResource(R.string.health_calendar_more), style = caption, color = scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun calendarValue(kind: CalendarKind, value: Long): String = when (kind) {
    CalendarKind.SLEEP -> duration(value.toInt())
    CalendarKind.ACTIVITY -> number(value)
    CalendarKind.HEART -> stringResource(R.string.health_resting_line, value.toInt())
}

/** One short line beside the date: for the heart how the day compares with the usual of its month, for activity whether the goal was met. */
@Composable
private fun calendarNote(kind: CalendarKind, value: Long?, usual: Long?, goal: Long?): String? {
    if (value == null) return null
    return when (kind) {
        CalendarKind.HEART -> usual?.let {
            val off = (value - it).toInt()
            when {
                off > 1 -> stringResource(R.string.health_calendar_above, off)
                off < -1 -> stringResource(R.string.health_calendar_below, -off)
                else -> stringResource(R.string.health_calendar_usual_day)
            }
        }
        CalendarKind.ACTIVITY -> if (CalendarHeat.met(value, goal)) stringResource(R.string.health_calendar_goal_met) else null
        CalendarKind.SLEEP -> null
    }
}

private fun longDate(date: LocalDate): String =
    date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())).replaceFirstChar { it.titlecase(Locale.getDefault()) }

private fun hourOf(hour: Int) = "%02d:00".format(hour % 24)

/** The hours of a day as bars from midnight on, so a day shows when it was walked. A tap picks an hour; another tap lets it go. */
@Composable
internal fun HourBars(values: List<Int>, selected: Int?, onSelect: (Int?) -> Unit, description: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val current by androidx.compose.runtime.rememberUpdatedState(selected)
    val select by androidx.compose.runtime.rememberUpdatedState(onSelect)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(
            Modifier.fillMaxWidth().height(76.dp)
                .semantics { contentDescription = description }
                .pointerInput(values.size) {
                    detectTapGestures { tap ->
                        val index = (tap.x / (size.width.toFloat() / values.size)).toInt().coerceIn(0, values.lastIndex)
                        select(if (current == index) null else index)
                    }
                },
        ) {
            val slot = size.width / values.size
            val bar = kotlin.math.min(slot * 0.6f, 10.dp.toPx())
            val top = (values.maxOrNull() ?: 0).coerceAtLeast(1) * 1.08f
            values.forEachIndexed { index, value ->
                val left = slot * index + (slot - bar) / 2
                if (value <= 0) {
                    drawRoundRect(scheme.surfaceContainerHighest, Offset(left, size.height - 4.dp.toPx()), Size(bar, 4.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
                    return@forEachIndexed
                }
                val full = kotlin.math.max(value / top * size.height, 4.dp.toPx())
                val alpha = if (selected != null && selected != index) 0.28f else 1f
                drawRoundRect(scheme.primary.copy(alpha = alpha), Offset(left, size.height - full), Size(bar, full), androidx.compose.ui.geometry.CornerRadius(kotlin.math.min(bar, full) / 2))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf(0, 6, 12, 18, 24).forEachIndexed { index, hour ->
                Text(
                    hourOf(hour), fontSize = 10.sp, color = scheme.onSurfaceVariant,
                    textAlign = when (index) { 0 -> TextAlign.Start; 4 -> TextAlign.End; else -> TextAlign.Center },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * The steps of today by the hour, live from the watch, in place of the week on the page of today: the day as it goes, a tap on an hour
 * shows its steps. Without the watch the last whole day the core knows, named by its date.
 */
@Composable
internal fun StepsByHourCard(index: Int, total: Int, hours: List<Int>, totalSteps: Long, caption: String) {
    var picked by androidx.compose.runtime.remember(hours.size) { androidx.compose.runtime.mutableStateOf<Int?>(null) }
    val scheme = MaterialTheme.colorScheme
    HealthCard(index, total) {
        CardTitle(stringResource(R.string.health_steps_title), stringResource(R.string.health_more_steps_by_hour))
        BigNumber(number(picked?.let { hours[it].toLong() } ?: totalSteps), Modifier.padding(top = 4.dp))
        Text(
            picked?.let { stringResource(R.string.health_night_span, hourOf(it), hourOf(it + 1)) } ?: caption,
            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
        )
        HourBars(hours, picked, { picked = it }, stringResource(R.string.health_steps_by_hour), Modifier.padding(top = 12.dp))
    }
}
