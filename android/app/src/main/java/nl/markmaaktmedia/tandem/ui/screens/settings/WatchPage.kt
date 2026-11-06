package nl.markmaaktmedia.tandem.ui.screens.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.engine.FocusMode
import nl.markmaaktmedia.tandem.engine.Permissions
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.components.ActionRow
import nl.markmaaktmedia.tandem.ui.components.ContentRow
import nl.markmaaktmedia.tandem.ui.components.SettingsGroup
import nl.markmaaktmedia.tandem.ui.components.SwitchRow
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import nl.markmaaktmedia.tandem.ui.theme.TandemMotion
import nl.markmaaktmedia.tandem.watch.WatchState

private enum class WatchStage { Unpaired, Waiting, Code, Paired }

/** The watch: coupling it to this phone, and what it may do here. The watch itself only shows the code to compare. */
@Composable
internal fun WatchPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val watch = graph.watch
    val prefs = graph.prefs
    val scope = rememberCoroutineScope()
    val state by watch.state.collectAsState()
    val mouse by prefs.watchMouse.collectAsState(initial = false)
    val sleepQuiet by prefs.watchSleepQuiet.collectAsState(initial = true)

    var canQuiet by remember { mutableStateOf(FocusMode.canUse(context)) }
    var hasListener by remember { mutableStateOf(Permissions.notificationAccess(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        canQuiet = FocusMode.canUse(context)
        hasListener = Permissions.notificationAccess(context)
    }
    val askBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.values.all { it }) watch.beginPairing()
    }
    val tooOld = stringResource(R.string.watch_needs_android_13)

    SettingsPageFrame(stringResource(R.string.settings_cat_watch), onBack) {
        Spacer(Modifier.height(16.dp))
        val stage = when {
            state.code != null -> WatchStage.Code
            state.pairingOpen -> WatchStage.Waiting
            state.paired -> WatchStage.Paired
            else -> WatchStage.Unpaired
        }
        AnimatedContent(
            targetState = stage,
            transitionSpec = { fadeIn(TandemMotion.fadeSpec()) togetherWith fadeOut(TandemMotion.fadeSpec()) },
            label = "watchStage",
        ) { current ->
            Column {
                when (current) {
                    WatchStage.Unpaired -> SettingsGroup {
                        ActionRow(
                            0, 1, TandemIcons.Watch, stringResource(R.string.watch_pair), stringResource(R.string.watch_pair_sub),
                            onClick = {
                                when {
                                    !watch.supported -> Toast.makeText(context, tooOld, Toast.LENGTH_LONG).show()
                                    Permissions.bluetooth(context) -> watch.beginPairing()
                                    else -> askBluetooth.launch(Permissions.bluetoothPermissions)
                                }
                            },
                            modifier = if (watch.supported) Modifier else Modifier.alpha(0.6f),
                        )
                    }
                    WatchStage.Waiting -> SettingsGroup {
                        ActionRow(
                            0, 1, TandemIcons.Watch, stringResource(R.string.watch_waiting), stringResource(R.string.watch_waiting_sub),
                            onClick = watch::cancelPairing,
                            trailing = { Text(stringResource(R.string.watch_cancel), color = MaterialTheme.colorScheme.primary) },
                        )
                    }
                    WatchStage.Code -> SettingsGroup {
                        ContentRow(0, 1, TandemIcons.Watch, stringResource(R.string.watch_code_title)) {
                            Text(state.code.orEmpty(), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                Button(watch::confirmPairing, Modifier.weight(1f)) { Text(stringResource(R.string.watch_code_match)) }
                                OutlinedButton(watch::cancelPairing, Modifier.weight(1f)) { Text(stringResource(R.string.watch_code_differs)) }
                            }
                        }
                    }
                    WatchStage.Paired -> PairedGroups(state, mouse, sleepQuiet, canQuiet, hasListener) { on ->
                        scope.launch { prefs.setWatchMouse(on) }
                    }
                }
            }
        }
        HealthHistory()
    }
}

@Composable
private fun PairedGroups(
    state: WatchState,
    mouse: Boolean,
    sleepQuiet: Boolean,
    canQuiet: Boolean,
    hasListener: Boolean,
    onMouse: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val graph = context.graph
    val watch = graph.watch
    val scope = rememberCoroutineScope()
    SettingsGroup {
        ActionRow(
            0, 2, TandemIcons.Watch,
            stringResource(if (state.ready) R.string.watch_connected else R.string.watch_searching),
            state.batteryLevel?.takeIf { state.ready }?.let {
                stringResource(if (state.charging) R.string.watch_battery_charging else R.string.watch_battery, it)
            },
            onClick = {},
        )
        ActionRow(
            1, 2, TandemIcons.Ring,
            stringResource(if (state.ringing) R.string.watch_find_stop else R.string.watch_find),
            stringResource(R.string.watch_find_sub),
            onClick = {
                if (state.ready) watch.ringWatch(!state.ringing)
                else Toast.makeText(context, R.string.watch_not_connected, Toast.LENGTH_SHORT).show()
            },
            modifier = if (state.ready) Modifier else Modifier.alpha(0.6f),
        )
    }
    AnimatedVisibility(!hasListener) {
        Column {
            Spacer(Modifier.height(12.dp))
            SettingsGroup {
                ActionRow(
                    0, 1, TandemIcons.Notifications, stringResource(R.string.watch_needs_listener), stringResource(R.string.watch_needs_listener_sub),
                    onClick = { Permissions.openNotificationAccessSettings(context) },
                )
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    SettingsGroup {
        SettingsTarget(FocusKeys.WatchMouse, 0, 2) {
            SwitchRow(0, 2, TandemIcons.Mouse, stringResource(R.string.watch_mouse), stringResource(R.string.watch_mouse_sub), mouse, onMouse)
        }
        SettingsTarget(FocusKeys.WatchQuiet, 1, 2) {
            SwitchRow(
                1, 2, TandemIcons.Sleep, stringResource(R.string.watch_quiet), stringResource(R.string.watch_quiet_sub), sleepQuiet,
                { on -> scope.launch { graph.prefs.setWatchSleepQuiet(on) } },
                blocked = if (canQuiet) null else stringResource(R.string.watch_quiet_blocked),
                onBlocked = { runCatching { context.startActivity(FocusMode.accessIntent()) } },
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    SettingsGroup {
        ActionRow(0, 1, TandemIcons.Delete, stringResource(R.string.watch_unpair), stringResource(R.string.watch_unpair_sub), watch::unpair, danger = true)
    }
}
