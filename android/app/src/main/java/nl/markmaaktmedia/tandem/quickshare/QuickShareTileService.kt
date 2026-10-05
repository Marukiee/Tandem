package nl.markmaaktmedia.tandem.quickshare

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.graph

/**
 * The tile for Quick Share in the Quick Settings of Android: lit while Quick Share is on. A tap turns it on or off, or opens
 * its page, as the person chose in Settings.
 */
class QuickShareTileService : TileService() {

    override fun onStartListening() {
        refresh()
    }

    private fun refresh() {
        val on = graph.quickShare.enabled.value
        qsTile?.apply {
            state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = getString(R.string.tile_quickshare_label)
            if (Build.VERSION.SDK_INT >= 29) subtitle = getString(if (on) R.string.tile_quickshare_on else R.string.tile_quickshare_off)
            updateTile()
        }
    }

    override fun onClick() {
        val host = graph.quickShare
        if (host.tileOpens.value) {
            val intent = Intent(this, QuickShareSheetActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } else {
            host.setEnabled(!host.enabled.value)
            refresh()
        }
    }

    companion object {
        const val ACTION_OPEN_PAGE = "nl.markmaaktmedia.tandem.OPEN_QUICKSHARE"

        /** Asks Android to offer adding the tile, so nobody has to dig through the tile editor. */
        fun requestAdd(context: Context) {
            if (Build.VERSION.SDK_INT < 33) return
            val manager = context.getSystemService(android.app.StatusBarManager::class.java)
            manager.requestAddTileService(
                ComponentName(context, QuickShareTileService::class.java),
                context.getString(R.string.tile_quickshare_label),
                android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_tile_quickshare),
                java.util.concurrent.Executors.newSingleThreadExecutor(),
            ) { }
        }

        /** The state of Quick Share changed: the tile is asked to draw itself again. */
        fun refresh(context: Context) {
            runCatching { requestListeningState(context, ComponentName(context, QuickShareTileService::class.java)) }
        }
    }
}
