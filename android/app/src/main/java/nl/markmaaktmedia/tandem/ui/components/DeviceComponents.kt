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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.scaleOut
import androidx.compose.animation.scaleIn
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.theme.CardSquircle
import nl.markmaaktmedia.tandem.ui.theme.LocalTandemExtraColors
import nl.markmaaktmedia.tandem.ui.theme.PillShape
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import nl.markmaaktmedia.tandem.ui.theme.TandemMotion
import uniffi.tandem_core.TandemBattery
import uniffi.tandem_core.TandemPlatform
import uniffi.tandem_core.TandemRoute

@Composable
fun platformIcon(platform: TandemPlatform, picked: String? = null): Painter =
    DeviceIconChoice.fromKey(picked)?.painter() ?: when (platform) {
        TandemPlatform.ANDROID, TandemPlatform.IOS -> TandemIcons.Phone
        TandemPlatform.MAC_OS -> TandemIcons.Laptop
        TandemPlatform.LINUX -> TandemIcons.Desktop
        TandemPlatform.WINDOWS -> TandemIcons.Windows
        TandemPlatform.OTHER -> TandemIcons.Desktop
    }

/** The icons a device can be given, the same six as on the Mac. */
enum class DeviceIconChoice(val key: String, val label: Int) {
    Phone("phone", R.string.icon_phone),
    Tablet("tablet", R.string.icon_tablet),
    Laptop("laptop", R.string.icon_laptop),
    Desktop("desktop", R.string.icon_desktop),
    Watch("watch", R.string.icon_watch),
    Tv("tv", R.string.icon_tv);

    @Composable
    fun painter(): Painter = when (this) {
        Phone -> TandemIcons.Phone
        Tablet -> TandemIcons.Tablet
        Laptop -> TandemIcons.Laptop
        Desktop -> TandemIcons.DesktopMac
        Watch -> TandemIcons.Watch
        Tv -> TandemIcons.Tv
    }

    companion object {
        fun fromKey(key: String?): DeviceIconChoice? = entries.firstOrNull { it.key == key }
    }
}

/** The icon picked for this device, or null for the platform's own. Follows changes as they are made. */
@Composable
fun rememberPickedIcon(deviceId: String?): String? {
    if (deviceId == null) return null
    val icons by LocalContext.current.graph.deviceIcons.collectAsState()
    return icons[deviceId]
}

@Composable
fun platformName(platform: TandemPlatform): String = when (platform) {
    TandemPlatform.ANDROID -> "Android"
    TandemPlatform.MAC_OS -> "macOS"
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

/**
 * A big square-ish action: the icon above, the label under it. Tiles share a row in equal
 * parts, so a long Dutch label wraps inside its own tile instead of pushing the others around.
 */
@Composable
fun ActionTile(
    icon: Painter,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
) {
    val container = if (primary) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer
    val content = if (primary) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val badge = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer
    val onBadge = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
    Column(
        modifier
            .clip(CardSquircle)
            .background(container)
            .bouncyClickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(badge), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = onBadge, modifier = Modifier.size(22.dp))
        }
        Text(
            label, style = MaterialTheme.typography.labelLarge, color = content,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1, softWrap = false,
        )
    }
}

/** The system green the Mac uses for a charging battery. */
private val ChargingGreen = Color(0xFF30D158)

/** The device as a round tonal badge. Filled with the accent while it is online. */
@Composable
fun DeviceGlyph(
    platform: TandemPlatform,
    online: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    /** For a glyph on a coloured tile, where the usual fill would disappear into it. */
    onTile: Boolean = false,
    /** With the id, the icon the person picked for this device is used. */
    deviceId: String? = null,
) {
    val picked = rememberPickedIcon(deviceId)
    val container by animateColorAsState(
        when {
            onTile && online -> MaterialTheme.colorScheme.surface
            online -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainerHighest
        },
        TandemMotion.colourSpec(), label = "glyphContainer",
    )
    val content by animateColorAsState(
        if (online) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        TandemMotion.colourSpec(), label = "glyphContent",
    )
    Box(modifier.size(size).clip(CircleShape).background(container), contentAlignment = Alignment.Center) {
        AnimatedContent(
            targetState = picked,
            transitionSpec = { (scaleIn(TandemMotion.bouncy(), initialScale = 0.5f) + fadeIn()) togetherWith (scaleOut(TandemMotion.spatial(), targetScale = 0.5f) + fadeOut()) },
            label = "glyphIcon",
        ) { choice ->
            Icon(platformIcon(platform, choice), contentDescription = null, tint = content, modifier = Modifier.size(size * 0.5f))
        }
    }
}

/**
 * The six icons a device can be given, and a way back to the platform's own. Opens under the
 * device header when its icon is tapped, and the choice takes effect everywhere at once.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun DeviceIconPicker(
    picked: String?,
    onPick: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (choice in DeviceIconChoice.entries) {
            val selected = choice.key == picked
            val container by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                TandemMotion.colourSpec(), label = "iconChoiceFill",
            )
            val content by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                TandemMotion.colourSpec(), label = "iconChoiceInk",
            )
            Row(
                Modifier
                    .clip(PillShape)
                    .background(container)
                    .bouncyClickable(withHaptics = true, onClickLabel = stringResource(choice.label)) { onPick(choice.key) }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(choice.painter(), null, tint = content, modifier = Modifier.size(18.dp))
                Text(stringResource(choice.label), style = MaterialTheme.typography.labelLarge, color = content)
            }
        }
        if (picked != null) {
            Row(
                Modifier
                    .clip(PillShape)
                    .bouncyClickable(withHaptics = true) { onPick(null) }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.icon_default), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
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
    // The whole ring gives a small bounce when charging starts or stops, so the change is seen.
    val bounce = remember { androidx.compose.animation.core.Animatable(1f) }
    var firstFrame by remember { androidx.compose.runtime.mutableStateOf(true) }
    androidx.compose.runtime.LaunchedEffect(battery.charging) {
        if (firstFrame) { firstFrame = false; return@LaunchedEffect }
        bounce.snapTo(0.88f)
        bounce.animateTo(1f, TandemMotion.bouncy())
    }
    Box(modifier.size(size).graphicsLayer { scaleX = bounce.value; scaleY = bounce.value }, contentAlignment = Alignment.Center) {
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
            // The bolt pops in and out when charging starts and stops, and breathes while it lasts.
            androidx.compose.animation.AnimatedVisibility(
                visible = battery.charging,
                enter = androidx.compose.animation.scaleIn(TandemMotion.bouncy(), initialScale = 0.2f) + fadeIn(),
                exit = androidx.compose.animation.scaleOut(TandemMotion.spatial(), targetScale = 0.2f) + fadeOut(),
            ) {
                val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "boltPulse").animateFloat(
                    initialValue = 0.55f, targetValue = 1f,
                    animationSpec = androidx.compose.animation.core.infiniteRepeatable(tween(900), RepeatMode.Reverse),
                    label = "boltAlpha",
                )
                Icon(TandemIcons.BoltFilled, null, tint = tint, modifier = Modifier.size(size * 0.2f).graphicsLayer { alpha = pulse })
            }
        }
    }
}

/** A compact percentage with a bolt while charging, for list rows. */
@Composable
fun BatteryBadge(battery: TandemBattery, modifier: Modifier = Modifier) {
    val low = battery.level.toInt() <= 15 && !battery.charging
    val extras = LocalTandemExtraColors.current
    val base = MaterialTheme.colorScheme.surfaceContainerHighest
    // The Mac's charging green, not the deeper one used for the online dot, which reads as olive in a pill.
    val charging = ChargingGreen
    val container by animateColorAsState(
        when {
            battery.charging -> androidx.compose.ui.graphics.lerp(base, charging, 0.3f)
            low -> extras.urgentContainer
            else -> base
        },
        TandemMotion.colourSpec(), label = "batteryBadgeFill",
    )
    val content by animateColorAsState(
        when {
            battery.charging -> charging
            low -> extras.onUrgentContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        TandemMotion.colourSpec(), label = "batteryBadgeInk",
    )
    Row(
        modifier
            .clip(PillShape)
            .background(container)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AnimatedContent(
            targetState = battery.charging,
            transitionSpec = { (scaleIn(TandemMotion.bouncy(), initialScale = 0.3f) + fadeIn()) togetherWith (scaleOut(TandemMotion.spatial(), targetScale = 0.3f) + fadeOut()) },
            label = "batteryIcon",
        ) { charging ->
            Icon(
                if (charging) TandemIcons.Bolt else TandemIcons.Battery, null,
                modifier = Modifier.size(15.dp),
                tint = content,
            )
        }
        Text("${battery.level}%", style = MaterialTheme.typography.labelMedium, color = content)
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
