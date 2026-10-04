package nl.markmaaktmedia.tandem.live

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.engine.TandemService
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.components.PrimaryPillButton
import nl.markmaaktmedia.tandem.ui.components.SecondaryPillButton
import nl.markmaaktmedia.tandem.ui.components.TandemDialog
import nl.markmaaktmedia.tandem.ui.theme.TandemTheme
import nl.markmaaktmedia.tandem.ui.theme.collectAppearance
import uniffi.tandem_core.TandemMediaEnd
import uniffi.tandem_core.TandemMediaFacing
import uniffi.tandem_core.TandemMediaKind

/**
 * Where the person decides, and where the system asks for the screen or the camera.
 *
 * Two doors: a Mac asked (`forRequest`, from the notification or straight away when Tandem is open) and the person
 * started it on the phone (`forStart`). Either way the system's own question comes last, because Android shows it every
 * time and nothing an app does can answer it. Once it is answered the foreground service takes over and this closes.
 */
class LiveShareActivity : ComponentActivity() {
    private var session: ULong? = null
    private lateinit var peer: String
    private lateinit var kind: TandemMediaKind
    private var facing = TandemMediaFacing.ANY
    private var preApproved = false
    private var asking by mutableStateOf(true)
    private var handled = false

    private val screenPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) start(result.resultCode, data) else declined()
    }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            start(0, null)
        } else {
            Toast.makeText(this, R.string.live_camera_denied, Toast.LENGTH_LONG).show()
            declined(TandemMediaEnd.UNAVAILABLE)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        TandemService.start(this)
        val live = graph.live
        val requested = intent.getLongExtra(EXTRA_SESSION, 0)
        if (requested != 0L) {
            val id = requested.toULong()
            val waiting = live.pendingRequest(id) ?: return finish()
            session = id
            peer = waiting.from
            kind = waiting.request.kind
            facing = waiting.request.facing
            preApproved = waiting.preApproved
        } else {
            peer = intent.getStringExtra(EXTRA_PEER) ?: return finish()
            kind = runCatching { TandemMediaKind.valueOf(intent.getStringExtra(EXTRA_KIND).orEmpty()) }.getOrNull() ?: return finish()
            facing = runCatching { TandemMediaFacing.valueOf(intent.getStringExtra(EXTRA_FACING).orEmpty()) }.getOrDefault(TandemMediaFacing.ANY)
        }
        lifecycleScope.launch {
            live.changes.collect { change ->
                if (change is LiveShare.Change.RequestGone && change.session == session && !handled) {
                    handled = true
                    finish()
                }
            }
        }

        // Asked for by a Mac that was allowed for good, or started on the phone for the screen: only the system asks.
        val needsNoQuestion = preApproved || (session == null && kind == TandemMediaKind.SCREEN)
        if (needsNoQuestion && savedInstanceState == null) {
            asking = false
            proceed()
        }

        setContent {
            val appearance = graph.prefs.appearance.collectAppearance()
            TandemTheme(appearance, applySystemBarStyle = false) {
                if (asking) {
                    val name = graph.host.device(peer)?.name ?: stringResource(R.string.live_mac_fallback)
                    val camera = kind == TandemMediaKind.CAMERA
                    if (session == null) {
                        // Started here: only the camera has a choice to make.
                        TandemDialog(
                            title = stringResource(R.string.live_pick_camera_title, name),
                            icon = painterResource(R.drawable.sym_videocam),
                            body = stringResource(R.string.live_pick_camera_body),
                            onDismiss = { finishNow() },
                            closeLabel = stringResource(R.string.live_cancel),
                            actions = {
                                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    PrimaryPillButton(stringResource(R.string.live_camera_back), { facing = TandemMediaFacing.BACK; proceed() }, Modifier.fillMaxWidth())
                                    SecondaryPillButton(stringResource(R.string.live_camera_front), { facing = TandemMediaFacing.FRONT; proceed() }, Modifier.fillMaxWidth())
                                }
                            },
                        )
                    } else {
                        TandemDialog(
                            title = stringResource(if (camera) R.string.live_ask_camera else R.string.live_ask_screen, name),
                            icon = painterResource(if (camera) R.drawable.sym_videocam else R.drawable.sym_screen_share),
                            body = stringResource(if (camera) R.string.live_ask_body_camera else R.string.live_ask_body_screen),
                            onDismiss = { declined() },
                            closeLabel = stringResource(R.string.live_deny),
                            actions = {
                                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    PrimaryPillButton(stringResource(R.string.live_allow), { proceed() }, Modifier.fillMaxWidth())
                                    SecondaryPillButton(
                                        stringResource(R.string.live_allow_always, name),
                                        { graph.live.allowAlways(peer, kind); proceed() },
                                        Modifier.fillMaxWidth(),
                                    )
                                    SecondaryPillButton(stringResource(R.string.live_deny), { declined() }, Modifier.fillMaxWidth())
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    /** The person agreed. Now the system's question. */
    private fun proceed() {
        asking = false
        when (kind) {
            TandemMediaKind.SCREEN ->
                screenPermission.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
            TandemMediaKind.CAMERA ->
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    start(0, null)
                } else {
                    cameraPermission.launch(Manifest.permission.CAMERA)
                }
        }
    }

    private fun start(resultCode: Int, data: Intent?) {
        handled = true
        LiveShareService.begin(this, kind, peer, session, facing, resultCode, data)
        finish()
    }

    private fun declined(why: TandemMediaEnd = TandemMediaEnd.DECLINED) {
        if (!handled) {
            handled = true
            session?.let { graph.live.deny(it, why) }
        }
        finish()
    }

    private fun finishNow() {
        handled = true
        finish()
    }

    override fun onDestroy() {
        // Gone without an answer (back gesture, swiped away): that is a no.
        if (!handled && !isChangingConfigurations) session?.let { graph.live.deny(it, TandemMediaEnd.DECLINED) }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_SESSION = "session"
        private const val EXTRA_PEER = "peer"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_FACING = "facing"

        /** A Mac asked and the person has to be asked about it. */
        fun forRequest(context: Context, session: ULong): Intent =
            Intent(context, LiveShareActivity::class.java).putExtra(EXTRA_SESSION, session.toLong())

        /** The person wants to show this phone to a Mac. */
        fun forStart(context: Context, peer: String, kind: TandemMediaKind): Intent =
            Intent(context, LiveShareActivity::class.java).putExtra(EXTRA_PEER, peer).putExtra(EXTRA_KIND, kind.name)
    }
}
