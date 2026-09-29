package nl.markmaaktmedia.tandem.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

class PillNavItem(
    val label: String,
    val icon: @Composable () -> Painter,
    val selectedIcon: @Composable () -> Painter = icon,
    val badgeCount: Int = 0,
)

/**
 * The floating navigation bar.
 *
 * One indicator, not four backgrounds. A single pill lives behind the row and travels
 * to whichever tab was tapped, which is what makes the bar read as one object rather
 * than four buttons that light up independently.
 *
 * The pill is elastic, see [ElasticIndicator]: its two edges are separate springs, the
 * one in the direction of travel is the stiffer, so it stretches out and gathers itself
 * back at the destination. A hop to the neighbour barely deforms, a jump across the bar
 * visibly stretches, so the distance is legible rather than decorative.
 *
 * Icon and label take their colour from how much of their slot the pill covers, so
 * they change as the pill passes under them and not on a timer of their own.
 *
 * Icon and label are laid out as one centred block inside the full indicator height,
 * with the icon given a fixed box. Letting the label sit under a free standing icon
 * leaves the pair riding high inside the pill, which is subtle enough that it just
 * reads as sloppy.
 */
@Composable
fun PillNavigationBar(
    items: List<PillNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    indicatorColor: Color = MaterialTheme.colorScheme.primary,
) {
    if (items.isEmpty()) return

    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(BarHeight)
            .clip(RoundedCornerShape(percent = 50))
            .background(containerColor)
            .padding(Padding),
    ) {
        // BoxWithConstraints reports the space it has left after its own modifiers, so
        // the padding is already gone by the time this runs. Subtracting it a second
        // time made every slot narrower than its tab, and the error piled up on the
        // last one: the pill sat flush on the left and left a gap on the right.
        val slotWidth = maxWidth / items.size
        val slotPx = with(density) { slotWidth.toPx() }

        // The indicator is drawn in physical pixels, so a right to left layout has to
        // mirror the slots itself.
        fun slotLeft(index: Int) = if (rtl) slotPx * (items.size - 1 - index) else slotPx * index

        val target = remember(selectedIndex, slotPx, rtl, items.size) {
            IndicatorTarget(selectedIndex, slotLeft(selectedIndex), slotLeft(selectedIndex) + slotPx)
        }
        val indicator = rememberElasticIndicator(target)

        Row(modifier = Modifier.fillMaxSize().elasticIndicator(indicator, indicatorColor)) {
            items.forEachIndexed { index, item ->
                PillNavTab(
                    item = item,
                    selected = index == selectedIndex,
                    covered = indicator.coverage(slotLeft(index), slotLeft(index) + slotPx),
                    onSelect = { onSelect(index) },
                    modifier = Modifier.width(slotWidth),
                    selectedContentColor = MaterialTheme.colorScheme.onPrimary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PillNavTab(
    item: PillNavItem,
    selected: Boolean,
    covered: Float,
    onSelect: () -> Unit,
    selectedContentColor: Color,
    unselectedContentColor: Color,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }

    val contentColor = lerp(unselectedContentColor, selectedContentColor, covered)
    // The covered tab grows a touch, so the pill has something to be holding up rather
    // than sitting behind. Driven by the pill as well, so the two cannot drift apart.
    val lift = covered

    Box(
        modifier = modifier
            .fillMaxHeight()
            .selectable(
                selected = selected,
                onClick = onSelect,
                role = Role.Tab,
                interactionSource = interactionSource,
                indication = null,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp),
            modifier = Modifier.graphicsLayer {
                scaleX = 1f + lift * 0.05f
                scaleY = 1f + lift * 0.05f
            },
        ) {
            Box(
                modifier = Modifier.size(IconBox),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = if (selected) item.selectedIcon() else item.icon(),
                    contentDescription = item.label,
                    tint = contentColor,
                    modifier = Modifier.size(IconSize),
                )
                if (item.badgeCount > 0) {
                    CountBadge(
                        count = item.badgeCount,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 8.dp, y = (-2).dp),
                    )
                }
            }
            Text(
                text = item.label,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
                maxLines = 1,
                textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val BarHeight: Dp = 66.dp
private val Padding: Dp = 7.dp
private val IconBox: Dp = 24.dp
private val IconSize: Dp = 21.dp
