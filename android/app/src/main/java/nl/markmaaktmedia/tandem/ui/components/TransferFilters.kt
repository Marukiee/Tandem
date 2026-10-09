package nl.markmaaktmedia.tandem.ui.components

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.engine.TransferItem
import nl.markmaaktmedia.tandem.ui.theme.PillShape
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons

/** What a file is, by the end of its name. */
enum class FileKind(@StringRes val title: Int) {
    All(R.string.filter_kind_all),
    Pictures(R.string.filter_kind_pictures),
    Videos(R.string.filter_kind_videos),
    Audio(R.string.filter_kind_audio),
    Documents(R.string.filter_kind_documents),
    Archives(R.string.filter_kind_archives),
    Other(R.string.filter_kind_other);

    companion object {
        fun of(name: String): FileKind = when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg", "png", "gif", "heic", "heif", "webp", "bmp", "tif", "tiff", "svg", "raw", "dng", "cr2", "nef", "arw" -> Pictures
            "mp4", "mov", "mkv", "avi", "webm", "m4v", "3gp", "mts" -> Videos
            "mp3", "m4a", "wav", "flac", "aac", "ogg", "opus", "aiff" -> Audio
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "rtf", "odt", "ods", "odp", "csv", "pages", "numbers", "key", "epub" -> Documents
            "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "dmg", "iso", "apk", "pkg" -> Archives
            else -> Other
        }
    }
}

enum class TransferDirection(@StringRes val title: Int) {
    All(R.string.filter_direction_all),
    Received(R.string.filter_direction_received),
    Sent(R.string.filter_direction_sent),
}

/** The choices on the Shared page: what kind, with whom, which way, and only what went wrong. */
data class TransferFilter(
    val kind: FileKind = FileKind.All,
    val peer: String? = null,
    val direction: TransferDirection = TransferDirection.All,
    val failedOnly: Boolean = false,
) {
    val active: Boolean get() = kind != FileKind.All || peer != null || direction != TransferDirection.All || failedOnly

    fun matches(item: TransferItem): Boolean =
        (kind == FileKind.All || FileKind.of(item.name) == kind) &&
            (peer == null || item.peer == peer) &&
            (direction == TransferDirection.All || (direction == TransferDirection.Received) == item.incoming) &&
            (!failedOnly || item.state == TransferItem.State.Failed)
}

/**
 * The row of filters under the title: each is a pill that opens a short list, coloured while something other than "all" is chosen,
 * and a last one that lets all of them go.
 */
@Composable
fun TransferFilters(
    filter: TransferFilter,
    peers: List<Pair<String, String>>,
    onChange: (TransferFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        MenuPill(
            label = stringResource(if (filter.kind == FileKind.All) R.string.filter_type else filter.kind.title),
            icon = TandemIcons.File, active = filter.kind != FileKind.All,
            options = FileKind.entries.map { stringResource(it.title) },
            selected = filter.kind.ordinal,
            onPick = { onChange(filter.copy(kind = FileKind.entries[it])) },
        )
        MenuPill(
            label = peers.firstOrNull { it.first == filter.peer }?.second ?: stringResource(R.string.filter_device),
            icon = TandemIcons.Devices, active = filter.peer != null,
            options = listOf(stringResource(R.string.filter_all_devices)) + peers.map { it.second },
            selected = if (filter.peer == null) 0 else peers.indexOfFirst { it.first == filter.peer } + 1,
            onPick = { onChange(filter.copy(peer = if (it == 0) null else peers[it - 1].first)) },
        )
        MenuPill(
            label = stringResource(if (filter.direction == TransferDirection.All) R.string.filter_direction else filter.direction.title),
            icon = TandemIcons.Transfers, active = filter.direction != TransferDirection.All,
            options = TransferDirection.entries.map { stringResource(it.title) },
            selected = filter.direction.ordinal,
            onPick = { onChange(filter.copy(direction = TransferDirection.entries[it])) },
        )
        Pill(stringResource(R.string.filter_failed), null, filter.failedOnly) { onChange(filter.copy(failedOnly = !filter.failedOnly)) }
        AnimatedVisibility(
            visible = filter.active,
            enter = fadeIn() + scaleIn(initialScale = 0.9f),
            exit = fadeOut() + scaleOut(targetScale = 0.9f),
        ) {
            Pill(stringResource(R.string.filter_clear), TandemIcons.Close, false) { onChange(TransferFilter()) }
        }
    }
}

@Composable
private fun MenuPill(label: String, icon: Painter, active: Boolean, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Pill(label, icon, active) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { index, text ->
                DropdownMenuItem(
                    text = { Text(text) },
                    leadingIcon = { if (index == selected) Icon(TandemIcons.Check, null, modifier = Modifier.size(18.dp)) else Box(Modifier.size(18.dp)) },
                    onClick = {
                        open = false
                        onPick(index)
                    },
                )
            }
        }
    }
}

@Composable
private fun Pill(label: String, icon: Painter?, active: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .clip(PillShape)
            .background(if (active) scheme.primaryContainer else scheme.surfaceContainerHigh)
            .bouncyClickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = if (active) scheme.onPrimaryContainer else scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (active) scheme.onPrimaryContainer else scheme.onSurface, maxLines = 1)
    }
}
