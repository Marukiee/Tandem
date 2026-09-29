package nl.markmaaktmedia.tandem.ui.appearance

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import nl.markmaaktmedia.tandem.ui.components.bouncyClickable
import nl.markmaaktmedia.tandem.ui.theme.PaletteStyle
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import nl.markmaaktmedia.tandem.ui.theme.TandemMotion
import nl.markmaaktmedia.tandem.ui.theme.appearanceScheme
import nl.markmaaktmedia.tandem.ui.theme.blendSchemes
import nl.markmaaktmedia.tandem.ui.theme.rememberBlended
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min

/*
 * The idea behind these previews comes from the palette page in PixelPlayer
 * (theovilardo/PixelPlayer): every choice is drawn in the scheme it would produce, not
 * in the app's own colours, so you compare real results instead of names. PixelPlayer's
 * licence does not allow reuse of its code, so nothing here is copied. The tiles, the
 * mock screen and the selection motion are drawn for Tandem.
 */

/**
 * The scheme a preview tile shows, or null for the few frames before it exists.
 *
 * Generating a scheme costs a millisecond or more, and this page has fourteen tiles
 * that all change together when the light or dark switch flips. Done inline that is a
 * dropped frame or two on the very tap the user is watching, so the tiles are built on
 * a background thread and the old scheme stays on screen until the new one lands.
 * Once it has, it is blended in, so tiles follow a theme change in step with the rest
 * of the app instead of jumping ahead of it.
 */
@Composable
private fun rememberPreviewScheme(seed: Color, style: PaletteStyle, dark: Boolean, pureBlack: Boolean): ColorScheme? {
    val built by produceState<ColorScheme?>(null, seed, style, dark, pureBlack) {
        value = withContext(Dispatchers.Default) { appearanceScheme(seed, style, dark, pureBlack) }
    }
    val target = built ?: return null
    return rememberBlended(target, ::blendSchemes)
}

/**
 * A card that previews the whole scheme of one palette style: a small Tandem screen
 * with its title, a device slab, two chips and the navigation pill, all in that
 * style's primary, secondary, tertiary and container tones.
 */
@Composable
fun StylePreviewCard(
    style: PaletteStyle,
    seed: Color,
    dark: Boolean,
    pureBlack: Boolean,
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = rememberPreviewScheme(seed, style, dark, pureBlack)
    PreviewTile(
        scheme = scheme,
        selected = selected,
        label = label,
        aspect = 0.82f,
        corner = 22.dp,
        badgeSize = 22.dp,
        labelStyle = MaterialTheme.typography.labelLarge,
        onClick = onClick,
        modifier = modifier,
        draw = { drawMiniScreen(it) },
    )
}

/**
 * A tile that previews one accent: the seed spread into primary, secondary and
 * tertiary in the palette style currently in use. [overlay] is for the wallpaper tile,
 * which carries an icon so it reads as "automatic" rather than as one more colour.
 */
@Composable
fun SeedPreviewTile(
    seed: Color,
    style: PaletteStyle,
    dark: Boolean,
    pureBlack: Boolean,
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    overlay: @Composable BoxScope.(ColorScheme) -> Unit = {},
) {
    val scheme = rememberPreviewScheme(seed, style, dark, pureBlack)
    PreviewTile(
        scheme = scheme,
        selected = selected,
        label = label,
        aspect = 1f,
        corner = 22.dp,
        badgeSize = 18.dp,
        labelStyle = MaterialTheme.typography.labelSmall,
        onClick = onClick,
        modifier = modifier,
        overlay = { if (scheme != null) overlay(scheme) },
        draw = { drawOrbs(it) },
    )
}

/**
 * Tile, ring and label in one, shared by both kinds of preview so a style card and a
 * seed tile select in exactly the same way.
 *
 * Selection is a spring, not a state flip: the tile pulls in from a ring that grows
 * around it and a check badge pops onto the corner. A spring because it is size and
 * position, and shared so the ring and the pull-in stay one motion.
 */
@Composable
private fun PreviewTile(
    scheme: ColorScheme?,
    selected: Boolean,
    label: String,
    aspect: Float,
    corner: Dp,
    badgeSize: Dp,
    labelStyle: TextStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    overlay: @Composable BoxScope.() -> Unit = {},
    draw: DrawScope.(ColorScheme) -> Unit,
) {
    val app = MaterialTheme.colorScheme
    val chosen by animateFloatAsState(if (selected) 1f else 0f, TandemMotion.springy(), label = "previewChosen")
    // Springs overshoot, and a ring narrower than nothing or a colour past its end
    // points is not a thing, so everything derived from the spring is clamped.
    val ring = chosen.coerceIn(0f, 1f)

    // Tiles fade in once their scheme is ready instead of popping.
    val shown by animateFloatAsState(if (scheme != null) 1f else 0f, TandemMotion.fadeSpec(), label = "previewShown")

    val outer = RoundedCornerShape(corner)
    val inner = RoundedCornerShape(corner - RingGap)

    Column(
        modifier = modifier
            .semantics(mergeDescendants = true) {
                this.selected = selected
                role = Role.RadioButton
                contentDescription = label
            }
            .bouncyClickable(role = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .border(RingWidth * ring, lerp(Color.Transparent, app.primary, ring), outer)
                .padding(RingGap * chosen.coerceAtLeast(0f)),
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = shown }
                    .clip(inner)
                    .background(scheme?.background ?: app.surfaceContainerHigh)
                    .drawBehind { if (scheme != null) draw(scheme) }
                    .border(1.dp, app.outlineVariant.copy(alpha = 0.7f), inner),
            ) { overlay() }

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-8).dp, y = 8.dp)
                    .size(badgeSize)
                    .graphicsLayer {
                        val scale = chosen.coerceAtLeast(0f)
                        scaleX = scale
                        scaleY = scale
                    }
                    .clip(CircleShape)
                    .background(app.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = TandemIcons.Check,
                    contentDescription = null,
                    tint = app.onPrimary,
                    modifier = Modifier.size(badgeSize * 0.66f),
                )
            }
        }
        Text(
            text = label,
            style = labelStyle,
            color = lerp(app.onSurfaceVariant, app.primary, ring),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

private val RingWidth = 2.5.dp
private val RingGap = 5.dp

/** A miniature Tandem screen, drawn straight into the tile in the tile's own scheme. */
private fun DrawScope.drawMiniScreen(scheme: ColorScheme) {
    val w = size.width
    val h = size.height
    val pad = w * 0.11f

    fun bar(x: Float, y: Float, width: Float, height: Float, color: Color) =
        drawRoundRect(color, Offset(x, y), Size(width, height), CornerRadius(height / 2f))

    // Title and subtitle, the way every Tandem page opens.
    bar(pad, h * 0.075f, w * 0.40f, h * 0.05f, scheme.onSurface.copy(alpha = 0.85f))
    bar(pad, h * 0.155f, w * 0.26f, h * 0.03f, scheme.onSurfaceVariant.copy(alpha = 0.6f))

    // A device slab: primary badge, two lines of text.
    val slabTop = h * 0.26f
    val slabHeight = h * 0.25f
    drawRoundRect(scheme.primaryContainer, Offset(pad, slabTop), Size(w - pad * 2, slabHeight), CornerRadius(w * 0.09f))
    val badge = slabHeight * 0.30f
    drawCircle(scheme.primary, badge, Offset(pad + slabHeight * 0.5f, slabTop + slabHeight / 2f))
    val textX = pad + slabHeight * 0.5f + badge * 1.6f
    bar(textX, slabTop + slabHeight * 0.34f, w - pad - textX - w * 0.1f, slabHeight * 0.14f, scheme.onPrimaryContainer.copy(alpha = 0.85f))
    bar(textX, slabTop + slabHeight * 0.58f, (w - pad - textX - w * 0.1f) * 0.6f, slabHeight * 0.10f, scheme.onPrimaryContainer.copy(alpha = 0.45f))

    // Two chips, one in the secondary family and one in the tertiary.
    val chipTop = h * 0.55f
    val chipHeight = h * 0.15f
    val gap = w * 0.04f
    val chipWidth = (w - pad * 2 - gap) / 2f
    drawRoundRect(scheme.secondaryContainer, Offset(pad, chipTop), Size(chipWidth, chipHeight), CornerRadius(w * 0.07f))
    drawRoundRect(scheme.tertiaryContainer, Offset(pad + chipWidth + gap, chipTop), Size(chipWidth, chipHeight), CornerRadius(w * 0.07f))
    val dot = chipHeight * 0.26f
    drawCircle(scheme.secondary, dot, Offset(pad + chipWidth * 0.26f, chipTop + chipHeight / 2f))
    drawCircle(scheme.tertiary, dot, Offset(pad + chipWidth + gap + chipWidth * 0.26f, chipTop + chipHeight / 2f))
    bar(pad + chipWidth * 0.46f, chipTop + chipHeight * 0.42f, chipWidth * 0.34f, chipHeight * 0.16f, scheme.onSecondaryContainer.copy(alpha = 0.6f))
    bar(pad + chipWidth + gap + chipWidth * 0.46f, chipTop + chipHeight * 0.42f, chipWidth * 0.34f, chipHeight * 0.16f, scheme.onTertiaryContainer.copy(alpha = 0.6f))

    // The navigation pill with its moving indicator.
    val navTop = h * 0.76f
    val navHeight = h * 0.15f
    val navWidth = w - pad * 2
    drawRoundRect(scheme.surfaceContainerHigh, Offset(pad, navTop), Size(navWidth, navHeight), CornerRadius(navHeight / 2f))
    val inset = navHeight * 0.12f
    drawRoundRect(
        scheme.primary,
        Offset(pad + inset, navTop + inset),
        Size(navWidth / 3f - inset, navHeight - inset * 2f),
        CornerRadius((navHeight - inset * 2f) / 2f),
    )
    val dotY = navTop + navHeight / 2f
    drawCircle(scheme.onSurfaceVariant.copy(alpha = 0.6f), navHeight * 0.09f, Offset(pad + navWidth * 0.5f, dotY))
    drawCircle(scheme.onSurfaceVariant.copy(alpha = 0.6f), navHeight * 0.09f, Offset(pad + navWidth * 0.83f, dotY))
}

/** The accent as three orbs on its container: primary large, secondary and tertiary orbiting it. */
private fun DrawScope.drawOrbs(scheme: ColorScheme) {
    val w = size.width
    val h = size.height
    drawRect(scheme.primaryContainer)
    val unit = min(w, h)
    // The top right corner stays empty: that is where the selection badge lands.
    drawCircle(scheme.primary, unit * 0.26f, Offset(w * 0.40f, h * 0.44f))
    drawCircle(scheme.tertiary, unit * 0.12f, Offset(w * 0.79f, h * 0.56f))
    drawCircle(scheme.secondary, unit * 0.14f, Offset(w * 0.62f, h * 0.80f))
}

/**
 * A small Tandem screen in the colours the app is wearing right now. It is not a
 * screenshot but the same roles the real screens use, so a palette change shows here in
 * the context it will be used in, above the controls that caused it.
 */
@Composable
fun LiveScreenPreview(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(28.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(scheme.background)
            .border(1.dp, scheme.outlineVariant, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(width = 96.dp, height = 14.dp).clip(CircleShape).background(scheme.onBackground.copy(alpha = 0.85f)))
            Box(Modifier.size(width = 60.dp, height = 8.dp).clip(CircleShape).background(scheme.onSurfaceVariant.copy(alpha = 0.55f)))
        }
        MockSlab(scheme.surfaceContainerHigh, scheme.primaryContainer, scheme.onPrimaryContainer, scheme.onSurface, scheme.tertiaryContainer)
        MockSlab(scheme.surfaceContainerHigh, scheme.secondaryContainer, scheme.onSecondaryContainer, scheme.onSurface, scheme.tertiaryContainer)
        Row(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(CircleShape)
                .background(scheme.surfaceContainer)
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .height(28.dp)
                    .clip(CircleShape)
                    .background(scheme.primary),
            )
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(scheme.onSurfaceVariant.copy(alpha = 0.6f)))
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(scheme.onSurfaceVariant.copy(alpha = 0.6f)))
            }
        }
    }
}

@Composable
private fun MockSlab(container: Color, badge: Color, onBadge: Color, text: Color, tag: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(container)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(badge), contentAlignment = Alignment.Center) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(onBadge))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(Modifier.size(width = 84.dp, height = 9.dp).clip(CircleShape).background(text.copy(alpha = 0.8f)))
            Box(Modifier.size(width = 52.dp, height = 6.dp).clip(CircleShape).background(text.copy(alpha = 0.35f)))
        }
        Box(Modifier.size(width = 30.dp, height = 16.dp).clip(CircleShape).background(tag))
    }
}
