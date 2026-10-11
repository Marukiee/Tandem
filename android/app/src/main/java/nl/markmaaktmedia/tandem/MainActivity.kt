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
        handleLink(intent)
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
        if (intent?.action == nl.markmaaktmedia.tandem.quickshare.QuickShareTileService.ACTION_OPEN_PAGE) graph.openQuickShare.value = true
        if (intent?.action == ACTION_OPEN_DISPLAY) intent.getStringExtra(EXTRA_DEVICE)?.let { graph.openDisplay.value = it }
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
        /** From the notification of a computer that asks this phone to be its second screen. */
        const val ACTION_OPEN_DISPLAY = "nl.markmaaktmedia.tandem.OPEN_DISPLAY"
        const val EXTRA_DEVICE = "device"
    }
}
