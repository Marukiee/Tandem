package nl.markmaaktmedia.tandem.ui.screens.settings

import android.text.format.DateUtils
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.padding
import kotlin.math.roundToInt
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.ui.theme.TandemMotion
import uniffi.tandem_core.TandemHealthRecord
import uniffi.tandem_core.TandemSleepStage
import kotlin.math.max
import kotlin.math.min

private val BarLabels = DateTimeFormatter.ofPattern("EEEEE")

/** Comes up once when the page opens, so a chart reads as something measured and not as a picture. */
@Composable
private fun rememberReveal(): Float {
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) { reveal.animateTo(1f, TandemMotion.spatial()) }
    return reveal.value.coerceIn(0f, 1.1f)
}

/** The first letter of each day, with today in a circle. Half a day is added so a change of the clock can not move a day. */
@Composable
private fun DayLetters(days: List<Long>, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth()) {
        days.forEachIndexed { index, day ->
            val letter = Instant.ofEpochMilli(day + DateUtils.DAY_IN_MILLIS / 2).atZone(ZoneId.systemDefault()).format(BarLabels)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(20.dp).clip(CircleShape).then(if (index == days.lastIndex) Modifier.background(scheme.surfaceContainerHighest) else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(letter, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/**
 * Seven days as rounded bars, with a line for a goal when there is one. A day without a measurement gets a low grey stripe: not zero,
 * because nothing is known about it. Tapping a bar picks that day.
 */
@Composable
internal fun WeekBars(
    values: List<Float?>,
    days: List<Long>,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    goal: Float? = null,
    height: Dp = 76.dp,
) {
    val scheme = MaterialTheme.colorScheme
    val reveal = rememberReveal()
    val currentSelected by rememberUpdatedState(selected)
    val currentSelect by rememberUpdatedState(onSelect)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .semantics { contentDescription = description }
                .pointerInput(values.size) {
                    detectTapGestures { tap ->
                        val index = (tap.x / (size.width.toFloat() / values.size)).toInt().coerceIn(0, values.lastIndex)
                        currentSelect(if (currentSelected == index) null else index)
                    }
                },
        ) {
            val slot = size.width / values.size
            val barWidth = min(slot * 0.74f, 22.dp.toPx())
            val top = max(values.maxOfOrNull { it ?: 0f } ?: 0f, goal ?: 0f).coerceAtLeast(1f) * 1.08f
            val least = 8.dp.toPx()
            values.forEachIndexed { index, value ->
                val left = slot * index + (slot - barWidth) / 2
                val dimmed = selected != null && selected != index
                if (value == null) {
                    drawRoundRect(
                        scheme.surfaceContainerHighest,
                        Offset(left, size.height - 5.dp.toPx()),
                        Size(barWidth, 5.dp.toPx()),
                        CornerRadius(2.5.dp.toPx()),
                    )
                    return@forEachIndexed
                }
                val full = max(value / top * size.height, least) * reveal
                val reached = goal != null && value >= goal
                // The picked day full, whether or not it reached the goal: at half strength it stood out from the others only barely.
                val alpha = when {
                    dimmed -> 0.28f
                    selected == index || goal == null || reached -> 1f
                    else -> 0.5f
                }
                drawRoundRect(
                    scheme.primary.copy(alpha = alpha),
                    Offset(left, size.height - full),
                    Size(barWidth, full),
                    CornerRadius(min(barWidth, full) / 2),
                )
                if (reached && full > barWidth) {
                    val c = Offset(left + barWidth / 2, size.height - full + barWidth / 2)
                    val s = barWidth * 0.2f
                    val check = Path().apply {
                        moveTo(c.x - s, c.y)
                        lineTo(c.x - s * 0.25f, c.y + s * 0.75f)
                        lineTo(c.x + s, c.y - s * 0.7f)
                    }
                    drawPath(check, scheme.onPrimary.copy(alpha = alpha), style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
            if (goal != null) {
                val y = size.height - goal / top * size.height
                drawLine(scheme.primary.copy(alpha = 0.55f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
            }
        }
        DayLetters(days)
    }
}

/** Seven days as a line, for what never comes near zero and so would say nothing as bars. */
@Composable
internal fun WeekLine(
    values: List<Float?>,
    days: List<Long>,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    usual: Float? = null,
    height: Dp = 76.dp,
) {
    val scheme = MaterialTheme.colorScheme
    val reveal = rememberReveal()
    val surface = scheme.surfaceContainer
    val currentSelected by rememberUpdatedState(selected)
    val currentSelect by rememberUpdatedState(onSelect)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .semantics { contentDescription = description }
                .pointerInput(values.size) {
                    detectTapGestures { tap ->
                        val index = (tap.x / (size.width.toFloat() / values.size)).toInt().coerceIn(0, values.lastIndex)
                        currentSelect(if (currentSelected == index) null else index)
                    }
                },
        ) {
            val known = values.filterNotNull() + listOfNotNull(usual)
            if (known.isEmpty()) return@Canvas
            val low = known.min() - 3f
            val high = known.max() + 3f
            val pad = 8.dp.toPx()
            val slot = size.width / values.size
            fun x(index: Int) = slot * index + slot / 2
            fun y(value: Float) = pad + (1f - (value - low) / (high - low)) * (size.height - 2 * pad)
            if (usual != null) {
                drawLine(scheme.outline, Offset(0f, y(usual)), Offset(size.width, y(usual)), 1.dp.toPx())
            }
            clipRect(right = size.width * reveal) {
                val line = Path()
                var started = false
                values.forEachIndexed { index, value ->
                    if (value == null) return@forEachIndexed
                    if (started) line.lineTo(x(index), y(value)) else line.moveTo(x(index), y(value))
                    started = true
                }
                drawPath(line, scheme.primary, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                values.forEachIndexed { index, value ->
                    if (value == null) return@forEachIndexed
                    val big = selected == index
                    val center = Offset(x(index), y(value))
                    drawCircle(surface, (if (big) 7.dp else 5.5.dp).toPx(), center)
                    drawCircle(scheme.primary, (if (big) 5.dp else 3.5.dp).toPx(), center)
                }
            }
        }
        DayLetters(days)
    }
}

/**
 * From falling asleep to getting up, night by night: one look shows whether the nights start and end at the same time. Early at the
 * top. [beds] and [wakes] are minutes from the midnight of each day, negative for the evening before.
 */
@Composable
internal fun SleepWindows(
    beds: List<Int?>,
    wakes: List<Int?>,
    days: List<Long>,
    description: String,
    modifier: Modifier = Modifier,
    height: Dp = 112.dp,
) {
    val scheme = MaterialTheme.colorScheme
    val reveal = rememberReveal()
    val first = beds.filterNotNull().minOrNull() ?: return
    val last = wakes.filterNotNull().maxOrNull() ?: return
    // Whole hours, so the lines fall on times that read easily.
    val from = Math.floorDiv(first, 60) * 60
    val until = (Math.floorDiv(last - 1, 60) + 1) * 60
    val middle = from + (until - from) / 120 * 60
    val marks = listOf(from, middle, until).distinct()
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.width(44.dp).height(height), verticalArrangement = Arrangement.SpaceBetween) {
            marks.forEach { mark ->
                Text(clockOf(mark), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Canvas(Modifier.fillMaxWidth().height(height).semantics { contentDescription = description }) {
                val span = (until - from).coerceAtLeast(60).toFloat()
                fun y(minutes: Int) = (minutes - from) / span * size.height
                marks.forEach { mark ->
                    drawLine(scheme.outlineVariant.copy(alpha = 0.5f), Offset(0f, y(mark)), Offset(size.width, y(mark)), 1.dp.toPx())
                }
                val slot = size.width / days.size
                val barWidth = min(slot * 0.62f, 20.dp.toPx())
                beds.forEachIndexed { index, bed ->
                    val wake = wakes.getOrNull(index)
                    if (bed == null || wake == null) return@forEachIndexed
                    val top = y(bed)
                    val whole = y(wake) - top
                    drawRoundRect(
                        scheme.primary,
                        Offset(slot * index + (slot - barWidth) / 2, top),
                        Size(barWidth, max(whole * reveal, barWidth)),
                        CornerRadius(barWidth / 2),
                    )
                }
            }
            DayLetters(days)
        }
    }
}

/** Minutes from a midnight as a time of day. */
private fun clockOf(minutes: Int): String {
    val inDay = Math.floorMod(minutes, 1440)
    return java.time.LocalTime.of(inDay / 60, inDay % 60).format(DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT))
}

/**
 * A day of heart rate from midnight on, one point per five minutes. A gap of more than half an hour stays a gap: the watch was off the
 * wrist, and a line through it would claim otherwise.
 *
 * As the night can be: a finger sliding over it shows the time and the heart rate under it, and two fingers zoom in on a part of the
 * day and move along it, so one moment can be looked at closely. The scale follows what is in view, so a small change shows when zoomed.
 */
@Composable
internal fun DayHeartLine(curve: List<Int>, description: String, modifier: Modifier = Modifier, height: Dp = 96.dp) {
    val scheme = MaterialTheme.colorScheme
    val reveal = rememberReveal()
    val surface = scheme.surfaceContainer
    if (curve.size < 2 || curve.none { it > 0 }) return
    var finger by remember { mutableStateOf<Float?>(null) }
    var width by remember { mutableStateOf(1f) }
    // The part of the day in view: from [from], a share of the day, over 1 / [zoom] of it.
    var zoom by remember { mutableStateOf(1f) }
    var from by remember { mutableStateOf(0f) }
    val last = curve.size - 1
    val span = 1f / zoom

    // A time on the axis, on a round five minutes and the whole day 0:00 to 0:00, so the labels read 6:00 and not 5:58.
    fun minuteAt(share: Float) = (share * DAY_MINUTES / HEART_SLOT_MINUTES).roundToInt() * HEART_SLOT_MINUTES
    // The measured point nearest to a share of the day, within a quarter of an hour, or none where the watch was off.
    fun pointAt(share: Float): Int? {
        val at = (share * last).roundToInt().coerceIn(0, last)
        return (0..HEART_NEAR_SLOTS).asSequence()
            .flatMap { sequenceOf(at - it, at + it) }
            .firstOrNull { it in 0..last && curve[it] > 0 }
    }

    val heading = finger?.let { x ->
        val share = from + (x / width).coerceIn(0f, 1f) * span
        val point = pointAt(share)
        if (point != null) "${clockOf(point * HEART_SLOT_MINUTES)} · ${stringResource(R.string.health_resting_line, curve[point])}"
        else "${clockOf(minuteAt(share))} · ${stringResource(R.string.health_nothing)}"
    } ?: if (zoom > 1.01f) {
        stringResource(R.string.health_night_span, clockOf(minuteAt(from)), clockOf(minuteAt(from + span)))
    } else {
        stringResource(R.string.health_heart_hint)
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(heading, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.weight(1f))
            if (zoom > 1.01f) {
                Text(
                    stringResource(R.string.health_heart_whole_day),
                    style = MaterialTheme.typography.labelMedium, color = scheme.primary,
                    modifier = Modifier.clip(CircleShape).clickable {
                        zoom = 1f
                        from = 0f
                    }.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .semantics { contentDescription = description }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        finger = down.position.x
                        var pinched = false
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.size >= 2) {
                                // Two fingers: zoom around the point between them, and move along the day as they move.
                                pinched = true
                                finger = null
                                val w = size.width.toFloat()
                                val centre = event.calculateCentroid(useCurrent = true).x / w
                                val under = from + centre / zoom
                                zoom = (zoom * event.calculateZoom()).coerceIn(1f, HEART_MAX_ZOOM)
                                from = (under - centre / zoom - event.calculatePan().x / w / zoom).coerceIn(0f, 1f - 1f / zoom)
                                event.changes.forEach { it.consume() }
                            } else if (pressed.size == 1 && !pinched) {
                                val change = pressed.first()
                                finger = change.position.x
                                // Sideways is this chart's, up and down stays the page's.
                                val move = change.position - change.previousPosition
                                if (kotlin.math.abs(move.x) > kotlin.math.abs(move.y)) change.consume()
                            }
                        } while (event.changes.any { it.pressed })
                        finger = null
                    }
                },
        ) {
            width = size.width
            val first = (from * last).toInt().coerceIn(0, last)
            val end = ((from + span) * last).toInt().plus(1).coerceIn(0, last)
            val inView = (first..end).map { curve[it] }.filter { it > 0 }.ifEmpty { curve.filter { it > 0 } }
            val low = inView.min() - 4f
            val high = inView.max() + 4f
            val pad = 6.dp.toPx()
            fun x(index: Int) = (index / last.toFloat() - from) * zoom * size.width
            fun y(value: Int) = pad + (1f - (value - low) / (high - low)) * (size.height - 2 * pad)
            for (quarter in 0..4) {
                val at = size.width * quarter / 4
                drawLine(scheme.outlineVariant.copy(alpha = 0.4f), Offset(at, 0f), Offset(at, size.height), 1.dp.toPx())
            }
            clipRect(right = size.width * reveal) {
                val line = Path()
                var lastIndex = -100
                var lastPoint = -1
                curve.forEachIndexed { index, value ->
                    if (value <= 0) return@forEachIndexed
                    if (index - lastIndex > MAX_HEART_GAP_SLOTS) line.moveTo(x(index), y(value)) else line.lineTo(x(index), y(value))
                    lastIndex = index
                    lastPoint = index
                }
                drawPath(line, scheme.primary, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                val marked = finger?.let { pointAt(from + (it / size.width).coerceIn(0f, 1f) * span) }
                val dot = marked ?: lastPoint.takeIf { it >= 0 && finger == null }
                if (dot != null) {
                    val at = Offset(x(dot), y(curve[dot]))
                    drawCircle(surface, 6.dp.toPx(), at)
                    drawCircle(scheme.primary, 4.dp.toPx(), at)
                }
            }
            finger?.let { fx ->
                val at = fx.coerceIn(0f, size.width)
                drawLine(scheme.onSurface, Offset(at, 0f), Offset(at, size.height), 1.5.dp.toPx())
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            // The whole day in hours, or the times at the edges and quarters of the part in view.
            (0..4).forEach { quarter ->
                Text(
                    clockOf(minuteAt(from + span * quarter / 4)),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private const val HEART_SLOT_MINUTES = 5
private const val DAY_MINUTES = 24 * 60
private const val HEART_NEAR_SLOTS = 3
private const val HEART_MAX_ZOOM = 12f

private const val MAX_HEART_GAP_SLOTS = 6

private val Lanes = listOf(TandemSleepStage.WAKE, TandemSleepStage.REM, TandemSleepStage.LIGHT, TandemSleepStage.DEEP)

/**
 * The night as it went: which stage when, on four lanes with the deepest at the bottom. Sliding a finger over it says what the model
 * thought of that moment, so a person can hold it against what they remember.
 */
@Composable
internal fun SleepTimeline(record: TandemHealthRecord, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val reveal = rememberReveal()
    val runs = record.stages
    val total = runs.sumOf { it.seconds.toLong() }.toFloat()
    if (total <= 0f) return
    val colors = Lanes.associateWith { stageColor(it) }
    val names = mapOf(
        TandemSleepStage.WAKE to stringResource(R.string.health_stage_awake),
        TandemSleepStage.REM to stringResource(R.string.health_stage_rem),
        TandemSleepStage.LIGHT to stringResource(R.string.health_stage_light),
        TandemSleepStage.DEEP to stringResource(R.string.health_stage_deep),
    )
    var finger by remember { mutableStateOf<Float?>(null) }
    var width by remember { mutableStateOf(1f) }

    val time = { ms: Long -> DateUtils.formatDateTime(context, ms, DateUtils.FORMAT_SHOW_TIME) }
    val start = record.startMs.toLong()
    val end = record.endMs.toLong()
    val heading = finger?.let { x ->
        val share = (x / width).coerceIn(0f, 1f)
        var seconds = share * total
        val run = runs.firstOrNull { candidate ->
            seconds -= candidate.seconds.toFloat()
            seconds <= 0f
        }
        val at = start + ((end - start) * share).toLong()
        val name = names[run?.stage] ?: stringResource(R.string.health_nothing)
        "${time(at)} · $name"
    } ?: stringResource(R.string.health_night_span, time(start), time(end))

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(heading, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.width(44.dp).height(LANE_HEIGHT * Lanes.size)) {
                Lanes.forEach { lane ->
                    Box(Modifier.height(LANE_HEIGHT), contentAlignment = Alignment.CenterStart) {
                        Text(names.getValue(lane), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
            Canvas(
                Modifier
                    .weight(1f)
                    .height(LANE_HEIGHT * Lanes.size)
                    .semantics { contentDescription = heading }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            finger = down.position.x
                            do {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (change.pressed) {
                                    finger = change.position.x
                                    // Sideways is this chart's, up and down stays the page's.
                                    val move = change.position - change.previousPosition
                                    if (kotlin.math.abs(move.x) > kotlin.math.abs(move.y)) change.consume()
                                }
                            } while (event.changes.any { it.pressed })
                            finger = null
                        }
                    },
            ) {
                width = size.width
                val lane = size.height / Lanes.size
                val bar = lane * 0.62f
                Lanes.indices.forEach { index ->
                    val y = lane * index + lane / 2
                    drawLine(scheme.outlineVariant.copy(alpha = 0.35f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                }
                clipRect(right = size.width * reveal) {
                    var seconds = 0L
                    var before = -1
                    runs.forEach { run ->
                        val index = Lanes.indexOf(run.stage)
                        val x = seconds / total * size.width
                        val length = run.seconds.toFloat() / total * size.width
                        seconds += run.seconds.toLong()
                        if (index < 0) {
                            before = -1
                            return@forEach
                        }
                        val y = lane * index + lane / 2
                        if (before >= 0 && before != index) {
                            val from = lane * before + lane / 2
                            drawLine(scheme.outline.copy(alpha = 0.5f), Offset(x, from), Offset(x, y), 1.dp.toPx())
                        }
                        drawRoundRect(
                            colors.getValue(run.stage),
                            Offset(x, y - bar / 2),
                            Size(max(length, 2.dp.toPx()), bar),
                            CornerRadius(min(bar, max(length, 2.dp.toPx())) / 2),
                        )
                        before = index
                    }
                }
                finger?.let { x ->
                    drawLine(scheme.onSurface, Offset(x.coerceIn(0f, size.width), 0f), Offset(x.coerceIn(0f, size.width), size.height), 1.5.dp.toPx())
                }
            }
        }
    }
}

private val LANE_HEIGHT = 30.dp

/** One part of a score, as a thin bar with its number: why it is what it is. */
@Composable
internal fun ScorePart(label: String, value: Int, color: Color = MaterialTheme.colorScheme.primary) {
    val scheme = MaterialTheme.colorScheme
    val reveal = rememberReveal()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.width(64.dp), maxLines = 1)
        Canvas(Modifier.weight(1f).height(8.dp)) {
            val radius = CornerRadius(size.height / 2)
            drawRoundRect(scheme.surfaceContainerHighest, cornerRadius = radius)
            val filled = max(size.width * value.coerceIn(0, 100) / 100f * reveal, size.height)
            drawRoundRect(color, size = Size(filled, size.height), cornerRadius = radius)
        }
        Text("$value", style = MaterialTheme.typography.labelMedium, color = scheme.onSurface, textAlign = TextAlign.End, modifier = Modifier.width(26.dp))
    }
}

/** How many of the nights something needs are already there. A row of dots says "this is coming" better than an empty card. */
@Composable
internal fun NightsToGo(needed: Int, total: Int, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val have = (total - needed).coerceIn(0, total)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(total) { index ->
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (index < have) scheme.primary else scheme.surfaceContainerHighest))
        }
    }
}
