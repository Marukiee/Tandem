package nl.markmaaktmedia.tandem.wifi

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.engine.Permissions
import nl.markmaaktmedia.tandem.engine.TandemService
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.components.PillSpinner
import nl.markmaaktmedia.tandem.ui.components.PrimaryPillButton
import nl.markmaaktmedia.tandem.ui.components.SecondaryPillButton
import nl.markmaaktmedia.tandem.ui.components.TandemDialog
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import nl.markmaaktmedia.tandem.ui.theme.TandemTheme
import nl.markmaaktmedia.tandem.ui.theme.collectAppearance
import uniffi.tandem_core.TandemWifiRefusal

/**
 * Where the phone waits for the computer's answer, and then hands the password to the system's sheet for saving a network
 * (`ACTION_WIFI_ADD_NETWORKS`). That sheet is the one to use: it saves the network as the person's own, like one picked by hand in
 * the settings, and joins it at once, also away from another network. A silent suggestion is only a hint the system may follow
 * later, never moves the phone off a network it chose, and is gone with the app. The sheet may only be opened from a screen in
 * front, which is why this one waits here rather than in the background.
 */
class WifiJoinActivity : ComponentActivity() {
    private lateinit var computer: String
    private val share by lazy { WifiShare.get(this) }

    /** What this screen does next to the question in [WifiShare.join]. */
    private sealed interface Step {
        data object Waiting : Step
        data object Saving : Step
        data class Failed(val text: String) : Step
        data object Location : Step
    }

    private var step by mutableStateOf<Step>(Step.Waiting)

    private val save = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { afterSheet(it.resultCode, it.data) }
    private val location = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        share.evaluate()
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        TandemService.start(this)
        computer = intent.getStringExtra(EXTRA_COMPUTER) ?: return finish()
        // Turned round while the sheet is up: its answer comes back here, and it must not be opened a second time.
        if (savedInstanceState?.getBoolean(STATE_SAVING) == true) step = Step.Saving
        if (savedInstanceState == null) {
            val asking = intent.getBooleanExtra(EXTRA_ASK, false)
            val ready = (share.join.value as? WifiShare.Join.Ready)?.computer == computer
            if ((asking && !share.ask(computer)) || (!asking && !ready)) {
                Toast.makeText(this, R.string.wifi_gone, Toast.LENGTH_LONG).show()
                finish()
                return
            }
        }
        lifecycleScope.launch {
            share.join.collect { state ->
                if (state is WifiShare.Join.Ready && state.computer == computer && step == Step.Waiting) open(state)
            }
        }

        setContent {
            val appearance = graph.prefs.appearance.collectAppearance()
            TandemTheme(appearance, applySystemBarStyle = false) {
                val join by share.join.collectAsState()
                val name = graph.host.device(computer)?.name ?: stringResource(R.string.wifi_computer_fallback)
                when (val now = step) {
                    Step.Saving -> Unit
                    is Step.Failed -> Notice(stringResource(R.string.wifi_failed_title), now.text)
                    Step.Location -> LocationQuestion()
                    Step.Waiting -> when (val state = join) {
                        is WifiShare.Join.Asking -> TandemDialog(
                            title = stringResource(R.string.wifi_asking_title, name),
                            icon = TandemIcons.Wifi,
                            body = stringResource(R.string.wifi_asking_body, name, state.ssid),
                            onDismiss = { cancel() },
                            dismissOnOutsideTap = false,
                            closeLabel = stringResource(R.string.wifi_cancel),
                            content = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { PillSpinner(size = 36.dp) } },
                            actions = { SecondaryPillButton(stringResource(R.string.wifi_cancel), { cancel() }, Modifier.fillMaxWidth()) },
                        )
                        is WifiShare.Join.Refused -> Notice(stringResource(R.string.wifi_refused_title, name, state.ssid), refusal(state.why, name, state.ssid))
                        is WifiShare.Join.NoAnswer -> TandemDialog(
                            title = stringResource(R.string.wifi_no_answer_title, name),
                            icon = TandemIcons.Wifi,
                            body = stringResource(R.string.wifi_no_answer_body, name),
                            onDismiss = { cancel() },
                            closeLabel = stringResource(R.string.wifi_close),
                            actions = {
                                SecondaryPillButton(stringResource(R.string.wifi_close), { cancel() })
                                PrimaryPillButton(stringResource(R.string.wifi_try_again), { if (!share.ask(computer)) cancel() })
                            },
                        )
                        // The sheet opens from the collector above.
                        is WifiShare.Join.Ready -> Unit
                        // Let go of elsewhere (it waited too long): nothing left to show.
                        null -> LaunchedEffect(Unit) { finish() }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        visible = true
    }

    override fun onStop() {
        visible = false
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_SAVING, step == Step.Saving)
    }

    private fun open(ready: WifiShare.Join.Ready) {
        val suggestion = share.suggestion(ready.network, ready.password)
        if (suggestion == null) {
            step = Step.Failed(getString(R.string.wifi_failed_password, ready.ssid))
            share.finished()
            return
        }
        step = Step.Saving
        try {
            save.launch(share.sheet(suggestion))
        } catch (_: ActivityNotFoundException) {
            share.finished()
            if (share.suggest(suggestion)) {
                Toast.makeText(this, getString(R.string.wifi_suggested, ready.ssid), Toast.LENGTH_LONG).show()
                finish()
            } else {
                step = Step.Failed(getString(R.string.wifi_failed, ready.ssid))
            }
        }
    }

    private fun afterSheet(code: Int, data: Intent?) {
        val ssid = share.join.value?.ssid.orEmpty()
        share.finished()
        // Closed without saving: the person changed their mind, nothing to say.
        if (code != RESULT_OK) return finish()
        val results = data?.getIntegerArrayListExtra(Settings.EXTRA_WIFI_NETWORK_RESULT_LIST)
        val saved = results == null || results.any { it == Settings.ADD_WIFI_RESULT_SUCCESS || it == Settings.ADD_WIFI_RESULT_ALREADY_EXISTS }
        if (!saved) {
            step = Step.Failed(getString(R.string.wifi_failed, ssid))
            return
        }
        Toast.makeText(this, getString(R.string.wifi_saved, ssid), Toast.LENGTH_LONG).show()
        // Seeing the networks nearby lets the offer come by itself when one is in range. Asked once, now that it was of use.
        if (!share.locationAsked && !Permissions.isGranted(this, Manifest.permission.ACCESS_FINE_LOCATION)) {
            share.locationAsked = true
            step = Step.Location
        } else {
            finish()
        }
    }

    private fun cancel() {
        share.finished()
        finish()
    }

    @Composable
    private fun Notice(title: String, body: String) {
        TandemDialog(
            title = title,
            icon = TandemIcons.Wifi,
            body = body,
            onDismiss = { cancel() },
            closeLabel = stringResource(R.string.wifi_close),
            actions = { PrimaryPillButton(stringResource(R.string.wifi_close), { cancel() }) },
        )
    }

    @Composable
    private fun LocationQuestion() {
        TandemDialog(
            title = stringResource(R.string.wifi_location_title),
            icon = TandemIcons.Wifi,
            body = stringResource(R.string.wifi_location_body),
            onDismiss = { finish() },
            closeLabel = stringResource(R.string.wifi_not_now),
            actions = {
                SecondaryPillButton(stringResource(R.string.wifi_not_now), { finish() })
                PrimaryPillButton(stringResource(R.string.wifi_allow), {
                    // Android only hands out the precise one together with the rough one.
                    location.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                })
            },
        )
    }

    @Composable
    private fun refusal(why: TandemWifiRefusal, name: String, ssid: String): String = when (why) {
        TandemWifiRefusal.DECLINED -> stringResource(R.string.wifi_refused_declined, name)
        TandemWifiRefusal.NOT_ON_IT -> stringResource(R.string.wifi_refused_not_on_it, name, ssid)
        TandemWifiRefusal.CANNOT_SHARE -> stringResource(R.string.wifi_refused_cannot, ssid)
        TandemWifiRefusal.UNREADABLE -> stringResource(R.string.wifi_refused_unreadable, name)
        TandemWifiRefusal.OTHER -> stringResource(R.string.wifi_refused_other, name)
    }

    companion object {
        private const val EXTRA_COMPUTER = "computer"
        private const val EXTRA_ASK = "ask"
        private const val STATE_SAVING = "saving"

        /** Whether this screen is in front, waiting: then the password goes to it and not to a notification. */
        @Volatile var visible = false
            private set

        /** Asks the computer for its Wi-Fi and waits for the answer. */
        fun asking(context: Context, computer: String): Intent =
            Intent(context, WifiJoinActivity::class.java).putExtra(EXTRA_COMPUTER, computer).putExtra(EXTRA_ASK, true)

        /** The password of the computer is here already: straight to the sheet. */
        fun saving(context: Context, computer: String): Intent =
            Intent(context, WifiJoinActivity::class.java).putExtra(EXTRA_COMPUTER, computer)
    }
}
