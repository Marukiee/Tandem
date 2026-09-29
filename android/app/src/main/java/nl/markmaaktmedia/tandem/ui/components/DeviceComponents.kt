package nl.markmaaktmedia.tandem.ui.components

import android.text.format.Formatter
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.engine.TransferItem
import nl.markmaaktmedia.tandem.ui.theme.LocalTandemExtraColors
import nl.markmaaktmedia.tandem.ui.theme.PillShape
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import nl.markmaaktmedia.tandem.ui.theme.TandemMotion
import uniffi.tandem_core.TandemBattery
import uniffi.tandem_core.TandemPlatform
import uniffi.tandem_core.TandemRoute

@Composable
fun platformIcon(platform: TandemPlatform): Painter = when (platform) {
    TandemPlatform.ANDROID, TandemPlatform.IOS -> TandemIcons.Phone
    TandemPlatform.MAC_OS -> TandemIcons.Laptop
    TandemPlatform.LINUX -> TandemIcons.Desktop
    TandemPlatform.WINDOWS -> TandemIcons.Windows
    TandemPlatform.OTHER -> TandemIcons.Desktop
}

@Composable
fun platformName(platform: TandemPlatform): String = when (platform) {
    TandemPlatform.ANDROID -> "Android"
    TandemPlatform.MAC_OS -> "Mac"
    TandemPlatform.LINUX -> "Linux"
    TandemPlatform.WINDOWS -> "Windows"
    TandemPlatform.IOS -> "iPhone"
    TandemPlatform.OTHER -> stringResource(R.string.platform_other)
}

@Composable
fun routeName(route: TandemRoute): String = when (route) {
    TandemRoute.LAN -> stringResource(R.string.route_lan)
    TandemRoute.TAILNET -> "Tailscale"
    TandemRoute.OTHER -> "Internet"
}

/** The device as a round tonal badge. Filled with the accent while it is online. */
@Composable
fun DeviceGlyph(
    platform: TandemPlatform,
    online: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
) {
    val container by animateColorAsState(
        if (online) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        TandemMotion.colourSpec(), label = "glyphContainer",
    )
    val content by animateColorAsState(
        if (online) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        TandemMotion.colourSpec(), label = "glyphContent",
    )
    Box(modifier.size(size).clip(CircleShape).background(container), contentAlignment = Alignment.Center) {
        Icon(platformIcon(platform), contentDescription = null, tint = content, modifier = Modifier.size(size * 0.5f))
    }
}

/** A dot that breathes while the device is online. */
@Composable
fun PresenceDot(online: Boolean, modifier: Modifier = Modifier) {
    val color by animateColorAsState(
        if (online) LocalTandemExtraColors.current.online else MaterialTheme.colorScheme.outline,
        TandemMotion.colourSpec(), label = "presence",
    )
    val transition = rememberInfiniteTransition(label = "presenceBreath")
    val ring by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1800), RepeatMode.Restart), label = "ring",
    )
    Box(modifier.size(14.dp), contentAlignment = Alignment.Center) {
        if (online) {
            Box(
                Modifier
                    .size(8.dp + 6.dp * ring)
                    .background(color.copy(alpha = 0.35f * (1f - ring)), CircleShape),
            )
        }
        Box(Modifier.size(8.dp).background(color, CircleShape))
    }
}

/** Battery as a ring. The number rolls when it changes. */
@Composable
fun BatteryRing(battery: TandemBattery, modifier: Modifier = Modifier, size: Dp = 64.dp) {
    val level = battery.level.toInt()
    val sweep by animateFloatAsState(level / 100f, TandemMotion.spatial(), label = "batterySweep")
    val tint by animateColorAsState(
        when {
            battery.charging -> LocalTandemExtraColors.current.online
            level <= 15 -> LocalTandemExtraColors.current.urgent
            else -> MaterialTheme.colorScheme.primary
        },
        TandemMotion.colourSpec(), label = "batteryTint",
    )
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = size.toPx() * 0.11f
            val inset = stroke / 2
            val arc = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
            drawArc(tint, -90f, 360f * sweep, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(
                targetState = level,
                transitionSpec = {
                    (slideInVertically { it / 2 } + fadeIn()) togetherWith (slideOutVertically { -it / 2 } + fadeOut())
                },
                label = "batteryNumber",
            ) { value ->
                Text(
                    "$value",
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = (size.value * 0.27f).sp),
                    fontWeight = FontWeight.Bold,
                )
            }
            if (battery.charging) {
                Icon(TandemIcons.Bolt, null, tint = tint, modifier = Modifier.size(size * 0.2f))
            }
        }
    }
}

/** A compact percentage with a bolt while charging, for list rows. */
@Composable
fun BatteryBadge(battery: TandemBattery, modifier: Modifier = Modifier) {
    val low = battery.level.toInt() <= 15 && !battery.charging
    Row(
        modifier
            .clip(PillShape)
            .background(if (low) LocalTandemExtraColors.current.urgentContainer else MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            if (battery.charging) TandemIcons.Bolt else TandemIcons.Battery, null,
            modifier = Modifier.size(15.dp),
            tint = if (low) LocalTandemExtraColors.current.onUrgentContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "${battery.level}%",
            style = MaterialTheme.typography.labelMedium,
            color = if (low) LocalTandemExtraColors.current.onUrgentContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun StatusChip(
    icon: Painter,
    text: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
) {
    Row(
        modifier.clip(PillShape).background(container).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(15.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1)
    }
}

// ---- Transfers ---------------------------------------------------------------

@Composable
fun formatBytes(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes)

@Composable
fun TransferRow(
    item: TransferItem,
    peerName: String,
    modifier: Modifier = Modifier,
    onOpen: (() -> Unit)? = null,
) {
    val tint = when (item.state) {
        TransferItem.State.Active -> MaterialTheme.colorScheme.primary
        TransferItem.State.Done -> LocalTandemExtraColors.current.online
        TransferItem.State.Failed -> LocalTandemExtraColors.current.urgent
    }
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(
                when (item.state) {
                    TransferItem.State.Active -> if (item.incoming) TandemIcons.Download else TandemIcons.Upload
                    TransferItem.State.Done -> TandemIcons.Check
                    TransferItem.State.Failed -> TandemIcons.Error
                },
                null, tint = tint, modifier = Modifier.size(20.dp),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.MiddleEllipsis)
            val subtitle = when (item.state) {
                TransferItem.State.Active -> "${formatBytes(item.done)} / ${formatBytes(item.total)}" +
                    if (item.speed > 1) "  ${formatBytes(item.speed.toLong())}/s" else ""
                TransferItem.State.Done -> stringResource(if (item.incoming) R.string.transfer_from else R.string.transfer_to, peerName)
                TransferItem.State.Failed -> item.error ?: stringResource(R.string.transfer_failed)
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            if (item.state == TransferItem.State.Active) {
                val fraction by animateFloatAsState(item.fraction, TandemMotion.spatial(), label = "transferProgress")
                Box(Modifier.fillMaxWidth().height(5.dp).clip(PillShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                    Box(Modifier.fillMaxWidth(fraction.coerceAtLeast(0.02f)).height(5.dp).clip(PillShape).background(tint))
                }
            }
        }
        if (onOpen != null && item.state == TransferItem.State.Done && item.incoming) {
            TandemIconButton(TandemIcons.OpenInNew, stringResource(R.string.action_open), onOpen)
        }
    }
}
