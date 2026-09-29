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
    }
}
