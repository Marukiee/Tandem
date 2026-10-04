package nl.markmaaktmedia.tandem.ui.screens.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.ui.components.TandemIconButton
import nl.markmaaktmedia.tandem.ui.components.bouncyClickable
import nl.markmaaktmedia.tandem.ui.theme.PillShape
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import nl.markmaaktmedia.tandem.ui.theme.TandemMotion

/**
 * The search pill at the top of Settings.
 *
 * While there is something to search the magnifier turns into a back arrow, which does what the system Back does:
 * clears the search. The clear button only shows while there are words to clear. Focus is a ring and a slightly
 * lighter fill, both eased, because the page around it does not change when the field is touched.
 */
@Composable
internal fun SettingsSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    active: Boolean,
    onFocusChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    val fill by animateColorAsState(
        if (focused) scheme.surfaceContainerHighest else scheme.surfaceContainerHigh,
        TandemMotion.colourSpec(), label = "searchFill",
    )
    val ring by animateFloatAsState(if (focused) 1f else 0f, tween(TandemMotion.DurationMedium, easing = TandemMotion.Standard), label = "searchRing")

    Row(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(PillShape)
            .background(fill)
            .border(2.dp, scheme.primary.copy(alpha = ring), PillShape)
            .clickable(remember { MutableInteractionSource() }, indication = null) { focusRequester.requestFocus() }
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .then(if (active) Modifier.bouncyClickable(onClickLabel = stringResource(R.string.action_back), onClick = onBack) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = active,
                transitionSpec = {
                    (fadeIn(TandemMotion.fadeSpec()) + scaleIn(TandemMotion.springy(), initialScale = 0.6f)) togetherWith
                        (fadeOut(tween(TandemMotion.DurationFast)) + scaleOut(tween(TandemMotion.DurationFast), targetScale = 0.6f))
                },
                label = "searchLeading",
            ) { searching ->
                Icon(
                    if (searching) TandemIcons.Back else SettingsIcons.search(),
                    contentDescription = if (searching) stringResource(R.string.action_back) else null,
                    tint = if (searching) scheme.onSurface else scheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
            cursorBrush = SolidColor(scheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp)
                .focusRequester(focusRequester)
                .onFocusChanged {
                    focused = it.isFocused
                    onFocusChange(it.isFocused)
                },
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            stringResource(R.string.settings_search_hint),
                            style = MaterialTheme.typography.bodyLarge,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    inner()
                }
            },
        )

        AnimatedVisibility(
            visible = query.isNotEmpty(),
            enter = fadeIn(TandemMotion.fadeSpec()) + scaleIn(TandemMotion.springy(), initialScale = 0.5f),
            exit = fadeOut(tween(TandemMotion.DurationFast)) + scaleOut(tween(TandemMotion.DurationFast), targetScale = 0.5f),
        ) {
            TandemIconButton(
                TandemIcons.Close, stringResource(R.string.settings_search_clear),
                onClick = {
                    onQueryChange("")
                    focusRequester.requestFocus()
                },
                size = 48, iconSize = 20,
            )
        }
    }
}

/** Icons that only Settings uses, so the shared icon set stays as it is. */
internal object SettingsIcons {
    @Composable
    fun search() = painterResource(R.drawable.sym_search)
}
