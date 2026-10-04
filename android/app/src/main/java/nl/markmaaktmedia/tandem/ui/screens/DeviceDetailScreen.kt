package nl.markmaaktmedia.tandem.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.ui.Route
import nl.markmaaktmedia.tandem.ui.routeBounds
import nl.markmaaktmedia.tandem.ui.routeKey
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.components.ActionRow
import nl.markmaaktmedia.tandem.ui.components.ActionTile
import nl.markmaaktmedia.tandem.ui.components.platformName
import nl.markmaaktmedia.tandem.ui.components.BatteryRing
import nl.markmaaktmedia.tandem.ui.components.DeviceGlyph
import nl.markmaaktmedia.tandem.ui.components.DeviceIconPicker
import nl.markmaaktmedia.tandem.ui.components.rememberTransferActions
import nl.markmaaktmedia.tandem.ui.components.rememberPickedIcon
import nl.markmaaktmedia.tandem.ui.theme.TandemMotion
import nl.markmaaktmedia.tandem.ui.components.PrimaryPillButton
import nl.markmaaktmedia.tandem.ui.components.SecondaryPillButton
import nl.markmaaktmedia.tandem.ui.components.SectionHeader
import nl.markmaaktmedia.tandem.ui.components.SettingsGroup
import nl.markmaaktmedia.tandem.ui.components.StatusChip
import nl.markmaaktmedia.tandem.ui.components.SwitchRow
import nl.markmaaktmedia.tandem.ui.components.TandemConfirmDialog
import nl.markmaaktmedia.tandem.ui.components.TandemIconButton
import nl.markmaaktmedia.tandem.ui.components.TransferRow
import nl.markmaaktmedia.tandem.ui.components.routeName
import nl.markmaaktmedia.tandem.ui.theme.CardSquircle
import nl.markmaaktmedia.tandem.ui.theme.LocalTandemExtraColors
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import uniffi.tandem_core.TandemNetKind
import uniffi.tandem_core.TandemPlatform
import uniffi.tandem_core.TandemShareOrigin

@Composable
fun DeviceDetailScreen(id: String, onBack: () -> Unit, onRemote: (String) -> Unit) {
    val context = LocalContext.current
    val host = context.graph.host
    val devices by host.devices.collectAsState()
    val transfers by host.transfers.collectAsState()
    val device = devices.firstOrNull { it.id == id }
    val scope = rememberCoroutineScope()
    var confirmRemove by remember { mutableStateOf(false) }
    var choosingIcon by remember { mutableStateOf(false) }
    val asleep = device?.let { !it.online && it.status.asleep == true } == true
    val pickedIcon = rememberPickedIcon(id)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch { host.sendUris(uris, listOf(id), TandemShareOrigin.FILES) }
    }

    if (device == null) {
        // Removed while looking at it.
        androidx.compose.runtime.LaunchedEffect(Unit) { onBack() }
        return
    }

    fun set(clipboard: Boolean? = null, autoAccept: Boolean? = null, notifications: Boolean? = null) {
        host.engine?.setDeviceSettings(
            device.id,
            clipboard ?: device.clipboardEnabled,
            autoAccept ?: device.autoAccept,
            notifications ?: device.notificationsEnabled,
        )
        host.refreshDevices()
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TandemIconButton(TandemIcons.Back, stringResource(R.string.action_back), onBack)
        }

        // Header
        Column(
            Modifier.fillMaxWidth().clip(CardSquircle).background(MaterialTheme.colorScheme.surfaceContainer).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                DeviceGlyph(
                    device.platform, device.online, size = 68.dp, deviceId = device.id,
                    onClick = { choosingIcon = !choosingIcon },
                    onClickLabel = stringResource(R.string.icon_change),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(device.name, style = MaterialTheme.typography.headlineSmall, maxLines = 2)
                    Text(
                        listOfNotNull(device.appVersion?.let { "Tandem $it" }, platformName(device.platform)).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                device.status.battery?.let { BatteryRing(it, size = 68.dp) }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = choosingIcon,
                enter = androidx.compose.animation.fadeIn(TandemMotion.fadeSpec()) + androidx.compose.animation.expandVertically(TandemMotion.sizeSpring()),
                exit = androidx.compose.animation.fadeOut(TandemMotion.fadeSpec()) + androidx.compose.animation.shrinkVertically(TandemMotion.sizeSpring()),
            ) {
                DeviceIconPicker(
                    picked = pickedIcon,
                    onPick = { scope.launch { context.graph.prefs.setDeviceIcon(device.id, it) } },
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(
                    when {
                        device.online -> TandemIcons.Check
                        device.ble -> TandemIcons.Bluetooth
                        asleep -> TandemIcons.Sleep
                        else -> TandemIcons.Close
                    },
                    stringResource(
                        when {
                            device.online -> R.string.status_online
                            device.ble -> R.string.status_bluetooth
                            asleep -> R.string.status_asleep
                            else -> R.string.status_offline
                        },
                    ),
                    tint = if (device.online) LocalTandemExtraColors.current.online else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (nl.markmaaktmedia.tandem.ui.components.rememberViaHotspot(device)) {
                    StatusChip(TandemIcons.Hotspot, stringResource(R.string.route_hotspot_long), tint = MaterialTheme.colorScheme.primary)
                } else {
                    device.route?.takeIf { device.online }?.let { StatusChip(TandemIcons.Lan, routeName(it)) }
                }
                device.rttMs?.takeIf { device.online }?.let { StatusChip(TandemIcons.Sync, "$it ms") }
                device.status.network?.takeIf { device.online }?.let { n ->
                    StatusChip(
                        if (n.kind == TandemNetKind.CELLULAR) TandemIcons.Cellular else TandemIcons.Wifi,
                        when (n.kind) {
                            TandemNetKind.WIFI -> "Wi-Fi"
                            TandemNetKind.CELLULAR -> stringResource(R.string.net_mobile)
                            TandemNetKind.ETHERNET -> "Ethernet"
                            TandemNetKind.NONE -> stringResource(R.string.net_none)
                            TandemNetKind.OTHER -> stringResource(R.string.net_other)
                        },
                    )
                }
                if (device.status.dnd == true) StatusChip(TandemIcons.Dnd, stringResource(R.string.status_dnd))
                if (device.status.hotspot == true) StatusChip(TandemIcons.Hotspot, stringResource(R.string.status_hotspot_on), tint = MaterialTheme.colorScheme.primary)
            }
        }

        // Without a network: the clipboard still goes over Bluetooth, and a sleeping device on a cable may be woken.
        val wakeMac = device.status.wakeMac
        if (!device.online && (device.ble || (asleep && wakeMac != null))) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (device.ble) {
                    ActionTile(TandemIcons.Paste, stringResource(R.string.tile_clipboard), {
                        val text = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                        if (text.isNotEmpty()) scope.launch { host.sendClipboard(listOf(id), text) }
                    }, Modifier.weight(1f).fillMaxHeight(), primary = true)
                }
                if (asleep && wakeMac != null) {
                    ActionTile(TandemIcons.Power, stringResource(R.string.action_wake), {
                        scope.launch {
                            val sent = nl.markmaaktmedia.tandem.engine.WakeOnLan.send(context, wakeMac)
                            android.widget.Toast.makeText(
                                context, context.getString(if (sent) R.string.wake_sent else R.string.wake_failed), android.widget.Toast.LENGTH_LONG,
                            ).show()
                        }
                    }, Modifier.weight(1f).fillMaxHeight())
                }
            }
            if (asleep && wakeMac != null) {
                Text(
                    stringResource(R.string.wake_hint),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }

        // Actions
        if (device.online) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionTile(TandemIcons.Upload, stringResource(R.string.tile_files), { picker.launch(arrayOf("*/*")) }, Modifier.weight(1f).fillMaxHeight(), primary = true)
                ActionTile(TandemIcons.Paste, stringResource(R.string.tile_clipboard), {
                    val text = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                    if (text.isNotEmpty()) scope.launch { host.sendClipboard(listOf(id), text) }
                }, Modifier.weight(1f).fillMaxHeight())
                if (device.platform == TandemPlatform.MAC_OS || device.platform == TandemPlatform.LINUX || device.platform == TandemPlatform.WINDOWS) {
                    ActionTile(TandemIcons.Mouse, stringResource(R.string.tile_trackpad), { onRemote(id) }, Modifier.weight(1f).fillMaxHeight().routeBounds(routeKey(Route.Remote(id))))
                }
                if (device.platform == TandemPlatform.ANDROID) {
                    ActionTile(TandemIcons.Ring, stringResource(R.string.tile_find), { scope.launch { runCatching { host.engine?.ring(id, true) } } }, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }

        // Its files, in the Files app of the system, next to the storage of this phone.
        if (device.online && "files" in device.caps) {
            SettingsGroup {
                ActionRow(
                    0, 1, TandemIcons.Folder, stringResource(R.string.files_browse), stringResource(R.string.files_browse_sub),
                    { nl.markmaaktmedia.tandem.files.TandemDocumentsProvider.open(context, id) },
                )
            }
        }

        // What the device is playing, with the buttons for it.
        nl.markmaaktmedia.tandem.ui.components.NowPlayingSection(device)

        // Recent transfers with this device
        val recent = transfers.filter { it.peer == id }.take(5)
        if (recent.isNotEmpty()) {
            SectionHeader(stringResource(R.string.section_recent), top = 12.dp, bottom = 0.dp)
            SettingsGroup {
                recent.forEach { item ->
                    Column(Modifier.fillMaxWidth().clip(CardSquircle).background(MaterialTheme.colorScheme.surfaceContainer)) {
                        TransferRow(item, device.name, rememberTransferActions(item, onRemove = { host.removeTransfer(item.id) }))
                    }
                }
            }
        }

        SectionHeader(stringResource(R.string.section_preferences), top = 12.dp, bottom = 0.dp)
        SettingsGroup {
            SwitchRow(0, 3, TandemIcons.Paste, stringResource(R.string.setting_clipboard), stringResource(R.string.setting_clipboard_sub), device.clipboardEnabled, { set(clipboard = it) })
            SwitchRow(1, 3, TandemIcons.Notifications, stringResource(R.string.setting_notifications_from), stringResource(R.string.setting_notifications_from_sub), device.notificationsEnabled, { set(notifications = it) })
            SwitchRow(2, 3, TandemIcons.Download, stringResource(R.string.setting_auto_accept), stringResource(R.string.setting_auto_accept_sub), device.autoAccept, { set(autoAccept = it) })
        }
        if (device.vouchedByRemoved) {
            Text(stringResource(R.string.warning_vouched), style = MaterialTheme.typography.bodySmall, color = LocalTandemExtraColors.current.urgent, modifier = Modifier.padding(horizontal = 8.dp))
        }
        SettingsGroup {
            ActionRow(0, 1, TandemIcons.Delete, stringResource(R.string.action_remove_device), stringResource(R.string.action_remove_device_sub), { confirmRemove = true }, danger = true)
        }
        Spacer(Modifier.height(24.dp))
    }

    if (confirmRemove) {
        TandemConfirmDialog(
            title = stringResource(R.string.remove_title, device.name),
            body = stringResource(R.string.remove_body),
            confirmLabel = stringResource(R.string.remove_confirm),
            cancelLabel = stringResource(R.string.action_cancel),
            destructive = true,
            onConfirm = { scope.launch { runCatching { host.engine?.removeDevice(id) }; onBack() } },
            onDismiss = { confirmRemove = false },
        )
    }
}

fun openLocation(context: Context, location: String) {
    val uri = android.net.Uri.parse(location)
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
        .setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
