package nl.markmaaktmedia.tandem.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.data.BackupDevice
import nl.markmaaktmedia.tandem.ui.components.PrimaryPillButton
import nl.markmaaktmedia.tandem.ui.components.SecondaryPillButton
import nl.markmaaktmedia.tandem.ui.components.TandemDialog
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons

/**
 * Which device of a backup comes back on this phone. The one it recognised is chosen already; a device of another kind stays in
 * the list, grey, and says why when tapped.
 */
@Composable
internal fun RestoreDialog(
    devices: List<BackupDevice>,
    recognised: Int?,
    onRestore: (BackupDevice) -> Unit,
    onDismiss: () -> Unit,
) {
    var picked by remember { mutableStateOf(recognised) }
    var reason by remember { mutableStateOf<String?>(null) }
    val phoneReason = stringResource(R.string.restore_not_phone_mac)
    val desktopReason = stringResource(R.string.restore_not_phone_desktop)

    TandemDialog(
        title = stringResource(R.string.restore_title),
        body = stringResource(if (recognised != null) R.string.restore_recognised else R.string.restore_pick),
        icon = TandemIcons.Download,
        onDismiss = onDismiss,
        closeLabel = stringResource(R.string.action_cancel),
        actions = {
            SecondaryPillButton(label = stringResource(R.string.action_cancel), onClick = onDismiss)
            PrimaryPillButton(
                label = stringResource(R.string.restore_confirm),
                onClick = { devices.firstOrNull { it.index == picked }?.let { onDismiss(); onRestore(it) } },
                enabled = picked != null,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (device in devices) {
                RestoreRow(device, picked == device.index, recognised == device.index) {
                    if (device.fits) {
                        picked = device.index
                        reason = null
                    } else {
                        reason = if (device.kind == "mac") phoneReason else desktopReason
                    }
                }
            }
            AnimatedVisibility(reason != null) {
                Text(
                    reason.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, top = 6.dp, end = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun RestoreRow(device: BackupDevice, picked: Boolean, recognised: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val background by animateColorAsState(if (picked) scheme.secondaryContainer else Color.Transparent, label = "restore-row")
    val detail = when {
        recognised -> stringResource(R.string.restore_this_phone)
        else -> listOfNotNull(
            when (device.platform) {
                "android" -> stringResource(R.string.restore_kind_phone)
                "mac" -> "Mac"
                "windows" -> "Windows"
                "linux" -> "Linux"
                else -> null
            },
            device.model,
        ).joinToString(" · ")
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .alpha(if (device.fits) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(if (device.fits) scheme.primary else scheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                when (device.platform) {
                    "android" -> TandemIcons.Phone
                    "mac" -> TandemIcons.Laptop
                    else -> TandemIcons.Desktop
                },
                contentDescription = null, tint = if (device.fits) scheme.onPrimary else scheme.onSurfaceVariant, modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                device.name.ifEmpty { stringResource(R.string.restore_kind_computer) }, style = MaterialTheme.typography.bodyLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (detail.isNotEmpty()) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (device.fits) {
            Icon(
                if (picked) TandemIcons.CheckCircleFilled else TandemIcons.CheckCircle, contentDescription = null,
                tint = if (picked) scheme.primary else scheme.outline, modifier = Modifier.size(22.dp),
            )
        }
    }
}
