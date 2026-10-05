package nl.markmaaktmedia.tandem.quickshare

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.components.PrimaryPillButton
import nl.markmaaktmedia.tandem.ui.components.SecondaryPillButton
import nl.markmaaktmedia.tandem.ui.components.bouncyClickable
import nl.markmaaktmedia.tandem.ui.theme.SquircleShape
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import nl.markmaaktmedia.tandem.ui.theme.TandemMotion
import nl.markmaaktmedia.tandem.ui.theme.TandemTheme
import nl.markmaaktmedia.tandem.ui.theme.collectAppearance
import uniffi.tandem_core.TandemDevice
import uniffi.tandem_core.TandemQsKind
import uniffi.tandem_core.TandemShareOrigin

/**
 * The Quick Share page that comes up from the tile of the quick settings, as a sheet and not full screen: who you receive as,
 * the requests to receive, and (under Send) the devices to send to. It has the layout of the Quick Share of Android, because
 * that is what people know. A long press on the tile opens it too.
 */
class QuickShareSheetActivity : ComponentActivity() {
    private sealed interface Target {
        data class Mine(val device: TandemDevice) : Target
        data class Nearby(val peer: QuickShareHost.Peer) : Target
    }

    private var pending: Target? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        nl.markmaaktmedia.tandem.engine.TandemService.start(this)
        setContent {
            val appearance = graph.prefs.appearance.collectAppearance()
            TandemTheme(appearance, applySystemBarStyle = false) { Screen() }
        }
    }

    private fun send(uris: List<Uri>) {
        val target = pending ?: return
        pending = null
        val graph = graph
        lifecycleScope.launch {
            when (target) {
                is Target.Mine -> graph.host.sendUris(uris, listOf(target.device.id), TandemShareOrigin.Files)
                is Target.Nearby -> graph.quickShare.send(uris, target.peer).onFailure {
                    Toast.makeText(this@QuickShareSheetActivity, it.message ?: it.toString(), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    @Composable
    private fun Screen() {
        val quick = graph.quickShare
        val devices by graph.host.devices.collectAsState()
        val enabled by quick.enabled.collectAsState()
        val minutes by quick.visibleMinutes.collectAsState()
        val peers by quick.peers.collectAsState()
        val incoming by quick.incoming.collectAsState()
        val outgoing by quick.outgoing.collectAsState()
        var tab by remember { mutableIntStateOf(0) }
        var visible by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        LaunchedEffect(Unit) { visible = true }
        // A request that comes in is shown at once.
        LaunchedEffect(incoming.size) { if (incoming.isNotEmpty()) tab = 0 }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris -> if (uris.isNotEmpty()) send(uris) else pending = null }
        val name = graph.host.myName.ifBlank { "Tandem" }
        val close: () -> Unit = { scope.launch { visible = false; delay(250); finish() } }
        val scrim by animateFloatAsState(if (visible) 0.5f else 0f, TandemMotion.fadeSpec(), label = "scrim")

        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrim))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { close() },
            contentAlignment = Alignment.BottomCenter,
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = slideInVertically(TandemMotion.spatial()) { it } + fadeIn(),
                exit = slideOutVertically(TandemMotion.spatial()) { it } + fadeOut(),
            ) {
                Box(
                    Modifier.fillMaxWidth().heightIn(max = 640.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                        .navigationBarsPadding(),
                ) {
                    Column(
                        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 22.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(stringResource(R.string.quickshare_title), style = MaterialTheme.typography.headlineLarge)
                        Spacer(Modifier.height(6.dp))
                        if (tab == 0) {
                            Receive(name, enabled, minutes, incoming, onToggle = { quick.setEnabled(it) })
                        } else {
                            Send(
                                name, enabled, devices.filter { it.online }, peers, outgoing,
                                onTurnOn = { quick.setEnabled(true) },
                                onMine = { pending = Target.Mine(it); picker.launch("*/*") },
                                onNearby = { pending = Target.Nearby(it); picker.launch("*/*") },
                            )
                        }
                    }
                    // The two ways of the page, in a pill that floats at the bottom.
                    Row(
                        Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp)
                            .shadow(6.dp, CircleShape).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Tab(stringResource(R.string.quickshare_tab_receive), TandemIcons.Download, tab == 0) { tab = 0 }
                        Tab(stringResource(R.string.quickshare_tab_send), TandemIcons.Send, tab == 1) { tab = 1 }
                    }
                }
            }
        }
    }

    @Composable
    private fun Tab(label: String, icon: androidx.compose.ui.graphics.painter.Painter, selected: Boolean, onClick: () -> Unit) {
        Row(
            Modifier.clip(CircleShape)
                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                .bouncyClickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (selected) Icon(icon, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface)
        }
    }

    @Composable
    private fun Receive(name: String, enabled: Boolean, minutes: Int, incoming: List<QuickShareHost.Incoming>, onToggle: (Boolean) -> Unit) {
        Text(stringResource(R.string.quickshare_receive_as), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            Modifier.fillMaxWidth().clip(SquircleShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Avatar(name.take(1).uppercase())
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(if (!enabled) R.string.quickshare_status_off else if (minutes > 0) R.string.quickshare_status_temp else R.string.quickshare_status_visible),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.quickshare_requests), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(
            Modifier.fillMaxWidth().clip(SquircleShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (incoming.isEmpty()) {
                Text(stringResource(R.string.quickshare_no_requests), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            incoming.forEach { Request(it) }
        }
    }

    @Composable
    private fun Request(item: QuickShareHost.Incoming) {
        val quick = graph.quickShare
        val first = item.files.firstOrNull()?.name ?: item.texts.firstOrNull()?.title ?: stringResource(R.string.quickshare_something)
        val rest = item.files.size + item.texts.size - 1
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Avatar(item.sender.take(1).uppercase())
                Column(Modifier.weight(1f)) {
                    Text(item.sender, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (rest > 0) stringResource(R.string.quickshare_and_more, first, rest) else first,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            when {
                item.failure != null -> {
                    Text(stringResource(R.string.quickshare_stopped), style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { SecondaryPillButton(stringResource(R.string.quickshare_close), { quick.dismiss(item.id) }) }
                }
                item.saved != null -> {
                    Text(stringResource(R.string.quickshare_received_state), style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        item.link?.let { link ->
                            SecondaryPillButton(stringResource(R.string.quickshare_open_link), {
                                startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(link)))
                                quick.dismiss(item.id)
                            })
                        }
                        PrimaryPillButton(stringResource(R.string.quickshare_done), { quick.dismiss(item.id) })
                    }
                }
                item.accepted -> {
                    Text(stringResource(R.string.quickshare_receiving_state), style = MaterialTheme.typography.titleMedium)
                    val total = item.total.toFloat().coerceAtLeast(1f)
                    LinearProgressIndicator(progress = { (item.done.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { SecondaryPillButton(stringResource(R.string.quickshare_cancel), { quick.decline(item.id) }) }
                }
                else -> {
                    Text(stringResource(R.string.quickshare_ready), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.quickshare_pin_value, item.pin), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        SecondaryPillButton(stringResource(R.string.quickshare_decline), { quick.decline(item.id) })
                        PrimaryPillButton(stringResource(R.string.quickshare_accept), { quick.accept(item.id) })
                    }
                }
            }
        }
    }

    @Composable
    private fun Send(
        name: String, enabled: Boolean, mine: List<TandemDevice>, peers: List<QuickShareHost.Peer>, outgoing: List<QuickShareHost.Outgoing>,
        onTurnOn: () -> Unit, onMine: (TandemDevice) -> Unit, onNearby: (QuickShareHost.Peer) -> Unit,
    ) {
        Text(stringResource(R.string.quickshare_share_as), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer).padding(start = 6.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Avatar(name.take(1).uppercase(), 28)
            Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(4.dp))
        if (mine.isNotEmpty()) {
            Text(stringResource(R.string.quickshare_your_devices), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp))
            DevicesCard {
                mine.forEach { device ->
                    DeviceCircle(device.name, stringResource(R.string.quickshare_type_device), TandemIcons.Phone) { onMine(device) }
                }
            }
        }
        Text(stringResource(R.string.quickshare_send_nearby), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp))
        if (!enabled) {
            Column(
                Modifier.fillMaxWidth().clip(SquircleShape(28.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.quickshare_off_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                PrimaryPillButton(stringResource(R.string.quickshare_turn_on), onTurnOn)
            }
        } else {
            DevicesCard {
                if (peers.isEmpty()) {
                    Text(stringResource(R.string.quickshare_searching), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
                }
                peers.forEach { peer ->
                    val sending = outgoing.any { it.peerName == peer.name && it.state == QuickShareHost.Outgoing.State.Sending }
                    DeviceCircle(
                        peer.name,
                        stringResource(if (sending) R.string.quickshare_sending_state else if (peer.kind == TandemQsKind.PHONE) R.string.quickshare_type_phone else R.string.quickshare_type_computer),
                        null,
                    ) { onNearby(peer) }
                }
            }
        }
    }

    @Composable
    private fun DevicesCard(content: @Composable () -> Unit) {
        FlowRow(
            Modifier.fillMaxWidth().clip(SquircleShape(28.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
        ) { content() }
    }

    @Composable
    private fun DeviceCircle(name: String, sub: String, icon: androidx.compose.ui.graphics.painter.Painter?, onClick: () -> Unit) {
        Column(
            Modifier.width(92.dp).clip(SquircleShape(20.dp)).bouncyClickable(onClick = onClick).padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(60.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                if (icon != null) Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(28.dp))
                else Text(name.take(1).uppercase(), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Text(name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }

    @Composable
    private fun Avatar(letter: String, size: Int = 40) {
        Box(Modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Text(letter, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}
