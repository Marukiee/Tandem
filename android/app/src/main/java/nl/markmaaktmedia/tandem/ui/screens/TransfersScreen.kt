package nl.markmaaktmedia.tandem.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.engine.TransferItem
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.components.EmptyState
import nl.markmaaktmedia.tandem.ui.components.SecondaryPillButton
import nl.markmaaktmedia.tandem.ui.components.SwipeToDelete
import nl.markmaaktmedia.tandem.ui.components.TransferRow
import nl.markmaaktmedia.tandem.ui.theme.CardSquircle
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons

@Composable
fun TransfersScreen(bottomPadding: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val host = context.graph.host
    val transfers by host.transfers.collectAsState()
    val devices by host.devices.collectAsState()

    LazyColumn(
        modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = bottomPadding + 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "header") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.tab_transfers), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (transfers.any { it.state != TransferItem.State.Active }) {
                    SecondaryPillButton(stringResource(R.string.action_clear), { host.clearFinishedTransfers() })
                }
            }
        }
        if (transfers.isEmpty()) {
            item(key = "empty") {
                Box(Modifier.fillMaxWidth().padding(top = 60.dp)) {
                    EmptyState(
                        title = stringResource(R.string.transfers_empty_title),
                        body = stringResource(R.string.transfers_empty_body),
                        icon = TandemIcons.Transfers,
                    )
                }
            }
        }
        items(transfers, key = { it.id }) { item ->
            // The swipe key is the transfer id, not the item, so progress updates do not reset a swipe.
            SwipeToDelete(
                item = item,
                key = item.id,
                onDelete = { host.removeTransfer(it.id) },
                enabled = item.state != TransferItem.State.Active,
                shape = CardSquircle,
                modifier = Modifier.animateItem(),
            ) {
                Column(
                    Modifier.fillMaxWidth().clip(CardSquircle).background(MaterialTheme.colorScheme.surfaceContainer).animateContentSize(),
                ) {
                    TransferRow(
                        item = item,
                        peerName = devices.firstOrNull { it.id == item.peer }?.name ?: "?",
                        onOpen = item.location?.let { location -> { openLocation(context, location) } },
                    )
                }
            }
        }
    }
}
