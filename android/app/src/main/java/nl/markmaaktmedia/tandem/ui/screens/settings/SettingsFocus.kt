package nl.markmaaktmedia.tandem.ui.screens.settings

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import nl.markmaaktmedia.tandem.ui.theme.TandemMotion
import nl.markmaaktmedia.tandem.ui.theme.groupedShape

/**
 * What the Settings tab remembers while a page is open on top of it: the search, and the one entry a search result
 * is taking the person to. It lives in a ViewModel because the tab leaves the composition when a page opens, and
 * coming back to the words that were typed, with the results still there, is what makes searching bearable.
 */
class SettingsViewModel : ViewModel() {
    var query by mutableStateOf("")

    val focus = SettingsFocus()
}

/**
 * The entry a search result asks the next page to scroll to and light up. It is a request that is answered once:
 * the row that owns the key takes it, and nobody else ever sees it. A request nobody takes (the row is not on screen
 * because its switch was off, say) goes stale after a few seconds, so it cannot surprise anyone on a later visit.
 */
class SettingsFocus {
    private var key by mutableStateOf<String?>(null)
    private var requestedAt = 0L

    fun request(key: String?) {
        requestedAt = SystemClock.uptimeMillis()
        this.key = key
    }

    internal fun isPending(key: String): Boolean =
        this.key == key && SystemClock.uptimeMillis() - requestedAt < StaleAfterMillis

    internal fun consume(key: String) {
        if (this.key == key) this.key = null
    }

    fun clear() {
        key = null
    }

    private companion object {
        const val StaleAfterMillis = 4_000L
    }
}

/**
 * Makes a row findable: when a search result points at [key], the page scrolls the row into view and the row pulses
 * twice in the accent colour. Rows without a request around them cost one remembered animation and nothing else.
 *
 * [shape] has to be the shape of the row itself, because the glow is drawn over the row and would show square
 * corners on a rounded slab.
 */
@Composable
fun Modifier.settingsTarget(key: String, shape: Shape): Modifier {
    val owner = LocalViewModelStoreOwner.current ?: return this
    val focus = viewModel<SettingsViewModel>(owner).focus
    val requester = remember { BringIntoViewRequester() }
    val glow = remember { Animatable(0f) }
    var bounds by remember { mutableStateOf(IntSize.Zero) }
    val margin = with(LocalDensity.current) { RevealMargin.toPx() }

    LaunchedEffect(focus, key) {
        // collect, not collectLatest: taking the request flips the flag back, and that must not cancel the pulse.
        snapshotFlow { focus.isPending(key) }.filter { it }.collect {
            focus.consume(key)
            // The page is still sliding in, and rows that fade in one by one are not at rest yet.
            delay(ArriveMillis)
            requester.bringIntoView(Rect(0f, -margin, bounds.width.toFloat(), bounds.height + margin))
            repeat(2) { round ->
                glow.animateTo(1f, tween(durationMillis = 220, easing = TandemMotion.Standard))
                glow.animateTo(0f, tween(durationMillis = if (round == 0) 320 else 900, easing = TandemMotion.Standard))
            }
        }
    }

    val colour = MaterialTheme.colorScheme.primary
    return this
        .bringIntoViewRequester(requester)
        .onSizeChanged { bounds = it }
        .drawWithContent {
            drawContent()
            val amount = glow.value
            if (amount > 0f) {
                val outline = shape.createOutline(this.size, layoutDirection, this)
                drawOutline(outline, colour.copy(alpha = 0.18f * amount))
                val width = 2.dp.toPx()
                // Inset by half the line, so the ring is drawn inside the row and not half cut off by its edge.
                inset(width / 2) {
                    drawOutline(shape.createOutline(this.size, layoutDirection, this), colour.copy(alpha = 0.9f * amount), style = Stroke(width))
                }
            }
        }
}

/** The same, for a row that sits in a slab: its corners follow from where it is in the group. */
@Composable
fun Modifier.settingsTarget(key: String, index: Int, total: Int): Modifier = settingsTarget(key, groupedShape(index, total))

/**
 * For rows that take no modifier (a switch, a segmented row): the same marker, around the row.
 */
@Composable
fun SettingsTarget(key: String, index: Int, total: Int, content: @Composable () -> Unit) {
    Box(Modifier.settingsTarget(key, index, total)) { content() }
}

/** The same, for something that is not a row of a slab, like a card, with the shape it has. */
@Composable
fun SettingsTarget(key: String, shape: Shape, content: @Composable () -> Unit) {
    Box(Modifier.settingsTarget(key, shape)) { content() }
}

private const val ArriveMillis = 420L

/** A little room above and below, so the row does not end up flush against the edge of the screen. */
private val RevealMargin = 96.dp
