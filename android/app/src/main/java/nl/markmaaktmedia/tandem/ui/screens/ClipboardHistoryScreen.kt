package nl.markmaaktmedia.tandem.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.data.ClipHistory
import nl.markmaaktmedia.tandem.data.ClipItem
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.components.TandemIconButton
import nl.markmaaktmedia.tandem.ui.components.bouncyClickable
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons

/**
 * What passed through the clipboard of this phone by way of Tandem: tap one to copy it again, pin the ones to keep, remove
 * what should not stay. The list is on this phone only.
 */
@Composable
fun ClipboardHistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val history = context.graph.clipHistory
    val items by history.items.collectAsState()
    var query by remember { mutableStateOf("") }
    val shown = remember(items, query) {
        // Pinned first, then by time, as the list is kept.
        ClipHistory.search(items, query).sortedByDescending { it.pinned }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TandemIconButton(TandemIcons.Back, stringResource(R.string.action_back), onBack)
            Spacer(Modifier.weight(1f))
            if (items.isNotEmpty()) {
                TandemIconButton(TandemIcons.Delete, stringResource(R.string.clip_history_clear), { history.clear(keepPinned = true) })
            }
        }
        Text(stringResource(R.string.clip_history_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 8.dp))
        Text(
            stringResource(R.string.clip_history_sub),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
        if (items.isNotEmpty()) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text(stringResource(R.string.clip_history_search)) },
                shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
        }
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(if (items.isEmpty()) R.string.clip_history_empty else R.string.clip_history_none),
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
                items(shown, key = { it.id }) { item ->
                    val index = shown.indexOf(item)
                    ClipRow(item, first = index == 0, last = index == shown.lastIndex, history = history, context = context)
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun ClipRow(item: ClipItem, first: Boolean, last: Boolean, history: ClipHistory, context: Context) {
    val shape = RoundedCornerShape(
        topStart = if (first) 24.dp else 6.dp, topEnd = if (first) 24.dp else 6.dp,
        bottomStart = if (last) 24.dp else 6.dp, bottomEnd = if (last) 24.dp else 6.dp,
    )
    val copied = stringResource(R.string.clip_history_copied)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainer)
            .bouncyClickable {
                val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                manager.setPrimaryClip(ClipData.newPlainText("Tandem", item.text))
                Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
            }
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.text, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
            val ago = DateUtils.getRelativeTimeSpanString(item.atMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
            Text(
                if (item.from.isEmpty()) ago else "${item.from} · $ago",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TandemIconButton(
            TandemIcons.Pin,
            stringResource(if (item.pinned) R.string.clip_history_unpin else R.string.clip_history_pin),
            { history.togglePin(item.id) },
            tint = if (item.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TandemIconButton(TandemIcons.Close, stringResource(R.string.clip_history_remove), { history.remove(item.id) })
    }
}
