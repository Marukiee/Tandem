package nl.markmaaktmedia.tandem.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.Route
import nl.markmaaktmedia.tandem.ui.components.ActionRow
import nl.markmaaktmedia.tandem.ui.components.GroupedRow
import nl.markmaaktmedia.tandem.ui.components.SegmentedPillRow
import nl.markmaaktmedia.tandem.ui.components.SettingsGroup
import nl.markmaaktmedia.tandem.ui.components.TandemConfirmDialog
import nl.markmaaktmedia.tandem.ui.components.TandemDialog
import nl.markmaaktmedia.tandem.ui.components.TandemIconButton
import nl.markmaaktmedia.tandem.ui.components.bouncyClickable
import nl.markmaaktmedia.tandem.ui.components.platformIcon
import nl.markmaaktmedia.tandem.ui.components.rememberPickedIcon
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import nl.markmaaktmedia.tandem.ui.theme.TandemMotion
import uniffi.tandem_core.TandemDevice
import uniffi.tandem_core.TandemPermission
import uniffi.tandem_core.TandemTrustLevel

/** One kind of permission: a card of its own, in a tone of its own. Only what a phone can be asked: unlocking, a restart from afar and
 *  the Wi-Fi password are things of a computer. */
private enum class Kind(@param:StringRes val question: Int, @param:StringRes val title: Int, val perms: List<TandemPermission>) {
    See(R.string.circle_q_see, R.string.circle_g_see, listOf(TandemPermission.CLIPBOARD, TandemPermission.SEE_NOTIFICATIONS)),
    Files(R.string.circle_q_files, R.string.circle_g_files, listOf(TandemPermission.BROWSE_FILES, TandemPermission.FILES_WITHOUT_ASKING, TandemPermission.DELETE_FILES)),
    Control(R.string.circle_q_control, R.string.circle_g_control, listOf(TandemPermission.CONTROL, TandemPermission.LOCK)),
    Phone(R.string.circle_q_phone, R.string.circle_g_phone, listOf(TandemPermission.CALLS, TandemPermission.MESSAGES)),
}

private enum class Share { All, Some, None }

private fun share(denied: List<TandemPermission>, kind: Kind): Share {
    val off = kind.perms.count { it in denied }
    return when (off) { 0 -> Share.All; kind.perms.size -> Share.None; else -> Share.Some }
}

/** What one device may do here, its level, and what it lets this phone do (null for an older version, which does not say). */
private data class Trust(val denied: List<TandemPermission>, val level: TandemTrustLevel, val allowsMe: List<TandemPermission>?)

private data class Tone(val container: Color, val onContainer: Color, val strong: Color, val onStrong: Color)

@Composable
private fun tone(kind: Kind): Tone {
    val c = MaterialTheme.colorScheme
    return when (kind) {
        Kind.See -> Tone(c.primaryContainer, c.onPrimaryContainer, c.primary, c.onPrimary)
        Kind.Files -> Tone(c.tertiaryContainer, c.onTertiaryContainer, c.tertiary, c.onTertiary)
        Kind.Control -> Tone(c.secondaryContainer, c.onSecondaryContainer, c.secondary, c.onSecondary)
        // Material You has three tones of its own; the fourth is the green Tandem already uses, not the red of an error.
        Kind.Phone -> {
            val green = nl.markmaaktmedia.tandem.ui.theme.LocalTandemExtraColors.current.online
            val onGreen = if (green.luminance() > 0.45f) Color.Black else Color.White
            Tone(androidx.compose.ui.graphics.lerp(c.surface, green, 0.22f), c.onSurface, green, onGreen)
        }
    }
}

@Composable
private fun permName(p: TandemPermission): String = stringResource(
    when (p) {
        TandemPermission.CLIPBOARD -> R.string.perm_clipboard
        TandemPermission.SEE_NOTIFICATIONS -> R.string.perm_see_notifications
        TandemPermission.BROWSE_FILES -> R.string.perm_browse_files
        TandemPermission.DELETE_FILES -> R.string.perm_delete_files
        TandemPermission.FILES_WITHOUT_ASKING -> R.string.perm_files_without_asking
        TandemPermission.CONTROL -> R.string.perm_control
        TandemPermission.UNLOCK -> R.string.perm_unlock
        TandemPermission.LOCK -> R.string.perm_lock
        TandemPermission.RESTART -> R.string.perm_restart
        TandemPermission.CALLS -> R.string.perm_calls
        TandemPermission.MESSAGES -> R.string.perm_text_messages
        TandemPermission.WIFI_PASSWORD -> R.string.perm_wifi_password
    },
)

@StringRes
private fun levelName(level: TandemTrustLevel) = when (level) {
    TandemTrustLevel.OWN -> R.string.level_own
    TandemTrustLevel.LIMITED -> R.string.level_limited
    TandemTrustLevel.SEND_ONLY -> R.string.level_send
    TandemTrustLevel.CUSTOM -> R.string.level_custom
}

@StringRes
private fun levelSub(level: TandemTrustLevel) = when (level) {
    TandemTrustLevel.OWN -> R.string.level_own_sub
    TandemTrustLevel.LIMITED -> R.string.level_limited_sub
    TandemTrustLevel.SEND_ONLY -> R.string.level_send_sub
    TandemTrustLevel.CUSTOM -> R.string.level_custom_sub
}

/**
 * Settings › Devices, also called Circle (P6): what each device of the circle may do on this phone. A sentence says it in words, a
 * tonal card per kind has the devices as round switches, and the list has each device with its level. The core checks it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DevicesPage(onBack: () -> Unit, onOpen: (Route) -> Unit) {
    val host = LocalContext.current.graph.host
    val all by host.devices.collectAsState()
    val devices = all.filter { it.id != host.myId }
    val scope = rememberCoroutineScope()
    var trust by remember { mutableStateOf<Map<String, Trust>?>(null) }
    var details by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<TandemDevice?>(null) }

    suspend fun load() {
        val engine = host.engine ?: return
        trust = withContext(Dispatchers.IO) {
            val web = engine.trustWeb()
            val me = host.myId
            devices.associate { d ->
                d.id to Trust(
                    runCatching { engine.deniedPermissions(d.id) }.getOrDefault(emptyList()),
                    runCatching { engine.trustLevel(d.id) }.getOrDefault(TandemTrustLevel.OWN),
                    web.firstOrNull { it.from == d.id && it.to == me }?.denied,
                )
            }
        }
    }
    // Asked again when a device comes or goes, or changes its table.
    LaunchedEffect(all) { load() }
    fun change(work: suspend () -> Unit) = scope.launch {
        runCatching { withContext(Dispatchers.IO) { work() } }
        host.refreshDevices()
        load()
    }
    fun entry(id: String) = trust?.get(id) ?: Trust(emptyList(), TandemTrustLevel.OWN, null)

    SettingsPageFrameWithName(onBack) {
        val loaded = trust
        if (loaded == null) return@SettingsPageFrameWithName
        if (devices.isEmpty()) {
            Text(
                stringResource(R.string.circle_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
            )
        } else {
            Summary(devices, ::entry)
            Kind.entries.forEach { kind ->
                KindCard(kind, devices, ::entry) { device ->
                    val on = share(entry(device.id).denied, kind) != Share.All
                    change { kind.perms.forEach { host.engine?.setPermission(device.id, it, on) } }
                }
                Spacer(Modifier.height(10.dp))
            }
            Spacer(Modifier.height(4.dp))
            SettingsGroup {
                devices.forEachIndexed { index, device ->
                    DeviceTrustRow(index, devices.size, device, entry(device.id), onDetails = { details = device.id }, onRemove = { removing = device })
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        SettingsGroup {
            ActionRow(0, 1, TandemIcons.Add, stringResource(R.string.action_pair), stringResource(R.string.circle_add_sub), { onOpen(Route.Pair) })
        }
    }

    devices.firstOrNull { it.id == details }?.let { device ->
        val e = entry(device.id)
        TandemDialog(title = device.name, onDismiss = { details = null }, closeLabel = stringResource(R.string.action_close)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val levels = listOf(TandemTrustLevel.OWN, TandemTrustLevel.LIMITED, TandemTrustLevel.SEND_ONLY)
                val names = levels.associateWith { stringResource(levelName(it)) }
                SegmentedPillRow(
                    levels, e.level, { names.getValue(it) },
                    { level -> change { host.engine?.setTrustLevel(device.id, level) } }, equalWidth = true,
                )
                Text(stringResource(levelSub(e.level)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Kind.entries.forEach { kind ->
                    val t = tone(kind)
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(t.container).padding(horizontal = 14.dp, vertical = 6.dp)) {
                        Text(stringResource(kind.title), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = t.onContainer, modifier = Modifier.padding(top = 6.dp))
                        kind.perms.forEach { p ->
                            val on = p !in e.denied
                            Row(
                                Modifier.fillMaxWidth().bouncyClickable { change { host.engine?.setPermission(device.id, p, !on) } }.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(permName(p), style = MaterialTheme.typography.bodyMedium, color = t.onContainer, modifier = Modifier.weight(1f))
                                Switch(checked = on, onCheckedChange = null)
                            }
                        }
                    }
                }
            }
        }
    }

    removing?.let { device ->
        TandemConfirmDialog(
            title = stringResource(R.string.remove_title, device.name),
            body = stringResource(R.string.remove_body),
            confirmLabel = stringResource(R.string.remove_confirm),
            cancelLabel = stringResource(R.string.action_cancel),
            destructive = true,
            onConfirm = { removing = null; change { host.engine?.removeDevice(device.id) } },
            onDismiss = { removing = null },
        )
    }
}

/** The frame of a Settings page, with "Circle" as a small second name beside the title. */
@Composable
private fun SettingsPageFrameWithName(onBack: () -> Unit, content: @Composable () -> Unit) {
    SettingsPageFrame(stringResource(R.string.circle_title), onBack) {
        Text(
            stringResource(R.string.circle_name), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun Summary(devices: List<TandemDevice>, entry: (String) -> Trust) {
    val and = stringResource(R.string.circle_and)
    val parts = listOf(
        TandemTrustLevel.OWN to (R.string.circle_says_own to R.string.circle_says_own_many),
        TandemTrustLevel.LIMITED to (R.string.circle_says_limited to R.string.circle_says_limited_many),
        TandemTrustLevel.SEND_ONLY to (R.string.circle_says_send to R.string.circle_says_send_many),
        TandemTrustLevel.CUSTOM to (R.string.circle_says_custom to R.string.circle_says_custom_many),
    ).mapNotNull { (level, words) ->
        val names = devices.filter { entry(it.id).level == level }.map { it.name }
        if (names.isEmpty()) null else names to stringResource(if (names.size > 1) words.second else words.first)
    }
    val text = buildAnnotatedString {
        parts.forEachIndexed { i, (names, says) ->
            if (i > 0) append(" ")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)) {
                append(if (names.size <= 1) names.joinToString("") else names.dropLast(1).joinToString(", ") + and + names.last())
            }
            append(" ")
            append(says)
        }
    }
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    Spacer(Modifier.height(10.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KindCard(kind: Kind, devices: List<TandemDevice>, entry: (String) -> Trust, onToggle: (TandemDevice) -> Unit) {
    val t = tone(kind)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(t.container).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.padding(horizontal = 4.dp)) {
            Text(stringResource(kind.question), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = t.onContainer)
            Text(kind.perms.map { permName(it) }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = t.onContainer.copy(alpha = 0.8f))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            devices.forEach { device -> Face(device, share(entry(device.id).denied, kind), t) { onToggle(device) } }
        }
    }
}

/** A device as a round switch: filled when all of the kind is allowed, a ring when part of it, faint and struck through when none. */
@Composable
private fun Face(device: TandemDevice, share: Share, t: Tone, onClick: () -> Unit) {
    val fill by animateColorAsState(if (share == Share.All) t.strong else t.onContainer.copy(alpha = 0.12f), TandemMotion.colourSpec(), label = "faceFill")
    val tint = if (share == Share.All) t.onStrong else t.onContainer
    val said = stringResource(
        when (share) { Share.All -> R.string.circle_allowed; Share.Some -> R.string.circle_partly; Share.None -> R.string.circle_not_allowed },
        device.name,
    )
    Column(
        Modifier.width(72.dp).alpha(if (share == Share.None) 0.55f else 1f).semantics { contentDescription = said },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(if (share == Share.Some) Color.Transparent else fill)
                .then(if (share == Share.Some) Modifier.border(BorderStroke(2.5.dp, t.strong), CircleShape) else Modifier)
                .bouncyClickable(onClick = onClick)
                .drawWithContent {
                    drawContent()
                    if (share == Share.None) {
                        drawLine(t.onContainer, Offset(size.width * 0.2f, size.height * 0.8f), Offset(size.width * 0.8f, size.height * 0.2f), strokeWidth = 2.dp.toPx())
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(platformIcon(device.platform, rememberPickedIcon(device.id)), null, tint = tint, modifier = Modifier.size(24.dp))
        }
        Text(device.name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = t.onContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DeviceTrustRow(index: Int, total: Int, device: TandemDevice, trust: Trust, onDetails: () -> Unit, onRemove: () -> Unit) {
    val lets = when {
        trust.allowsMe == null -> stringResource(R.string.circle_lets_unknown)
        trust.allowsMe.isEmpty() -> stringResource(R.string.circle_lets_all)
        else -> stringResource(R.string.circle_lets_not, trust.allowsMe.map { permName(it).lowercase() }.joinToString(", "))
    }
    GroupedRow(index, total, onClick = onDetails) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                Icon(platformIcon(device.platform, rememberPickedIcon(device.id)), null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(device.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(lets, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                LevelChip(trust.level)
            }
            TandemIconButton(TandemIcons.Delete, stringResource(R.string.action_remove_device), onRemove)
        }
    }
}

@Composable
private fun LevelChip(level: TandemTrustLevel) {
    val c = MaterialTheme.colorScheme
    val (bg, fg) = when (level) {
        TandemTrustLevel.OWN -> c.primaryContainer to c.onPrimaryContainer
        TandemTrustLevel.LIMITED -> c.secondaryContainer to c.onSecondaryContainer
        TandemTrustLevel.SEND_ONLY -> c.tertiaryContainer to c.onTertiaryContainer
        TandemTrustLevel.CUSTOM -> c.surfaceContainerHighest to c.onSurfaceVariant
    }
    Text(
        stringResource(levelName(level)), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = fg,
        modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 3.dp),
    )
}
