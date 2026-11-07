package nl.markmaaktmedia.tandem.watch.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import nl.markmaaktmedia.tandem.watch.R
import nl.markmaaktmedia.tandem.watch.link.LinkService
import nl.markmaaktmedia.tandem.watch.link.WatchLink
import nl.markmaaktmedia.tandem.watch.link.WatchUi
import nl.markmaaktmedia.tandem.watch.protocol.CallStatus
import nl.markmaaktmedia.tandem.watch.protocol.LinkFailure
import nl.markmaaktmedia.tandem.watch.protocol.PlayerRules
import nl.markmaaktmedia.tandem.watch.track.GentleWake
import nl.markmaaktmedia.tandem.watch.track.Tracking
import nl.markmaaktmedia.tandem.watch.ui.theme.TandemMotion
import nl.markmaaktmedia.tandem.watch.ui.theme.TandemWatchTheme

private val HEART_RATE_PERMISSION =
    if (Build.VERSION.SDK_INT >= 36) "android.permission.health.READ_HEART_RATE" else Manifest.permission.BODY_SENSORS

private val RUNTIME_PERMISSIONS = arrayOf(
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.POST_NOTIFICATIONS,
    Manifest.permission.ACTIVITY_RECOGNITION,
    HEART_RATE_PERMISSION,
)

/** Asked for together with the others, but saying no costs nothing: a night is simply logged without skin temperature. */
internal val ASKED_PERMISSIONS = RUNTIME_PERMISSIONS + "android.permission.health.READ_SKIN_TEMPERATURE"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Tracking.load(this)
        PointerPrefs.load(this)
        setContent {
            TandemWatchTheme {
                AppScaffold(timeText = { TimeText() }) { WatchRoot() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        LinkService.start(this)
    }
}

@Composable
private fun WatchRoot() {
    val ui by WatchLink.ui.collectAsState()
    val context = LocalContext.current
    val ringingId = ui.call?.takeIf { it.state == CallStatus.RINGING }?.id
    // The system only opens the call screen by itself while the screen is off.
    LaunchedEffect(ringingId) {
        if (ringingId != null) context.startActivity(Intent(context, CallActivity::class.java))
    }
    AnimatedContent(
        targetState = ui.code?.let { Page.Code(it) } ?: if (ui.pairing) Page.Waiting else Page.Main,
        transitionSpec = { fadeIn(TandemMotion.fadeSpec()) togetherWith fadeOut(TandemMotion.fadeSpec()) },
        label = "pairing",
    ) { page ->
        when (page) {
            is Page.Code -> PairingCode(page.code)
            Page.Waiting -> PairingWait()
            Page.Main -> Home(ui)
        }
    }
}

private sealed interface Page {
    data class Code(val code: String) : Page
    data object Waiting : Page
    data object Main : Page
}

/** All of it fits at once, because the one thing to do here must not be hidden under a scroll. */
@Composable
internal fun PairingCode(code: String) {
    // The other half of the confirmation is on the phone, and nothing else on this screen would change.
    var confirmed by remember(code) { mutableStateOf(false) }
    ScreenScaffold {
        Column(
            Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                stringResource(if (confirmed) R.string.code_waiting else R.string.code_question),
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
            )
            Text(code, style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                FilledTonalIconButton(
                    onClick = { WatchLink.cancelPairing() },
                    modifier = Modifier.size(56.dp),
                    shapes = IconButtonDefaults.animatedShapes(),
                ) { Icon(painterResource(R.drawable.sym_close), stringResource(R.string.code_mismatch)) }
                FilledIconButton(
                    onClick = {
                        confirmed = true
                        WatchLink.confirmPairing()
                    },
                    modifier = Modifier.size(56.dp),
                    enabled = !confirmed,
                    shapes = IconButtonDefaults.animatedShapes(),
                ) { Icon(painterResource(R.drawable.sym_check), stringResource(R.string.code_match)) }
            }
        }
    }
}

/** The phone has to take the next step. This is where that is said, and not on the button that leads here: the screen is small. */
@Composable
internal fun PairingWait() {
    ScreenScaffold {
        Box(Modifier.fillMaxSize()) {
            Column(
                // As far up as the time allows, because the button takes the whole bottom of a round screen.
                Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 74.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            ) {
                TurningCookieIcon(R.drawable.sym_smartphone, size = 40.dp)
                Text(stringResource(R.string.pair_wait), style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
            }
            EdgeButton(
                onClick = { WatchLink.cancelPairing() },
                modifier = Modifier.align(Alignment.BottomCenter),
                buttonSize = EdgeButtonSize.Medium,
                colors = ButtonDefaults.filledTonalButtonColors(),
            ) { Text(stringResource(R.string.cancel), maxLines = 1) }
        }
    }
}

@Composable
internal fun Home(ui: WatchUi) {
    val context = LocalContext.current
    val testTools = context.resources.getBoolean(R.bool.test_tools)
    val tracking by Tracking.ui.collectAsState()
    val list = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    var missing by remember { mutableStateOf(missingPermissions(context)) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        missing = missingPermissions(context)
        LinkService.start(context)
    }
    val askAccess = { request.launch(ASKED_PERMISSIONS) }

    // Unpairing is rare, so it waits at the end. A watch that still has to pair has nothing more urgent. Only these failures mean that:
    // a hiccup on the link recovers by itself, and asking to pair again would throw away a pairing that is fine.
    val mustPair = ui.failure == LinkFailure.NOT_PAIRED || ui.failure == LinkFailure.AUTH_FAILED || ui.failure == LinkFailure.PAIRING_FAILED
    val settled = ui.paired && !mustPair && !ui.pairing

    // The list holds on to the row in its middle, and the link comes up a moment after the screen. Without this, what then appears above
    // that row pushes the top out of sight. Read here, before the new rows are laid out.
    val wasAtTop = !list.canScrollBackward
    LaunchedEffect(settled, ui.ready, ui.call != null, ui.players.isEmpty(), ui.batteries.size, missing.size, tracking.active) {
        if (wasAtTop) list.scrollToItem(0)
    }

    val rows: @Composable BoxScope.(PaddingValues) -> Unit = { padding ->
        TransformingLazyColumn(state = list, contentPadding = padding.withRoomAbove(), modifier = Modifier.fillMaxSize()) {
            // Being connected is the normal state, and not worth the top of a small screen. Only what is wrong gets said.
            if (!ui.ready) item(key = "status") { StatusHeader(ui, rimRow(spec), SurfaceTransformation(spec)) }

            ui.failure?.let { failure ->
                failureText(failure)?.let { text ->
                    item(key = "failure") {
                        Text(
                            stringResource(text),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = rimContent(spec).padding(bottom = 4.dp),
                        )
                    }
                }
            }

            if (ui.call != null) {
                item(key = "call") {
                    Button(
                        onClick = { context.startActivity(Intent(context, CallActivity::class.java)) },
                        modifier = rimRow(spec),
                        transformation = SurfaceTransformation(spec),
                        icon = { Icon(painterResource(R.drawable.sym_call), null) },
                        label = { Text(stringResource(R.string.back_to_call)) },
                    )
                }
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

            if (!settled) {
                item(key = "pairing") { PairingButton(ui, settled, missing.isEmpty(), rimRow(spec), SurfaceTransformation(spec)) }
            }

            // What is recording comes first: stopping it is why someone opens the app then.
            tracking.active?.let { kind ->
                item(key = "recording") { TrackingButton(kind, tracking, askAccess, rimRow(spec), SurfaceTransformation(spec)) }
            }

            PlayerRules.shown(ui.players, ui.pickedPlayer)?.let { player ->
                item(key = "media") { NowPlayingButton(player, ui.covers[player.artKey], rimRow(spec), SurfaceTransformation(spec)) }
            }

            if (ui.ready) {
                item(key = "steer_header") {
                    ListHeader(modifier = rimRow(spec), transformation = SurfaceTransformation(spec)) { Text(stringResource(R.string.steer)) }
                }
                item(key = "mouse_allowed") {
                    SwitchButton(
                        checked = ui.pointerEnabled,
                        onCheckedChange = { WatchLink.setMouseEnabled(it) },
                        modifier = rimRow(spec),
                        transformation = SurfaceTransformation(spec),
                        label = { Text(stringResource(R.string.mouse_allowed)) },
                    )
                }
                if (ui.pointerEnabled) {
                    item(key = "mouse") { SteerButton(ui, false, rimRow(spec), SurfaceTransformation(spec)) }
                    item(key = "presentation") { SteerButton(ui, true, rimRow(spec), SurfaceTransformation(spec)) }
                    item(key = "mouse_speed") {
                        val speed by PointerPrefs.speed.collectAsState()
                        FilledTonalButton(
                            onClick = { PointerPrefs.nextSpeed(context) },
                            modifier = rimRow(spec),
                            transformation = SurfaceTransformation(spec),
                            label = { Text(stringResource(R.string.mouse_speed)) },
                            secondaryLabel = {
                                Text(stringResource(when (speed) {
                                    PointerPrefs.Speed.SLOW -> R.string.mouse_speed_slow
                                    PointerPrefs.Speed.MEDIUM -> R.string.mouse_speed_medium
                                    PointerPrefs.Speed.FAST -> R.string.mouse_speed_fast
                                }))
                            },
                        )
                    }
                    item(key = "mouse_pinch") {
                        val pinch by PointerPrefs.pinch.collectAsState()
                        SwitchButton(
                            checked = pinch,
                            onCheckedChange = { PointerPrefs.setPinch(context, it) },
                            modifier = rimRow(spec),
                            transformation = SurfaceTransformation(spec),
                            label = { Text(stringResource(R.string.mouse_pinch)) },
                            secondaryLabel = { Text(stringResource(R.string.mouse_pinch_sub)) },
                        )
                    }
                }
                item(key = "find_phone") {
                    FilledTonalButton(
                        onClick = { WatchLink.findPhone(!ui.findingPhone) },
                        modifier = rimRow(spec),
                        transformation = SurfaceTransformation(spec),
                        icon = { Icon(painterResource(R.drawable.sym_smartphone), null) },
                        label = { Text(stringResource(if (ui.findingPhone) R.string.find_phone_stop else R.string.find_phone)) },
                    )
                }
            }

            if (ui.ringing) {
                item(key = "ringing") {
                    Button(
                        onClick = { WatchLink.foundWatch() },
                        modifier = rimRow(spec),
                        transformation = SurfaceTransformation(spec),
                        icon = { Icon(painterResource(R.drawable.sym_check), null) },
                        label = { Text(stringResource(R.string.found_it)) },
                    )
                }
            }

            if (ui.batteries.isNotEmpty()) {
                item(key = "devices_header") {
                    ListHeader(modifier = rimRow(spec), transformation = SurfaceTransformation(spec)) { Text(stringResource(R.string.devices)) }
                }
                ui.batteries.forEachIndexed { index, device ->
                    item(key = "battery$index") {
                        Text(
                            stringResource(R.string.battery_row, device.name, device.level),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = rimContent(spec).padding(horizontal = 14.dp, vertical = 2.dp),
                        )
                    }
                }
            }

            if (testTools) {
                item(key = "pinch_test") {
                    FilledTonalButton(
                        onClick = { PointerActivity.open(context, "", presentation = false) },
                        modifier = rimRow(spec),
                        transformation = SurfaceTransformation(spec),
                        icon = { Icon(painterResource(R.drawable.sym_monitoring), null) },
                        label = { Text(stringResource(R.string.pinch_test)) },
                    )
                }
                item(key = "wake_buzz") {
                    FilledTonalButton(
                        onClick = { GentleWake.tryOnce(context) },
                        modifier = rimRow(spec),
                        transformation = SurfaceTransformation(spec),
                        icon = { Icon(painterResource(R.drawable.sym_bedtime), null) },
                        label = { Text(stringResource(R.string.wake_buzz_test)) },
                    )
                }
            }

            item(key = "health") {
                FilledTonalButton(
                    onClick = { context.startActivity(Intent(context, HealthActivity::class.java)) },
                    modifier = rimRow(spec),
                    transformation = SurfaceTransformation(spec),
                    icon = { Icon(painterResource(R.drawable.sym_favorite), null) },
                    label = { Text(stringResource(R.string.health)) },
                )
            }

            // Its own key: with the same one as the button above, the list would follow it down here once the watch connects.
            if (settled) {
                item(key = "unpair") { PairingButton(ui, settled, missing.isEmpty(), rimRow(spec), SurfaceTransformation(spec)) }
            }
        }
    }

    ScreenScaffold(scrollState = list, content = rows)
}

@Composable
private fun PairingButton(ui: WatchUi, settled: Boolean, canPair: Boolean, modifier: Modifier, transformation: androidx.wear.compose.material3.SurfaceTransformation) {
    when {
        settled -> FilledTonalButton(
            onClick = { WatchLink.unpair() },
            modifier = modifier,
            transformation = transformation,
            colors = ButtonDefaults.filledTonalButtonColors(contentColor = MaterialTheme.colorScheme.error),
            label = { Text(stringResource(R.string.unpair)) },
        )
        else -> Button(
            onClick = { WatchLink.beginPairing() },
            modifier = modifier,
            transformation = transformation,
            enabled = canPair,
            icon = { Icon(painterResource(R.drawable.sym_link), null) },
            label = { Text(stringResource(R.string.pair)) },
        )
    }
}

@Composable
private fun StatusHeader(ui: WatchUi, modifier: Modifier, transformation: androidx.wear.compose.material3.SurfaceTransformation) {
    // After one of these the watch has stopped trying, so "looking for your phone" would not be true.
    val stopped = ui.failure == LinkFailure.NOT_PAIRED || ui.failure == LinkFailure.AUTH_FAILED ||
        ui.failure == LinkFailure.VERSION_MISMATCH
    val text = when {
        stopped && !ui.pairing -> R.string.status_unpaired
        ui.connected -> R.string.status_setting_up
        ui.paired || ui.pairing -> R.string.status_searching
        else -> R.string.status_unpaired
    }
    ListHeader(modifier = modifier, transformation = transformation) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CookieIcon(
                R.drawable.sym_smartphone,
                size = 26.dp,
                container = MaterialTheme.colorScheme.surfaceContainerHigh,
                content = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(text),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}

private fun failureText(failure: LinkFailure): Int? = when (failure) {
    LinkFailure.NOT_PAIRED, LinkFailure.AUTH_FAILED -> R.string.pair_again
    LinkFailure.VERSION_MISMATCH -> R.string.update_needed
    LinkFailure.PAIRING_FAILED -> R.string.pair_failed
    // The connection recovers by itself, so this is nothing to bother anyone with.
    LinkFailure.PROTOCOL_ERROR -> null
}

/**
 * What cannot work stays visible but grey and says why when pressed, as in the rest of Tandem. With several computers
 * this opens the first that can be steered; choosing between them comes with the settings in Tandem.
 */
@Composable
private fun SteerButton(ui: WatchUi, presentation: Boolean, modifier: Modifier, transformation: androidx.wear.compose.material3.SurfaceTransformation) {
    val context = LocalContext.current
    val target = ui.pointerTargets.firstOrNull { it.online && it.allowsInput }
    val why: String? = when {
        ui.pointerTargets.isEmpty() -> stringResource(R.string.pointer_none)
        target != null -> null
        ui.pointerTargets.any { it.online } ->
            stringResource(R.string.pointer_no_input, ui.pointerTargets.first { it.online }.name)
        else -> stringResource(R.string.pointer_offline, ui.pointerTargets.first().name)
    }
    FilledTonalButton(
        onClick = {
            if (why != null) Toast.makeText(context, why, Toast.LENGTH_SHORT).show()
            else PointerActivity.open(context, target!!.id, presentation)
        },
        modifier = modifier.alpha(if (why == null) 1f else 0.5f),
        transformation = transformation,
        icon = { Icon(painterResource(if (presentation) R.drawable.sym_play_arrow else R.drawable.sym_link), null) },
        label = { Text(stringResource(if (presentation) R.string.presentation else R.string.mouse)) },
    )
}

internal fun missingPermissions(context: Context) =
    RUNTIME_PERMISSIONS.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
