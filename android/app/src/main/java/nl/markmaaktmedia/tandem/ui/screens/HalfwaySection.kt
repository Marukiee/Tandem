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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.engine.HalfwayTransfers
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.components.PrimaryPillButton
import nl.markmaaktmedia.tandem.ui.components.SecondaryPillButton
import nl.markmaaktmedia.tandem.ui.components.WavyProgress
import nl.markmaaktmedia.tandem.ui.components.formatBytes
import nl.markmaaktmedia.tandem.ui.theme.CardSquircle
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import nl.markmaaktmedia.tandem.ui.theme.TandemMotion
import uniffi.tandem_core.TandemDevice
import uniffi.tandem_core.TandemResumable

/**
 * On top of the page Transfers: transfers that stopped halfway before Tandem started again, each with "Go on or throw away?" (P2).
 * Nothing goes on by itself.
 */
fun LazyListScope.halfwayRows(waiting: List<TandemResumable>, devices: List<TandemDevice>) {
    items(waiting, key = { "halfway-${it.from}-${it.offer}" }) { transfer ->
        HalfwayRow(transfer, online = devices.firstOrNull { it.id == transfer.from }?.online == true, Modifier.animateItem())
    }
}

/** One transfer that stopped halfway: what it is, how far it got, and the two answers. Laid out like the rows under it. */
@Composable
private fun HalfwayRow(transfer: TandemResumable, online: Boolean, modifier: Modifier = Modifier) {
    val halfway = LocalContext.current.graph.host.halfway
    val (done, total) = HalfwayTransfers.bytes(transfer)
    val name = halfway.senderName(transfer)
    val tint = MaterialTheme.colorScheme.tertiary
    val fraction by animateFloatAsState(if (total == 0L) 0f else done.toFloat() / total, TandemMotion.spatial(), label = "halfwayFraction")
    val dim by animateFloatAsState(if (online) 1f else 0.4f, TandemMotion.fadeSpec(), label = "halfwayDim")
    Column(
        modifier.fillMaxWidth().clip(CardSquircle).background(MaterialTheme.colorScheme.surfaceContainer).animateContentSize(TandemMotion.sizeSpring()),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(TandemIcons.Pause, null, tint = tint, modifier = Modifier.size(24.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(halfway.title(transfer), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                Text(
                    if (online) stringResource(R.string.halfway_row, formatBytes(done), formatBytes(total), name)
                    else stringResource(R.string.halfway_row_away, name),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                )
                WavyProgress(
                    fraction,
                    if (online) tint else MaterialTheme.colorScheme.onSurfaceVariant,
                    MaterialTheme.colorScheme.surfaceContainerHighest,
                    Modifier.alpha(dim),
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SecondaryPillButton(stringResource(R.string.halfway_throw_away), { halfway.throwAway(transfer) }, icon = TandemIcons.Delete)
            // Grey while the sender is away, but still pressable: it then says why, instead of doing nothing.
            PrimaryPillButton(
                stringResource(R.string.halfway_go_on), { halfway.goOn(transfer) },
                icon = TandemIcons.Play,
                container = if (online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                content = if (online) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
