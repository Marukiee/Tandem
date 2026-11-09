package nl.markmaaktmedia.tandem.ui.screens.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
 * A month as a calendar, a day per box, Monday first: for sleep how long the night was with a ring toward seven hours, for activity the
 * steps with a ring toward the goal that is filled on a day it was reached, for the heart the mean heart rate of the day.
 */
@Composable
internal fun MonthCalendar(kind: CalendarKind, goal: Int? = null) {
    var back by rememberSaveable { mutableIntStateOf(0) }
    val zone = ZoneId.systemDefault()
    val month = YearMonth.now(zone).minusMonths(back.toLong())
    val length = month.lengthOfMonth()
    val range = rememberRange(month.atDay(length).atTime(12, 0).atZone(zone).toInstant().toEpochMilli(), length)
    val lead = month.atDay(1).dayOfWeek.value - DayOfWeek.MONDAY.value
    val today = LocalDate.now(zone)
    val scheme = MaterialTheme.colorScheme
    SettingsGroup {
        HealthCard(0, 1) {
            CardTitle(stringResource(R.string.health_calendar), stringResource(kind.info))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    month.atDay(1).format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault())).replaceFirstChar { it.titlecase(Locale.getDefault()) },
                    style = MaterialTheme.typography.titleSmall, color = scheme.onSurface, modifier = Modifier.weight(1f),
                )
                Pager(back, { back = it }, R.string.health_month_before, R.string.health_month_after)
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                for (index in 0 until 7) {
                    Text(
                        DayOfWeek.of(index + 1).getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                        style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant,
                        textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                    )
                }
            }
            val cells: List<Pair<Int, TandemWeekDay>?> = List(lead) { null } + range?.days.orEmpty().mapIndexed { index, day -> index + 1 to day }
            cells.chunked(7).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    row.forEach { cell ->
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            if (cell != null) {
                                val date = month.atDay(cell.first)
                                DayCell(cell.first, cell.second, kind, goal, date == today, date.isAfter(today))
                            }
                        }
                    }
                    repeat(7 - row.size) { Spacer(Modifier.weight(1f)) }
                }
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
private fun DayCell(number: Int, day: TandemWeekDay, kind: CalendarKind, goal: Int?, today: Boolean, later: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val share: Float?
    val value: String
    when (kind) {
        CalendarKind.SLEEP -> {
            val minutes = day.sleepMinutes?.toInt()
            share = minutes?.let { (it / 420f).coerceAtMost(1f) }
            value = minutes?.let { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" }.orEmpty()
        }
        CalendarKind.ACTIVITY -> {
            val steps = day.steps?.toLong()
            share = if (steps != null && goal != null && goal > 0) (steps.toFloat() / goal).coerceAtMost(1f) else null
            value = steps?.let { if (it >= 1000) String.format(Locale.getDefault(), "%.1fk", it / 1000f) else "$it" }.orEmpty()
        }
        CalendarKind.HEART -> {
            share = null
            value = day.heartMean?.let { stringResource(R.string.health_resting_line, it.toInt()) }.orEmpty()
        }
    }
    val met = share != null && share >= 1f
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.alpha(if (later) 0.35f else 1f)) {
        Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
            if (met) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(scheme.primary))
            } else if (share != null) {
                // Toward the goal of the page as a ring, so a month shows at a glance which days got there.
                Canvas(Modifier.size(34.dp)) {
                    val stroke = 3.dp.toPx()
                    val inset = stroke / 2
                    val arc = Size(size.width - stroke, size.height - stroke)
                    drawArc(scheme.surfaceContainerHighest, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
                    drawArc(scheme.primary, -90f, 360f * share, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
                }
            }
            Text(
                "$number", fontSize = 12.sp,
                fontWeight = if (today || met) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    met -> scheme.onPrimary
                    today -> scheme.primary
                    else -> scheme.onSurface
                },
            )
        }
        Text(value, fontSize = 10.sp, color = scheme.onSurfaceVariant, maxLines = 1)
    }
}
