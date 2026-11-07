package nl.markmaaktmedia.tandem.ui.screens.settings

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.launch
import androidx.compose.material3.OutlinedTextField
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.data.TandemPrefs
import nl.markmaaktmedia.tandem.ui.components.PrimaryPillButton
import nl.markmaaktmedia.tandem.ui.components.SecondaryPillButton
import nl.markmaaktmedia.tandem.ui.components.TandemDialog
import nl.markmaaktmedia.tandem.engine.FocusMode
import nl.markmaaktmedia.tandem.engine.Permissions
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.Route
import nl.markmaaktmedia.tandem.ui.components.ActionRow
import nl.markmaaktmedia.tandem.ui.components.ArrivingBattery
import nl.markmaaktmedia.tandem.ui.components.BatteryRing
import nl.markmaaktmedia.tandem.ui.components.DeviceGlyph
import nl.markmaaktmedia.tandem.ui.components.DeviceIconChoice
import nl.markmaaktmedia.tandem.ui.components.SectionHeader
import nl.markmaaktmedia.tandem.ui.components.SettingsGroup
import nl.markmaaktmedia.tandem.ui.components.StatusChip
import nl.markmaaktmedia.tandem.ui.components.SwitchRow
import nl.markmaaktmedia.tandem.ui.components.TandemIconButton
import nl.markmaaktmedia.tandem.ui.screens.battery
import nl.markmaaktmedia.tandem.ui.theme.CardSquircle
import nl.markmaaktmedia.tandem.ui.theme.LocalTandemExtraColors
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import nl.markmaaktmedia.tandem.ui.theme.TandemMotion
import nl.markmaaktmedia.tandem.watch.WatchState
import nl.markmaaktmedia.tandem.watch.WatchAppState
import nl.markmaaktmedia.tandem.ui.components.NoteRow
import uniffi.tandem_core.TandemPlatform

/**
 * The watch as a device, built like the page of a computer: what it is and how it is doing, then what it can do. Coupling one has its
 * own place, next to the other ways to pair a device.
 */
@Composable
internal fun WatchPage(onBack: () -> Unit, onOpen: (Route) -> Unit) {
    val state by LocalContext.current.graph.watch.state.collectAsState()
    if (state.paired) {
        PairedWatch(state, onBack, onOpen)
    } else {
        SettingsPageFrame(stringResource(R.string.settings_cat_watch), onBack) {
            Spacer(Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingsGroup {
                    ActionRow(0, 1, TandemIcons.Watch, stringResource(R.string.watch_pair), null, onClick = { onOpen(Route.PairWatch) })
                }
            }
        }
    }
}

@Composable
private fun PairedWatch(state: WatchState, onBack: () -> Unit, onOpen: (Route) -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val watch = graph.watch
    val prefs = graph.prefs
    val scope = rememberCoroutineScope()
    val mouse by prefs.watchMouse.collectAsState(initial = false)
    val sleepQuiet by prefs.watchSleepQuiet.collectAsState(initial = true)

    var canQuiet by remember { mutableStateOf(FocusMode.canUse(context)) }
    var renaming by remember { mutableStateOf(false) }
    var hasListener by remember { mutableStateOf(Permissions.notificationAccess(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        canQuiet = FocusMode.canUse(context)
        hasListener = Permissions.notificationAccess(context)
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TandemIconButton(TandemIcons.Back, stringResource(R.string.action_back), onBack)
        }
        WatchHeader(state)
        AnimatedVisibility(
            !hasListener,
            enter = expandVertically(TandemMotion.sizeSpring()) + fadeIn(TandemMotion.fadeSpec()),
            exit = shrinkVertically(TandemMotion.sizeSpring()) + fadeOut(TandemMotion.fadeSpec()),
        ) {
            SettingsGroup {
                ActionRow(
                    0, 1, TandemIcons.Notifications, stringResource(R.string.watch_needs_listener), stringResource(R.string.watch_needs_listener_sub),
                    onClick = { Permissions.openNotificationAccessSettings(context) },
                )
            }
        }

        WatchGlance { onOpen(Route.SettingsPage(SettingsPageId.Health)) }

        SectionHeader(stringResource(R.string.section_control), top = 12.dp, bottom = 0.dp)
        SettingsGroup {
            ActionRow(
                0, 4, TandemIcons.Watch, stringResource(R.string.settings_name), state.name ?: stringResource(R.string.settings_cat_watch),
                { renaming = true },
            )
            ActionRow(
                1, 4, TandemIcons.Ring,
                stringResource(if (state.ringing) R.string.watch_find_stop else R.string.watch_find),
                stringResource(R.string.watch_find_sub),
                onClick = {
                    if (state.ready) watch.ringWatch(!state.ringing)
                    else Toast.makeText(context, R.string.watch_not_connected, Toast.LENGTH_SHORT).show()
                },
                modifier = if (state.ready) Modifier else Modifier.alpha(0.6f),
            )
            SettingsTarget(FocusKeys.WatchMouse, 2, 4) {
                SwitchRow(
                    2, 4, TandemIcons.Mouse, stringResource(R.string.watch_mouse), stringResource(R.string.watch_mouse_sub), mouse,
                    { on -> scope.launch { prefs.setWatchMouse(on) } },
                )
            }
            SettingsTarget(FocusKeys.WatchQuiet, 3, 4) {
                SwitchRow(
                    3, 4, TandemIcons.Sleep, stringResource(R.string.watch_quiet), stringResource(R.string.watch_quiet_sub), sleepQuiet,
                    { on -> scope.launch { prefs.setWatchSleepQuiet(on) } },
                    blocked = if (canQuiet) null else stringResource(R.string.watch_quiet_blocked),
                    onBlocked = { runCatching { context.startActivity(FocusMode.accessIntent()) } },
                )
            }
        }

        // The same choices as on the health page and on the computers' page of the watch: this is where someone looks for them too.
        SectionHeader(stringResource(R.string.settings_cat_health), top = 12.dp, bottom = 0.dp)
        SettingsGroup {
            HealthChoiceRows(0, 4)
            ActionRow(
                3, 4, TandemIcons.Sleep, stringResource(R.string.health_row_sub), null,
                onClick = { onOpen(Route.SettingsPage(SettingsPageId.Health)) },
            )
        }

        SectionHeader(stringResource(R.string.watch_section_wrist), top = 12.dp, bottom = 0.dp)
        SettingsGroup {
            ActionRow(
                0, 2, TandemIcons.Notifications, stringResource(R.string.watch_row_notifications), stringResource(R.string.watch_row_same_apps),
                onClick = { onOpen(Route.MirrorApps) },
            )
            ActionRow(
                1, 2, TandemIcons.Music, stringResource(R.string.watch_row_music), stringResource(R.string.watch_row_same_apps),
                onClick = { onOpen(Route.MediaApps) },
            )
        }

        // Says what it is doing, so a watch that restarts by itself during an update is no surprise.
        val appUpdate by watch.appUpdate.collectAsState()
        SettingsGroup {
            // A failure is never the end of it: a tap tries again.
            if (appUpdate is WatchAppState.Failed) {
                ActionRow(0, 1, TandemIcons.Update, stringResource(R.string.watch_app_title), watchAppText(appUpdate) + " " + stringResource(R.string.watch_app_retry), watch::retryAppUpdate)
            } else {
                NoteRow(0, 1, TandemIcons.Update, stringResource(R.string.watch_app_title), watchAppText(appUpdate))
            }
        }

        Spacer(Modifier.height(12.dp))
        SettingsGroup {
            ActionRow(0, 1, TandemIcons.Delete, stringResource(R.string.watch_unpair), stringResource(R.string.watch_unpair_sub), watch::unpair, danger = true)
        }
        Spacer(Modifier.height(24.dp))
    }

    if (renaming) {
        val plain = stringResource(R.string.settings_cat_watch)
        var text by remember { mutableStateOf(state.name ?: plain) }
        TandemDialog(
            title = stringResource(R.string.settings_name),
            onDismiss = { renaming = false },
            icon = TandemIcons.Watch,
            body = stringResource(R.string.watch_name_sub),
            actions = {
                SecondaryPillButton(stringResource(R.string.action_cancel), { renaming = false })
                PrimaryPillButton(stringResource(R.string.action_save), {
                    // The plain name is no name of its own: it then follows the language of each device.
                    watch.rename(text.trim().takeIf { it != plain }.orEmpty())
                    renaming = false
                })
            },
            content = {
                OutlinedTextField(value = text, onValueChange = { text = it.take(TandemPrefs.WATCH_NAME_MAX) }, singleLine = true, shape = MaterialTheme.shapes.large)
            },
        )
    }
}

@Composable
private fun watchAppText(state: WatchAppState): String = when (state) {
    WatchAppState.Unknown -> stringResource(R.string.watch_app_unknown)
    is WatchAppState.UpToDate -> stringResource(R.string.watch_app_up_to_date, state.name)
    is WatchAppState.OtherSigner -> stringResource(R.string.watch_app_other_signer, state.name)
    is WatchAppState.Fetching -> stringResource(R.string.watch_app_fetching, state.name)
    is WatchAppState.Sending -> stringResource(R.string.watch_app_sending, state.name, (state.progress * 100).toInt())
    is WatchAppState.Waiting -> stringResource(R.string.watch_app_waiting, state.name)
    is WatchAppState.Installing -> stringResource(R.string.watch_app_installing, state.name)
    is WatchAppState.Confirm -> stringResource(R.string.watch_app_confirm, state.name)
    is WatchAppState.Failed -> stringResource(R.string.watch_app_failed, state.name, state.reason)
}

@Composable
private fun WatchHeader(state: WatchState) {
    Column(
        Modifier.fillMaxWidth().clip(CardSquircle).background(MaterialTheme.colorScheme.surfaceContainer).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            DeviceGlyph(TandemPlatform.OTHER, state.ready, size = 68.dp, pickedIcon = DeviceIconChoice.Watch.key)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(state.name ?: stringResource(R.string.settings_cat_watch), style = MaterialTheme.typography.headlineSmall, maxLines = 2)
                Text("Wear OS", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ArrivingBattery(state.battery()) { BatteryRing(it, size = 68.dp, connected = state.ready) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.ready) {
                StatusChip(TandemIcons.Check, stringResource(R.string.status_online), tint = LocalTandemExtraColors.current.online)
                StatusChip(TandemIcons.Bluetooth, stringResource(R.string.status_bluetooth))
            } else {
                StatusChip(TandemIcons.Bluetooth, stringResource(R.string.watch_searching))
            }
            if (state.sleeping) StatusChip(TandemIcons.Sleep, stringResource(R.string.watch_chip_recording), tint = MaterialTheme.colorScheme.primary)
        }
    }
}
