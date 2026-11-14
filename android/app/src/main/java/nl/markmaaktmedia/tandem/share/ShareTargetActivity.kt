package nl.markmaaktmedia.tandem.share

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** What was shared into the app: files, or else text. */
private data class Shared(
    val uris: List<Uri>,
    val text: String?,
)

/**
 * The target of Android's share sheet. Each paired device also shows up in the share
 * sheet by name (Direct Share), so choosing one skips this screen and sends at once.
 */
class ShareTargetActivity : ComponentActivity() {

    /** This share. It is kept over a turn or a fold of the phone, so the screen made again picks up the send that runs. */
    private lateinit var launch: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        launch = savedInstanceState?.getString(KEY_LAUNCH) ?: UUID.randomUUID().toString()
        val shared = parse(intent)
        if (shared == null) {
            Toast.makeText(this, R.string.share_empty, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Make sure Tandem is running, and keep the file open in case the grant ends.
        TandemService.start(this)
        val picked = intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID)?.let { ShareShortcuts.parse(this, it) }
        picked?.let { Log.i(TAG, "shared with a shortcut, picked in the share sheet: ${it.proven}") }
        if (picked?.proven == true) {
            directSend(shared, picked.target)
            return
        }
        // Chosen as "Quick Share" in the share menu: only the devices nearby are shown.
        val nearbyOnly = intent.component?.className?.endsWith("QuickShareTarget") == true
        setContent {
            val appearance = graph.prefs.appearance.collectAppearance()
            // A shortcut without the secret was not picked in the share sheet: what it names is ticked, and Send is still a tap.
            TandemTheme(appearance, applySystemBarStyle = false) { ShareScreen(shared, nearbyOnly, picked?.target) { finish() } }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_LAUNCH, launch)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && ::launch.isInitialized) ShareSends.forget(launch)
    }

    private fun parse(intent: Intent): Shared? {
        val streams = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.parcelable<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.parcelableList<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else -> emptyList()
        }
        if (streams.isNotEmpty()) {
            // Text that comes with files (the caption of a photo) stays behind: it would replace the clipboard of the computer.
            val uris = streams.filter { allowed(it) }
            return if (uris.isEmpty()) null else Shared(uris, null)
        }
        // Text with styling arrives as a CharSequence, and some apps only put it in the clip.
        val text = (intent.getCharSequenceExtra(Intent.EXTRA_TEXT) ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text)
            ?.toString()?.takeIf { it.isNotBlank() } ?: return null
        return Shared(emptyList(), text)
    }

    /**
     * Only content of another app. A file path or a provider of Tandem itself would be opened with Tandem's own rights, so any
     * app could have it send Tandem's private files (the ssh key of the phone). `0@authority` reaches the same provider, so
     * what comes before an @ is left off.
     */
    private fun allowed(uri: Uri): Boolean {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false
        val authority = uri.authority?.substringAfterLast('@')?.takeIf { it.isNotEmpty() } ?: return false
        val owner = runCatching { packageManager.resolveContentProvider(authority, 0)?.packageName }.getOrNull()
        val ours = owner == packageName || authority == packageName || authority.startsWith("$packageName.")
        if (ours) Log.w(TAG, "refused a share of $uri")
        return !ours
    }

    /** A device was picked in the share sheet itself: send without asking again. */
    private fun directSend(shared: Shared, target: ShareShortcuts.Target) {
        val app = applicationContext
        ShareSends.start(app, launch, again = false) { sendPicked(app, shared, target) }
        lifecycleScope.launch {
            val end = ShareSends.phase(launch).first { it is SendPhase.Done || it is SendPhase.Failed }
            val text = when (end) {
                is SendPhase.Done -> resources.getQuantityString(R.plurals.share_done_n, end.count, end.count)
                is SendPhase.Failed -> end.reason
                else -> ""
            }
            Toast.makeText(app, text, if (end is SendPhase.Failed) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    @Composable
    private fun ShareScreen(shared: Shared, nearbyOnly: Boolean, picked: ShareShortcuts.Target?, onClose: () -> Unit) {
        val app = LocalContext.current.applicationContext
        val host = LocalContext.current.graph.host
        val quick = LocalContext.current.graph.quickShare
        val devices by host.devices.collectAsState()
        val nearbyOn by quick.enabled.collectAsState()
        val nearby by quick.peers.collectAsState()
        val outgoing by quick.outgoing.collectAsState()
        val nearbyPin = outgoing.lastOrNull { it.state == nl.markmaaktmedia.tandem.quickshare.QuickShareHost.Outgoing.State.Sending }?.pin
        val scope = rememberCoroutineScope()
        val phase by ShareSends.phase(launch).collectAsState()
        val sheet = nl.markmaaktmedia.tandem.ui.components.rememberPullSheetState()
        val leave: () -> Unit = { scope.launch { sheet.close() } }
        val screenshot by androidx.compose.runtime.produceState(false, shared.uris) {
            value = withContext(Dispatchers.IO) { shared.uris.any { looksLikeScreenshot(app, it) } }
        }
        val ticked = when (picked) {
            ShareShortcuts.Target.All -> devices.filter { it.online }.map { it.id }.toSet()
            is ShareShortcuts.Target.Device -> setOf(picked.id)
            null -> emptySet()
        }
        // While Quick Share devices are shown, phones nearby are woken and looked for.
        androidx.compose.runtime.DisposableEffect(nearbyOn) {
            quick.looking(nearbyOn)
            onDispose { quick.looking(false) }
        }
        // Keyed on the phase, so a screen made again after a turn still closes when the send is done.
        LaunchedEffect(phase) {
            if (phase is SendPhase.Done) {
                delay(1000)
                sheet.close()
            }
        }

        nl.markmaaktmedia.tandem.ui.components.PullSheet(sheet, onGone = onClose, dismissable = { phase is SendPhase.Choosing }) {
            run {
                SharePickerSheet(
                    devices = devices,
                    phase = phase,
                    summary = summaryOf(shared, screenshot),
                    preview = { Preview(shared) },
                    onSend = { targets ->
                        ShareSends.start(app, launch, again = true) {
                            val reached = sendShared(app, shared, targets)
                            if (reached > 0) SendPhase.Done(reached) else SendPhase.Failed(app.getString(R.string.share_none_online))
                        }
                    },
                    onClose = leave,
                    ticked = ticked,
                    nearbyOn = nearbyOn,
                    nearbyOnly = nearbyOnly,
                    onEnableNearby = { quick.setEnabled(true) },
                    nearby = nearby,
                    nearbyPin = nearbyPin,
                    onSendNearby = { peer -> ShareSends.start(app, launch, again = true) { sendNearby(app, shared, peer, quick) } },
                )
            }
        }
    }

    @Composable
    private fun summaryOf(shared: Shared, screenshot: Boolean): String = when {
        screenshot -> androidx.compose.ui.res.stringResource(R.string.share_summary_screenshot)
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

    private companion object {
        const val TAG = "TandemShare"
        const val KEY_LAUNCH = "launch"
    }
}

/**
 * The sends of the share screen, by launch. They run in the scope of the app instead of the screen: a turn or a fold of the
 * phone makes the screen again, which used to cancel a send halfway or start it a second time.
 */
private object ShareSends {
    private val phases = ConcurrentHashMap<String, MutableStateFlow<SendPhase>>()

    fun phase(launch: String): StateFlow<SendPhase> = phases.getOrPut(launch) { MutableStateFlow(SendPhase.Choosing) }

    /** Starts the send of [launch], unless it is already going or done. With [again] one that failed may be tried once more. */
    fun start(context: Context, launch: String, again: Boolean, send: suspend () -> SendPhase) {
        val phase = phases.getOrPut(launch) { MutableStateFlow(SendPhase.Choosing) }
        synchronized(phase) {
            val now = phase.value
            if (now != SendPhase.Choosing && !(again && now is SendPhase.Failed)) return
            phase.value = SendPhase.Sending
        }
        context.graph.scope.launch {
            phase.value = try {
                send()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SendPhase.Failed(e.message ?: e.toString())
            }
        }
    }

    fun forget(launch: String) {
        phases.remove(launch)
    }
}

/** Waits for Tandem and the picked devices for a moment, then sends: a device that just woke may need a moment to show as online. */
private suspend fun sendPicked(context: Context, shared: Shared, target: ShareShortcuts.Target): SendPhase {
    val host = context.graph.host
    withTimeoutOrNull(6000) { host.state.first { it == EngineState.Running } }
        ?: return SendPhase.Failed(context.getString(R.string.share_not_ready))
    val wanted = when (target) {
        ShareShortcuts.Target.All -> host.devices.value.map { it.id }
        is ShareShortcuts.Target.Device -> listOf(target.id)
    }
    withTimeoutOrNull(3500) { host.devices.first { list -> list.any { it.id in wanted && it.online } } }
    return SendPhase.Done(sendShared(context, shared, wanted))
}

/**
 * Sends to the devices of [targets] that are online. Returns how many it reached. Every caller comes after a tap (on the
 * device sheet, or on a device in the share sheet itself), so a link may open on the other side.
 */
private suspend fun sendShared(context: Context, shared: Shared, targets: List<String>): Int {
    val host = context.graph.host
    val online = targets.filter { id -> host.devices.value.any { it.id == id && it.online } }
    if (online.isEmpty()) return 0
    if (shared.uris.isNotEmpty()) {
        val screenshot = withContext(Dispatchers.IO) { shared.uris.any { looksLikeScreenshot(context, it) } }
        return host.sendUris(shared.uris, online, if (screenshot) TandemShareOrigin.Screenshot else TandemShareOrigin.Files)
    }
    val text = shared.text ?: return 0
    val isUrl = text.startsWith("http://") || text.startsWith("https://")
    return host.engine?.sendText(online, text, isUrl, isUrl)?.size ?: 0
}

/** Sends what was shared to a device that Quick Share found, and waits for the end of it. */
private suspend fun sendNearby(
    context: Context, shared: Shared, peer: nl.markmaaktmedia.tandem.quickshare.QuickShareHost.Peer, quick: nl.markmaaktmedia.tandem.quickshare.QuickShareHost,
): SendPhase {
    val uris = shared.uris
    val id = if (uris.isEmpty()) {
        // Text and links go as what they are, not as a file.
        val text = shared.text ?: return SendPhase.Failed(context.getString(R.string.share_empty))
        quick.sendText(text, peer).getOrElse { return SendPhase.Failed(it.message ?: it.toString()) }
    } else {
        quick.send(uris, peer).getOrElse { return SendPhase.Failed(it.message ?: it.toString()) }
    }
    val end = withTimeoutOrNull(10 * 60_000L) {
        quick.outgoing.first { list -> list.any { it.id == id && it.state != nl.markmaaktmedia.tandem.quickshare.QuickShareHost.Outgoing.State.Sending } }
    }?.firstOrNull { it.id == id }
    return when (end?.state) {
        nl.markmaaktmedia.tandem.quickshare.QuickShareHost.Outgoing.State.Sent -> SendPhase.Done(maxOf(uris.size, 1))
        nl.markmaaktmedia.tandem.quickshare.QuickShareHost.Outgoing.State.Refused -> SendPhase.Failed(context.getString(R.string.quickshare_refused, peer.name))
        else -> SendPhase.Failed(context.getString(R.string.quickshare_failed))
    }
}

/** Opening a file of another app can mean fetching it first (a photo only in the cloud), so this runs off the main thread. */
private fun looksLikeScreenshot(context: Context, uri: Uri): Boolean = runCatching {
    context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use { c ->
        c.moveToFirst() && (c.getString(0).orEmpty().startsWith("Screenshot", true) || c.getString(1).orEmpty().contains("Screenshot", true))
    } ?: false
}.getOrDefault(false)

@Suppress("DEPRECATION")
inline fun <reified T : android.os.Parcelable> Intent.parcelable(key: String): T? =
    if (android.os.Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, T::class.java) else getParcelableExtra(key) as? T

@Suppress("DEPRECATION")
inline fun <reified T : android.os.Parcelable> Intent.parcelableList(key: String): List<T>? =
    if (android.os.Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(key, T::class.java) else getParcelableArrayListExtra<T>(key)
