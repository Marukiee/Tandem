package nl.markmaaktmedia.tandem

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import nl.markmaaktmedia.tandem.ui.AppRoot
import nl.markmaaktmedia.tandem.ui.theme.collectAppearance
import nl.markmaaktmedia.tandem.ui.theme.TandemTheme

// AppCompatActivity so the per-app language (Settings, Language) applies without a restart.
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only a fresh start reads the intent: after a rotation or a restart of the process it is the old one, and would run again.
        if (savedInstanceState == null) handleLink(intent)
        setContent {
            val appearance = graph.prefs.appearance.collectAppearance()
            TandemTheme(appearance) { AppRoot() }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    private fun handleLink(intent: android.content.Intent?) {
        intent?.data?.toString()?.takeIf { it.startsWith("tandem://") }?.let { graph.pairLink.value = it }
        // The actions below open pages and ask for nothing, but any app could send them to the exported activity. Only
        // what came through the alias that is not exported (see [internalIntent]) is ours.
        if (intent?.component?.className != INTERNAL_ENTRY) return
        if (intent?.action == nl.markmaaktmedia.tandem.quickshare.QuickShareTileService.ACTION_OPEN_PAGE) graph.openQuickShare.value = true
        if (intent?.action == ACTION_OPEN_DISPLAY) intent.getStringExtra(EXTRA_DEVICE)?.let { graph.openDisplay.value = it }
        if (intent?.action == ACTION_OPEN_MIRROR) intent.getStringExtra(EXTRA_DEVICE)?.let { graph.openMirror.value = it }
        if (intent?.action == ACTION_OPEN_DEVICE) intent.getStringExtra(EXTRA_DEVICE)?.let { graph.openDevice.value = it }
        if (intent?.action == ACTION_OPEN_ACCESS) graph.openAccess.value = true
        if (intent?.action == ACTION_OPEN_TOUR) graph.openTour.value = true
        if (intent?.action == ACTION_OPEN_SHARING) graph.openSharing.value = true
        if (intent?.action == ACTION_OPEN_SHIZUKU) graph.openShizuku.value = true
        if (intent?.action == ACTION_OPEN_TRANSFERS) graph.openTransfers.value = true
    }

    override fun onStart() {
        super.onStart()
        graph.appVisible = true
    }

    override fun onStop() {
        graph.appVisible = false
        super.onStop()
    }

    companion object {
        private const val INTERNAL_ENTRY = "nl.markmaaktmedia.tandem.InternalEntry"

        /** An intent for the actions below: it goes through an activity alias that other apps cannot start. */
        fun internalIntent(context: android.content.Context): android.content.Intent =
            android.content.Intent().setClassName(context.packageName, INTERNAL_ENTRY)

        /** From the notification of a computer that asks this phone to be its second screen. */
        const val ACTION_OPEN_DISPLAY = "nl.markmaaktmedia.tandem.OPEN_DISPLAY"
        const val ACTION_OPEN_MIRROR = "nl.markmaaktmedia.tandem.OPEN_MIRROR"
        const val EXTRA_DEVICE = "device"

        /** From a ring of the battery widget: the page of that device. */
        const val ACTION_OPEN_DEVICE = "nl.markmaaktmedia.tandem.OPEN_DEVICE"

        /** From another device that shows what this phone is missing: Tandem's page of permissions. */
        const val ACTION_OPEN_ACCESS = "nl.markmaaktmedia.tandem.OPEN_ACCESS"

        /** From the reminder of the tour: what Tandem can do and what still waits. */
        const val ACTION_OPEN_TOUR = "nl.markmaaktmedia.tandem.OPEN_TOUR"

        /** From another device: the page of Settings with sharing, where the mouse of a computer is switched on. */
        const val ACTION_OPEN_SHARING = "nl.markmaaktmedia.tandem.OPEN_SHARING"

        /** From the notification of a transfer that stopped halfway: the page Transfers, where Go on and Throw away are. */
        const val ACTION_OPEN_TRANSFERS = "nl.markmaaktmedia.tandem.OPEN_TRANSFERS"

        /** After Tandem started again to get the connection of Shizuku: back on its page. */
        const val ACTION_OPEN_SHIZUKU = "nl.markmaaktmedia.tandem.OPEN_SHIZUKU"
    }
}
