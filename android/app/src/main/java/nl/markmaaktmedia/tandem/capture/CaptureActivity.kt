package nl.markmaaktmedia.tandem.capture

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
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
import nl.markmaaktmedia.tandem.ui.components.PillSpinner
import nl.markmaaktmedia.tandem.ui.components.bouncyClickable
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import nl.markmaaktmedia.tandem.ui.theme.TandemMotion
import nl.markmaaktmedia.tandem.ui.theme.TandemTheme
import nl.markmaaktmedia.tandem.ui.theme.collectAppearance
import uniffi.tandem_core.TandemCaptureKind
import uniffi.tandem_core.TandemCaptureWhy
import uniffi.tandem_core.TandemEvent
import java.io.File
import kotlin.math.max

/**
 * What a computer asked the phone for: a photo, a scan of a page or a picture from the gallery. It opens from the
 * notification the request makes, does the one thing, sends the result back tagged with the request and closes.
 */
class CaptureActivity : ComponentActivity() {

    private sealed interface Phase {
        data object NeedsCamera : Phase
        data object Viewfinder : Phase
        data class Review(val file: File) : Phase
        data object Waiting : Phase
        data object Sending : Phase
        data object Sent : Phase
        data class Failed(val message: Int?) : Phase
        data class Ended(val message: Int) : Phase
    }

    private lateinit var device: String
    private var request = 0L
    private var kind = TandemCaptureKind.PHOTO
    private var phase by mutableStateOf<Phase>(Phase.Waiting)
    private var cameraDenied by mutableStateOf(false)

    /** What failed to arrive, so Try again can send the same file once more. */
    private var lastFile: File? = null
    private var lastMime = "image/jpeg"

    /** Set once the result has gone out, after which closing the screen is not a cancellation. */
    private var delivered = false

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            phase = Phase.Viewfinder
        } else {
            cameraDenied = true
            phase = Phase.NeedsCamera
        }
    }

    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) closeCancelled() else lifecycleScope.launch { ingest(uri, "Picture", null) }
    }

    private val scanner = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val pdf = GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.pdf?.uri
        if (result.resultCode == RESULT_OK && pdf != null) {
            lifecycleScope.launch { ingest(pdf, getString(R.string.capture_name_scan), "pdf") }
        } else {
            closeCancelled()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        device = intent.getStringExtra(EXTRA_DEVICE) ?: return finish()
        request = intent.getLongExtra(EXTRA_REQUEST, 0)
        kind = intent.getStringExtra(EXTRA_KIND)?.let { runCatching { TandemCaptureKind.valueOf(it) }.getOrNull() } ?: TandemCaptureKind.PHOTO
        TandemService.start(this)
        lifecycleScope.launch(Dispatchers.IO) { CaptureFiles.sweep(CaptureFiles.folder(this@CaptureActivity)) }

        val host = graph.host
        // The computer closing the question, or going away, closes the screen too.
        lifecycleScope.launch {
            host.events.collect { event ->
                when {
                    event is TandemEvent.CaptureCancelled && event.id.toLong() == request && event.from == device -> end(R.string.capture_ended_mac)
                    event is TandemEvent.Disconnected && event.id == device -> end(R.string.capture_ended_lost)
                }
            }
        }

        phase = when (kind) {
            TandemCaptureKind.PHOTO ->
                if (hasCamera()) Phase.Viewfinder else Phase.NeedsCamera
            else -> Phase.Waiting
        }
        if (savedInstanceState == null) {
            when (kind) {
                TandemCaptureKind.DOCUMENT -> startScanner()
                TandemCaptureKind.PICTURE -> picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                TandemCaptureKind.PHOTO -> Unit
            }
        }

        setContent {
            val appearance = graph.prefs.appearance.collectAppearance()
            TandemTheme(appearance, applySystemBarStyle = false) {
                val name = host.device(device)?.name ?: stringResource(R.string.capture_mac_fallback)
                BackHandler {
                    val current = phase
                    if (current is Phase.Review) {
                        current.file.delete()
                        phase = Phase.Viewfinder
                    } else {
                        closeCancelled()
                    }
                }
                CaptureScreen(
                    phase = phase,
                    kind = kind,
                    deviceName = name,
                    cameraDenied = cameraDenied,
                    onClose = ::closeCancelled,
                    onAllowCamera = { cameraPermission.launch(Manifest.permission.CAMERA) },
                    onOpenSettings = {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                    },
                    onPhoto = { phase = Phase.Review(it) },
                    onCameraFailed = { phase = Phase.Failed(R.string.capture_error_local) },
                    onRetake = { review ->
                        review.delete()
                        phase = Phase.Viewfinder
                    },
                    onUse = { deliver(it, "image/jpeg") },
                    onRetry = {
                        val file = lastFile
                        when {
                            file != null && file.exists() -> deliver(file, lastMime)
                            kind == TandemCaptureKind.DOCUMENT -> startScanner()
                            kind == TandemCaptureKind.PICTURE -> picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            else -> phase = Phase.Viewfinder
                        }
                    },
                )
            }
        }
    }

    private fun hasCamera() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    // ---- Scanner and gallery ---------------------------------------------------------

    private fun startScanner() {
        phase = Phase.Waiting
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(false)
            .setPageLimit(10)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options).getStartScanIntent(this)
            .addOnSuccessListener { sender -> scanner.launch(IntentSenderRequest.Builder(sender).build()) }
            .addOnFailureListener {
                // Without the scanner of Google Play services there is nothing to offer, and the computer should know.
                lifecycleScope.launch { graph.host.cancelCapture(device, request, TandemCaptureWhy.UNAVAILABLE) }
                phase = Phase.Failed(R.string.capture_error_local)
            }
    }

    /** Copies what the scanner or the gallery gave into the cache, where it can wait for the computer to pull it. */
    private suspend fun ingest(uri: Uri, prefix: String, extension: String?) {
        phase = Phase.Sending
        val made = withContext(Dispatchers.IO) {
            runCatching {
                val resolver = contentResolver
                val mime = resolver.getType(uri) ?: "application/octet-stream"
                val ext = extension ?: MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "jpg"
                val shown = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
                val name = if (extension == null && !shown.isNullOrBlank()) shown else CaptureFiles.fileName(prefix, ext)
                val target = File(CaptureFiles.folder(this@CaptureActivity), name)
                resolver.openInputStream(uri)!!.use { input -> target.outputStream().use { input.copyTo(it, 256 * 1024) } }
                target to mime
            }.getOrNull()
        }
        if (made == null) {
            phase = Phase.Failed(R.string.capture_error_local)
            return
        }
        deliver(made.first, made.second)
    }

    // ---- Sending ----------------------------------------------------------------------

    private fun deliver(file: File, mime: String) {
        lastFile = file
        lastMime = mime
        phase = Phase.Sending
        lifecycleScope.launch {
            val host = graph.host
            withTimeoutOrNull(5000) { host.state.first { it == EngineState.Running } }
            val sent = runCatching { host.sendCapture(file, mime, device, request) }.getOrDefault(false)
            if (sent) {
                delivered = true
                phase = Phase.Sent
                delay(1100)
                finish()
            } else {
                phase = Phase.Failed(null)
            }
        }
    }

    // ---- Closing ----------------------------------------------------------------------

    private fun closeCancelled() {
        if (!delivered) {
            val why = if (kind == TandemCaptureKind.PHOTO && !hasCamera() && cameraDenied) TandemCaptureWhy.REFUSED else TandemCaptureWhy.CANCELLED
            graph.scope.launch { graph.host.cancelCapture(device, request, why) }
        }
        finish()
    }

    /** The computer is done with the question: say so for a moment, then close. */
    private fun end(message: Int) {
        if (delivered) return
        phase = Phase.Ended(message)
        lifecycleScope.launch {
            delay(1800)
            finish()
        }
    }

    companion object {
        private const val EXTRA_DEVICE = "device"
        private const val EXTRA_REQUEST = "request"
        private const val EXTRA_KIND = "kind"

        fun intent(context: Context, device: String, request: Long, kind: TandemCaptureKind): Intent =
            Intent(context, CaptureActivity::class.java)
                .putExtra(EXTRA_DEVICE, device)
                .putExtra(EXTRA_REQUEST, request)
                .putExtra(EXTRA_KIND, kind.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    // ---- Screens ----------------------------------------------------------------------

    @Composable
    private fun CaptureScreen(
        phase: Phase,
        kind: TandemCaptureKind,
        deviceName: String,
        cameraDenied: Boolean,
        onClose: () -> Unit,
        onAllowCamera: () -> Unit,
        onOpenSettings: () -> Unit,
        onPhoto: (File) -> Unit,
        onCameraFailed: () -> Unit,
        onRetake: (File) -> Unit,
        onUse: (File) -> Unit,
        onRetry: () -> Unit,
    ) {
        val title = when (kind) {
            TandemCaptureKind.PHOTO -> stringResource(R.string.capture_title_photo)
            TandemCaptureKind.DOCUMENT -> stringResource(R.string.capture_title_document)
            TandemCaptureKind.PICTURE -> stringResource(R.string.capture_title_picture)
        }
        Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
            AnimatedContent(
                targetState = phase,
                transitionSpec = { fadeIn(TandemMotion.fadeSpec()) togetherWith fadeOut(TandemMotion.fadeSpec()) },
                contentKey = { it::class },
                label = "capturePhase",
                modifier = Modifier.weight(1f),
            ) { current ->
                when (current) {
                    Phase.Viewfinder -> Viewfinder(title, deviceName, onClose, onPhoto, onCameraFailed)
                    is Phase.Review -> Review(title, deviceName, current.file, onClose, onRetake, onUse)
                    Phase.NeedsCamera -> Status(
                        title, deviceName, onClose,
                        icon = { Badge(TandemIcons.Camera, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer) },
                        heading = stringResource(R.string.capture_perm_title),
                        body = stringResource(R.string.capture_perm_text, deviceName),
                        primary = if (cameraDenied) stringResource(R.string.capture_perm_settings) else stringResource(R.string.capture_perm_allow),
                        onPrimary = if (cameraDenied) onOpenSettings else onAllowCamera,
                        secondary = stringResource(R.string.capture_perm_not_now),
                        onSecondary = onClose,
                    )
                    Phase.Waiting -> Status(
                        title, deviceName, onClose,
                        icon = { PillSpinner(size = 96.dp, color = Color.White) },
                        heading = stringResource(if (kind == TandemCaptureKind.DOCUMENT) R.string.capture_waiting_scan else R.string.capture_waiting_pick),
                        secondary = stringResource(R.string.capture_cancel),
                        onSecondary = onClose,
                    )
                    Phase.Sending -> Status(
                        title, deviceName, onClose,
                        icon = { PillSpinner(size = 96.dp, color = Color.White) },
                        heading = stringResource(R.string.capture_sending, deviceName),
                    )
                    Phase.Sent -> Status(
                        title, deviceName, onClose,
                        icon = { Badge(TandemIcons.Check, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer, pop = true) },
                        heading = stringResource(R.string.capture_sent, deviceName),
                    )
                    is Phase.Failed -> Status(
                        title, deviceName, onClose,
                        icon = { Badge(TandemIcons.Error, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer) },
                        heading = if (current.message == null) stringResource(R.string.capture_failed, deviceName) else stringResource(current.message),
                        body = stringResource(R.string.capture_failed_text),
                        primary = stringResource(R.string.capture_retry),
                        onPrimary = onRetry,
                        secondary = stringResource(R.string.capture_cancel),
                        onSecondary = onClose,
                    )
                    is Phase.Ended -> Status(
                        title, deviceName, onClose,
                        icon = { Badge(TandemIcons.Close, Color.White.copy(alpha = 0.16f), Color.White) },
                        heading = stringResource(current.message, deviceName),
                    )
                }
            }
        }
    }

    @Composable
    private fun Header(title: String, deviceName: String, onClose: () -> Unit, trailing: @Composable () -> Unit = { Spacer(Modifier.size(48.dp)) }) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundButton(TandemIcons.Close, stringResource(R.string.capture_close), onClose)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White)
                Text(stringResource(R.string.capture_for_device, deviceName), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
            }
            trailing()
        }
    }

    @Composable
    private fun Viewfinder(title: String, deviceName: String, onClose: () -> Unit, onPhoto: (File) -> Unit, onFailed: () -> Unit) {
        val context = LocalContext.current
        val owner = LocalLifecycleOwner.current
        val controller = remember {
            LifecycleCameraController(context).apply {
                setEnabledUseCases(CameraController.IMAGE_CAPTURE)
                imageCaptureMode = ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
            }
        }
        DisposableEffect(owner) {
            controller.bindToLifecycle(owner)
            onDispose { controller.unbind() }
        }
        var flash by remember { mutableIntStateOf(ImageCapture.FLASH_MODE_AUTO) }
        var front by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        LaunchedEffect(flash) { controller.imageCaptureFlashMode = flash }
        LaunchedEffect(front) {
            controller.cameraSelector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        }
        val photoName = stringResource(R.string.capture_name_photo)

        Column(Modifier.fillMaxSize()) {
            Header(title, deviceName, onClose) {
                val (icon, label) = when (flash) {
                    ImageCapture.FLASH_MODE_ON -> TandemIcons.FlashOn to R.string.capture_flash_on
                    ImageCapture.FLASH_MODE_OFF -> TandemIcons.FlashOff to R.string.capture_flash_off
                    else -> TandemIcons.FlashAuto to R.string.capture_flash_auto
                }
                RoundButton(icon, stringResource(label), onClick = {
                    flash = when (flash) {
                        ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                        ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_OFF
                        else -> ImageCapture.FLASH_MODE_AUTO
                    }
                })
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(32.dp)).background(Color(0xFF111111))) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                            this.controller = controller
                        }
                    },
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 24.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.size(52.dp))
                Shutter(enabled = !busy, label = stringResource(R.string.capture_shutter)) {
                    busy = true
                    val file = File(CaptureFiles.folder(context), CaptureFiles.fileName(photoName, "jpg"))
                    controller.takePicture(
                        ImageCapture.OutputFileOptions.Builder(file).build(),
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(output: ImageCapture.OutputFileResults) = onPhoto(file)
                            override fun onError(exception: ImageCaptureException) {
                                busy = false
                                onFailed()
                            }
                        },
                    )
                }
                RoundButton(TandemIcons.FlipCamera, stringResource(R.string.capture_flip), size = 52.dp, onClick = { front = !front })
            }
        }
    }

    @Composable
    private fun Review(title: String, deviceName: String, file: File, onClose: () -> Unit, onRetake: (File) -> Unit, onUse: (File) -> Unit) {
        val bitmap by produceState<Bitmap?>(null, file) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                        decoder.setTargetSampleSize(max(1, max(info.size.width, info.size.height) / 1600))
                    }
                }.getOrNull()
            }
        }
        Column(Modifier.fillMaxSize()) {
            Header(title, deviceName, onClose)
            Box(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(32.dp)).background(Color(0xFF111111)),
                contentAlignment = Alignment.Center,
            ) {
                bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(stringResource(R.string.capture_retake), Modifier.weight(1f), primary = false) { onRetake(file) }
                PillButton(stringResource(R.string.capture_use_photo), Modifier.weight(1f), primary = true) { onUse(file) }
            }
        }
    }

    @Composable
    private fun Status(
        title: String,
        deviceName: String,
        onClose: () -> Unit,
        icon: @Composable () -> Unit,
        heading: String,
        body: String? = null,
        primary: String? = null,
        onPrimary: () -> Unit = {},
        secondary: String? = null,
        onSecondary: () -> Unit = {},
    ) {
        Column(Modifier.fillMaxSize()) {
            Header(title, deviceName, onClose)
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) { icon() }
                Spacer(Modifier.height(28.dp))
                Text(heading, style = MaterialTheme.typography.headlineSmall, color = Color.White, textAlign = TextAlign.Center)
                if (body != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(body, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center)
                }
            }
            if (primary != null || secondary != null) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (primary != null) PillButton(primary, Modifier.fillMaxWidth(), primary = true, onClick = onPrimary)
                    if (secondary != null) PillButton(secondary, Modifier.fillMaxWidth(), primary = false, onClick = onSecondary)
                }
            }
        }
    }

    // ---- Parts -----------------------------------------------------------------------

    /** A round button on the picture. The icon is under half of the circle, so it sits in it with room to breathe. */
    @Composable
    private fun RoundButton(icon: androidx.compose.ui.graphics.painter.Painter, label: String, onClick: () -> Unit, size: androidx.compose.ui.unit.Dp = 48.dp) {
        Box(
            Modifier.size(size).clip(CircleShape).background(Color.White.copy(alpha = 0.16f)).bouncyClickable(onClickLabel = label, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, label, tint = Color.White, modifier = Modifier.size(size * 0.46f))
        }
    }

    @Composable
    private fun Shutter(enabled: Boolean, label: String, onClick: () -> Unit) {
        val source = remember { MutableInteractionSource() }
        val pressed by source.collectIsPressedAsState()
        val inner by animateFloatAsState(if (pressed || !enabled) 0.86f else 1f, TandemMotion.bouncy(), label = "shutter")
        Box(
            Modifier.size(84.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape)
                .semantics { contentDescription = label }
                .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(66.dp).scale(inner).clip(CircleShape).background(Color.White))
        }
    }

    @Composable
    private fun Badge(icon: androidx.compose.ui.graphics.painter.Painter, container: Color, content: Color, pop: Boolean = false) {
        var shown by remember { mutableStateOf(!pop) }
        LaunchedEffect(Unit) { shown = true }
        val scale by animateFloatAsState(if (shown) 1f else 0.4f, TandemMotion.springy(), label = "badge")
        Box(Modifier.size(96.dp).scale(scale).clip(CircleShape).background(container), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = content, modifier = Modifier.size(44.dp))
        }
    }

    @Composable
    private fun PillButton(text: String, modifier: Modifier, primary: Boolean, onClick: () -> Unit) {
        val scheme = MaterialTheme.colorScheme
        Box(
            modifier.height(56.dp).clip(CircleShape)
                .background(if (primary) scheme.primaryContainer else Color.White.copy(alpha = 0.16f))
                .bouncyClickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, style = MaterialTheme.typography.titleMedium, color = if (primary) scheme.onPrimaryContainer else Color.White)
        }
    }
}
