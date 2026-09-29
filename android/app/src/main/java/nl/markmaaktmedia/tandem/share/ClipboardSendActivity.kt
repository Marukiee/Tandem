package nl.markmaaktmedia.tandem.share

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.engine.EngineState
import nl.markmaaktmedia.tandem.engine.TandemService
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.theme.collectAppearance
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import nl.markmaaktmedia.tandem.ui.theme.TandemMotion
import nl.markmaaktmedia.tandem.ui.theme.TandemTheme

/**
 * Sends the clipboard. Android only lets an app read the clipboard while one of its
 * own windows has focus, so this is a transparent window that opens, reads, sends and
 * closes. The Quick Settings tile and the button on the service notification both
 * open it. With one device online it does not even ask.
 */
class ClipboardSendActivity : ComponentActivity() {

    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        TandemService.start(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || handled) return
        handled = true
        val text = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (text.isBlank()) {
            Toast.makeText(this, R.string.share_clipboard_empty, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        lifecycleScope.launch {
            val host = graph.host
            withTimeoutOrNull(5000) { host.state.first { it == EngineState.Running } }
            val online = host.devices.value.filter { it.online }
            if (online.size <= 1) {
                val reached = if (online.isEmpty()) 0 else host.sendClipboard(online.map { it.id }, text)
                Toast.makeText(
                    this@ClipboardSendActivity,
                    if (reached > 0) resources.getQuantityString(R.plurals.share_done_n, reached, reached) else getString(R.string.share_none_online),
                    Toast.LENGTH_SHORT,
                ).show()
                finish()
            } else {
                showPicker(text)
            }
        }
    }

    private fun showPicker(text: String) {
        setContent {
            val appearance = graph.prefs.appearance.collectAppearance()
            TandemTheme(appearance, applySystemBarStyle = false) {
                val host = graph.host
                val devices by host.devices.collectAsState()
                val scope = rememberCoroutineScope()
                var phase by remember { mutableStateOf<SendPhase>(SendPhase.Choosing) }
                var visible by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { visible = true }
                val scrim by animateFloatAsState(if (visible) 0.5f else 0f, TandemMotion.fadeSpec(), label = "scrim")
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrim))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { finish() },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    AnimatedVisibility(visible, enter = slideInVertically(TandemMotion.spatial()) { it } + fadeIn(), exit = slideOutVertically(TandemMotion.spatial()) { it } + fadeOut()) {
                        SharePickerSheet(
                            devices = devices, phase = phase,
                            summary = text.take(80),
                            preview = {
                                Box(Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                                    Icon(TandemIcons.Paste, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(28.dp))
                                }
                            },
                            onSend = { targets ->
                                phase = SendPhase.Sending
                                scope.launch {
                                    val reached = runCatching { host.sendClipboard(targets, text) }.getOrDefault(0)
                                    phase = if (reached > 0) SendPhase.Done(reached) else SendPhase.Failed(getString(R.string.share_none_online))
                                    if (reached > 0) { delay(900); finish() }
                                }
                            },
                            onClose = { finish() },
                        )
                    }
                }
            }
        }
    }
}
