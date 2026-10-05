package nl.markmaaktmedia.tandem.share

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.engine.EngineState
import nl.markmaaktmedia.tandem.engine.TandemService
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.theme.collectAppearance
import nl.markmaaktmedia.tandem.ui.theme.SquircleShape
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import nl.markmaaktmedia.tandem.ui.theme.TandemMotion
import nl.markmaaktmedia.tandem.ui.theme.TandemTheme
import uniffi.tandem_core.TandemShareOrigin

/** What was shared into the app. */
private data class Shared(
    val uris: List<Uri>,
    val text: String?,
    val origin: TandemShareOrigin,
)

/**
 * The target of Android's share sheet. Each paired device also shows up in the share
 * sheet by name (Direct Share), so choosing one skips this screen and sends at once.
 */
class ShareTargetActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val shared = parse(intent)
        if (shared == null) {
            Toast.makeText(this, R.string.share_empty, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Make sure Tandem is running, and keep the file open in case the grant ends.
        TandemService.start(this)
        val shortcutId = intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID)
        if (shortcutId != null) {
            directSend(shared, shortcutId)
            return
        }
        // Chosen as "Quick Share" in the share menu: only the devices nearby are shown.
        val nearbyOnly = intent.component?.className?.endsWith("QuickShareTarget") == true
        setContent {
            val appearance = graph.prefs.appearance.collectAppearance()
            TandemTheme(appearance, applySystemBarStyle = false) { ShareScreen(shared, nearbyOnly) { finish() } }
        }
    }

    private fun parse(intent: Intent): Shared? {
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.parcelable<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.parcelableList<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else -> emptyList()
        }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
        if (uris.isEmpty() && text == null) return null
        val screenshot = uris.any { looksLikeScreenshot(it) }
        return Shared(uris, text, if (screenshot) TandemShareOrigin.Screenshot else TandemShareOrigin.Files)
    }

    private fun looksLikeScreenshot(uri: Uri): Boolean = runCatching {
        contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use { c ->
            c.moveToFirst() && (c.getString(0).orEmpty().startsWith("Screenshot", true) || c.getString(1).orEmpty().contains("Screenshot", true))
        } ?: false
    }.getOrDefault(false)

    /** A device was picked in the share sheet itself: send without asking again. */
    private fun directSend(shared: Shared, shortcutId: String) {
        val graph = graph
        lifecycleScope.launch {
            val ready = withTimeoutOrNull(6000) { graph.host.state.first { it == EngineState.Running } }
            if (ready == null) {
                Toast.makeText(this@ShareTargetActivity, R.string.share_not_ready, Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            val wanted = if (shortcutId == ShareShortcuts.ALL) {
                graph.host.devices.value.map { it.id }
            } else {
                listOf(shortcutId.removePrefix(ShareShortcuts.DEVICE_PREFIX))
            }
            // A device that just woke may need a moment to show as online.
            withTimeoutOrNull(3500) { graph.host.devices.first { list -> list.any { it.id in wanted && it.online } } }
            val reached = send(shared, wanted)
            Toast.makeText(this@ShareTargetActivity, resources.getQuantityString(R.plurals.share_done_n, reached, reached), Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private suspend fun send(shared: Shared, targets: List<String>): Int {
        val host = graph.host
        val online = targets.filter { id -> host.devices.value.any { it.id == id && it.online } }
        if (online.isEmpty()) return 0
        var reached = 0
        if (shared.uris.isNotEmpty()) reached = host.sendUris(shared.uris, online, shared.origin)
        shared.text?.let { text ->
            val isUrl = text.startsWith("http://") || text.startsWith("https://")
            reached = maxOf(reached, host.engine?.sendText(online, text, isUrl, isUrl)?.size ?: 0)
        }
        return reached
    }

    @Composable
    private fun ShareScreen(shared: Shared, nearbyOnly: Boolean, onClose: () -> Unit) {
        val host = LocalContext.current.graph.host
        val quick = LocalContext.current.graph.quickShare
        val devices by host.devices.collectAsState()
        val nearbyOn by quick.enabled.collectAsState()
        val nearby by quick.peers.collectAsState()
        val outgoing by quick.outgoing.collectAsState()
        val nearbyPin = outgoing.lastOrNull { it.state == nl.markmaaktmedia.tandem.quickshare.QuickShareHost.Outgoing.State.Sending }?.pin
        val scope = rememberCoroutineScope()
        var phase by remember { mutableStateOf<SendPhase>(SendPhase.Choosing) }
        var visible by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { visible = true }

        val scrim by animateFloatAsState(if (visible) 0.5f else 0f, TandemMotion.fadeSpec(), label = "scrim")
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrim))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { if (phase is SendPhase.Choosing) onClose() },
            contentAlignment = Alignment.BottomCenter,
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = slideInVertically(TandemMotion.spatial()) { it } + fadeIn(),
                exit = slideOutVertically(TandemMotion.spatial()) { it } + fadeOut(),
            ) {
                SharePickerSheet(
                    devices = devices,
                    phase = phase,
                    summary = summaryOf(shared),
                    preview = { Preview(shared) },
                    onSend = { targets ->
                        phase = SendPhase.Sending
                        scope.launch {
                            val reached = runCatching { send(shared, targets) }.getOrElse {
                                phase = SendPhase.Failed(it.message ?: it.toString())
                                return@launch
                            }
                            phase = if (reached > 0) SendPhase.Done(reached) else SendPhase.Failed(getString(R.string.share_none_online))
                            if (reached > 0) {
                                delay(1000)
                                visible = false
                                delay(250)
                                onClose()
                            }
                        }
                    },
                    onClose = onClose,
                    nearbyOn = nearbyOn,
                    nearbyOnly = nearbyOnly,
                    onEnableNearby = { quick.setEnabled(true) },
                    nearby = nearby,
                    nearbyPin = nearbyPin,
                    onSendNearby = { peer ->
                        phase = SendPhase.Sending
                        scope.launch {
                            val result = sendNearby(shared, peer, quick)
                            phase = result
                            if (result is SendPhase.Done) {
                                delay(1000)
                                visible = false
                                delay(250)
                                onClose()
                            }
                        }
                    },
                )
            }
        }
    }

    /** Sends what was shared to a device that Quick Share found, and waits for the end of it. */
    private suspend fun sendNearby(
        shared: Shared, peer: nl.markmaaktmedia.tandem.quickshare.QuickShareHost.Peer, quick: nl.markmaaktmedia.tandem.quickshare.QuickShareHost,
    ): SendPhase {
        val uris = shared.uris
        val id = if (uris.isEmpty()) {
            // Text and links go as what they are, not as a file.
            val text = shared.text ?: return SendPhase.Failed(getString(R.string.share_empty))
            quick.sendText(text, peer).getOrElse { return SendPhase.Failed(it.message ?: it.toString()) }
        } else {
            quick.send(uris, peer).getOrElse { return SendPhase.Failed(it.message ?: it.toString()) }
        }
        val end = withTimeoutOrNull(10 * 60_000L) {
            quick.outgoing.first { list -> list.any { it.id == id && it.state != nl.markmaaktmedia.tandem.quickshare.QuickShareHost.Outgoing.State.Sending } }
        }?.firstOrNull { it.id == id }
        return when (end?.state) {
            nl.markmaaktmedia.tandem.quickshare.QuickShareHost.Outgoing.State.Sent -> SendPhase.Done(maxOf(uris.size, 1))
            nl.markmaaktmedia.tandem.quickshare.QuickShareHost.Outgoing.State.Refused -> SendPhase.Failed(getString(R.string.quickshare_refused, peer.name))
            else -> SendPhase.Failed(getString(R.string.quickshare_failed))
        }
    }

    @Composable
    private fun summaryOf(shared: Shared): String = when {
        shared.origin == TandemShareOrigin.Screenshot -> androidx.compose.ui.res.stringResource(R.string.share_summary_screenshot)
        shared.uris.isNotEmpty() -> androidx.compose.ui.res.pluralStringResource(R.plurals.file_count, shared.uris.size, shared.uris.size)
        shared.text?.startsWith("http") == true -> shared.text
        else -> androidx.compose.ui.res.stringResource(R.string.share_summary_text)
    }

    @Composable
    private fun Preview(shared: Shared) {
        val context = LocalContext.current
        val thumbnail by androidx.compose.runtime.produceState<Bitmap?>(null, shared.uris.firstOrNull()) {
            val uri = shared.uris.firstOrNull() ?: return@produceState
            value = withContext(Dispatchers.IO) { runCatching { context.contentResolver.loadThumbnail(uri, Size(256, 256), null) }.getOrNull() }
        }
        Box(Modifier.size(64.dp).clip(SquircleShape(18.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            val bitmap = thumbnail
            if (bitmap != null) {
                Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Icon(if (shared.text != null) TandemIcons.Link else TandemIcons.File, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(28.dp))
            }
        }
    }
}

@Suppress("DEPRECATION")
inline fun <reified T : android.os.Parcelable> Intent.parcelable(key: String): T? =
    if (android.os.Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, T::class.java) else getParcelableExtra(key) as? T

@Suppress("DEPRECATION")
inline fun <reified T : android.os.Parcelable> Intent.parcelableList(key: String): List<T>? =
    if (android.os.Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(key, T::class.java) else getParcelableArrayListExtra<T>(key)
