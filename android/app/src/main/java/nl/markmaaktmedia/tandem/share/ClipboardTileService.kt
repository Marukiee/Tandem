package nl.markmaaktmedia.tandem.share

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** The Quick Settings tile "Send clipboard": one swipe and one tap from anywhere. */
class ClipboardTileService : TileService() {

    companion object {
        /** Asks Android to offer adding the tile, so nobody has to dig through the tile editor. */
        fun requestAdd(context: android.content.Context) {
            if (Build.VERSION.SDK_INT < 33) return
            val manager = context.getSystemService(android.app.StatusBarManager::class.java)
            manager.requestAddTileService(
                android.content.ComponentName(context, ClipboardTileService::class.java),
                context.getString(nl.markmaaktmedia.tandem.R.string.tile_label),
                android.graphics.drawable.Icon.createWithResource(context, nl.markmaaktmedia.tandem.R.drawable.ic_stat_tandem),
                java.util.concurrent.Executors.newSingleThreadExecutor(),
            ) { }
        }
    }

    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        val intent = Intent(this, ClipboardSendActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
