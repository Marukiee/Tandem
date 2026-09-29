package nl.markmaaktmedia.tandem

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import nl.markmaaktmedia.tandem.ui.AppRoot
import nl.markmaaktmedia.tandem.ui.theme.Appearance
import nl.markmaaktmedia.tandem.ui.theme.TandemTheme

// AppCompatActivity so the per-app language (Settings, Language) applies without a restart.
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val appearance = graph.prefs.appearance.collectAsState(initial = Appearance()).value
            TandemTheme(appearance) { AppRoot() }
        }
    }
}
