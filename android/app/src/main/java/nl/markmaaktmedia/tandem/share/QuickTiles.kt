package nl.markmaaktmedia.tandem.share

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.TileService
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.quickshare.QuickShareTileService

/** One of the tiles Tandem puts in the Quick Settings of Android, with what the tiles page shows for it. */
class QuickTile(
    val service: Class<out TileService>,
    @param:StringRes val label: Int,
    @param:DrawableRes val icon: Int,
    @param:StringRes val about: Int,
)

object QuickTiles {
    /** Every tile in the manifest, in the order the tiles page lists them. */
    val all = listOf(
        QuickTile(ClipboardTileService::class.java, R.string.tile_label, R.drawable.ic_tile_clipboard, R.string.tiles_clipboard_sub),
        QuickTile(QuickShareTileService::class.java, R.string.tile_quickshare_label, R.drawable.ic_tile_quickshare, R.string.tiles_quickshare_sub),
        QuickTile(PointerTileService::class.java, R.string.tile_pointer_label, R.drawable.ic_tile_pointer, R.string.tiles_pointer_sub),
    )

    /** Android 12 has no dialog for this: there a tile is only added by hand, in the editor of Quick Settings. */
    val canAsk: Boolean get() = Build.VERSION.SDK_INT >= 33

    fun of(service: Class<out TileService>): QuickTile = all.first { it.service == service }

    /**
     * Asks Android to offer adding the tile, so nobody has to dig through the tile editor. Its answer is kept, because it is the
     * only moment Android tells whether the tile is there.
     */
    fun requestAdd(context: Context, tile: QuickTile) {
        if (Build.VERSION.SDK_INT < 33) return
        val manager = context.getSystemService(StatusBarManager::class.java) ?: return
        runCatching {
            manager.requestAddTileService(
                ComponentName(context, tile.service),
                context.getString(tile.label),
                Icon.createWithResource(context, tile.icon),
                context.mainExecutor,
            ) { result ->
                when (result) {
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED,
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> mark(context, tile.service, true)
                    // The dialog only shows for a tile that is not there, so a check left from before was wrong.
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> mark(context, tile.service, false)
                    else -> Unit
                }
            }
        }
    }

    /** Called from the dialog's answer and from the tiles themselves when Android adds or removes them. */
    fun mark(context: Context, service: Class<out TileService>, added: Boolean) {
        val graph = context.graph
        graph.scope.launch { graph.prefs.setTileAdded(service.name, added) }
    }
}
