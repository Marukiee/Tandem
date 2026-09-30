package nl.markmaaktmedia.tandem.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import kotlinx.coroutines.launch
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.components.PresenceDot
import nl.markmaaktmedia.tandem.ui.components.TandemIconButton
import nl.markmaaktmedia.tandem.ui.remote.KeyboardPanel
import nl.markmaaktmedia.tandem.ui.remote.MacKeys
import nl.markmaaktmedia.tandem.ui.remote.MediaControls
import nl.markmaaktmedia.tandem.ui.remote.Mods
import nl.markmaaktmedia.tandem.ui.remote.MouseButtons
import nl.markmaaktmedia.tandem.ui.remote.PadFeedback
import nl.markmaaktmedia.tandem.ui.remote.RemoteLink
import nl.markmaaktmedia.tandem.ui.remote.SwipeDirection
import nl.markmaaktmedia.tandem.ui.remote.TouchpadClassifier
import nl.markmaaktmedia.tandem.ui.remote.TouchpadConfig
import nl.markmaaktmedia.tandem.ui.remote.TouchpadOutput
import nl.markmaaktmedia.tandem.ui.remote.touchpad
import nl.markmaaktmedia.tandem.ui.theme.SheetSquircle
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import nl.markmaaktmedia.tandem.ui.theme.TandemMotion
import uniffi.tandem_core.TandemInput

/** The gap between every block of the screen. */
private val Gap = 12.dp

/** Turns the phone into a trackpad, keyboard and media remote for another device. */
@Composable
fun RemoteScreen(id: String, onBack: () -> Unit) {
    val graph = LocalContext.current.graph
    val host = graph.host
    val devices by host.devices.collectAsState()
    val device = devices.firstOrNull { it.id == id }
    val haptics = LocalHapticFeedback.current

    val link = remember(id) { RemoteLink(host, id, graph.scope) }
    DisposableEffect(link) {
        link.start()
        onDispose { link.release() }
    }

    var keyboard by rememberSaveable { mutableStateOf(false) }
    val prefs = graph.prefs
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val mouseButtons by prefs.remoteMouse.collectAsState(initial = true)
    val mediaOn by prefs.remoteMedia.collectAsState(initial = false)
    // The armed modifiers of the on-screen keyboard, spent by the next key or character.
    var mods by remember { mutableIntStateOf(0) }

    fun pressKey(code: Short) {
        link.key(code, mods)
        mods = 0
    }

    fun typeText(text: String) {
        var armed = mods
        mods = 0
        val plain = StringBuilder()
        fun flush() {
            if (plain.isNotEmpty()) link.send(TandemInput.Text(plain.toString()))
            plain.clear()
        }
        for (char in text) {
            // Only the first character takes the armed keys, and only a character with a
            // key of its own can: an accent or an emoji has nothing to press Cmd with.
            if (armed != 0 || char == '\n') {
                val stroke = MacKeys.strokeFor(char)
                if (stroke != null) {
                    flush()
                    link.key(stroke.code, armed or stroke.mods)
                    armed = 0
                    continue
                }
                armed = 0
            }
            plain.append(char)
        }
        flush()
    }

    val slop = LocalViewConfiguration.current.touchSlop
    val swipeDistance = with(LocalDensity.current) { 56.dp.toPx() }
    val classifier = remember(link, haptics, slop, swipeDistance) {
        TouchpadClassifier(
            TouchpadConfig(slop = slop, swipeDistance = swipeDistance),
            object : TouchpadOutput {
                override fun pointer(dx: Int, dy: Int) = link.pointer(dx, dy)
                override fun scroll(dx: Int, dy: Int) = link.scroll(dx, dy)
                override fun click(button: Int, count: Int) = link.click(button, count)
                override fun button(button: Int, down: Boolean) = link.hold(button, down)

                // Three fingers up or down open Mission Control and App Exposé, left and
                // right change space. The Mac maps all four to Ctrl plus an arrow.
                override fun swipe(direction: SwipeDirection) = link.key(
                    when (direction) {
                        SwipeDirection.Up -> MacKeys.UP
                        SwipeDirection.Down -> MacKeys.DOWN
                        // As on a real trackpad: swiping left pulls in the space to the right.
                        SwipeDirection.Left -> MacKeys.RIGHT
                        SwipeDirection.Right -> MacKeys.LEFT
                    },
                    Mods.CTRL,
                )

                override fun feedback(kind: PadFeedback) {
                    haptics.performHapticFeedback(
                        when (kind) {
                            PadFeedback.Click -> HapticFeedbackType.TextHandleMove
                            PadFeedback.DragStart -> HapticFeedbackType.LongPress
                            PadFeedback.Swipe -> HapticFeedbackType.GestureThresholdActivate
                        },
                    )
                }
            },
        )
    }

    // Closing the panel has to take the phone keyboard with it, or the keyboard stays up
    // over a screen that no longer has anything to type into.
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    LaunchedEffect(keyboard) {
        if (!keyboard) {
            keyboardController?.hide()
            focusManager.clearFocus()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp),
    ) {
        TopBar(
            name = device?.name ?: "",
            online = device?.online == true,
            keyboardOn = keyboard,
            mouseOn = mouseButtons,
            onBack = onBack,
            onKeyboard = { keyboard = !keyboard },
            onMouse = { scope.launch { prefs.setRemoteMouse(!mouseButtons) } },
            mediaOn = mediaOn,
            onMedia = { scope.launch { prefs.setRemoteMedia(!mediaOn) } },
        )

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(SheetSquircle)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .touchpad(classifier),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(TandemIcons.Touch, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(40.dp))
                Text(
                    stringResource(R.string.remote_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
                Text(
                    stringResource(R.string.remote_hint_three),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
        }

        // Each block that comes and goes brings its own gap with it, so the gap grows and
        // shrinks with the block and nothing is left standing between two neighbours.
        AnimatedVisibility(
            visible = keyboard,
            enter = fadeIn(TandemMotion.fadeSpec()) + expandVertically(TandemMotion.sizeSpring(), expandFrom = Alignment.Top),
            exit = fadeOut(TandemMotion.fadeSpec()) + shrinkVertically(TandemMotion.sizeSpring(), shrinkTowards = Alignment.Top),
        ) {
            Column {
                Spacer(Modifier.height(Gap))
                KeyboardPanel(
                    mods = mods,
                    onToggleMod = { bit -> mods = mods xor bit },
                    onText = ::typeText,
                    onKey = ::pressKey,
                )
            }
        }

        AnimatedVisibility(
            visible = mediaOn,
            enter = fadeIn(TandemMotion.fadeSpec()) + expandVertically(TandemMotion.sizeSpring(), expandFrom = Alignment.Top),
            exit = fadeOut(TandemMotion.fadeSpec()) + shrinkVertically(TandemMotion.sizeSpring(), shrinkTowards = Alignment.Top),
        ) {
            Column {
                Spacer(Modifier.height(Gap))
                MediaControls(onKey = { link.send(TandemInput.Media(it)) })
            }
        }

        AnimatedVisibility(
            visible = mouseButtons,
            enter = fadeIn(TandemMotion.fadeSpec()) + expandVertically(TandemMotion.sizeSpring(), expandFrom = Alignment.Top),
            exit = fadeOut(TandemMotion.fadeSpec()) + shrinkVertically(TandemMotion.sizeSpring(), shrinkTowards = Alignment.Top),
        ) {
            Column {
                Spacer(Modifier.height(Gap))
                MouseButtons(onButton = link::hold)
            }
        }
        Spacer(Modifier.height(Gap))
    }
}

@Composable
private fun TopBar(
    name: String,
    online: Boolean,
    keyboardOn: Boolean,
    mouseOn: Boolean,
    mediaOn: Boolean,
    onBack: () -> Unit,
    onKeyboard: () -> Unit,
    onMouse: () -> Unit,
    onMedia: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TandemIconButton(TandemIcons.Back, stringResource(R.string.action_back), onBack)
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The dot sits in a 14dp box so its ring has room to breathe, which put the
                // dot itself 3dp right of the name. Pulled back so the line starts where the
                // name starts, with the box still taking its full width for the text.
                PresenceDot(online, Modifier.offset(x = (-3).dp))
                Text(
                    stringResource(if (online) R.string.remote_ready else R.string.status_offline),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // One connected group: the ends are fully round, the corners that meet are tight, and
        // a pressed button rounds off, like the connected button groups in Material 3 Expressive.
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            GroupToggle(TandemIcons.Keyboard, stringResource(R.string.remote_keyboard), keyboardOn, GroupEnd.Start, onKeyboard)
            GroupToggle(TandemIcons.Mouse, stringResource(R.string.remote_mouse_buttons), mouseOn, GroupEnd.Middle, onMouse)
            var menu by remember { mutableStateOf(false) }
            Box {
                GroupToggle(TandemIcons.More, stringResource(R.string.remote_more), menu, GroupEnd.End) { menu = true }
                androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.remote_media)) },
                        leadingIcon = if (mediaOn) ({ Icon(TandemIcons.Check, null) }) else null,
                        onClick = { onMedia(); menu = false },
                    )
                }
            }
        }
    }
}

private enum class GroupEnd { Start, Middle, End }

private val GroupOuter = 22.dp
private val GroupInner = 6.dp

/** A toggle in a connected group. It stays lit while what it shows is on; the colours fade, never snap. */
@Composable
private fun GroupToggle(icon: Painter, description: String, on: Boolean, position: GroupEnd, onClick: () -> Unit) {
    val container by animateColorAsState(
        if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        TandemMotion.colourSpec(), label = "toggleContainer",
    )
    val tint by animateColorAsState(
        if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        TandemMotion.colourSpec(), label = "toggleTint",
    )
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    // The tight corners open up while the finger is down, and spring back.
    val inner by androidx.compose.animation.core.animateDpAsState(if (pressed) GroupOuter else GroupInner, TandemMotion.springy(), label = "innerCorner")
    val shape = when (position) {
        GroupEnd.Start -> androidx.compose.foundation.shape.RoundedCornerShape(GroupOuter, inner, inner, GroupOuter)
        GroupEnd.Middle -> androidx.compose.foundation.shape.RoundedCornerShape(inner)
        GroupEnd.End -> androidx.compose.foundation.shape.RoundedCornerShape(inner, GroupOuter, GroupOuter, inner)
    }
    Box(
        Modifier
            .size(width = 52.dp, height = 44.dp)
            .clip(shape)
            .background(container)
            .clickable(interactionSource = source, indication = androidx.compose.material3.ripple(), role = androidx.compose.ui.semantics.Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(22.dp))
    }
}
