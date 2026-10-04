package nl.markmaaktmedia.tandem.ui.components

import android.text.format.Formatter
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.engine.TransferItem
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.theme.CardSquircle
import nl.markmaaktmedia.tandem.ui.theme.LocalTandemExtraColors
import nl.markmaaktmedia.tandem.ui.theme.PillShape
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import nl.markmaaktmedia.tandem.ui.theme.TandemMotion
import uniffi.tandem_core.TandemBattery
import uniffi.tandem_core.TandemDevice
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

/**
 * True when this device is probably reaching the phone over the phone's own hotspot: the hotspot is
 * on, a Mac is connected, and, where the system says how many devices are on the hotspot, there is one.
 * A Mac on the same home Wi-Fi while the hotspot happens to be on is the one case this cannot tell apart.
 */
@Composable
fun rememberViaHotspot(device: TandemDevice): Boolean {
    if (!device.online || device.platform != TandemPlatform.MAC_OS) return false
    val snapshot by nl.markmaaktmedia.tandem.hotspot.HotspotModule.get(LocalContext.current).controller.snapshot.collectAsState()
    return snapshot.on && (snapshot.clients?.let { it > 0 } ?: true)
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

/** A dot that breathes a few times when it shows up online, and then rests. */
@Composable
fun PresenceDot(online: Boolean, modifier: Modifier = Modifier) {
    val color by animateColorAsState(
        if (online) LocalTandemExtraColors.current.online else MaterialTheme.colorScheme.outline,
        TandemMotion.colourSpec(), label = "presence",
    )
    Box(modifier.size(14.dp), contentAlignment = Alignment.Center) {
        if (online) BreathingRing(color)
        Box(Modifier.size(8.dp).background(color, CircleShape))
    }
}

/**
 * The ring around an online dot. It goes out three times and stops: an animation that never ends keeps the screen
 * drawing 60 frames a second for as long as the page is open, which on the emulator was half a core for a dot.
 */
@Composable
private fun BreathingRing(color: Color) {
    val ring = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        repeat(3) {
            ring.snapTo(0f)
            ring.animateTo(1f, tween(1800, easing = LinearEasing))
        }
    }
    // One circle that never changes, in a layer of its own that is scaled and faded.
    Box(
        Modifier
            .size(14.dp)
            .graphicsLayer {
                val scale = (8f + 6f * ring.value) / 14f
                scaleX = scale
                scaleY = scale
                alpha = 0.35f * (1f - ring.value)
            }
            .background(color, CircleShape),
    )
}

/**
 * A number that rolls up when it grows and down when it shrinks, the way the counters on the Mac do. Only the
 * number is passed in, so a unit next to it stays where it is.
 */
@Composable
fun RollingNumber(
    value: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
) {
    AnimatedContent(
        targetState = value,
        modifier = modifier,
        transitionSpec = {
            val rising = targetState > initialState
            (slideInVertically(TandemMotion.spatial()) { if (rising) it / 2 else -it / 2 } + fadeIn(TandemMotion.fadeSpec())) togetherWith
                (slideOutVertically(TandemMotion.spatial()) { if (rising) -it / 2 else it / 2 } + fadeOut(TandemMotion.fadeSpec()))
        },
        label = "rollingNumber",
    ) { shown ->
        Text("$shown", style = style, color = color, fontWeight = fontWeight)
    }
}

/**
 * Battery as a ring, drawn the way the Mac draws it for a phone: the arc grows to the level, the number rolls, and
 * while it charges the ring turns the green of the online dot and a bolt breathes under the number.
 */
@Composable
fun BatteryRing(battery: TandemBattery, modifier: Modifier = Modifier, size: Dp = 64.dp) {
    val level = battery.level.toInt()
    val extras = LocalTandemExtraColors.current
    // Only a full battery closes the ring: at 97 percent a closed circle with a flaw would read as full.
    val sweep by animateFloatAsState(
        if (level >= 100) 1f else level / 100f * 0.945f,
        TandemMotion.spatial(), label = "batterySweep",
    )
    val tint by animateColorAsState(
        when {
            battery.charging -> extras.online
            level <= 15 -> extras.urgent
            else -> MaterialTheme.colorScheme.primary
        },
        TandemMotion.colourSpec(), label = "batteryTint",
    )
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    // Sized from the ring, not from the font scale, so the number always fits inside it.
    val numberSize = with(LocalDensity.current) { (size * 0.28f).toSp() }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = size.toPx() * 0.11f
            val inset = stroke / 2
            val arc = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
            drawArc(tint, -90f, 360f * sweep, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            RollingNumber(
                level,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = numberSize),
                fontWeight = FontWeight.Bold,
            )
            // The bolt grows in under the number, which makes room by moving up, and breathes while it lasts.
            AnimatedVisibility(
                visible = battery.charging,
                enter = fadeIn(TandemMotion.fadeSpec()) + expandVertically(TandemMotion.spatial()),
                exit = fadeOut(TandemMotion.fadeSpec()) + shrinkVertically(TandemMotion.spatial()),
            ) {
                // Four breaths, then it stays lit: see BreathingRing.
                val pulse = remember { Animatable(1f) }
                LaunchedEffect(Unit) {
                    repeat(4) {
                        pulse.animateTo(0.45f, tween(900))
                        pulse.animateTo(1f, tween(900))
                    }
                }
                Icon(TandemIcons.BoltFilled, null, tint = tint, modifier = Modifier.size(size * 0.18f).graphicsLayer { alpha = pulse.value })
            }
        }
    }
}

/** A compact percentage with a bolt while charging, for list rows. */
@Composable
fun BatteryBadge(battery: TandemBattery, modifier: Modifier = Modifier) {
    val level = battery.level.toInt()
    val low = level <= 15 && !battery.charging
    val extras = LocalTandemExtraColors.current
    val base = MaterialTheme.colorScheme.surfaceContainerHighest
    // Charging is the green of the online dot, to the last digit, so a connected device that charges is one colour.
    val container by animateColorAsState(
        when {
            battery.charging -> lerp(base, extras.online, 0.2f)
            low -> extras.urgentContainer
            else -> base
        },
        TandemMotion.colourSpec(), label = "batteryBadgeFill",
    )
    val content by animateColorAsState(
        when {
            battery.charging -> extras.online
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            RollingNumber(level, style = MaterialTheme.typography.labelMedium, color = content)
            Text("%", style = MaterialTheme.typography.labelMedium, color = content)
        }
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

/**
 * One shared file: a picture of it (or an icon for its kind), its name and where it came from or went to, and a menu
 * with the same things the Mac offers on a right click. A tap opens the file. While it is still coming the tile shows
 * the direction and a bar shows how far it is; a failed one says why.
 */
@Composable
fun TransferRow(
    item: TransferItem,
    peerName: String,
    actions: TransferActions,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val tint = when (item.state) {
        TransferItem.State.Active -> MaterialTheme.colorScheme.primary
        TransferItem.State.Done -> MaterialTheme.colorScheme.onSurfaceVariant
        TransferItem.State.Failed -> LocalTandemExtraColors.current.urgent
    }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val tappable = actions.open != null && item.state == TransferItem.State.Done
    Row(
        modifier
            .fillMaxWidth()
            .then(if (tappable) Modifier.bouncyClickable(onClickLabel = stringResource(R.string.transfer_menu_open)) { actions.open?.invoke() } else Modifier)
            .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PreviewTile(item, tint)
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
        Box {
            TandemIconButton(TandemIcons.More, stringResource(R.string.transfer_menu_more), { menu = true })
            androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                @Composable
                fun entry(label: Int, icon: androidx.compose.ui.graphics.painter.Painter, action: (() -> Unit)?) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(label)) },
                        leadingIcon = { Icon(icon, null, Modifier.size(20.dp)) },
                        enabled = action != null,
                        onClick = { menu = false; action?.invoke() },
                    )
                }
                entry(R.string.transfer_menu_open, TandemIcons.OpenInNew, actions.open)
                entry(R.string.transfer_menu_show, TandemIcons.Folder, actions.showFolder)
                entry(R.string.transfer_menu_copy_path, TandemIcons.Copy, actions.copyPath)
                entry(R.string.transfer_menu_remove, TandemIcons.Close, actions.remove)
                // Only what arrived on this phone. A file that was sent is the person's own original.
                if (actions.deleteFile != null) entry(R.string.transfer_menu_delete, TandemIcons.Delete) { confirmDelete = true }
            }
        }
    }
    if (confirmDelete) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.transfer_delete_title)) },
            text = { Text(stringResource(R.string.transfer_delete_body, item.name)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirmDelete = false
                    if (actions.deleteFile?.invoke() == true) actions.remove?.invoke()
                    else android.widget.Toast.makeText(context, R.string.transfer_delete_failed, android.widget.Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.transfer_delete_confirm), color = LocalTandemExtraColors.current.urgent) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** The square at the start of a row: the picture of a finished file, or an icon for what it is or what it is doing. */
@Composable
private fun PreviewTile(item: TransferItem, tint: Color) {
    val context = LocalContext.current
    val size = 52.dp
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    val where = (item.location ?: item.source)?.takeIf { item.state == TransferItem.State.Done }
    val preview by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(null, where, item.name) {
        value = where?.let { Previews.load(context, it, item.name, sizePx) }
    }
    val icon = when (item.state) {
        TransferItem.State.Active -> if (item.incoming) TandemIcons.Download else TandemIcons.Upload
        TransferItem.State.Failed -> TandemIcons.Error
        TransferItem.State.Done -> when (Previews.kindOf(item.name, null)) {
            Previews.Kind.Image -> TandemIcons.Image
            Previews.Kind.Audio -> TandemIcons.Music
            Previews.Kind.Video -> TandemIcons.Play
            Previews.Kind.Archive -> TandemIcons.Folder
            else -> TandemIcons.File
        }
    }
    val background = if (item.state == TransferItem.State.Done) MaterialTheme.colorScheme.surfaceContainerHighest else tint.copy(alpha = 0.16f)
    Box(Modifier.size(size).clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).background(background), contentAlignment = Alignment.Center) {
        androidx.compose.animation.Crossfade(targetState = preview, animationSpec = TandemMotion.fadeSpec(), label = "preview") { picture ->
            if (picture != null) {
                androidx.compose.foundation.Image(
                    picture.asImageBitmap(), null, Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
            } else {
                Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
            }
        }
    }
}
