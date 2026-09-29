package nl.markmaaktmedia.tandem.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.components.ContentRow
import nl.markmaaktmedia.tandem.ui.components.SectionHeader
import nl.markmaaktmedia.tandem.ui.components.SegmentedPillRow
import nl.markmaaktmedia.tandem.ui.components.SettingsGroup
import nl.markmaaktmedia.tandem.ui.components.SwitchRow
import nl.markmaaktmedia.tandem.ui.components.TandemIconButton
import nl.markmaaktmedia.tandem.ui.components.bouncyClickable
import nl.markmaaktmedia.tandem.ui.theme.Appearance
import nl.markmaaktmedia.tandem.ui.theme.ColourSeed
import nl.markmaaktmedia.tandem.ui.theme.PaletteStyle
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import nl.markmaaktmedia.tandem.ui.theme.TandemMotion
import nl.markmaaktmedia.tandem.ui.theme.ThemeMode

@Composable
fun AppearanceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.graph.prefs
    val scope = rememberCoroutineScope()
    val appearance by prefs.appearance.collectAsState(initial = Appearance())

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TandemIconButton(TandemIcons.Back, stringResource(R.string.action_back), onBack)
        }
        Text(stringResource(R.string.settings_appearance), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))

        SectionHeader(stringResource(R.string.appearance_theme))
        SettingsGroup {
            ContentRow(0, 2, TandemIcons.DarkMode, stringResource(R.string.appearance_mode)) {
                SegmentedPillRow(
                    options = ThemeMode.entries,
                    selected = appearance.mode,
                    label = { context.getString(when (it) { ThemeMode.SYSTEM -> R.string.language_system; ThemeMode.LIGHT -> R.string.appearance_light; ThemeMode.DARK -> R.string.appearance_dark }) },
                    onSelect = { scope.launch { prefs.setThemeMode(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            SwitchRow(1, 2, TandemIcons.DarkMode, stringResource(R.string.appearance_black), stringResource(R.string.appearance_black_sub), appearance.pureBlack, { scope.launch { prefs.setPureBlack(it) } })
        }

        SectionHeader(stringResource(R.string.appearance_colour))
        SettingsGroup {
            SwitchRow(0, 3, TandemIcons.Palette, stringResource(R.string.appearance_wallpaper), stringResource(R.string.appearance_wallpaper_sub), appearance.seed == ColourSeed.WALLPAPER, {
                scope.launch { prefs.setSeed(if (it) ColourSeed.WALLPAPER else ColourSeed.INDIGO) }
            })
            ContentRow(1, 3, TandemIcons.Palette, stringResource(R.string.appearance_accent)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ColourSeed.swatches.forEach { seed ->
                        Swatch(seed, selected = appearance.seed == seed) { scope.launch { prefs.setSeed(seed) } }
                    }
                }
            }
            ContentRow(2, 3, TandemIcons.Palette, stringResource(R.string.appearance_style)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PaletteStyle.entries.forEach { style ->
                        StyleChip(style, appearance.style == style) { scope.launch { prefs.setStyle(style) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun Swatch(seed: ColourSeed, selected: Boolean, onClick: () -> Unit) {
    val scale by animateFloatAsState(if (selected) 1.08f else 1f, TandemMotion.bouncy(), label = "swatch")
    val ring by animateColorAsState(if (selected) MaterialTheme.colorScheme.onSurface else androidx.compose.ui.graphics.Color.Transparent, TandemMotion.colourSpec(), label = "swatchRing")
    Box(
        Modifier.size(46.dp).scale(scale).clip(CircleShape).background(seed.seed).border(3.dp, ring, CircleShape).bouncyClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(TandemIcons.Check, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun StyleChip(style: PaletteStyle, selected: Boolean, onClick: () -> Unit) {
    val container by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh, TandemMotion.colourSpec(), label = "chipContainer")
    val content by animateColorAsState(if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, TandemMotion.colourSpec(), label = "chipContent")
    Text(
        text = style.name.lowercase().split('_').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } },
        style = MaterialTheme.typography.labelLarge,
        color = content,
        modifier = Modifier.clip(CircleShape).background(container).bouncyClickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
    )
}
