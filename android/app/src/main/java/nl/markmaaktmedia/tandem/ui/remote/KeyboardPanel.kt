package nl.markmaaktmedia.tandem.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.ui.theme.PillShape
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons

private val FieldHeight = 48.dp
private val ModHeight = 40.dp
private val KeyHeight = 44.dp

private class ModifierKey(val bit: Int, val icon: @Composable () -> Painter, val label: Int)

/**
 * A typing field and the keys a phone keyboard does not have.
 *
 * Text goes across as it is typed. The keys below are the ones a Mac needs and a phone
 * does not show: escape and tab, the arrows, delete and enter, and the four modifiers
 * as sticky keys. A modifier is armed by a tap and spent by the next key or typed
 * character, which is how you get Cmd+C out of a keyboard that has no Cmd.
 */
@Composable
fun KeyboardPanel(
    mods: Int,
    onToggleMod: (Int) -> Unit,
    onText: (String) -> Unit,
    onKey: (Short) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TypingField(onText, onKey)
        ModifierKeys(mods, onToggleMod)
        KeyCluster(onKey)
    }
}

@Composable
private fun TypingField(onText: (String) -> Unit, onKey: (Short) -> Unit) {
    val focus = remember { FocusRequester() }
    var value by remember { mutableStateOf(TextFieldValue("")) }
    LaunchedEffect(Unit) { focus.requestFocus() }

    // What is typed goes straight across, so the field only ever holds the last few
    // letters. It is there for the phone keyboard to type into, not to be read back.
    BasicTextField(
        value = value,
        onValueChange = { new ->
            val edit = TextDiff.between(value.text, new.text)
            repeat(edit.deleted) { onKey(MacKeys.DELETE) }
            if (edit.inserted.isNotEmpty()) onText(edit.inserted)
            // Emptied between words, so the phone keyboard's own composing is never cut.
            value = if (new.text.length > 40 && new.composition == null) TextFieldValue("") else new
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(FieldHeight)
            .clip(PillShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .focusRequester(focus)
            // A phone keyboard sends backspace as a key event when the field is empty,
            // and there is nothing in the field to delete, so it has to be passed on.
            .onPreviewKeyEvent { event ->
                if (event.key == Key.Backspace && value.text.isEmpty()) {
                    if (event.type == KeyEventType.KeyDown) onKey(MacKeys.DELETE)
                    true
                } else {
                    false
                }
            },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Send,
        ),
        keyboardActions = KeyboardActions(onSend = { onKey(MacKeys.RETURN) }),
        decorationBox = { field ->
            Box(Modifier.padding(horizontal = 20.dp), contentAlignment = Alignment.CenterStart) {
                if (value.text.isEmpty()) {
                    Text(
                        stringResource(R.string.remote_type_hint),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                    )
                }
                field()
            }
        },
    )
}

@Composable
private fun ModifierKeys(mods: Int, onToggle: (Int) -> Unit) {
    val keys = listOf(
        ModifierKey(Mods.SHIFT, { TandemIcons.KeyShift }, R.string.remote_mod_shift),
        ModifierKey(Mods.CTRL, { TandemIcons.KeyControl }, R.string.remote_mod_ctrl),
        ModifierKey(Mods.OPTION, { TandemIcons.KeyOption }, R.string.remote_mod_option),
        ModifierKey(Mods.COMMAND, { TandemIcons.KeyCommand }, R.string.remote_mod_command),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(JoinGap)) {
        keys.forEachIndexed { index, key ->
            val armed = mods and key.bit != 0
            val label = stringResource(key.label)
            GroupButton(
                press = rememberPressState(),
                height = ModHeight,
                joins = Joins.row(index, keys.size),
                // An armed key rounds out fully and turns solid, which is the same thing a
                // selected button in a connected group does, so it reads as "on".
                colors = if (armed) GroupTone.selected() else GroupTone.neutral(),
                round = armed,
                modifier = Modifier.weight(1f),
                description = label,
                onUp = { inside -> if (inside) onToggle(key.bit) },
            ) { tint ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(key.icon(), null, tint = tint, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(label, style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1)
                }
            }
        }
    }
}

/**
 * Escape and tab on the left, the arrows as an inverted T in the middle, delete over
 * enter on the right. That is where a Mac keyboard has them, so the hand already
 * knows the layout.
 */
@Composable
private fun KeyCluster(onKey: (Short) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1.15f), verticalArrangement = Arrangement.spacedBy(JoinGap)) {
            TextKey(stringResource(R.string.remote_key_esc), Joins.column(0, 2), MacKeys.ESCAPE, onKey)
            TextKey(stringResource(R.string.remote_key_tab), Joins.column(1, 2), MacKeys.TAB, onKey)
        }

        Column(Modifier.weight(3f), verticalArrangement = Arrangement.spacedBy(JoinGap)) {
            Row(horizontalArrangement = Arrangement.spacedBy(JoinGap)) {
                Spacer(Modifier.weight(1f))
                IconKey(TandemIcons.KeyUp, R.string.remote_key_up, Joins(false, false, true, true), MacKeys.UP, onKey, Modifier.weight(1f))
                Spacer(Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(JoinGap)) {
                IconKey(TandemIcons.KeyLeft, R.string.remote_key_left, Joins(false, true, true, false), MacKeys.LEFT, onKey, Modifier.weight(1f))
                IconKey(TandemIcons.KeyDown, R.string.remote_key_down, Joins(true, true, true, true), MacKeys.DOWN, onKey, Modifier.weight(1f))
                IconKey(TandemIcons.KeyRight, R.string.remote_key_right, Joins(true, false, false, true), MacKeys.RIGHT, onKey, Modifier.weight(1f))
            }
        }

        Column(Modifier.weight(2.2f), verticalArrangement = Arrangement.spacedBy(JoinGap)) {
            IconKey(TandemIcons.Backspace, R.string.remote_key_delete, Joins.column(0, 2), MacKeys.DELETE, onKey, Modifier.fillMaxWidth())
            val label = stringResource(R.string.remote_key_enter)
            GroupButton(
                press = rememberPressState(),
                height = KeyHeight,
                joins = Joins.column(1, 2),
                colors = GroupTone.accent(),
                modifier = Modifier.fillMaxWidth(),
                description = label,
                onDown = { onKey(MacKeys.RETURN) },
            ) { tint ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(TandemIcons.Enter, null, tint = tint, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(label, style = MaterialTheme.typography.labelLarge, color = tint, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun TextKey(label: String, joins: Joins, code: Short, onKey: (Short) -> Unit) {
    GroupButton(
        press = rememberPressState(),
        height = KeyHeight,
        joins = joins,
        colors = GroupTone.neutral(),
        modifier = Modifier.fillMaxWidth(),
        description = label,
        onDown = { onKey(code) },
    ) { tint ->
        Text(label, style = MaterialTheme.typography.labelLarge, color = tint, maxLines = 1)
    }
}

/** A key that repeats while it is held, like the arrows and delete of a real keyboard. */
@Composable
private fun IconKey(
    icon: Painter,
    label: Int,
    joins: Joins,
    code: Short,
    onKey: (Short) -> Unit,
    modifier: Modifier,
) {
    GroupButton(
        press = rememberPressState(),
        height = KeyHeight,
        joins = joins,
        colors = GroupTone.neutral(),
        modifier = modifier,
        description = stringResource(label),
        onDown = { onKey(code) },
        onRepeat = { onKey(code) },
    ) { tint ->
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
    }
}
