package nl.markmaaktmedia.tandem.ui.screens.settings

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.ui.components.EmptyState
import nl.markmaaktmedia.tandem.ui.components.RowIcon
import nl.markmaaktmedia.tandem.ui.components.SectionHeader
import nl.markmaaktmedia.tandem.ui.components.bouncyClickable
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import nl.markmaaktmedia.tandem.ui.theme.TandemMotion

/**
 * What the search found, in one slab. A result looks like the row it leads to: the icon of that row, its title with
 * the letters that matched in the accent colour, and the place it lives under it.
 */
@Composable
internal fun SettingsResults(
    hits: List<SearchHit>,
    index: SettingsIndex,
    summaries: Map<SettingsCategory, String>,
    query: String,
    bottomPadding: Dp,
    onDragStart: () -> Unit,
    onPick: (SettingsEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (hits.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.settings_search_none_title),
            body = stringResource(R.string.settings_search_none_body, query.trim()),
            icon = SettingsIcons.search(),
            modifier = modifier,
        )
        return
    }

    // Pulling the list is the sign that the person is done typing and wants to read.
    val closeKeyboardOnDrag = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) onDragStart()
                return Offset.Zero
            }
        }
    }

    LazyColumn(
        modifier.fillMaxSize().nestedScroll(closeKeyboardOnDrag),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item(key = "count") {
            SectionHeader(
                pluralStringResource(R.plurals.settings_search_results, hits.size, hits.size),
                top = 8.dp, modifier = Modifier.animateItem(),
            )
        }
        itemsIndexed(hits, key = { _, hit -> hit.entry.id }) { position, hit ->
            val target = index.target(hit.entry.id) ?: return@itemsIndexed
            ResultRow(position, hits.size, hit, target, summaries[target.category], onClick = { onPick(target) }, modifier = Modifier.animateItem())
        }
    }
}

@Composable
private fun ResultRow(position: Int, total: Int, hit: SearchHit, target: SettingsEntry, summary: String?, onClick: () -> Unit, modifier: Modifier) {
    val title = hit.entry.title
    val accent = MaterialTheme.colorScheme.primary
    val styled = remember(hit, accent) {
        buildAnnotatedString {
            append(title)
            hit.titleMatch.forEach { addStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold), it.first, it.last + 1) }
        }
    }
    // A page is found by its name, so what is more useful under it than its own name is how it is set now.
    val where = if (target.isCategoryPage && !summary.isNullOrBlank()) summary else listOfNotNull(hit.entry.category, hit.entry.page).joinToString(", ")

    SlabRow(position, total, onClick, modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            RowIcon(target.icon())
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(styled, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(where, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(TandemIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * A row of a slab whose corners follow its place in the list. The shared grouped row would snap when typing takes the
 * last row away and the one above it becomes the end of the slab, so here the corners move on a spring instead.
 */
@Composable
private fun SlabRow(position: Int, total: Int, onClick: () -> Unit, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val top by animateDpAsState(if (position == 0) Outer else Inner, TandemMotion.spatial(), label = "slabTop")
    val bottom by animateDpAsState(if (position == total - 1) Outer else Inner, TandemMotion.spatial(), label = "slabBottom")
    val shape = RoundedCornerShape(
        topStart = top.coerceAtLeast(0.dp), topEnd = top.coerceAtLeast(0.dp),
        bottomStart = bottom.coerceAtLeast(0.dp), bottomEnd = bottom.coerceAtLeast(0.dp),
    )
    Column(
        modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainer).bouncyClickable(onClick = onClick),
        content = content,
    )
}

private val Outer = 24.dp
private val Inner = 4.dp
