package nl.markmaaktmedia.tandem.live

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.ui.components.ContentRow
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.components.ActionRow
import nl.markmaaktmedia.tandem.ui.components.SectionHeader
import nl.markmaaktmedia.tandem.ui.components.SegmentedPillRow
import nl.markmaaktmedia.tandem.ui.components.SettingsGroup
import nl.markmaaktmedia.tandem.ui.components.SwitchRow
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import uniffi.tandem_core.TandemDevice
import uniffi.tandem_core.TandemMediaKind
import uniffi.tandem_core.TandemMediaPermission

/**
 * On the page of a computer that can show this phone: start showing the screen or the camera from here, stop it, and
 * say what the computer may ask for. Nothing appears for a device that cannot look.
 */
@Composable
fun LiveDeviceSection(device: TandemDevice, permissions: Boolean = false) {
    val canScreen = "screen.view" in device.caps && LiveShare.supports(LocalContext.current, TandemMediaKind.SCREEN)
    val canCamera = "camera.view" in device.caps && LiveShare.supports(LocalContext.current, TandemMediaKind.CAMERA)
    val canSound = "audio.play" in device.caps
    if (!canScreen && !canCamera && !canSound) return
    // What a device may ask is only about its screen and camera, so a device with neither has nothing to show here.
    if (permissions && !canScreen && !canCamera) return

    val context = LocalContext.current
    val live = context.graph.live
    val active by live.active.collectAsState()
    val engine = context.graph.host.engine
    var policy by remember(device.id) { mutableStateOf(runCatching { engine?.mediaPolicy(device.id) }.getOrNull()) }

    if (!permissions) {
    SectionHeader(stringResource(R.string.live_section_share), top = 12.dp, bottom = 0.dp)
    SettingsGroup {
        val soundTo by SoundShareService.active.collectAsState()
        val soundRow = if (canSound) 1 else 0
        val rows = listOfNotNull(
            if (canScreen) TandemMediaKind.SCREEN else null,
            if (canCamera) TandemMediaKind.CAMERA else null,
        )
        if (canSound) {
            val sending = soundTo == device.id
            ActionRow(
                0, rows.size + soundRow, TandemIcons.VolumeUp,
                stringResource(if (sending) R.string.sound_stop_title else R.string.sound_send_title, device.name),
                stringResource(if (sending) R.string.sound_stop_sub else R.string.sound_send_sub),
                {
                    if (sending) SoundShareService.stop(context) else context.startActivity(SoundShareActivity.forPeer(context, device.id))
                },
            )
        }
        rows.forEachIndexed { index, kind ->
            val showing = active.any { it.peer == device.id && it.kind == kind }
            val camera = kind == TandemMediaKind.CAMERA
            ActionRow(
                index + soundRow, rows.size + soundRow,
                painterResource(if (camera) R.drawable.sym_videocam else R.drawable.sym_screen_share),
                if (showing) stringResource(if (camera) R.string.live_stop_camera else R.string.live_stop_screen)
                else stringResource(if (camera) R.string.live_show_camera else R.string.live_show_screen, device.name),
                stringResource(if (camera) R.string.live_show_camera_sub else R.string.live_show_screen_sub),
                {
                    if (showing) live.stopAll() else context.startActivity(LiveShareActivity.forStart(context, device.id, kind))
                },
            )
        }
    }

    return
    }

    SectionHeader(stringResource(R.string.live_section_allow, device.name), top = 12.dp, bottom = 0.dp)
    SettingsGroup {
        val labels = mapOf(
            TandemMediaPermission.ASK to stringResource(R.string.live_perm_ask),
            TandemMediaPermission.ALWAYS to stringResource(R.string.live_perm_always),
            TandemMediaPermission.NEVER to stringResource(R.string.live_perm_never),
        )
        val kinds = listOfNotNull(if (canScreen) TandemMediaKind.SCREEN else null, if (canCamera) TandemMediaKind.CAMERA else null)
        // One row each, in the same form as the other pickers of the app, so the corners and the spacing are the same.
        val total = kinds.size + (if (canScreen) 2 else 0) + 1
        kinds.forEachIndexed { index, kind ->
            val camera = kind == TandemMediaKind.CAMERA
            val current = policy?.let { if (camera) it.camera else it.screen } ?: TandemMediaPermission.ASK
            ContentRow(
                index, total, painterResource(if (camera) R.drawable.sym_videocam else R.drawable.sym_screen_share),
                stringResource(if (camera) R.string.live_perm_camera else R.string.live_perm_screen),
            ) {
                SegmentedPillRow(
                    options = listOf(TandemMediaPermission.ASK, TandemMediaPermission.ALWAYS, TandemMediaPermission.NEVER),
                    selected = current,
                    label = { labels.getValue(it) },
                    onSelect = { choice ->
                        val base = policy ?: return@SegmentedPillRow
                        val next = if (camera) base.copy(camera = choice) else base.copy(screen = choice)
                        runCatching { engine?.setMediaPolicy(device.id, next) }
                        policy = next
                    },
                    modifier = Modifier.fillMaxWidth(),
                    equalWidth = true,
                )
            }
        }
        var next = kinds.size
        // Whether this computer is asked every time it wants to click and type too. Without "always" here, a computer that
        // is allowed to see the screen for good is still asked each time, because it asks for both.
        if (canScreen) {
            val current = policy?.control ?: TandemMediaPermission.ASK
            ContentRow(next++, total, TandemIcons.Mouse, stringResource(R.string.live_perm_control)) {
                SegmentedPillRow(
                    options = listOf(TandemMediaPermission.ASK, TandemMediaPermission.ALWAYS, TandemMediaPermission.NEVER),
                    selected = current,
                    label = { labels.getValue(it) },
                    onSelect = { choice ->
                        val base = policy ?: return@SegmentedPillRow
                        val changed = base.copy(control = choice)
                        runCatching { engine?.setMediaPolicy(device.id, changed) }
                        policy = changed
                    },
                    modifier = Modifier.fillMaxWidth(),
                    equalWidth = true,
                )
            }
        }
        // Clicking and typing from the computer needs the accessibility service, which only the person can turn on.
        if (canScreen) {
            var controlOn by remember { mutableStateOf(TandemAccessibilityService.running) }
            androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
                controlOn = TandemAccessibilityService.running
                onPauseOrDispose {}
            }
            ActionRow(
                next++, total, TandemIcons.Mouse, stringResource(R.string.live_control_title),
                stringResource(if (controlOn) R.string.live_control_on else R.string.live_control_off),
                { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
            )
        }
        var wanted by remember { mutableStateOf(live.indicatorWanted && live.indicator.canShow()) }
        SwitchRow(
            next, total, TandemIcons.Info, stringResource(R.string.live_indicator), stringResource(R.string.live_indicator_sub), wanted,
            { on ->
                live.indicatorWanted = on
                if (on && !live.indicator.canShow()) {
                    // The permission is the system's to give.
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                wanted = on
            },
        )
    }
}
