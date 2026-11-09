package nl.markmaaktmedia.tandem.ui.screens

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
import nl.markmaaktmedia.tandem.ui.components.ArrivingBattery
import nl.markmaaktmedia.tandem.ui.components.BatteryBadge
import nl.markmaaktmedia.tandem.ui.components.DeviceGlyph
import nl.markmaaktmedia.tandem.ui.components.DeviceIconChoice
import nl.markmaaktmedia.tandem.ui.components.EmptyState
import nl.markmaaktmedia.tandem.ui.components.PresenceDot
import nl.markmaaktmedia.tandem.ui.components.PrimaryPillButton
import nl.markmaaktmedia.tandem.ui.components.SecondaryPillButton
import nl.markmaaktmedia.tandem.ui.components.StatusChip
import nl.markmaaktmedia.tandem.ui.components.SwipeToPin
import nl.markmaaktmedia.tandem.ui.components.bouncyClickable
import nl.markmaaktmedia.tandem.ui.components.platformIcon
import nl.markmaaktmedia.tandem.ui.components.routeName
import nl.markmaaktmedia.tandem.ui.screens.settings.SettingsPageId
import nl.markmaaktmedia.tandem.ui.theme.CardSquircle
import nl.markmaaktmedia.tandem.ui.theme.PillShape
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import nl.markmaaktmedia.tandem.watch.WatchState
import nl.markmaaktmedia.tandem.wifi.rememberWifiOffer
import nl.markmaaktmedia.tandem.wifi.wifiOfferItem
import uniffi.tandem_core.TandemBattery
import uniffi.tandem_core.TandemDevice
import uniffi.tandem_core.TandemPlatform

/** A card on the first page: a device in the circle, or the watch, which only ever talks to this phone and so is not in the circle. */
private sealed interface HomeCard {
    val id: String

    data class Device(val device: TandemDevice) : HomeCard {
        override val id get() = device.id
    }

    data object Watch : HomeCard {
        override val id get() = "watch"
    }
}

private val WatchRoute = Route.SettingsPage(SettingsPageId.Watch)

@Composable
fun DevicesScreen(
    onOpenDevice: (String) -> Unit,
    onPair: () -> Unit,
    onOpenWatch: () -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp,
    /** Opens the control of a computer's screen, from the pill on its card. */
    onControl: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
) {
    val context = LocalContext.current
    val host = context.graph.host
    val devices by host.devices.collectAsState()
    val state by host.state.collectAsState()
    val scope = rememberCoroutineScope()
    val wifiOffer = rememberWifiOffer()

    var pickerTarget by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val target = pickerTarget
        if (target != null && uris.isNotEmpty()) {
            scope.launch { host.sendUris(uris, listOf(target), uniffi.tandem_core.TandemShareOrigin.Files) }
        }
    }

    val watch = context.graph.watch
    val watchState by watch.state.collectAsState()
    val watchName = watchState.name ?: stringResource(R.string.settings_cat_watch)

    val online = devices.count { it.online } + if (watchState.ready) 1 else 0
    val prefs = context.graph.prefs
    val pinned by prefs.pinnedDevices.collectAsState(initial = emptySet())
    val togglePin: (HomeCard) -> Unit = { card -> scope.launch { prefs.setPinned(card.id, card.id !in pinned) } }
    val ordered = androidx.compose.runtime.remember(devices, pinned, watchState.paired, watchState.ready, watchName) {
        val cards: List<HomeCard> = devices.map { HomeCard.Device(it) } + listOfNotNull(HomeCard.Watch.takeIf { watchState.paired })
        orderDevices(
            cards, pinned, id = { it.id },
            name = { card ->
                when (card) {
                    is HomeCard.Device -> card.device.name
                    HomeCard.Watch -> watchName
                }
            },
            reach = { card ->
                when (card) {
                    is HomeCard.Device -> card.device.let { if (it.online) 0 else if (it.ble) 1 else if (it.status.asleep == true) 2 else 3 }
                    HomeCard.Watch -> if (watchState.ready) 0 else 3
                }
            },
        )
    }
    // Up to three devices get a card of their own with buttons; more than that go two by two, smaller, so they fit.
    val compact = ordered.size > 3

    LazyColumn(
        modifier = modifier.fillMaxSize().statusBarsPadding(),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = bottomPadding + 88.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.tab_devices), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Text(
                        when {
                            state is EngineState.Failed -> stringResource(R.string.engine_failed)
                            ordered.isEmpty() -> stringResource(R.string.devices_none)
                            online == 0 -> stringResource(R.string.devices_none_online)
                            else -> pluralStringResource(R.plurals.devices_online, online, online)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // With no device yet the page has its own button in the middle; after that this is the way to the next one.
                if (ordered.isNotEmpty()) SecondaryPillButton(stringResource(R.string.action_pair), onPair, icon = TandemIcons.Add)
            }
        }

        wifiOfferItem(wifiOffer)


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

        if (ordered.isEmpty() && state !is EngineState.Failed) {
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

        if (compact) {
            items(ordered.chunked(2), key = { row -> row.joinToString("+") { it.id } }) { row ->
                Row(Modifier.fillMaxWidth().animateItem(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { card ->
                        SwipeToPin(
                            item = card,
                            key = card.id,
                            pinned = card.id in pinned,
                            onToggle = togglePin,
                            shape = CardSquircle,
                            modifier = Modifier.weight(1f),
                        ) {
                            when (card) {
                                is HomeCard.Device -> CompactDeviceCard(
                                    device = card.device,
                                    pinned = card.id in pinned,
                                    modifier = Modifier.fillMaxWidth().routeBounds(routeKey(Route.Device(card.id))),
                                    onOpen = { onOpenDevice(card.id) },
                                )
                                HomeCard.Watch -> CompactWatchCard(
                                    state = watchState,
                                    pinned = card.id in pinned,
                                    modifier = Modifier.fillMaxWidth().routeBounds(routeKey(WatchRoute)),
                                    onOpen = onOpenWatch,
                                )
                            }
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        if (!compact) items(ordered, key = { it.id }) { card ->
          // The swipe is keyed on the id, so a status update does not reset a swipe that is under way.
          SwipeToPin(
            item = card,
            key = card.id,
            pinned = card.id in pinned,
            onToggle = togglePin,
            shape = CardSquircle,
            modifier = Modifier.animateItem(),
          ) {
            when (card) {
                is HomeCard.Device -> {
                    val device = card.device
                    DeviceCard(
                        device = device,
                        pinned = device.id in pinned,
                        modifier = Modifier.routeBounds(routeKey(Route.Device(device.id))),
                        onOpen = { onOpenDevice(device.id) },
                        onSendFiles = { pickerTarget = device.id; picker.launch(arrayOf("*/*")) },
                        onControl = { onControl(device.id) },
                        onSendClipboard = {
                            val clip = host.readClipboard()
                            if (clip != null) scope.launch { host.sendClipboard(listOf(device.id), clip) }
                        },
                        onWake = {
                            val mac = device.status.wakeMac ?: return@DeviceCard
                            scope.launch {
                                val sent = nl.markmaaktmedia.tandem.engine.WakeOnLan.send(context, mac)
                                android.widget.Toast.makeText(
                                    context,
                                    context.getString(if (sent) R.string.wake_sent else R.string.wake_failed),
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                            }
                        },
                    )
                }
                HomeCard.Watch -> WatchCard(
                    state = watchState,
                    pinned = card.id in pinned,
                    modifier = Modifier.routeBounds(routeKey(WatchRoute)),
                    onOpen = onOpenWatch,
                    onRing = { watch.ringWatch(!watchState.ringing) },
                )
            }
          }
        }
    }
}

internal fun WatchState.battery() = batteryLevel?.let { TandemBattery(it.toUByte(), charging, false) }

/** Looks like [DeviceCard]. It has no files or clipboard to send, so its button is the one thing a watch is reached for. */
@Composable
private fun WatchCard(
    state: WatchState,
    pinned: Boolean,
    onOpen: () -> Unit,
    onRing: () -> Unit,
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
            DeviceGlyph(TandemPlatform.OTHER, state.ready, pickedIcon = DeviceIconChoice.Watch.key)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(state.name ?: stringResource(R.string.settings_cat_watch), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PresenceDot(state.ready)
                    Text(
                        if (state.ready) {
                            listOf(stringResource(R.string.status_online), stringResource(R.string.status_bluetooth)).joinToString(" · ")
                        } else {
                            stringResource(if (state.bluetoothOff) R.string.watch_bluetooth_off else R.string.watch_searching)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            if (pinned) {
                Icon(TandemIcons.Pin, stringResource(R.string.device_pinned), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
            ArrivingBattery(state.battery()) { BatteryBadge(it, connected = state.ready) }
        }

        AnimatedVisibility(
            visible = state.ready,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickAction(TandemIcons.Ring, stringResource(if (state.ringing) R.string.find_stop_sound else R.string.find_play), onRing)
            }
        }
    }
}

@Composable
private fun CompactWatchCard(
    state: WatchState,
    pinned: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(CardSquircle)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .bouncyClickable(onClick = onOpen)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DeviceGlyph(TandemPlatform.OTHER, state.ready, size = 44.dp, pickedIcon = DeviceIconChoice.Watch.key)
            Spacer(Modifier.weight(1f))
            if (pinned) Icon(TandemIcons.Pin, stringResource(R.string.device_pinned), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            ArrivingBattery(state.battery()) { BatteryBadge(it, connected = state.ready) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(state.name ?: stringResource(R.string.settings_cat_watch), style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PresenceDot(state.ready)
                Text(
                    stringResource(if (state.ready) R.string.status_online else R.string.status_offline),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
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
    onWake: () -> Unit = {},
    onControl: () -> Unit = {},
    pinned: Boolean = false,
) {
    // Asleep is only known from what the device said as it went, so it is a guess that it can be woken.
    val asleep = !device.online && device.status.asleep == true
    val canWake = asleep && device.status.wakeMac != null
    val viaHotspot = nl.markmaaktmedia.tandem.ui.components.rememberViaHotspot(device)
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
                    PresenceDot(device.online, bluetooth = device.ble)
                    val route = device.route
                    Text(
                        if (device.online) {
                            listOfNotNull(
                                stringResource(R.string.status_online),
                                if (viaHotspot) stringResource(R.string.route_hotspot) else route?.let { routeName(it) },
                                device.rttMs?.let { "$it ms" },
                            ).joinToString(" · ")
                        } else when {
                            device.ble -> stringResource(R.string.status_bluetooth)
                            asleep -> stringResource(R.string.status_asleep)
                            else -> stringResource(R.string.status_offline)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            if (pinned) {
                Icon(TandemIcons.Pin, stringResource(R.string.device_pinned), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
            ArrivingBattery(device.status.battery) { BatteryBadge(it, connected = device.online) }
        }

        BatchStrip(device.id)

        AnimatedVisibility(
            visible = device.online || device.ble || canWake,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (device.online) QuickAction(TandemIcons.Upload, stringResource(R.string.action_files), onSendFiles)
                // A computer: its screen is what is reached for most, so that is the second pill; the clipboard is on its page. Without
                // the screen of that computer set up, a press says what is missing there.
                val computer = device.platform == TandemPlatform.MAC_OS || device.platform == TandemPlatform.LINUX || device.platform == TandemPlatform.WINDOWS
                if (computer && device.online) {
                    val context = LocalContext.current
                    val mac = device.platform == TandemPlatform.MAC_OS
                    QuickAction(TandemIcons.Desktop, stringResource(if (mac) R.string.tile_control_mac else R.string.tile_control_computer)) {
                        if ("screen.host" in device.caps) onControl()
                        else android.widget.Toast.makeText(
                            context, if (mac) R.string.control_sub_setup else R.string.control_sub_unavailable, android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                } else if (device.online || device.ble) {
                    QuickAction(TandemIcons.Paste, stringResource(R.string.action_clipboard), onSendClipboard)
                }
                if (canWake) QuickAction(TandemIcons.Power, stringResource(R.string.action_wake), onWake)
            }
        }
    }
}

/** "Receiving 5 of 23 files" with a bar, on the card of the device they come from (or go to), for as long as the set is under way. */
@Composable
private fun BatchStrip(peer: String) {
    val host = androidx.compose.ui.platform.LocalContext.current.graph.host
    val batches by host.batches.collectAsState()
    val transfers by host.transfers.collectAsState()
    val batch = batches.values.firstOrNull { it.peer == peer }
    AnimatedVisibility(visible = batch != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        val shown = batch ?: return@AnimatedVisibility
        val running = transfers.filter { it.peer == peer && it.state == nl.markmaaktmedia.tandem.engine.TransferItem.State.Active }
        val fraction = ((shown.done + if (running.isEmpty()) 0.0 else running.sumOf { it.fraction.toDouble() } / running.size) / shown.total).toFloat().coerceIn(0f, 1f)
        val animated by androidx.compose.animation.core.animateFloatAsState(fraction, nl.markmaaktmedia.tandem.ui.theme.TandemMotion.spatial(), label = "batchProgress")
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(if (shown.incoming) R.string.batch_receiving else R.string.batch_sending, minOf(shown.done + 1, shown.total), shown.total),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            )
            nl.markmaaktmedia.tandem.ui.components.WavyProgress(animated, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.surfaceContainerHighest)
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


/** A smaller card for when there are many devices: who, how it is doing, and nothing else. Tap opens, swipe pins. */
@Composable
private fun CompactDeviceCard(
    device: TandemDevice,
    pinned: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val asleep = !device.online && device.status.asleep == true
    Column(
        modifier
            .clip(CardSquircle)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .bouncyClickable(onClick = onOpen)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DeviceGlyph(device.platform, device.online, size = 44.dp, deviceId = device.id)
            Spacer(Modifier.weight(1f))
            if (pinned) Icon(TandemIcons.Pin, stringResource(R.string.device_pinned), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            ArrivingBattery(device.status.battery) { BatteryBadge(it, connected = device.online) }
        }
        BatchStrip(device.id)
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(device.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PresenceDot(device.online, bluetooth = device.ble)
                Text(
                    when {
                        device.online -> stringResource(R.string.status_online)
                        device.ble -> stringResource(R.string.status_bluetooth)
                        asleep -> stringResource(R.string.status_asleep)
                        else -> stringResource(R.string.status_offline)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (!device.online && device.ble) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}
