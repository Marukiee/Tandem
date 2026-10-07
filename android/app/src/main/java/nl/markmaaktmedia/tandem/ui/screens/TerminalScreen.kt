package nl.markmaaktmedia.tandem.ui.screens

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ssh.SshKeys
import nl.markmaaktmedia.tandem.ssh.SshSession
import nl.markmaaktmedia.tandem.ui.components.PillLoader
import nl.markmaaktmedia.tandem.ui.theme.PillShape
import nl.markmaaktmedia.tandem.ui.components.PrimaryPillButton
import nl.markmaaktmedia.tandem.ui.components.SecondaryPillButton
import nl.markmaaktmedia.tandem.ui.components.TandemIconButton
import nl.markmaaktmedia.tandem.ui.components.bouncyClickable
import nl.markmaaktmedia.tandem.ui.components.sshAddress
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons

private sealed interface Phase {
    data object Form : Phase
    data object Connecting : Phase
    data object Connected : Phase
    data class Ended(val reason: String?) : Phase
}

/**
 * A login to a computer over SSH. First who to log in as, then the terminal: the output of the computer is written to a web view that
 * shows it with xterm.js (the same terminal as in the apps of the computers), and what is typed goes back. A row of keys under it has what
 * a phone keyboard does not: Esc, Tab, Ctrl and the arrows.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TerminalScreen(id: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val host = context.graph.host
    val scope = rememberCoroutineScope()
    val name = host.device(id)?.name ?: ""
    val preferences = remember { context.getSharedPreferences("ssh", Context.MODE_PRIVATE) }

    var phase by remember { mutableStateOf<Phase>(Phase.Form) }
    var user by remember { mutableStateOf(preferences.getString("user.$id", "").orEmpty()) }
    var password by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    var showKey by remember { mutableStateOf(false) }
    var ctrl by remember { mutableStateOf(false) }
    var session by remember { mutableStateOf<SshSession?>(null) }

    // What was printed before the web view was ready is kept and written when it is.
    val pending = remember { mutableListOf<ByteArray>() }
    var ready by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf<WebView?>(null) }

    fun push(bytes: ByteArray) {
        val view = web
        if (view == null || !ready) {
            synchronized(pending) { pending.add(bytes) }
            return
        }
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        view.post { view.evaluateJavascript("tandemWrite('$b64')", null) }
    }

    fun send(text: String) {
        session?.write(text.toByteArray())
    }

    val bridge = remember {
        object {
            @JavascriptInterface fun input(data: String) {
                var out = data
                if (ctrl && data.length == 1) {
                    out = (data[0].code and 0x1f).toChar().toString()
                    ctrl = false
                }
                session?.write(out.toByteArray())
            }
            @JavascriptInterface fun resize(cols: Int, rows: Int) { session?.resize(cols, rows) }
            @JavascriptInterface fun ready() {
                ready = true
                val view = web
                val queued = synchronized(pending) { pending.toList().also { pending.clear() } }
                if (view != null) view.post { queued.forEach { view.evaluateJavascript("tandemWrite('${Base64.encodeToString(it, Base64.NO_WRAP)}')", null) } }
            }
        }
    }

    fun connect() {
        val who = user.trim()
        if (who.isEmpty()) return
        preferences.edit().putString("user.$id", who).apply()
        problem = null
        phase = Phase.Connecting
        ready = false
        scope.launch {
            val address = sshAddress(runCatching { host.engine?.deviceIps(id) }.getOrNull().orEmpty())
            if (address == null) {
                problem = "refused"
                phase = Phase.Form
                return@launch
            }
            val made = SshSession(
                context,
                onOutput = { push(it) },
                onClosed = { reason -> scope.launch { phase = Phase.Ended(reason) } },
            )
            val failure = withContext(Dispatchers.IO) { made.connect(address, who, password.takeIf { it.isNotEmpty() }, 80, 24) }
            if (failure == null) {
                session = made
                phase = Phase.Connected
            } else {
                problem = failure
                phase = Phase.Form
            }
        }
    }

    fun leave() {
        session?.close()
        session = null
        onBack()
    }
    BackHandler { leave() }
    DisposableEffect(Unit) { onDispose { session?.close(); web?.destroy() } }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TandemIconButton(TandemIcons.Back, stringResource(R.string.action_back), { leave() })
            Text(
                stringResource(R.string.terminal_title_for, name), style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val now = phase) {
                Phase.Form, is Phase.Ended -> LoginForm(
                    name = name, user = user, onUser = { user = it }, password = password, onPassword = { password = it },
                    problem = problem ?: (now as? Phase.Ended)?.let { it.reason ?: "ended" },
                    showKey = showKey, onShowKey = { showKey = !showKey },
                    onLogin = ::connect,
                    again = now is Phase.Ended,
                )
                Phase.Connecting -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    PillLoader(stringResource(R.string.terminal_connecting))
                }
                Phase.Connected -> {}
            }
            // The terminal is made as soon as the login starts, so it has loaded when the first output arrives.
            if (phase == Phase.Connecting || phase == Phase.Connected) {
                AndroidView(
                    modifier = Modifier.fillMaxSize().then(if (phase == Phase.Connecting) Modifier.size(1.dp) else Modifier),
                    factory = { c ->
                        WebView(c).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = false
                            setBackgroundColor(android.graphics.Color.parseColor("#121216"))
                            addJavascriptInterface(bridge, "Android")
                            loadUrl("file:///android_asset/terminal/index.html")
                            web = this
                        }
                    },
                )
            }
        }
        if (phase == Phase.Connected) {
            KeysRow(ctrl = ctrl, onCtrl = { ctrl = !ctrl }, onKey = { send(it) })
        }
    }
}

@Composable
private fun LoginForm(
    name: String,
    user: String,
    onUser: (String) -> Unit,
    password: String,
    onPassword: (String) -> Unit,
    problem: String?,
    showKey: Boolean,
    onShowKey: () -> Unit,
    onLogin: () -> Unit,
    again: Boolean,
) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.terminal_login_to, name), style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = user, onValueChange = onUser, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.terminal_user)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            shape = MaterialTheme.shapes.large,
        )
        OutlinedTextField(
            value = password, onValueChange = onPassword, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.terminal_password)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            shape = MaterialTheme.shapes.large,
        )
        problem?.let {
            Text(problemText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        PrimaryPillButton(stringResource(if (again) R.string.terminal_again else R.string.terminal_login), onLogin, Modifier.fillMaxWidth())
        SecondaryPillButton(stringResource(R.string.terminal_key_show), onShowKey, Modifier.fillMaxWidth())
        if (showKey) {
            val key = remember { SshKeys.ensure(context) }
            Column(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainer).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.terminal_key_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(key?.publicLine ?: "", style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                SecondaryPillButton(stringResource(R.string.terminal_key_copy), {
                    key?.let {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("ssh key", it.publicLine))
                    }
                }, icon = TandemIcons.Copy)
            }
        }
    }
}

@Composable
private fun problemText(code: String): String = stringResource(
    when (code) {
        "auth" -> R.string.terminal_err_auth
        "changed" -> R.string.terminal_err_changed
        "timeout" -> R.string.terminal_err_timeout
        "refused" -> R.string.terminal_err_refused
        "ended" -> R.string.terminal_ended
        else -> R.string.terminal_err_other
    },
).let { text -> if (code !in setOf("auth", "changed", "timeout", "refused", "ended")) text.replace("%1\$s", code) else text }

/** What a phone keyboard has no key for. */
@Composable
private fun KeysRow(ctrl: Boolean, onCtrl: () -> Unit, onKey: (String) -> Unit) {
    val keys = listOf(
        "Esc" to "\u001b", "Tab" to "\t", "↑" to "\u001b[A", "↓" to "\u001b[B", "←" to "\u001b[D", "→" to "\u001b[C",
        "|" to "|", "/" to "/", "~" to "~", "-" to "-",
    )
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        KeyButton("Ctrl", ctrl, onCtrl)
        keys.forEach { (label, text) -> KeyButton(label, false) { onKey(text) } }
    }
}

@Composable
private fun KeyButton(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(PillShape)
            .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest)
            .bouncyClickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) MaterialTheme.colorScheme.onPrimary else Color.Unspecified)
    }
}
