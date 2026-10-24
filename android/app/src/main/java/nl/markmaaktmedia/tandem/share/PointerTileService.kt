package nl.markmaaktmedia.tandem.share

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.graph

/**
 * The tile for the mouse of a computer in the Quick Settings of Android: lit while this phone takes the pointer of a computer that
 * pushes it over the edge of its screen, like the button in the Control Center of the Mac. A tap turns it on or off.
 */
class PointerTileService : TileService() {

    override fun onStartListening() {
        graph.scope.launch { show(graph.prefs.pointerIn.first()) }
    }

    override fun onClick() {
        graph.scope.launch {
            val on = !graph.prefs.pointerIn.first()
            graph.prefs.setPointerIn(on)
            show(on)
        }
    }

    private fun show(on: Boolean) {
        android.os.Handler(mainLooper).post {
            qsTile?.apply {
                state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                label = getString(R.string.tile_pointer_label)
                if (Build.VERSION.SDK_INT >= 29) subtitle = getString(if (on) R.string.tile_quickshare_on else R.string.tile_quickshare_off)
                updateTile()
            }
        }
    }
}
