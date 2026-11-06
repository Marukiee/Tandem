package nl.markmaaktmedia.tandem.ui.screens

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.annotation.StringRes
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.engine.EngineState
import nl.markmaaktmedia.tandem.engine.Permissions
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.components.PermissionStatus
import nl.markmaaktmedia.tandem.ui.components.PillSpinner
import nl.markmaaktmedia.tandem.ui.components.PrimaryPillButton
import nl.markmaaktmedia.tandem.ui.components.SecondaryPillButton
import nl.markmaaktmedia.tandem.ui.components.TandemDialog
import nl.markmaaktmedia.tandem.ui.components.TandemIconButton
import nl.markmaaktmedia.tandem.ui.components.bouncyClickable
import nl.markmaaktmedia.tandem.ui.components.rememberPermissionRequests
import nl.markmaaktmedia.tandem.ui.components.rememberPermissionStatus
import nl.markmaaktmedia.tandem.ui.theme.LocalTandemExtraColors
import nl.markmaaktmedia.tandem.ui.theme.PillShape
import nl.markmaaktmedia.tandem.ui.theme.SheetRadius
import nl.markmaaktmedia.tandem.ui.theme.SheetSquircle
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import nl.markmaaktmedia.tandem.ui.theme.TandemMotion
import nl.markmaaktmedia.tandem.ui.theme.squircleBrackets
import nl.markmaaktmedia.tandem.watch.WatchState
import uniffi.tandem_core.TandemException
import java.util.concurrent.Executors
import kotlin.math.abs

private enum class PairMode { Scan, Show, Watch }

/**
 * Why a pairing attempt failed, in the words of the app rather than the core.
 *
 * The core reports plain English sentences, and the language of the app is not
 * necessarily English, so the ones a person can act on are recognised here and shown
 * from resources. Anything else keeps its raw text, which is better than hiding it.
 */
private sealed interface PairFailure {
    /** [spent] codes never work again, so the camera must not offer them a second time. */
    class Known(@param:StringRes val title: Int, @param:StringRes val body: Int, val refresh: Boolean, val spent: Boolean = true) : PairFailure
    class Raw(val text: String) : PairFailure
}

private fun pairFailure(error: Throwable): PairFailure {
    val raw = when (error) {
        is TandemException.Pairing -> error.reason
        is TandemException.Failed -> error.reason
        else -> error.message ?: error.toString()
    }
    val text = raw.lowercase()
    return when {
        "has expired" in text -> PairFailure.Known(R.string.pair_err_expired_title, R.string.pair_err_expired_body, true)
        "did not match" in text -> PairFailure.Known(R.string.pair_err_mismatch_title, R.string.pair_err_mismatch_body, true)
        "not showing a pairing code" in text -> PairFailure.Known(R.string.pair_err_not_showing_title, R.string.pair_err_not_showing_body, true)
        "not a tandem pairing code" in text || "pairing code is damaged" in text ->
            PairFailure.Known(R.string.pair_err_invalid_title, R.string.pair_err_invalid_body, false)
        "newer version" in text -> PairFailure.Known(R.string.pair_err_newer_title, R.string.pair_err_newer_body, false)
        "from this device" in text -> PairFailure.Known(R.string.pair_err_own_title, R.string.pair_err_own_body, false)
        // The other device might just have been busy, so the same code stays worth another go.
        "did not answer" in text -> PairFailure.Known(R.string.pair_err_no_answer_title, R.string.pair_err_no_answer_body, false, spent = false)
        "too many tries" in text -> PairFailure.Known(R.string.pair_err_expired_title, R.string.pair_err_expired_body, true)
        "accepted these digits" in text -> PairFailure.Known(R.string.pair_err_digits_title, R.string.pair_err_digits_body, false, spent = false)
        "no device is showing a code" in text -> PairFailure.Known(R.string.pair_err_none_title, R.string.pair_err_none_body, false, spent = false)
        "has 8 digits" in text -> PairFailure.Known(R.string.pair_err_eight_title, R.string.pair_err_eight_body, false, spent = false)
        "in a circle already" in text || "already in a circle" in text ->
            PairFailure.Known(R.string.pair_err_circles_title, R.string.pair_err_circles_body, false, spent = false)
        "has to be updated" in text || "needs an update" in text ->
            PairFailure.Known(R.string.pair_err_update_title, R.string.pair_err_update_body, false, spent = false)
        else -> PairFailure.Raw(raw)
    }
}

/** Pairing: scan another device's code, show this device's own, or couple a watch. */
@Composable
fun PairScreen(onBack: () -> Unit, onPaired: () -> Unit, startWithWatch: Boolean = false) {
    val context = LocalContext.current
    val host = context.graph.host
    val watch = context.graph.watch
    val watchState by watch.state.collectAsState()
    val bluetooth = rememberPermissionStatus()
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf(if (startWithWatch && !watch.state.value.paired) PairMode.Watch else PairMode.Scan) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<PairFailure?>(null) }
    var joined by remember { mutableStateOf<String?>(null) }
    var watchRound by remember { mutableStateOf(0) }
    val spent = remember { mutableSetOf<String>() }

    // Open only while this tab is: during that time any watch nearby can offer itself to this phone.
    val wantsWatch = mode == PairMode.Watch && joined == null && !watchState.paired && watch.supported && bluetooth.bluetooth
    LaunchedEffect(wantsWatch, watchRound) { if (wantsWatch) watch.beginPairing() else watch.cancelPairing() }
    DisposableEffect(Unit) { onDispose { watch.cancelPairing() } }
    val watchName = stringResource(R.string.settings_cat_watch)
    LaunchedEffect(watchState.paired) { if (watchState.paired && mode == PairMode.Watch) joined = watchName }

    fun join(uri: String) {
        if (busy || joined != null) return
        busy = true
        failure = null
        scope.launch {
            try {
                // The engine starts a moment after the app does.
                withTimeoutOrNull(8000) { host.state.first { it == EngineState.Running } }
                val engine = host.engine ?: throw IllegalStateException(context.getString(R.string.share_not_ready))
                val id = engine.pairWithUri(uri)
                host.refreshDevices()
                joined = host.device(id)?.name ?: context.getString(R.string.pair_new_device)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = pairFailure(e)
                if (reason is PairFailure.Known && reason.spent) spent += uri
                failure = reason
            } finally {
                busy = false
            }
        }
    }

    // A pairing link opened from outside the app (a tandem:// link) can come from any page or app, and the device behind it would get this
    // phone's notifications, clipboard and files. So it asks first.
    var askLink by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        context.graph.pairLink.value?.let { link ->
            context.graph.pairLink.value = null
            askLink = link
        }
    }
    askLink?.let { link ->
        nl.markmaaktmedia.tandem.ui.components.TandemDialog(
            title = stringResource(R.string.pair_link_title),
            body = stringResource(R.string.pair_link_body),
            icon = TandemIcons.Link,
            onDismiss = { askLink = null },
            showClose = false,
            actions = {
                nl.markmaaktmedia.tandem.ui.components.PrimaryPillButton(
                    label = stringResource(R.string.pair_link_yes),
                    onClick = {
                        askLink = null
                        join(link)
                    },
                )
                nl.markmaaktmedia.tandem.ui.components.SecondaryPillButton(
                    label = stringResource(R.string.action_cancel),
                    onClick = { askLink = null },
                )
            },
        )
    }

    LaunchedEffect(joined) {
        if (joined != null) {
            delay(1700)
            onPaired()
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TandemIconButton(TandemIcons.Back, stringResource(R.string.action_back), onBack)
            Spacer(Modifier.weight(1f))
        }
        Text(stringResource(R.string.pair_title), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 4.dp))
        Spacer(Modifier.height(16.dp))

        AnimatedContent(
            targetState = joined,
            transitionSpec = { (fadeIn(TandemMotion.fadeSpec()) + scaleIn(TandemMotion.springy(), 0.9f)) togetherWith fadeOut(TandemMotion.fadeSpec()) },
            label = "pairState",
            modifier = Modifier.weight(1f),
        ) { done ->
            if (done != null) {
                PairedSuccess(done)
            } else {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    PairModeSwitch(
                        modes = if (watchState.paired) listOf(PairMode.Scan, PairMode.Show) else PairMode.entries,
                        mode = mode,
                        onSelect = { mode = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(20.dp))
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        AnimatedContent(
                            targetState = mode,
                            transitionSpec = {
                                val forward = targetState.ordinal > initialState.ordinal
                                (fadeIn(TandemMotion.fadeSpec()) + slideInHorizontally(TandemMotion.spatial()) { if (forward) it / 6 else -it / 6 }) togetherWith
                                    (fadeOut(TandemMotion.fadeSpec()) + slideOutHorizontally(TandemMotion.spatial()) { if (forward) -it / 6 else it / 6 })
                            },
                            label = "pairMode",
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize(),
                        ) { m ->
                            when (m) {
                                // Gone at once when another tab is chosen: a camera picture sliding away only looks like a delay. The
                                // field under it goes along, because a row that folds away under this box would move everything down.
                                PairMode.Scan -> if (mode == PairMode.Scan) {
                                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                            ScanPanel(
                                                onCode = { code -> if (code !in spent) join(code) },
                                                active = !busy && failure == null,
                                                locked = busy,
                                            )
                                        }
                                        PasteRow(onSubmit = ::join)
                                    }
                                } else {
                                    Spacer(Modifier.fillMaxSize())
                                }
                                PairMode.Show -> ShowPanel()
                                PairMode.Watch -> WatchPanel(
                                    state = watchState,
                                    supported = watch.supported,
                                    status = bluetooth,
                                    onMatch = watch::confirmPairing,
                                    onDiffers = watch::cancelPairing,
                                    onRetry = { watchRound++ },
                                )
                            }
                        }
                    }
                    AnimatedVisibility(
                        visible = busy,
                        enter = expandVertically(TandemMotion.sizeSpring()) + fadeIn(TandemMotion.fadeSpec()),
                        exit = shrinkVertically(TandemMotion.sizeSpring()) + fadeOut(TandemMotion.fadeSpec()),
                    ) {
                        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PillSpinner(size = 22.dp)
                            Text(stringResource(R.string.pair_connecting), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }

    failure?.let { shown ->
        val dismiss = { failure = null }
        when (shown) {
            is PairFailure.Known -> TandemDialog(
                title = stringResource(shown.title),
                body = stringResource(shown.body),
                icon = if (shown.refresh) TandemIcons.Refresh else TandemIcons.Info,
                onDismiss = dismiss,
                closeLabel = stringResource(R.string.action_close),
                actions = { PrimaryPillButton(stringResource(R.string.generic_ok), dismiss) },
            )
            is PairFailure.Raw -> TandemDialog(
                title = stringResource(R.string.pair_err_title),
                body = shown.text,
                icon = TandemIcons.Error,
                iconTint = MaterialTheme.colorScheme.error,
                onDismiss = dismiss,
                closeLabel = stringResource(R.string.action_close),
                actions = { PrimaryPillButton(stringResource(R.string.generic_ok), dismiss) },
            )
        }
    }
}

/**
 * The ways to pair, as parts of the same width with one pill travelling between them.
 * Same stretch and squash as the navigation pill, so the two move like one object. The
 * shared segmented row sizes each choice to its label, which is right for filters and
 * wrong here, where equal choices should look equal.
 */
@Composable
private fun PairModeSwitch(modes: List<PairMode>, mode: PairMode, onSelect: (PairMode) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val target = modes.indexOf(mode).coerceAtLeast(0).toFloat()
    val position = remember { Animatable(target) }
    LaunchedEffect(target) { position.animateTo(target, TandemMotion.spatial()) }

    Box(modifier.clip(PillShape).background(scheme.surfaceContainerHigh).padding(4.dp)) {
        Box(
            Modifier
                .fillMaxWidth(1f / modes.size)
                .height(SwitchHeight)
                .graphicsLayer {
                    translationX = position.value * size.width
                    val remaining = abs(target - position.value).coerceIn(0f, 1.5f)
                    scaleX = 1f + remaining * 0.16f
                    scaleY = 1f - remaining * 0.05f
                }
                .clip(PillShape)
                .background(scheme.primary),
        )
        Row {
            modes.forEach { option ->
                val selected = option == mode
                val content by animateColorAsState(
                    if (selected) scheme.onPrimary else scheme.onSurfaceVariant,
                    TandemMotion.colourSpec(), label = "switchContent",
                )
                Row(
                    Modifier
                        .weight(1f)
                        .height(SwitchHeight)
                        .clip(PillShape)
                        .bouncyClickable { onSelect(option) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                ) {
                    Icon(
                        when (option) {
                            PairMode.Scan -> TandemIcons.QrScan
                            PairMode.Show -> TandemIcons.QrShow
                            PairMode.Watch -> TandemIcons.Watch
                        },
                        null,
                        tint = content,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        stringResource(
                            when (option) {
                                PairMode.Scan -> R.string.pair_tab_scan
                                PairMode.Show -> R.string.pair_tab_show
                                PairMode.Watch -> R.string.settings_cat_watch
                            },
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = content,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private val SwitchHeight = 46.dp

@Composable
private fun PairedSuccess(name: String) {
    val scale = remember { Animatable(0.3f) }
    LaunchedEffect(Unit) { scale.animateTo(1f, TandemMotion.bouncy()) }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(
            Modifier
                .size(120.dp)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                }
                .clip(CircleShape)
                .background(LocalTandemExtraColors.current.online.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(TandemIcons.CheckCircleFilled, null, tint = LocalTandemExtraColors.current.online, modifier = Modifier.size(64.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.pair_done, name), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.pair_done_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun PasteRow(onSubmit: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        SecondaryPillButton(stringResource(R.string.pair_have_link), { open = !open }, icon = TandemIcons.Link)
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(TandemMotion.sizeSpring()) + fadeIn(TandemMotion.fadeSpec()),
            exit = shrinkVertically(TandemMotion.sizeSpring()) + fadeOut(TandemMotion.fadeSpec()),
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.pair_paste_hint)) },
                    keyboardOptions = KeyboardOptions.Default,
                    shape = MaterialTheme.shapes.large,
                )
                PrimaryPillButton(stringResource(R.string.pair_join), { onSubmit(text.ifBlank { clipboard.getText()?.text.orEmpty() }) })
            }
        }
    }
}

// ---- Scanning ------------------------------------------------------------------

@Composable
private fun ScanPanel(onCode: (String) -> Unit, active: Boolean, locked: Boolean) {
    val status = rememberPermissionStatus()
    val requests = rememberPermissionRequests(status)

    if (!status.camera) {
        val blocked = status.isBlocked(android.Manifest.permission.CAMERA)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(24.dp)) {
            Box(Modifier.size(84.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(TandemIcons.QrScan, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(38.dp))
            }
            Text(
                stringResource(if (blocked) R.string.perm_blocked else R.string.pair_camera_why),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PrimaryPillButton(
                stringResource(if (blocked) R.string.perm_open_app_info else R.string.action_allow),
                requests::camera,
            )
        }
        return
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(
            Modifier.weight(1f, fill = false).fillMaxWidth().aspectRatio(1f).clip(SheetSquircle).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            CameraPreview(onCode = onCode, active = active)
            ViewfinderCorners(locked)
        }
        Text(
            stringResource(R.string.pair_scan_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

@Composable
private fun CameraPreview(onCode: (String) -> Unit, active: Boolean) {
    val lifecycle = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
    }
    var last by remember { mutableStateOf("") }
    var streaming by remember { mutableStateOf(false) }
    // The analyzer outlives recompositions, so it has to read the latest values, not the first.
    val currentActive by androidx.compose.runtime.rememberUpdatedState(active)
    val currentOnCode by androidx.compose.runtime.rememberUpdatedState(onCode)

    DisposableEffect(Unit) {
        onDispose {
            executor.shutdown()
            scanner.close()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val view = PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
            view.previewStreamState.observe(lifecycle) { streaming = it == PreviewView.StreamState.STREAMING }
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(executor) { proxy ->
                    val media = proxy.image
                    if (media == null) {
                        proxy.close()
                        return@setAnalyzer
                    }
                    scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                        .addOnSuccessListener { codes ->
                            codes.firstNotNullOfOrNull { it.rawValue }?.let { value ->
                                if (currentActive && value.startsWith("tandem://") && value != last) {
                                    last = value
                                    view.post { currentOnCode(value) }
                                }
                            }
                        }
                        .addOnCompleteListener { proxy.close() }
                }
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }
            }, ContextCompat.getMainExecutor(ctx))
            view
        },
    )

    // The frame is calm until the first picture arrives, instead of flashing black.
    val cover by animateFloatAsState(if (streaming) 0f else 1f, TandemMotion.fadeSpec(), label = "cameraCover")
    if (cover > 0.01f) {
        Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = cover }.background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(TandemIcons.QrScan, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(44.dp))
        }
    }
}

/**
 * Four corner brackets that breathe while looking, then close in and turn green on a
 * code, so the frame answers the moment something is found.
 *
 * White on purpose: they sit on a live camera picture, where no theme colour is
 * guaranteed to contrast.
 */
@Composable
private fun ViewfinderCorners(locked: Boolean) {
    val found = LocalTandemExtraColors.current.online
    val color by animateColorAsState(if (locked) found else Color.White.copy(alpha = 0.92f), TandemMotion.colourSpec(), label = "cornerColour")
    val inset by animateDpAsState(if (locked) 26.dp else 16.dp, TandemMotion.springy(), label = "cornerInset")
    val transition = rememberInfiniteTransition(label = "viewfinder")
    val pulse by transition.animateFloat(
        0.9f, 1f,
        infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    Canvas(Modifier.fillMaxSize().padding(inset)) {
        // The curve of the picture they sit on, smaller by the distance to its edge, so that the two run parallel.
        val radius = (SheetRadius - inset).toPx()
        val arm = size.minDimension * 0.07f * (if (locked) 1f else pulse)
        drawPath(squircleBrackets(0f, 0f, size.width, size.height, radius, arm), color, style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round))
    }
}

// ---- A watch -------------------------------------------------------------------

private enum class WatchStep { TooOld, NeedsBluetooth, Looking, Compare, Confirming, Stopped }

/** Coupling a watch, one thing to do at a time. The words on the steps are the ones on the watch's own button. */
@Composable
private fun WatchPanel(
    state: WatchState,
    supported: Boolean,
    status: PermissionStatus,
    onMatch: () -> Unit,
    onDiffers: () -> Unit,
    onRetry: () -> Unit,
) {
    val requests = rememberPermissionRequests(status)
    // Closed before it ever opened is only the moment it takes to start, not something that went wrong.
    var seenOpen by remember { mutableStateOf(false) }
    LaunchedEffect(state.pairingOpen) { if (state.pairingOpen) seenOpen = true }
    var confirmed by remember(state.code) { mutableStateOf(false) }
    val step = when {
        !supported -> WatchStep.TooOld
        !status.bluetooth -> WatchStep.NeedsBluetooth
        state.code != null -> if (confirmed) WatchStep.Confirming else WatchStep.Compare
        seenOpen && !state.pairingOpen -> WatchStep.Stopped
        else -> WatchStep.Looking
    }
    AnimatedContent(
        targetState = step,
        transitionSpec = { (fadeIn(TandemMotion.fadeSpec()) + scaleIn(TandemMotion.springy(), 0.96f)) togetherWith fadeOut(TandemMotion.fadeSpec()) },
        contentAlignment = Alignment.Center,
        label = "watchStep",
    ) { current ->
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            when (current) {
                WatchStep.TooOld -> WatchNote(TandemIcons.Watch, stringResource(R.string.watch_needs_android_13))
                WatchStep.NeedsBluetooth -> {
                    val blocked = status.isBlocked(Permissions.bluetoothPermissions.toList())
                    WatchNote(TandemIcons.Bluetooth, stringResource(if (blocked) R.string.perm_blocked else R.string.watch_bluetooth_why))
                    PrimaryPillButton(stringResource(if (blocked) R.string.perm_open_app_info else R.string.action_allow), requests::bluetooth)
                }
                WatchStep.Looking -> {
                    WatchBeacon()
                    Column(
                        Modifier.fillMaxWidth().clip(SheetSquircle).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        WatchInstruction(1, stringResource(R.string.watch_step_open))
                        WatchInstruction(2, stringResource(R.string.watch_step_tap))
                    }
                    WatchProgress(stringResource(R.string.watch_waiting))
                }
                WatchStep.Compare -> {
                    Text(
                        stringResource(R.string.watch_code_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    WatchCode(state.code.orEmpty())
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryPillButton(
                            stringResource(R.string.watch_code_match),
                            {
                                confirmed = true
                                onMatch()
                            },
                            Modifier.fillMaxWidth(),
                            icon = TandemIcons.Check,
                        )
                        SecondaryPillButton(stringResource(R.string.watch_code_differs), onDiffers, Modifier.fillMaxWidth())
                    }
                }
                WatchStep.Confirming -> {
                    WatchCode(state.code.orEmpty())
                    WatchProgress(stringResource(R.string.watch_confirm_on_watch))
                }
                WatchStep.Stopped -> {
                    WatchNote(TandemIcons.Watch, stringResource(R.string.watch_pair_stopped), stringResource(R.string.watch_pair_stopped_body))
                    PrimaryPillButton(
                        stringResource(R.string.watch_pair_again),
                        {
                            seenOpen = false
                            onRetry()
                        },
                        icon = TandemIcons.Refresh,
                    )
                }
            }
        }
    }
}

/** A watch with rings going out from it, for as long as the phone is looking for one. */
@Composable
private fun WatchBeacon() {
    val ring = MaterialTheme.colorScheme.primary
    val transition = rememberInfiniteTransition(label = "beacon")
    val wave by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "wave")
    Box(Modifier.size(196.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val inner = 54.dp.toPx()
            val outer = size.minDimension / 2 - 2.dp.toPx()
            for (i in 0..1) {
                val phase = (wave + i * 0.5f) % 1f
                drawCircle(ring.copy(alpha = (1f - phase) * 0.4f), inner + (outer - inner) * phase, style = Stroke(3.dp.toPx()))
            }
        }
        Box(Modifier.size(108.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(TandemIcons.Watch, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(52.dp))
        }
    }
}

@Composable
private fun WatchInstruction(number: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
            Text(number.toString(), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimary)
        }
        Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun WatchProgress(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PillSpinner(size = 22.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Big, because it gets read off and compared with a much smaller screen an arm's length away. */
@Composable
private fun WatchCode(code: String) {
    Box(
        Modifier.fillMaxWidth().clip(SheetSquircle).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(vertical = 36.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            code,
            style = MaterialTheme.typography.displayLarge.copy(fontFeatureSettings = "tnum", letterSpacing = 3.sp),
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
    }
}

@Composable
private fun WatchNote(icon: androidx.compose.ui.graphics.painter.Painter, title: String, body: String? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(84.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(38.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        body?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

// ---- Showing -------------------------------------------------------------------

/** How long a code works, which is also what the countdown under it runs over. */
private const val CodeLifetimeMs = 300_000L

/** The space between the tile and the outside of the frame around it. */
private val FrameSpace = 22.dp

@Composable
private fun ShowPanel() {
    val context = LocalContext.current
    val host = context.graph.host
    val clipboard = LocalClipboardManager.current
    var uri by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf<String?>(null) }
    var expiresAt by remember { mutableStateOf(0L) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var round by remember { mutableStateOf(0) }

    val engineState by host.state.collectAsState()
    LaunchedEffect(round, engineState) {
        runCatching { host.engine?.createPairingOffer() }.getOrNull()?.let {
            uri = it.uri
            code = it.code
            expiresAt = it.expiresAtMs.toLong()
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    DisposableEffect(Unit) { onDispose { host.engine?.cancelPairingOffer() } }

    val expired = uri != null && now > expiresAt
    val left = if (uri == null) CodeLifetimeMs else (expiresAt - now).coerceIn(0L, CodeLifetimeMs)
    val remaining by animateFloatAsState(left / CodeLifetimeMs.toFloat(), tween(500, easing = LinearEasing), label = "countdown")
    val primary = MaterialTheme.colorScheme.primary
    // A QR code needs a light ground to scan, so it keeps one in both themes. The tile
    // goes back to the theme the moment there is no code on it.
    val tile by animateColorAsState(if (expired) MaterialTheme.colorScheme.surfaceContainerHigh else Color.White, TandemMotion.colourSpec(), label = "qrTile")
    val bitmap = remember(uri) { uri?.let { qrBitmap(it) } }

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // Leaves room for the countdown, the hint and the copy button, and never grows past what scans well.
        val side = minOf(maxWidth - (FrameSpace + 16.dp) * 2, maxHeight - 330.dp).coerceIn(170.dp, 300.dp)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(Modifier.size(side + FrameSpace * 2), contentAlignment = Alignment.Center) {
                CodeFrame(locked = bitmap != null && !expired)
                Box(Modifier.size(side).clip(SheetSquircle).background(tile), contentAlignment = Alignment.Center) {
                    AnimatedContent(
                        targetState = when {
                            expired -> 2
                            bitmap != null -> 1
                            else -> 0
                        },
                        transitionSpec = { (fadeIn(TandemMotion.fadeSpec()) + scaleIn(TandemMotion.springy(), 0.92f)) togetherWith fadeOut(TandemMotion.fadeSpec()) },
                        contentAlignment = Alignment.Center,
                        label = "qrState",
                    ) { state ->
                        when (state) {
                            2 -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(16.dp)) {
                                Text(stringResource(R.string.pair_expired), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    stringResource(R.string.pair_expired_body),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center,
                                )
                                Spacer(Modifier.height(6.dp))
                                PrimaryPillButton(stringResource(R.string.pair_new_code), {
                                    uri = null
                                    round++
                                }, icon = TandemIcons.Refresh)
                            }
                            1 -> bitmap?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(16.dp)) }
                            else -> PillSpinner(size = 40.dp, color = primary)
                        }
                    }
                }
            }
            Countdown(remaining, left, Modifier.width(side))
            AnimatedVisibility(
                visible = code != null && !expired,
                enter = expandVertically(TandemMotion.sizeSpring()) + fadeIn(TandemMotion.fadeSpec()),
                exit = shrinkVertically(TandemMotion.sizeSpring()) + fadeOut(TandemMotion.fadeSpec()),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.pair_show_code_label), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        code.orEmpty(),
                        style = MaterialTheme.typography.headlineLarge.copy(fontFeatureSettings = "tnum", letterSpacing = 2.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Text(
                stringResource(R.string.pair_show_hint),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp),
            )
            SecondaryPillButton(stringResource(R.string.pair_copy_link), { uri?.let { clipboard.setText(AnnotatedString(it)) } }, icon = TandemIcons.Copy)
        }
    }
}

/**
 * Four corner brackets around the code, the frame a camera draws around what it is about to read, so the screen
 * that shows a code and the one that scans it look like two halves of the same thing. They close in on the code
 * once there is one, the way the corners on the scanner close in on a find.
 */
@Composable
private fun CodeFrame(locked: Boolean) {
    val colour by animateColorAsState(
        if (locked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        TandemMotion.colourSpec(), label = "frameColour",
    )
    val inset by animateDpAsState(if (locked) 10.dp else 0.dp, TandemMotion.springy(), label = "frameInset")
    Canvas(Modifier.fillMaxSize()) {
        val stroke = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
        val left = inset.toPx()
        val top = left
        val right = size.width - left
        val bottom = size.height - top
        // The curve runs parallel to the tile inside it: the tile's corner plus the space between them.
        val r = (SheetRadius + FrameSpace - 10.dp).toPx()
        drawPath(squircleBrackets(left, top, right, bottom, r, 16.dp.toPx()), colour, style = stroke)
    }
}

/** How long the code still works: a bar that drains, and the time it has left. */
@Composable
private fun Countdown(fraction: Float, leftMs: Long, modifier: Modifier = Modifier) {
    val seconds = (leftMs + 999) / 1000
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.weight(1f).height(6.dp).clip(PillShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).clip(PillShape).background(MaterialTheme.colorScheme.primary))
        }
        Text(
            "%d:%02d".format(seconds / 60, seconds % 60),
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The dark indigo the code is drawn in: the colour of the app, and still about 16 to 1 against the white tile. */
private const val CodeInk = 0xFF1B1A4A.toInt()

/** Draws the QR with sharp modules and a quiet zone, so it scans from across a room. */
private fun qrBitmap(text: String, size: Int = 720): Bitmap {
    val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0)
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    val pixels = IntArray(size * size) { i -> if (matrix.get(i % size, i / size)) CodeInk else AndroidColor.WHITE }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}
