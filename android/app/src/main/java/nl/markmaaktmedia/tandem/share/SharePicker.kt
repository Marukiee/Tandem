package nl.markmaaktmedia.tandem.share

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.ui.components.BatteryBadge
import nl.markmaaktmedia.tandem.ui.components.DeviceGlyph
import nl.markmaaktmedia.tandem.ui.components.PillSpinner
import nl.markmaaktmedia.tandem.ui.components.PresenceDot
import nl.markmaaktmedia.tandem.ui.components.PrimaryPillButton
import nl.markmaaktmedia.tandem.ui.components.SecondaryPillButton
import nl.markmaaktmedia.tandem.ui.components.bouncyClickable
import nl.markmaaktmedia.tandem.ui.theme.LocalTandemExtraColors
import nl.markmaaktmedia.tandem.ui.theme.SquircleShape
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import nl.markmaaktmedia.tandem.ui.theme.TandemMotion
import uniffi.tandem_core.TandemDevice

sealed interface SendPhase {
    data object Choosing : SendPhase
    data object Sending : SendPhase
    data class Done(val count: Int) : SendPhase
    data class Failed(val reason: String) : SendPhase
}

/**
 * The sheet that appears when you share something to Tandem: every device that is
 * online, as a tile you can tap to add or remove, and one button to send.
 * With a single device online it is preselected, so sharing is two taps.
 */
@Composable
fun SharePickerSheet(
    devices: List<TandemDevice>,
    phase: SendPhase,
    summary: String,
    preview: @Composable () -> Unit,
    onSend: (List<String>) -> Unit,
    onClose: () -> Unit,
) {
    val online = devices.filter { it.online }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    // With one device there is nothing to choose.
    LaunchedEffect(online.map { it.id }) {
        selected = when {
            online.size == 1 -> setOf(online[0].id)
            else -> selected.filter { id -> online.any { it.id == id } }.toSet()
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(top = 10.dp, bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.width(38.dp).height(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant))
        Spacer(Modifier.height(18.dp))

        AnimatedContent(
            targetState = phase,
            transitionSpec = { (fadeIn(TandemMotion.fadeSpec()) + scaleIn(TandemMotion.springy(), 0.94f)) togetherWith fadeOut(TandemMotion.fadeSpec()) },
            label = "sharePhase",
        ) { current ->
            when (current) {
                SendPhase.Choosing -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        preview()
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(stringResource(R.string.share_send_to), style = MaterialTheme.typography.titleLarge)
                            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                    }
                    if (online.isEmpty()) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            PillSpinner(size = 36.dp)
                            Text(
                                stringResource(if (devices.isEmpty()) R.string.share_none_paired else R.string.share_none_online),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                            )
                        }
                    } else {
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), maxItemsInEachRow = 2) {
                            devices.forEach { device ->
                                DeviceTile(
                                    device = device,
                                    selected = device.id in selected,
                                    onClick = { if (device.online) selected = if (device.id in selected) selected - device.id else selected + device.id },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (online.size > 1) {
                                SecondaryPillButton(
                                    stringResource(if (selected.size == online.size) R.string.share_none else R.string.share_all),
                                    { selected = if (selected.size == online.size) emptySet() else online.map { it.id }.toSet() },
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            PrimaryPillButton(
                                label = if (selected.size > 1) stringResource(R.string.share_send_many, selected.size) else stringResource(R.string.share_send),
                                onClick = { onSend(selected.toList()) },
                                icon = TandemIcons.Send,
                                enabled = selected.isNotEmpty(),
                            )
                        }
                    }
                }

                SendPhase.Sending -> StatusBlock { PillSpinner(size = 44.dp); Text(stringResource(R.string.share_sending), style = MaterialTheme.typography.titleMedium) }

                is SendPhase.Done -> StatusBlock {
                    val scale by animateFloatAsState(1f, TandemMotion.bouncy(), label = "doneScale")
                    Box(Modifier.size(64.dp).scale(scale).clip(CircleShape).background(LocalTandemExtraColors.current.online.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                        Icon(TandemIcons.CheckCircleFilled, null, tint = LocalTandemExtraColors.current.online, modifier = Modifier.size(38.dp))
                    }
                    Text(stringResource(R.string.share_done, current.count), style = MaterialTheme.typography.titleMedium)
                }

                is SendPhase.Failed -> StatusBlock {
                    Icon(TandemIcons.Error, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(40.dp))
                    Text(current.reason, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    SecondaryPillButton(stringResource(R.string.action_close), onClose)
                }
            }
        }
    }
}

@Composable
private fun StatusBlock(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) { content() }
}

@Composable
private fun DeviceTile(device: TandemDevice, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val container by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        TandemMotion.colourSpec(), label = "tile",
    )
    val scale by animateFloatAsState(if (selected) 1f else 0.98f, TandemMotion.springy(), label = "tileScale")
    Column(
        modifier
            .scale(scale)
            .clip(SquircleShape(26.dp))
            .background(container)
            .bouncyClickable(enabled = device.online, onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DeviceGlyph(device.platform, device.online, size = 44.dp, onTile = selected)
            Spacer(Modifier.weight(1f))
            androidx.compose.animation.AnimatedVisibility(visible = selected, enter = scaleIn(TandemMotion.springy()) + fadeIn(), exit = fadeOut()) {
                Icon(TandemIcons.CheckCircleFilled, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
            }
        }
        Text(device.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, color = if (device.online) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PresenceDot(device.online)
            Text(
                stringResource(if (device.online) R.string.status_online else R.string.status_offline),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            device.status.battery?.takeIf { device.online }?.let { BatteryBadge(it) }
        }
    }
}
