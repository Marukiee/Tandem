package nl.markmaaktmedia.tandem.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.ui.Route
import nl.markmaaktmedia.tandem.ui.routeBounds
import nl.markmaaktmedia.tandem.ui.routeKey
import nl.markmaaktmedia.tandem.engine.EngineState
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.components.BatteryBadge
import nl.markmaaktmedia.tandem.ui.components.DeviceGlyph
import nl.markmaaktmedia.tandem.ui.components.EmptyState
import nl.markmaaktmedia.tandem.ui.components.PresenceDot
import nl.markmaaktmedia.tandem.ui.components.PrimaryPillButton
import nl.markmaaktmedia.tandem.ui.components.StatusChip
import nl.markmaaktmedia.tandem.ui.components.bouncyClickable
import nl.markmaaktmedia.tandem.ui.components.platformIcon
import nl.markmaaktmedia.tandem.ui.components.routeName
import nl.markmaaktmedia.tandem.ui.theme.CardSquircle
import nl.markmaaktmedia.tandem.ui.theme.PillShape
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import uniffi.tandem_core.TandemDevice

@Composable
fun DevicesScreen(
    onOpenDevice: (String) -> Unit,
    onPair: () -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
) {
    val context = LocalContext.current
    val host = context.graph.host
    val devices by host.devices.collectAsState()
    val state by host.state.collectAsState()
    val scope = rememberCoroutineScope()

    var pickerTarget by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val target = pickerTarget
        if (target != null && uris.isNotEmpty()) {
            scope.launch { host.sendUris(uris, listOf(target), uniffi.tandem_core.TandemShareOrigin.FILES) }
        }
    }

    val online = devices.count { it.online }

    LazyColumn(
        modifier = modifier.fillMaxSize().statusBarsPadding(),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = bottomPadding + 88.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 12.dp)) {
                Text(stringResource(R.string.tab_devices), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        state is EngineState.Failed -> stringResource(R.string.engine_failed)
                        devices.isEmpty() -> stringResource(R.string.devices_none)
                        online == 0 -> stringResource(R.string.devices_none_online)
                        else -> pluralStringResource(R.plurals.devices_online, online, online)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }


        (state as? EngineState.Failed)?.let { failed ->
            item(key = "failed") {
                Column(
                    Modifier.fillMaxWidth().clip(CardSquircle).background(MaterialTheme.colorScheme.errorContainer).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(stringResource(R.string.engine_failed), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                    Text(failed.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }

        if (devices.isEmpty() && state !is EngineState.Failed) {
            item(key = "empty") {
                Box(Modifier.fillMaxWidth().height(420.dp)) {
                    EmptyState(
                        title = stringResource(R.string.empty_title),
                        body = stringResource(R.string.empty_body),
                        action = { PrimaryPillButton(stringResource(R.string.action_pair), onPair, icon = TandemIcons.Add) },
                    )
                }
            }
        }

        items(devices, key = { it.id }) { device ->
            DeviceCard(
                device = device,
                modifier = Modifier.routeBounds(routeKey(Route.Device(device.id))),
                onOpen = { onOpenDevice(device.id) },
                onSendFiles = { pickerTarget = device.id; picker.launch(arrayOf("*/*")) },
                onSendClipboard = {
                    val text = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                    scope.launch {
                        if (text.isNotEmpty()) host.sendClipboard(listOf(device.id), text)
                    }
                },
            )
        }
    }
}

@Composable
fun DeviceCard(
    device: TandemDevice,
    onOpen: () -> Unit,
    onSendFiles: () -> Unit,
    onSendClipboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(CardSquircle)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .bouncyClickable(onClick = onOpen)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            DeviceGlyph(device.platform, device.online, deviceId = device.id)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(device.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PresenceDot(device.online)
                    val route = device.route
                    Text(
                        if (device.online) {
                            listOfNotNull(
                                stringResource(R.string.status_online),
                                route?.let { routeName(it) },
                                device.rttMs?.let { "$it ms" },
                            ).joinToString(" · ")
                        } else stringResource(R.string.status_offline),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            device.status.battery?.let { BatteryBadge(it) }
        }

        AnimatedVisibility(
            visible = device.online,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickAction(TandemIcons.Upload, stringResource(R.string.action_files), onSendFiles)
                QuickAction(TandemIcons.Paste, stringResource(R.string.action_clipboard), onSendClipboard)
            }
        }
    }
}

@Composable
private fun QuickAction(icon: androidx.compose.ui.graphics.painter.Painter, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(PillShape)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .bouncyClickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}
