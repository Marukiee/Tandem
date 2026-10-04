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
fun LiveDeviceSection(device: TandemDevice) {
    val canScreen = "screen.view" in device.caps && LiveShare.supports(LocalContext.current, TandemMediaKind.SCREEN)
    val canCamera = "camera.view" in device.caps && LiveShare.supports(LocalContext.current, TandemMediaKind.CAMERA)
    if (!canScreen && !canCamera) return

    val context = LocalContext.current
    val live = context.graph.live
    val active by live.active.collectAsState()
    val engine = context.graph.host.engine
    var policy by remember(device.id) { mutableStateOf(runCatching { engine?.mediaPolicy(device.id) }.getOrNull()) }

    SettingsGroup {
        val rows = listOfNotNull(
            if (canScreen) TandemMediaKind.SCREEN else null,
            if (canCamera) TandemMediaKind.CAMERA else null,
        )
        rows.forEachIndexed { index, kind ->
            val showing = active.any { it.peer == device.id && it.kind == kind }
            val camera = kind == TandemMediaKind.CAMERA
            ActionRow(
                index, rows.size,
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

    SectionHeader(stringResource(R.string.live_section_allow, device.name), top = 12.dp, bottom = 0.dp)
    SettingsGroup {
        val labels = mapOf(
            TandemMediaPermission.ASK to stringResource(R.string.live_perm_ask),
            TandemMediaPermission.ALWAYS to stringResource(R.string.live_perm_always),
            TandemMediaPermission.NEVER to stringResource(R.string.live_perm_never),
        )
        listOfNotNull(if (canScreen) TandemMediaKind.SCREEN else null, if (canCamera) TandemMediaKind.CAMERA else null).forEach { kind ->
            val current = policy?.let { if (kind == TandemMediaKind.CAMERA) it.camera else it.screen } ?: TandemMediaPermission.ASK
            Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
                Text(
                    stringResource(if (kind == TandemMediaKind.CAMERA) R.string.live_perm_camera else R.string.live_perm_screen),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 6.dp),
                )
                SegmentedPillRow(
                    options = listOf(TandemMediaPermission.ASK, TandemMediaPermission.ALWAYS, TandemMediaPermission.NEVER),
                    selected = current,
                    label = { labels.getValue(it) },
                    onSelect = { choice ->
                        val base = policy ?: return@SegmentedPillRow
                        val next = if (kind == TandemMediaKind.CAMERA) base.copy(camera = choice) else base.copy(screen = choice)
                        runCatching { engine?.setMediaPolicy(device.id, next) }
                        policy = next
                    },
                    equalWidth = true,
                )
            }
        }
        var wanted by remember { mutableStateOf(live.indicatorWanted && live.indicator.canShow()) }
        SwitchRow(
            0, 1, TandemIcons.Info, stringResource(R.string.live_indicator), stringResource(R.string.live_indicator_sub), wanted,
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
