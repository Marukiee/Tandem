package nl.markmaaktmedia.tandem.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.components.PillSpinner
import nl.markmaaktmedia.tandem.ui.components.PrimaryPillButton
import nl.markmaaktmedia.tandem.ui.components.SecondaryPillButton
import nl.markmaaktmedia.tandem.ui.components.SegmentedPillRow
import nl.markmaaktmedia.tandem.ui.components.TandemIconButton
import nl.markmaaktmedia.tandem.ui.theme.LocalTandemExtraColors
import nl.markmaaktmedia.tandem.ui.theme.SheetSquircle
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import nl.markmaaktmedia.tandem.ui.theme.TandemMotion
import java.util.concurrent.Executors

private enum class PairMode { Scan, Show }

/** Pairing: scan another device's code, or show this device's own. */
@Composable
fun PairScreen(onBack: () -> Unit, onPaired: () -> Unit) {
    val context = LocalContext.current
    val host = context.graph.host
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf(PairMode.Scan) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var joined by remember { mutableStateOf<String?>(null) }

    fun join(uri: String) {
        if (busy || joined != null) return
        busy = true
        error = null
        scope.launch {
            try {
                val id = host.engine!!.pairWithUri(uri)
                host.refreshDevices()
                joined = host.device(id)?.name ?: context.getString(R.string.pair_new_device)
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally {
                busy = false
            }
        }
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
        Text(stringResource(R.string.pair_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 4.dp))
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
                    SegmentedPillRow(
                        options = PairMode.entries,
                        selected = mode,
                        label = { if (it == PairMode.Scan) context.getString(R.string.pair_scan) else context.getString(R.string.pair_show) },
                        onSelect = { mode = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(20.dp))
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        AnimatedContent(
                            targetState = mode,
                            transitionSpec = { fadeIn(TandemMotion.fadeSpec()) togetherWith fadeOut(TandemMotion.fadeSpec()) },
                            label = "pairMode",
                        ) { m ->
                            when (m) {
                                PairMode.Scan -> ScanPanel(onCode = ::join, busy = busy)
                                PairMode.Show -> ShowPanel()
                            }
                        }
                    }
                    if (busy) {
                        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PillSpinner(size = 22.dp)
                            Text(stringResource(R.string.pair_connecting), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
                    }
                    PasteRow(onSubmit = ::join)
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun PairedSuccess(name: String) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val scale by animateFloatAsState(if (shown) 1f else 0.3f, TandemMotion.bouncy(), label = "successScale")
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(
            Modifier.size((120 * scale).dp).clip(CircleShape).background(LocalTandemExtraColors.current.online.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(TandemIcons.CheckCircleFilled, null, tint = LocalTandemExtraColors.current.online, modifier = Modifier.size((64 * scale).dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.pair_done, name), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
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
        if (open) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
private fun ScanPanel(onCode: (String) -> Unit, busy: Boolean) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }

    if (!granted) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(24.dp)) {
            Icon(TandemIcons.QrScan, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
            Text(stringResource(R.string.pair_camera_why), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PrimaryPillButton(stringResource(R.string.action_allow), { request.launch(Manifest.permission.CAMERA) })
        }
        return
    }

    Box(
        Modifier.fillMaxWidth().aspectRatio(1f).clip(SheetSquircle).background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        CameraPreview(onCode = onCode, active = !busy)
        ViewfinderCorners()
    }
}

@Composable
private fun CameraPreview(onCode: (String) -> Unit, active: Boolean) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
    }
    var last by remember { mutableStateOf("") }

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
                                if (active && value.startsWith("tandem://") && value != last) {
                                    last = value
                                    view.post { onCode(value) }
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
}

/** Four corner brackets that breathe, so the camera reads as looking for something. */
@Composable
private fun ViewfinderCorners() {
    val color = Color.White.copy(alpha = 0.9f)
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "viewfinder")
    val pulse by transition.animateFloat(
        0.9f, 1f,
        androidx.compose.animation.core.infiniteRepeatable(tween(1400), androidx.compose.animation.core.RepeatMode.Reverse),
        label = "pulse",
    )
    Canvas(Modifier.fillMaxSize().padding(36.dp)) {
        val arm = size.minDimension * 0.16f * pulse
        val stroke = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
        val w = size.width
        val h = size.height
        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            drawLine(color, Offset(x, y), Offset(x + dx * arm, y), stroke.width, StrokeCap.Round)
            drawLine(color, Offset(x, y), Offset(x, y + dy * arm), stroke.width, StrokeCap.Round)
        }
        corner(0f, 0f, 1f, 1f)
        corner(w, 0f, -1f, 1f)
        corner(0f, h, 1f, -1f)
        corner(w, h, -1f, -1f)
    }
}

// ---- Showing -------------------------------------------------------------------

@Composable
private fun ShowPanel() {
    val context = LocalContext.current
    val host = context.graph.host
    val clipboard = LocalClipboardManager.current
    var uri by remember { mutableStateOf<String?>(null) }
    var expiresAt by remember { mutableStateOf(0L) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var round by remember { mutableStateOf(0) }

    LaunchedEffect(round) {
        runCatching { host.engine?.createPairingOffer() }.getOrNull()?.let {
            uri = it.uri
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
    val remaining = if (uri == null) 1f else ((expiresAt - now) / 300_000f).coerceIn(0f, 1f)
    val ring by animateFloatAsState(remaining, tween(500, easing = LinearEasing), label = "ring")
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(300.dp)) {
                val stroke = 8.dp.toPx()
                drawArc(track, 0f, 360f, false, Offset(stroke / 2, stroke / 2), Size(size.width - stroke, size.height - stroke), style = Stroke(stroke))
                drawArc(primary, -90f, 360f * ring, false, Offset(stroke / 2, stroke / 2), Size(size.width - stroke, size.height - stroke), style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Box(Modifier.size(232.dp).clip(SheetSquircle).background(Color.White), contentAlignment = Alignment.Center) {
                val bitmap = remember(uri) { uri?.let { qrBitmap(it) } }
                when {
                    expired -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.pair_expired), color = Color.Black, style = MaterialTheme.typography.titleMedium)
                        PrimaryPillButton(stringResource(R.string.pair_new_code), { round++ })
                    }
                    bitmap != null -> Image(bitmap.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(14.dp))
                    else -> PillSpinner(size = 40.dp)
                }
            }
        }
        Text(stringResource(R.string.pair_show_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        SecondaryPillButton(stringResource(R.string.pair_copy_link), { uri?.let { clipboard.setText(AnnotatedString(it)) } }, icon = TandemIcons.Copy)
    }
}

/** Draws the QR with sharp modules and a quiet zone, so it scans from across a room. */
private fun qrBitmap(text: String, size: Int = 720): Bitmap {
    val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0)
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    val pixels = IntArray(size * size) { i -> if (matrix.get(i % size, i / size)) AndroidColor.BLACK else AndroidColor.WHITE }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}
