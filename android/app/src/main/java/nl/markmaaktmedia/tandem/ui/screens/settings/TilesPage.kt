package nl.markmaaktmedia.tandem.ui.screens.settings

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.share.QuickTile
import nl.markmaaktmedia.tandem.share.QuickTiles
import nl.markmaaktmedia.tandem.ui.components.GroupedRow
import nl.markmaaktmedia.tandem.ui.components.RowIcon
import nl.markmaaktmedia.tandem.ui.components.SettingsGroup
import nl.markmaaktmedia.tandem.ui.theme.LocalTandemExtraColors
import nl.markmaaktmedia.tandem.ui.theme.PillShape
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import nl.markmaaktmedia.tandem.ui.theme.TandemMotion

/** Every tile Tandem has for the Quick Settings of Android, each with a way to add it. */
@Composable
internal fun TilesPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val added by context.graph.prefs.addedTiles.collectAsState(initial = emptySet())

    SettingsPageFrame(stringResource(R.string.settings_tiles), onBack) {
        Spacer(Modifier.height(16.dp))
        SettingsGroup {
            QuickTiles.all.forEachIndexed { index, tile ->
                TileRow(index, QuickTiles.all.size, tile, tile.service.name in added) { QuickTiles.requestAdd(context, tile) }
            }
        }
        if (!QuickTiles.canAsk) Footnote(stringResource(R.string.tiles_by_hand))
        Footnote(stringResource(R.string.tiles_hold))
    }
}

/**
 * Like an action row, with an Add pill or a check at the end. A tap on an added tile still asks: Android then answers without a
 * dialog, and a check left over from a tile that was removed by hand goes away when it says the tile is not there.
 */
@Composable
private fun TileRow(index: Int, total: Int, tile: QuickTile, added: Boolean, onAdd: () -> Unit) {
    GroupedRow(index, total, onClick = if (QuickTiles.canAsk) onAdd else null) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            RowIcon(painterResource(tile.icon))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(tile.label), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(tile.about), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Crossfade(added, animationSpec = TandemMotion.fadeSpec(), label = "tileAdded") { isAdded ->
                when {
                    isAdded -> Icon(
                        TandemIcons.Check, stringResource(R.string.tiles_added),
                        tint = LocalTandemExtraColors.current.online, modifier = Modifier.size(24.dp),
                    )
                    QuickTiles.canAsk -> Box(
                        Modifier.clip(PillShape).background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(stringResource(R.string.tiles_add), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
        }
    }
}

@Composable
private fun Footnote(text: String) {
    Text(
        text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
