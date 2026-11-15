package nl.markmaaktmedia.tandem.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.engine.UnderwayTransfers
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.components.PrimaryPillButton
import nl.markmaaktmedia.tandem.ui.components.SecondaryPillButton
import nl.markmaaktmedia.tandem.ui.components.TandemIconButton
import nl.markmaaktmedia.tandem.ui.components.WavyProgress
import nl.markmaaktmedia.tandem.ui.components.formatBytes
import nl.markmaaktmedia.tandem.ui.components.transferReason
import nl.markmaaktmedia.tandem.ui.theme.CardSquircle
import nl.markmaaktmedia.tandem.ui.theme.LocalTandemExtraColors
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import nl.markmaaktmedia.tandem.ui.theme.TandemMotion
import uniffi.tandem_core.TandemDevice
import uniffi.tandem_core.TandemResumable

/**
 * On top of the page Transfers (P3b): what is underway, and what is incoming. Underway is what this phone sends that runs, waits for a
 * yes, waits for a device that is away, or failed; incoming is what comes in, waits for a yes here, or stopped halfway (P2). Each row is
 * one transfer with one device, so stopping one leaves the others going.
 */
fun LazyListScope.underwayRows(
    rows: List<UnderwayTransfers.Row>,
    halfway: List<TandemResumable>,
    devices: List<TandemDevice>,
) {
    val outgoing = rows.filter { !it.incoming }
    val incoming = rows.filter { it.incoming }
    if (outgoing.isNotEmpty()) {
        item(key = "underway-title") { GroupTitle(R.string.underway_title, Modifier.animateItem()) }
        items(outgoing, key = { it.id }) { UnderwayRow(it, Modifier.animateItem()) }
    }
    if (incoming.isNotEmpty() || halfway.isNotEmpty()) {
        item(key = "incoming-title") { GroupTitle(R.string.incoming_title, Modifier.animateItem()) }
        items(halfway, key = { "halfway-${it.from}-${it.offer}" }) { transfer ->
            HalfwayRow(transfer, online = devices.firstOrNull { it.id == transfer.from }?.online == true, Modifier.animateItem())
        }
        items(incoming, key = { it.id }) { UnderwayRow(it, Modifier.animateItem()) }
    }
}

@Composable
private fun GroupTitle(title: Int, modifier: Modifier = Modifier) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.fillMaxWidth().padding(start = 8.dp, top = 6.dp),
    )
}

/** One transfer with one device, laid out like [HalfwayRow] and the rows under it. */
@Composable
private fun UnderwayRow(row: UnderwayTransfers.Row, modifier: Modifier = Modifier) {
    val underway = LocalContext.current.graph.host.underway
    val (done, total) = row.bytes
    val name = underway.deviceName(row)
    val failed = row.kind == UnderwayTransfers.Kind.Failed
    val tint = if (failed) LocalTandemExtraColors.current.urgent else MaterialTheme.colorScheme.primary
    val fraction by animateFloatAsState(if (total == 0L) 0f else done.toFloat() / total, TandemMotion.spatial(), label = "underwayFraction")
    val subtitle = when (row.kind) {
        UnderwayTransfers.Kind.Queued -> stringResource(R.string.underway_queued, name)
        UnderwayTransfers.Kind.Waiting ->
            if (row.incoming) stringResource(R.string.underway_waiting_in, name, formatBytes(total))
            else stringResource(R.string.underway_waiting_out, name)
        UnderwayTransfers.Kind.Running ->
            stringResource(if (row.incoming) R.string.underway_running_in else R.string.underway_running_out, formatBytes(done), formatBytes(total), name)
        UnderwayTransfers.Kind.Failed -> transferReason(row.error, row.incoming, name)
    }
    val askingHere = row.kind == UnderwayTransfers.Kind.Waiting && row.incoming
    Column(modifier.fillMaxWidth().clip(CardSquircle).background(MaterialTheme.colorScheme.surfaceContainer).animateContentSize(TandemMotion.sizeSpring())) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = if (askingHere || failed) 0.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(symbol(row), null, tint = tint, modifier = Modifier.size(24.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(underway.title(row), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
                if (row.kind == UnderwayTransfers.Kind.Running) {
                    WavyProgress(fraction, tint, MaterialTheme.colorScheme.surfaceContainerHighest)
                }
            }
            when {
                askingHere -> Unit
                failed -> TandemIconButton(TandemIcons.Close, stringResource(R.string.underway_hide), { underway.putAway(row) })
                else -> TandemIconButton(
                    TandemIcons.Close,
                    stringResource(if (row.kind == UnderwayTransfers.Kind.Queued) R.string.underway_do_not_send else R.string.action_stop),
                    { underway.stop(row) },
                )
            }
        }
        if (askingHere || failed) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (askingHere) {
                    SecondaryPillButton(stringResource(R.string.action_decline), { underway.decline(row) }, icon = TandemIcons.Close)
                    PrimaryPillButton(stringResource(R.string.action_accept), { underway.accept(row) }, icon = TandemIcons.Download)
                } else {
                    PrimaryPillButton(stringResource(R.string.underway_send_again), { underway.sendAgain(row) }, icon = TandemIcons.Refresh)
                }
            }
        }
    }
}

@Composable
private fun symbol(row: UnderwayTransfers.Row) = when (row.kind) {
    UnderwayTransfers.Kind.Queued -> TandemIcons.Schedule
    UnderwayTransfers.Kind.Waiting -> if (row.incoming) TandemIcons.Download else TandemIcons.Send
    UnderwayTransfers.Kind.Running -> if (row.incoming) TandemIcons.Download else TandemIcons.Upload
    UnderwayTransfers.Kind.Failed -> TandemIcons.Error
}
