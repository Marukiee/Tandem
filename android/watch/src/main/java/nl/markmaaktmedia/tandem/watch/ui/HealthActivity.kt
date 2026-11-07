package nl.markmaaktmedia.tandem.watch.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import kotlinx.coroutines.delay
import nl.markmaaktmedia.tandem.watch.R
import nl.markmaaktmedia.tandem.watch.link.LinkService
import nl.markmaaktmedia.tandem.watch.track.AutoSleep
import nl.markmaaktmedia.tandem.watch.track.HeartTracker
import nl.markmaaktmedia.tandem.watch.track.LastSleep
import nl.markmaaktmedia.tandem.watch.track.Tracking
import nl.markmaaktmedia.tandem.watch.track.TrackingKind
import nl.markmaaktmedia.tandem.watch.track.TrackingService
import nl.markmaaktmedia.tandem.watch.track.TrackingUi
import nl.markmaaktmedia.tandem.watch.ui.theme.TandemWatchTheme

/**
 * Sleep and workouts, with an icon of their own in the list of apps. Nobody opens the watch to steer a computer and to see how they
 * slept in one go, so they do not share a screen.
 */
class HealthActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Tracking.load(this)
        setContent {
            TandemWatchTheme {
                AppScaffold(timeText = { TimeText() }) { HealthScreen() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        LinkService.start(this)
    }
}

@Composable
internal fun HealthScreen() {
    val context = LocalContext.current
    val tracking by Tracking.ui.collectAsState()
    val list = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    var missing by remember { mutableStateOf(missingPermissions(context)) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        missing = missingPermissions(context)
        LinkService.start(context)
    }
    val askAccess = { request.launch(ASKED_PERMISSIONS) }
    var backgroundMissing by remember { mutableStateOf(backgroundHeartMissing(context)) }
    val requestBackground = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        backgroundMissing = backgroundHeartMissing(context)
        AutoSleep.refresh()
    }

    val rows: @Composable BoxScope.(PaddingValues) -> Unit = { padding ->
        TransformingLazyColumn(state = list, contentPadding = padding.withRoomAbove(), modifier = Modifier.fillMaxSize()) {
            // What is recording comes first: stopping it is why someone opens this then.
            tracking.active?.let { kind ->
                item(key = "recording") { TrackingButton(kind, tracking, askAccess, rimRow(spec), SurfaceTransformation(spec)) }
            }
            if (missing.isNotEmpty()) {
                item(key = "access") {
                    Button(
                        onClick = { askAccess() },
                        modifier = rimRow(spec),
                        transformation = SurfaceTransformation(spec),
                        label = { Text(stringResource(R.string.allow_access)) },
                    )
                }
            }
            if (tracking.active != TrackingKind.SLEEP) {
                tracking.lastSleep?.let { last ->
                    item(key = "last_night") { LastNight(last, rimContent(spec)) }
                }
            }
            if (tracking.heartAllDay && backgroundMissing && missing.isEmpty()) {
                item(key = "heart_access") {
                    Button(
                        onClick = { requestBackground.launch(BACKGROUND_HEART_PERMISSION) },
                        modifier = rimRow(spec),
                        transformation = SurfaceTransformation(spec),
                        label = { Text(stringResource(R.string.heart_allow)) },
                    )
                }
            }
            tracking.heartNow?.let { now ->
                item(key = "heart") {
                    Text(
                        stringResource(R.string.heart_today, now, tracking.heartLow ?: now, tracking.heartHigh ?: now),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = rimContent(spec).padding(vertical = 4.dp),
                    )
                }
            }
            tracking.lastNap?.let { nap ->
                item(key = "nap") {
                    Text(
                        stringResource(R.string.last_nap, durationText(nap.minutes)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = rimContent(spec).padding(vertical = 4.dp),
                    )
                }
            }
            item(key = "steps") {
                Text(
                    stringResource(R.string.steps_of_goal, tracking.stepsToday, tracking.stepGoal, tracking.activeMinutesToday),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = rimContent(spec).padding(vertical = 4.dp),
                )
            }
            if (tracking.active != TrackingKind.WORKOUT) {
                item(key = "workout") { TrackingButton(TrackingKind.WORKOUT, tracking, askAccess, rimRow(spec), SurfaceTransformation(spec)) }
            }
            item(key = "heart_all_day") {
                SwitchButton(
                    checked = tracking.heartAllDay,
                    onCheckedChange = { on -> HeartTracker.setEnabled(context, on, tellPhone = true) },
                    modifier = rimRow(spec),
                    transformation = SurfaceTransformation(spec),
                    label = { Text(stringResource(R.string.heart_all_day)) },
                )
            }
            item(key = "auto_sleep") {
                SwitchButton(
                    checked = tracking.autoSleep,
                    onCheckedChange = { on ->
                        AutoSleep.setEnabled(context, on)
                        if (on && !TrackingService.canRun(context)) askAccess()
                    },
                    modifier = rimRow(spec),
                    transformation = SurfaceTransformation(spec),
                    label = { Text(stringResource(R.string.auto_sleep)) },
                )
            }
        }
    }

    // Sleep is noticed by itself, so starting it by hand can wait at the end of the list.
    if (tracking.active == null) {
        ScreenScaffold(
            scrollState = list,
            edgeButton = {
                EdgeButton(
                    onClick = { toggleTracking(context, TrackingKind.SLEEP, active = false, askAccess) },
                    buttonSize = EdgeButtonSize.Medium,
                ) { Text(stringResource(R.string.sleep_start)) }
            },
            content = rows,
        )
    } else {
        ScreenScaffold(scrollState = list, content = rows)
    }
}

/** From Android 16 on: asked for apart, because the system only offers it once the heart rate itself is allowed. */
private const val BACKGROUND_HEART_PERMISSION = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"

private fun backgroundHeartMissing(context: Context) =
    Build.VERSION.SDK_INT >= 36 && context.checkSelfPermission(BACKGROUND_HEART_PERMISSION) != PackageManager.PERMISSION_GRANTED

/** How the last night went, on a cookie with the moon. The stages are numbers under each other: as a sentence they fill the screen. */
@Composable
private fun LastNight(last: LastSleep, modifier: Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier.padding(vertical = 4.dp)) {
        CookieIcon(R.drawable.sym_bedtime, size = 40.dp)
        Text(
            if (last.found) stringResource(R.string.sleep_last, durationText(last.minutes), last.efficiencyPercent) else stringResource(R.string.sleep_none),
            style = if (last.found) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall,
            color = if (last.found) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp),
        )
        last.stages?.let { stages ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 4.dp)) {
                StageColumn(R.string.stage_light, stages.light)
                StageColumn(R.string.stage_deep, stages.deep)
                StageColumn(R.string.stage_rem, stages.rem)
            }
        }
    }
}

@Composable
private fun StageColumn(label: Int, minutes: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.stage_time, minutes / 60, minutes % 60), style = MaterialTheme.typography.labelMedium)
    }
}

private fun toggleTracking(context: Context, kind: TrackingKind, active: Boolean, onNeedAccess: () -> Unit) {
    when {
        active -> {
            TrackingService.stop(context)
            if (kind == TrackingKind.SLEEP) AutoSleep.onStoppedByHand()
        }
        TrackingService.canRun(context) -> TrackingService.start(context, kind)
        else -> onNeedAccess()
    }
}

@Composable
internal fun TrackingButton(
    kind: TrackingKind,
    tracking: TrackingUi,
    onNeedAccess: () -> Unit,
    modifier: Modifier,
    transformation: androidx.wear.compose.material3.SurfaceTransformation,
) {
    val context = LocalContext.current
    val active = tracking.active == kind
    val otherActive = tracking.active != null && !active
    val icon = if (kind == TrackingKind.SLEEP) R.drawable.sym_bedtime else R.drawable.sym_monitoring
    val label = when {
        kind == TrackingKind.SLEEP && active -> R.string.sleep_stop
        kind == TrackingKind.SLEEP -> R.string.sleep_start
        active -> R.string.workout_stop
        else -> R.string.workout_start
    }
    val detail = when {
        active -> elapsedText(tracking.startedAtMs)
        kind == TrackingKind.SLEEP -> null
        else -> tracking.lastWorkout?.let { stringResource(R.string.workout_last, durationText(it.minutes), it.meanHeartRate) }
    }
    Button(
        onClick = { toggleTracking(context, kind, active, onNeedAccess) },
        modifier = modifier,
        transformation = transformation,
        colors = if (active) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
        // Two recordings at once would fight over the same sensors.
        enabled = !otherActive,
        icon = { Icon(painterResource(icon), null) },
        label = { Text(stringResource(label)) },
        secondaryLabel = detail?.let { text -> { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
    )
}

@Composable
private fun elapsedText(startedAtMs: Long): String {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAtMs) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    return durationText(((now - startedAtMs) / 60_000).toInt().coerceAtLeast(0))
}

@Composable
internal fun durationText(minutes: Int): String =
    if (minutes >= 60) stringResource(R.string.duration_hm, minutes / 60, minutes % 60)
    else stringResource(R.string.duration_m, minutes)
