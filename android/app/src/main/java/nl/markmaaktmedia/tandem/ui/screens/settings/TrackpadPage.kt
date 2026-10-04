package nl.markmaaktmedia.tandem.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.data.TandemPrefs
import nl.markmaaktmedia.tandem.ui.components.ActionRow
import nl.markmaaktmedia.tandem.ui.components.ContentRow
import nl.markmaaktmedia.tandem.ui.components.SettingsGroup
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import kotlin.math.roundToInt

/** The trackpad: how fast the pointer and the scrolling follow the fingers, and how soon a held finger starts a drag. */
@Composable
internal fun TrackpadPage(onBack: () -> Unit) {
    val prefs = LocalContext.current.graph.prefs
    val scope = rememberCoroutineScope()
    val speed by prefs.padSpeed.collectAsState(initial = TandemPrefs.DEFAULT_PAD_SPEED.toInt())
    val scroll by prefs.padScroll.collectAsState(initial = TandemPrefs.DEFAULT_PAD_SCROLL.toInt())
    val hold by prefs.padHoldMs.collectAsState(initial = TandemPrefs.DEFAULT_PAD_HOLD_MS.toInt())

    SettingsPageFrame(stringResource(R.string.settings_cat_trackpad), onBack) {
        Spacer(Modifier.height(16.dp))
        SettingsGroup {
            SettingsTarget(FocusKeys.PadSpeed, 0, 3) {
                PadSlider(
                    0, 3, stringResource(R.string.pad_speed), stringResource(R.string.pad_speed_sub),
                    value = speed, range = 30..300, step = 10, label = { stringResource(R.string.pad_percent, it) },
                ) { scope.launch { prefs.setPadSpeed(it) } }
            }
            SettingsTarget(FocusKeys.PadScroll, 1, 3) {
                PadSlider(
                    1, 3, stringResource(R.string.pad_scroll), stringResource(R.string.pad_scroll_sub),
                    value = scroll, range = 30..300, step = 10, label = { stringResource(R.string.pad_percent, it) },
                ) { scope.launch { prefs.setPadScroll(it) } }
            }
            SettingsTarget(FocusKeys.PadHold, 2, 3) {
                PadSlider(
                    2, 3, stringResource(R.string.pad_hold), stringResource(R.string.pad_hold_sub),
                    value = hold, range = 120..800, step = 20, label = { stringResource(R.string.pad_ms, it) },
                ) { scope.launch { prefs.setPadHoldMs(it) } }
            }
        }
        Spacer(Modifier.height(12.dp))
        SettingsGroup {
            ActionRow(0, 1, TandemIcons.Refresh, stringResource(R.string.pad_reset), stringResource(R.string.pad_reset_sub), { scope.launch { prefs.resetPad() } })
        }
    }
}

/** A row with a title, the value on the right, a line of explanation and a slider that ticks at every step. */
@Composable
private fun PadSlider(
    index: Int,
    total: Int,
    title: String,
    body: String,
    value: Int,
    range: IntRange,
    step: Int,
    label: @Composable (Int) -> String,
    onCommit: (Int) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    var moved by remember(value) { mutableFloatStateOf(value.toFloat()) }
    var lastStep by remember(value) { mutableFloatStateOf(value.toFloat()) }
    fun snap(raw: Float) = ((raw / step).roundToInt() * step).coerceIn(range.first, range.last)
    ContentRow(index, total, TandemIcons.Mouse, title) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(label(snap(moved)), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            Slider(
                value = moved,
                onValueChange = {
                    moved = it
                    val now = snap(it)
                    if (now.toFloat() != lastStep) {
                        lastStep = now.toFloat()
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                    }
                },
                onValueChangeFinished = {
                    onCommit(snap(moved))
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                },
                valueRange = range.first.toFloat()..range.last.toFloat(),
            )
        }
    }
}
