package nl.markmaaktmedia.tandem.ui.screens

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.graph
import nl.markmaaktmedia.tandem.ui.appearance.LiveScreenPreview
import nl.markmaaktmedia.tandem.ui.appearance.SeedPreviewTile
import nl.markmaaktmedia.tandem.ui.appearance.StylePreviewCard
import nl.markmaaktmedia.tandem.ui.components.ContentRow
import nl.markmaaktmedia.tandem.ui.components.SectionHeader
import nl.markmaaktmedia.tandem.ui.components.SegmentedPillRow
import nl.markmaaktmedia.tandem.ui.components.SettingsGroup
import nl.markmaaktmedia.tandem.ui.components.SwitchRow
import nl.markmaaktmedia.tandem.ui.components.TandemIconButton
import nl.markmaaktmedia.tandem.ui.screens.settings.FocusKeys
import nl.markmaaktmedia.tandem.ui.screens.settings.SettingsTarget
import nl.markmaaktmedia.tandem.ui.theme.ColourSeed
import nl.markmaaktmedia.tandem.ui.theme.LocalAppearance
import nl.markmaaktmedia.tandem.ui.theme.PaletteStyle
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons
import nl.markmaaktmedia.tandem.ui.theme.TandemMotion
import nl.markmaaktmedia.tandem.ui.theme.ThemeMode
import nl.markmaaktmedia.tandem.ui.theme.isDark
import nl.markmaaktmedia.tandem.ui.theme.resolve

@Composable
fun AppearanceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.graph.prefs
    val scope = rememberCoroutineScope()
    // Read from the theme rather than collected again: what the theme is wearing is
    // what the previews should be compared against.
    val appearance = LocalAppearance.current
    val dark = appearance.isDark()

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TandemIconButton(TandemIcons.Back, stringResource(R.string.action_back), onBack)
        }
        Text(stringResource(R.string.settings_appearance), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))

        Spacer(Modifier.height(6.dp))
        LiveScreenPreview()

        SectionHeader(stringResource(R.string.appearance_theme))
        SettingsGroup {
            SettingsTarget(FocusKeys.AppearanceMode, 0, 2) {
                ContentRow(0, 2, TandemIcons.DarkMode, stringResource(R.string.appearance_mode)) {
                    SegmentedPillRow(
                        options = ThemeMode.entries,
                        selected = appearance.mode,
                        label = { context.getString(when (it) { ThemeMode.SYSTEM -> R.string.language_system; ThemeMode.LIGHT -> R.string.appearance_light; ThemeMode.DARK -> R.string.appearance_dark }) },
                        onSelect = { scope.launch { prefs.setThemeMode(it) } },
                        modifier = Modifier.fillMaxWidth(),
                        equalWidth = true,
                    )
                }
            }
            SettingsTarget(FocusKeys.AppearanceBlack, 1, 2) {
                SwitchRow(1, 2, TandemIcons.DarkMode, stringResource(R.string.appearance_black), stringResource(R.string.appearance_black_sub), appearance.pureBlack, { scope.launch { prefs.setPureBlack(it) } })
            }
        }

        SectionHeader(stringResource(R.string.appearance_colour))
        SettingsGroup {
            SettingsTarget(FocusKeys.AppearanceAccent, 0, 2) {
                ContentRow(0, 2, TandemIcons.Palette, stringResource(R.string.appearance_accent)) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Wallpaper is one of the tiles, not a switch above them: it is a
                        // choice between the same things, and a switch made the accent
                        // tiles look usable while they were ignored.
                        ColourSeed.entries.chunked(SeedColumns).forEach { rowSeeds ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                rowSeeds.forEach { seed ->
                                    SeedPreviewTile(
                                        seed = seed.resolve(context),
                                        style = appearance.style,
                                        dark = dark,
                                        pureBlack = appearance.pureBlack,
                                        selected = appearance.seed == seed,
                                        label = stringResource(seedLabel(seed)),
                                        onClick = { scope.launch { prefs.setSeed(seed) } },
                                        modifier = Modifier.weight(1f),
                                        overlay = { scheme ->
                                            if (seed == ColourSeed.WALLPAPER) {
                                                Box(
                                                    Modifier
                                                        .align(Alignment.TopStart)
                                                        .padding(6.dp)
                                                        .size(20.dp)
                                                        .clip(CircleShape)
                                                        .background(scheme.surface.copy(alpha = 0.9f)),
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    Icon(TandemIcons.Image, null, tint = scheme.onSurface, modifier = Modifier.size(12.dp))
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                        }
                        Note(visible = appearance.seed == ColourSeed.WALLPAPER, text = stringResource(R.string.colours_wallpaper_note))
                        Note(visible = appearance.style == PaletteStyle.MONOCHROME, text = stringResource(R.string.colours_mono_note))
                    }
                }
            }
            SettingsTarget(FocusKeys.AppearanceStyle, 1, 2) {
                ContentRow(1, 2, TandemIcons.Palette, stringResource(R.string.appearance_style)) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val seed = appearance.seed.resolve(context)
                        PaletteStyle.entries.chunked(StyleColumns).forEach { rowStyles ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                rowStyles.forEach { style ->
                                    StylePreviewCard(
                                        style = style,
                                        seed = seed,
                                        dark = dark,
                                        pureBlack = appearance.pureBlack,
                                        selected = appearance.style == style,
                                        label = stringResource(styleLabel(style)),
                                        onClick = { scope.launch { prefs.setStyle(style) } },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                        AnimatedContent(
                            targetState = appearance.style,
                            transitionSpec = { fadeIn(TandemMotion.fadeSpec()) togetherWith fadeOut(TandemMotion.fadeSpec()) },
                            label = "styleDescription",
                        ) { style ->
                            Text(
                                text = stringResource(styleDescription(style)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

/** A line of explanation that folds away when it stops being true. */
@Composable
private fun Note(visible: Boolean, text: String) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(TandemMotion.sizeSpring()) + fadeIn(TandemMotion.fadeSpec()),
        exit = shrinkVertically(TandemMotion.sizeSpring()) + fadeOut(TandemMotion.fadeSpec()),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

private const val SeedColumns = 4
private const val StyleColumns = 3

@StringRes
internal fun seedLabel(seed: ColourSeed): Int = when (seed) {
    ColourSeed.WALLPAPER -> R.string.colours_seed_wallpaper
    ColourSeed.INDIGO -> R.string.colours_seed_indigo
    ColourSeed.OCEAN -> R.string.colours_seed_ocean
    ColourSeed.FOREST -> R.string.colours_seed_forest
    ColourSeed.AMBER -> R.string.colours_seed_amber
    ColourSeed.ROSE -> R.string.colours_seed_rose
    ColourSeed.PLUM -> R.string.colours_seed_plum
    ColourSeed.SLATE -> R.string.colours_seed_slate
}

@StringRes
private fun styleLabel(style: PaletteStyle): Int = when (style) {
    PaletteStyle.TONAL_SPOT -> R.string.colours_style_tonal_spot
    PaletteStyle.VIBRANT -> R.string.colours_style_vibrant
    PaletteStyle.EXPRESSIVE -> R.string.colours_style_expressive
    PaletteStyle.FRUIT_SALAD -> R.string.colours_style_fruit_salad
    PaletteStyle.FIDELITY -> R.string.colours_style_fidelity
    PaletteStyle.MONOCHROME -> R.string.colours_style_monochrome
}

@StringRes
private fun styleDescription(style: PaletteStyle): Int = when (style) {
    PaletteStyle.TONAL_SPOT -> R.string.colours_style_tonal_spot_desc
    PaletteStyle.VIBRANT -> R.string.colours_style_vibrant_desc
    PaletteStyle.EXPRESSIVE -> R.string.colours_style_expressive_desc
    PaletteStyle.FRUIT_SALAD -> R.string.colours_style_fruit_salad_desc
    PaletteStyle.FIDELITY -> R.string.colours_style_fidelity_desc
    PaletteStyle.MONOCHROME -> R.string.colours_style_monochrome_desc
}
