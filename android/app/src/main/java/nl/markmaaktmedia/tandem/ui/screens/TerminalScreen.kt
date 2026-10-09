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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.Icon
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.VisualTransformation
import kotlinx.coroutines.delay
import nl.markmaaktmedia.tandem.ui.components.DeviceGlyph
import nl.markmaaktmedia.tandem.ui.components.GroupedRow
import nl.markmaaktmedia.tandem.ui.components.RowIcon
import nl.markmaaktmedia.tandem.ui.components.SettingsGroup
import nl.markmaaktmedia.tandem.ui.components.StatusChip
import nl.markmaaktmedia.tandem.ui.theme.CardSquircle
import nl.markmaaktmedia.tandem.ui.theme.LocalTandemExtraColors
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
    var port by remember { mutableStateOf(preferences.getInt("port.$id", 22).toString()) }
    var problem by remember { mutableStateOf<String?>(null) }
    var showKey by remember { mutableStateOf(false) }
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    var connectedAs by remember { mutableStateOf("") }
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
                if (alt && data.length == 1) {
                    out = "\u001b" + out
                    alt = false
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
        val portNumber = port.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: 22
        preferences.edit().putString("user.$id", who).putInt("port.$id", portNumber).apply()
        problem = null
        phase = Phase.Connecting
        ready = false
        scope.launch {
            val address = sshAddress(runCatching { host.engine?.deviceIps(id) }.getOrNull().orEmpty(), portNumber)
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
            val failure = withContext(Dispatchers.IO) { made.connect(address, who, password.takeIf { it.isNotEmpty() }, 80, 24, portNumber) }
            if (failure == null) {
                session = made
                connectedAs = "$who@$address"
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
        TerminalBar(name, phase, connectedAs, onBack = { leave() }, onClose = { leave() })
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = if (phase == Phase.Connecting || phase == Phase.Connected) 8.dp else 0.dp)) {
            when (val now = phase) {
                Phase.Form, is Phase.Ended -> LoginForm(
                    name = name, platform = host.device(id)?.platform,
                    user = user, onUser = { user = it }, password = password, onPassword = { password = it }, port = port, onPort = { port = it.filter(Char::isDigit).take(5) },
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
                    modifier = Modifier.fillMaxSize().then(if (phase == Phase.Connecting) Modifier.size(1.dp) else Modifier.clip(RoundedCornerShape(20.dp))),
                    factory = { c ->
                        WebView(c).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            setBackgroundColor(android.graphics.Color.parseColor("#0E0E13"))
                            addJavascriptInterface(bridge, "Android")
                            loadUrl("file:///android_asset/terminal/index.html")
                            web = this
                        }
                    },
                )
            }
        }
        if (phase == Phase.Connected) {
            KeysPanel(
                ctrl = ctrl, alt = alt, onCtrl = { ctrl = !ctrl }, onAlt = { alt = !alt }, onKey = { send(it) },
                onPaste = {
                    val text = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                    if (text.isNotEmpty()) send(text)
                },
                onFont = { delta -> web?.evaluateJavascript("tandemFontStep($delta)", null) },
            )
        }
    }
}

/** The bar at the top: back, what this is and where it is logged in, and while it runs a sign that it does and a button to close it. */
@Composable
private fun TerminalBar(name: String, phase: Phase, connectedAs: String, onBack: () -> Unit, onClose: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TandemIconButton(TandemIcons.Back, stringResource(R.string.action_back), onBack)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(stringResource(R.string.terminal_card_title), style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(
                if (phase == Phase.Connected && connectedAs.isNotEmpty()) connectedAs else name,
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (phase == Phase.Connected) {
            StatusChip(TandemIcons.Check, stringResource(R.string.terminal_connected), tint = LocalTandemExtraColors.current.online)
            TandemIconButton(TandemIcons.Close, stringResource(R.string.terminal_close), onClose)
        }
    }
}

@Composable
private fun LoginForm(
    name: String,
    platform: uniffi.tandem_core.TandemPlatform?,
    user: String,
    onUser: (String) -> Unit,
    password: String,
    onPassword: (String) -> Unit,
    port: String,
    onPort: (String) -> Unit,
    problem: String?,
    showKey: Boolean,
    onShowKey: () -> Unit,
    onLogin: () -> Unit,
    again: Boolean,
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var visible by remember { mutableStateOf(false) }
    val fieldColors = TextFieldDefaults.colors(
        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(CardSquircle).background(scheme.surfaceContainer).padding(18.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            DeviceGlyph(platform ?: uniffi.tandem_core.TandemPlatform.LINUX, true, size = 52.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.terminal_login_to, name), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.terminal_form_sub), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
        SettingsGroup {
            GroupedRow(0, 3) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RowIcon(TandemIcons.Person)
                    TextField(
                        value = user, onValueChange = onUser, singleLine = true, modifier = Modifier.weight(1f), colors = fieldColors,
                        label = { Text(stringResource(R.string.terminal_user)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    )
                }
            }
            GroupedRow(1, 3) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RowIcon(TandemIcons.Key)
                    TextField(
                        value = password, onValueChange = onPassword, singleLine = true, modifier = Modifier.weight(1f), colors = fieldColors,
                        label = { Text(stringResource(R.string.terminal_password)) },
                        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    TandemIconButton(
                        if (visible) TandemIcons.VisibilityOff else TandemIcons.Visibility,
                        stringResource(if (visible) R.string.terminal_hide_password else R.string.terminal_show_password), { visible = !visible },
                    )
                }
            }
            GroupedRow(2, 3) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RowIcon(TandemIcons.Lan)
                    TextField(
                        value = port, onValueChange = onPort, singleLine = true, modifier = Modifier.weight(1f), colors = fieldColors,
                        label = { Text(stringResource(R.string.terminal_port)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            }
        }
        problem?.let {
            Row(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(scheme.errorContainer).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(TandemIcons.Error, null, tint = scheme.onErrorContainer, modifier = Modifier.size(22.dp))
                Text(problemText(it), color = scheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
        PrimaryPillButton(stringResource(if (again) R.string.terminal_again else R.string.terminal_login), onLogin, Modifier.fillMaxWidth())
        SecondaryPillButton(stringResource(R.string.terminal_key_show), onShowKey, Modifier.fillMaxWidth())
        if (showKey) {
            val key = remember { SshKeys.ensure(context) }
            Column(
                Modifier.fillMaxWidth().clip(CardSquircle).background(scheme.surfaceContainer).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(stringResource(R.string.terminal_key_hint), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                Text(
                    key?.publicLine ?: "", style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(scheme.surfaceContainerHighest).padding(12.dp),
                )
                SecondaryPillButton(stringResource(R.string.terminal_key_copy), {
                    key?.let {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("ssh key", it.publicLine))
                    }
                }, icon = TandemIcons.Copy)
            }
        }
        androidx.compose.foundation.layout.Spacer(Modifier.height(24.dp))
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

/**
 * What a phone keyboard has no key for, on a panel that rises from the bottom: the keys of a keyboard that matter to a terminal, the
 * arrows (which repeat while they are held), a button to paste, and the size of the text.
 */
@Composable
private fun KeysPanel(
    ctrl: Boolean, alt: Boolean, onCtrl: () -> Unit, onAlt: () -> Unit, onKey: (String) -> Unit, onPaste: () -> Unit, onFont: (Int) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KeyButton("Esc", false) { onKey("\u001b") }
            KeyButton("Tab", false) { onKey("\t") }
            KeyButton("Ctrl", ctrl) { onCtrl() }
            KeyButton("Alt", alt) { onAlt() }
            listOf("|", "/", "-", "~", "_", "\\", "$", "&").forEach { sign -> KeyButton(sign, false) { onKey(sign) } }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KeyButton("←", false, repeat = true) { onKey("\u001b[D") }
            KeyButton("↓", false, repeat = true) { onKey("\u001b[B") }
            KeyButton("↑", false, repeat = true) { onKey("\u001b[A") }
            KeyButton("→", false, repeat = true) { onKey("\u001b[C") }
            KeyButton("Home", false) { onKey("\u001b[H") }
            KeyButton("End", false) { onKey("\u001b[F") }
            KeyButton("PgUp", false) { onKey("\u001b[5~") }
            KeyButton("PgDn", false) { onKey("\u001b[6~") }
            KeyButton(stringResource(R.string.terminal_paste), false, icon = TandemIcons.Paste) { onPaste() }
            KeyButton("A−", false, repeat = true) { onFont(-1) }
            KeyButton("A+", false, repeat = true) { onFont(1) }
        }
    }
}

/** One key. A tap presses it (when the finger comes up, so a swipe over the row scrolls it and presses nothing), a hold on one that [repeat]s presses it again and again, and every press is felt. */
@Composable
private fun KeyButton(
    label: String, on: Boolean, repeat: Boolean = false, icon: androidx.compose.ui.graphics.painter.Painter? = null, onPress: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val scheme = MaterialTheme.colorScheme
    var down by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (down) 0.92f else 1f, label = "key")
    val current by androidx.compose.runtime.rememberUpdatedState(onPress)
    Row(
        Modifier
            .scale(scale)
            .heightIn(min = 40.dp)
            .clip(PillShape)
            .background(if (on) scheme.primary else scheme.surfaceContainerHighest)
            .pointerInput(repeat) {
                awaitEachGesture {
                    // A finger that lands on a key may be about to scroll the row, so nothing is pressed on the way down: a tap presses
                    // when the finger comes up, and a hold on a key that repeats presses when it has been held a moment.
                    awaitFirstDown(requireUnconsumed = false)
                    down = true
                    var scrolled = false
                    val up = withTimeoutOrNull(if (repeat) 380 else 60_000) {
                        val lifted = waitForUpOrCancellation()
                        if (lifted == null) scrolled = true
                        lifted
                    }
                    when {
                        up != null -> {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            current()
                        }
                        scrolled -> {}
                        else -> {
                            // Held: it goes on until it is let go.
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            current()
                            val job = kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
                                while (true) { delay(55); current() }
                            }
                            waitForUpOrCancellation()
                            job.cancel()
                        }
                    }
                    down = false
                }
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = if (on) scheme.onPrimary else scheme.onSurface, modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) scheme.onPrimary else scheme.onSurface)
    }
}
