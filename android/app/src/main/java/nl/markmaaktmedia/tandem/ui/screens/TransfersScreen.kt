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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.ui.theme.LocalTandemExtraColors
import nl.markmaaktmedia.tandem.ui.theme.TandemMotion
import nl.markmaaktmedia.tandem.ui.components.dragSelect
import nl.markmaaktmedia.tandem.ui.components.FileActions
import nl.markmaaktmedia.tandem.ui.components.TandemIconButton
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.alpha
import androidx.compose.animation.togetherWith
import nl.markmaaktmedia.tandem.engine.TransferItem
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.components.EmptyState
import nl.markmaaktmedia.tandem.ui.components.SecondaryPillButton
import nl.markmaaktmedia.tandem.ui.components.SwipeToDelete
import nl.markmaaktmedia.tandem.ui.components.TransferFilter
import nl.markmaaktmedia.tandem.ui.components.TransferFilters
import nl.markmaaktmedia.tandem.ui.components.TransferRow
import nl.markmaaktmedia.tandem.ui.components.rememberTransferActions
import nl.markmaaktmedia.tandem.ui.theme.CardSquircle
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons

@Composable
fun TransfersScreen(bottomPadding: Dp, modifier: Modifier = Modifier, listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState()) {
    val context = LocalContext.current
    val host = context.graph.host
    val transfers by host.transfers.collectAsState()
    val devices by host.devices.collectAsState()
    var filter by remember { mutableStateOf(TransferFilter()) }
    val shown = transfers.filter { filter.matches(it) }
    // The devices that something was sent to or came from, for the list of devices.
    val peers = transfers.map { it.peer }.distinct().map { id -> id to (devices.firstOrNull { it.id == id }?.name ?: "?") }
    // Held down and dragged over (see dragSelect), or tapped while something is chosen. What is no longer in the list is let go of.
    var chosen by remember { mutableStateOf(emptySet<String>()) }
    val shownIds = shown.map { it.id }
    androidx.compose.runtime.LaunchedEffect(shownIds) { chosen = chosen intersect shownIds.toSet() }
    val choosing = chosen.isNotEmpty()
    androidx.activity.compose.BackHandler(enabled = choosing) { chosen = emptySet() }
    var confirmDelete by remember { mutableStateOf(false) }

    LazyColumn(
        modifier.fillMaxSize().statusBarsPadding().dragSelect(listState, shownIds, chosen, { chosen = it }, bottomInset = bottomPadding),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = bottomPadding + 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "header") {
            androidx.compose.animation.AnimatedContent(
                targetState = choosing,
                transitionSpec = {
                    (androidx.compose.animation.fadeIn(TandemMotion.fadeSpec()) togetherWith androidx.compose.animation.fadeOut(TandemMotion.fadeSpec()))
                        .using(androidx.compose.animation.SizeTransform(clip = false) { _, _ -> TandemMotion.sizeSpring() })
                },
                label = "transfersHeader",
            ) { selecting ->
                if (selecting) {
                    SelectionBar(
                        count = chosen.size,
                        all = chosen.size == shown.size,
                        onAll = { chosen = if (chosen.size == shown.size) emptySet() else shownIds.toSet() },
                        onShare = { shareFiles(context, transfers.filter { it.id in chosen }) },
                        canShare = transfers.any { it.id in chosen && it.state == TransferItem.State.Done && (it.location ?: it.source) != null },
                        onRemove = {
                            transfers.filter { it.id in chosen && it.state != TransferItem.State.Active }.forEach { host.removeTransfer(it.id) }
                            chosen = emptySet()
                        },
                        canDelete = transfers.any { it.id in chosen && it.received },
                        onDelete = { confirmDelete = true },
                        onClose = { chosen = emptySet() },
                    )
                } else {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.tab_transfers), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (transfers.any { it.state != TransferItem.State.Active }) {
                            SecondaryPillButton(stringResource(R.string.action_clear), { host.clearFinishedTransfers() }, icon = TandemIcons.Close)
                        }
                    }
                }
            }
        }
        if (transfers.isNotEmpty()) {
            item(key = "filters") {
                TransferFilters(filter, peers, { filter = it }, Modifier.fillMaxWidth().padding(bottom = 4.dp))
            }
            if (shown.isEmpty()) {
                item(key = "nothing") {
                    Box(Modifier.fillMaxWidth().padding(top = 40.dp)) {
                        EmptyState(
                            title = stringResource(R.string.filter_nothing_title),
                            body = stringResource(R.string.filter_nothing_body),
                            icon = TandemIcons.Transfers,
                        )
                    }
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
        items(shown, key = { it.id }) { item ->
            // The swipe key is the transfer id, not the item, so progress updates do not reset a swipe.
            SwipeToDelete(
                item = item,
                key = item.id,
                onDelete = { host.removeTransfer(it.id) },
                enabled = item.state != TransferItem.State.Active && !choosing,
                shape = CardSquircle,
                modifier = Modifier.animateItem(),
            ) {
                val isChosen = item.id in chosen
                val card by androidx.compose.animation.animateColorAsState(
                    if (isChosen) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                    TandemMotion.colourSpec(), label = "chosen",
                )
                Column(
                    Modifier.fillMaxWidth().clip(CardSquircle).background(card).animateContentSize(),
                ) {
                    TransferRow(
                        item = item,
                        peerName = devices.firstOrNull { it.id == item.peer }?.name ?: "?",
                        actions = rememberTransferActions(item, onRemove = { host.removeTransfer(item.id) }),
                        selected = if (choosing) isChosen else null,
                    )
                }
            }
        }
    }
    if (confirmDelete) {
        val received = transfers.filter { it.id in chosen && it.received }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(pluralStringResource(R.plurals.transfers_delete_title, received.size, received.size)) },
            text = { Text(stringResource(R.string.transfers_delete_body)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirmDelete = false
                    // Only what arrived on this phone is deleted; a file that was sent is the person's own original and stays.
                    val failed = received.count { item ->
                        val gone = item.location?.let { FileActions.delete(context, it) } == true
                        if (gone) host.removeTransfer(item.id)
                        !gone
                    }
                    if (failed > 0) android.widget.Toast.makeText(context, R.string.transfer_delete_failed, android.widget.Toast.LENGTH_SHORT).show()
                    chosen = emptySet()
                }) { Text(stringResource(R.string.transfer_delete_confirm), color = LocalTandemExtraColors.current.urgent) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

private val TransferItem.received get() = incoming && state == TransferItem.State.Done && location != null

/** Hands the chosen files that are on this phone to another app, as the share sheet of Android. */
private fun shareFiles(context: android.content.Context, items: List<TransferItem>) {
    val uris = items.filter { it.state == TransferItem.State.Done }.mapNotNull { (it.location ?: it.source)?.let(android.net.Uri::parse) }
    if (uris.isEmpty()) return
    val send = android.content.Intent(if (uris.size == 1) android.content.Intent.ACTION_SEND else android.content.Intent.ACTION_SEND_MULTIPLE)
        .setType("*/*")
        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    if (uris.size == 1) send.putExtra(android.content.Intent.EXTRA_STREAM, uris[0])
    else send.putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, ArrayList(uris))
    runCatching { context.startActivity(android.content.Intent.createChooser(send, null).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/**
 * In place of the title while rows are chosen: how many, and what can be done with all of them at once. What cannot be done with what is
 * chosen looks dimmed and says why when it is pressed.
 */
@Composable
private fun SelectionBar(
    count: Int,
    all: Boolean,
    onAll: () -> Unit,
    canShare: Boolean,
    onShare: () -> Unit,
    onRemove: () -> Unit,
    canDelete: Boolean,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TandemIconButton(TandemIcons.Close, stringResource(R.string.action_close), onClose)
        androidx.compose.animation.AnimatedContent(
            targetState = count,
            transitionSpec = {
                val up = targetState > initialState
                (androidx.compose.animation.slideInVertically(TandemMotion.spatial()) { if (up) it else -it } + androidx.compose.animation.fadeIn(TandemMotion.fadeSpec())) togetherWith
                    (androidx.compose.animation.slideOutVertically(TandemMotion.spatial()) { if (up) -it else it } + androidx.compose.animation.fadeOut(TandemMotion.fadeSpec()))
            },
            label = "chosenCount",
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        ) { n ->
            Text(pluralStringResource(R.plurals.transfers_chosen, n, n), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        TandemIconButton(
            if (all) TandemIcons.CheckCircleFilled else TandemIcons.CheckCircle,
            stringResource(if (all) R.string.transfers_choose_none else R.string.transfers_choose_all),
            onAll,
        )
        TandemIconButton(
            TandemIcons.Share, stringResource(R.string.transfers_share),
            { if (canShare) onShare() else android.widget.Toast.makeText(context, R.string.transfers_share_none, android.widget.Toast.LENGTH_SHORT).show() },
            modifier = Modifier.alpha(if (canShare) 1f else 0.38f),
        )
        Box {
            TandemIconButton(TandemIcons.More, stringResource(R.string.transfer_menu_more), { menu = true })
            androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(stringResource(R.string.transfer_menu_remove)) },
                    leadingIcon = { androidx.compose.material3.Icon(TandemIcons.Close, null, Modifier.size(20.dp)) },
                    onClick = { menu = false; onRemove() },
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(stringResource(R.string.transfers_delete_files), color = if (canDelete) androidx.compose.ui.graphics.Color.Unspecified else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)) },
                    leadingIcon = { androidx.compose.material3.Icon(TandemIcons.Delete, null, Modifier.size(20.dp).alpha(if (canDelete) 1f else 0.38f)) },
                    onClick = {
                        menu = false
                        if (canDelete) onDelete() else android.widget.Toast.makeText(context, R.string.transfers_delete_none, android.widget.Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }
    }
}
