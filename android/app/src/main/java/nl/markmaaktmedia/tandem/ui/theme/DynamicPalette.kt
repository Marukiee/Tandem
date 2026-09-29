package nl.markmaaktmedia.tandem.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.google.android.material.color.utilities.DynamicScheme
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.MaterialDynamicColors
import com.google.android.material.color.utilities.SchemeExpressive
import com.google.android.material.color.utilities.SchemeFidelity
import com.google.android.material.color.utilities.SchemeFruitSalad
import com.google.android.material.color.utilities.SchemeMonochrome
import com.google.android.material.color.utilities.SchemeTonalSpot
import com.google.android.material.color.utilities.SchemeVibrant

/**
 * How a seed colour is spread across the palette.
 *
 * These are the schemes the platform itself uses for Material You, exposed as a
 * choice rather than a fixed one. They are genuinely different characters and not
 * five names for the same thing: Tonal Spot is what Android ships and keeps the
 * accent close to the seed, Vibrant pushes chroma up, Expressive swings the
 * secondary and tertiary hues away from the seed, Fruit Salad shifts the whole
 * family, and Monochrome throws the hue away entirely.
 */
enum class PaletteStyle(val storageKey: String) {
    TONAL_SPOT("tonal_spot"),
    VIBRANT("vibrant"),
    EXPRESSIVE("expressive"),
    FRUIT_SALAD("fruit_salad"),
    FIDELITY("fidelity"),
    MONOCHROME("monochrome");

    companion object {
        val default = TONAL_SPOT

        fun fromKey(value: String?): PaletteStyle =
            entries.firstOrNull { it.storageKey == value } ?: default
    }
}

/**
 * Where the accent colour comes from.
 *
 * Wallpaper is the default and the reason Material You exists. The fixed seeds are
 * for a wallpaper that produces a colour you dislike, or for a phone where the
 * wallpaper changes constantly and the app changing with it is a nuisance.
 */
enum class ColourSeed(val storageKey: String, val seed: Color) {
    WALLPAPER("wallpaper", Color(0xFF5B5BD6)),
    INDIGO("indigo", Color(0xFF5B5BD6)),
    OCEAN("ocean", Color(0xFF1E7C93)),
    FOREST("forest", Color(0xFF3F7D3C)),
    AMBER("amber", Color(0xFFB4761A)),
    ROSE("rose", Color(0xFFB5386B)),
    PLUM("plum", Color(0xFF7A4CC0)),
    SLATE("slate", Color(0xFF5A6472));

    companion object {
        val default = WALLPAPER

        fun fromKey(value: String?): ColourSeed =
            entries.firstOrNull { it.storageKey == value } ?: default

        /** Everything except wallpaper, which is presented as its own switch. */
        val swatches: List<ColourSeed> = entries.filter { it != WALLPAPER }
    }
}

/**
 * Turns a seed colour into a full Material 3 scheme.
 *
 * The colour utilities hand back roles one at a time as ARGB integers, so this maps
 * all of them across in one place. Doing it by hand rather than taking the platform's
 * dynamic scheme is what makes the palette style a real setting: `dynamicColorScheme`
 * only ever gives you Tonal Spot.
 */
fun buildColorScheme(
    seed: Color,
    style: PaletteStyle,
    dark: Boolean,
    contrast: Double = 0.0,
): ColorScheme {
    val hct = Hct.fromInt(seed.toArgb())
    val scheme: DynamicScheme = when (style) {
        PaletteStyle.TONAL_SPOT -> SchemeTonalSpot(hct, dark, contrast)
        PaletteStyle.VIBRANT -> SchemeVibrant(hct, dark, contrast)
        PaletteStyle.EXPRESSIVE -> SchemeExpressive(hct, dark, contrast)
        PaletteStyle.FRUIT_SALAD -> SchemeFruitSalad(hct, dark, contrast)
        PaletteStyle.FIDELITY -> SchemeFidelity(hct, dark, contrast)
        PaletteStyle.MONOCHROME -> SchemeMonochrome(hct, dark, contrast)
    }
    return scheme.toColorScheme()
}

private fun DynamicScheme.toColorScheme(): ColorScheme {
    val roles = MaterialDynamicColors()
    fun role(get: MaterialDynamicColors.() -> com.google.android.material.color.utilities.DynamicColor) =
        Color(roles.get().getArgb(this))

    return ColorScheme(
        primary = role { primary() },
        onPrimary = role { onPrimary() },
        primaryContainer = role { primaryContainer() },
        onPrimaryContainer = role { onPrimaryContainer() },
        inversePrimary = role { inversePrimary() },
        secondary = role { secondary() },
        onSecondary = role { onSecondary() },
        secondaryContainer = role { secondaryContainer() },
        onSecondaryContainer = role { onSecondaryContainer() },
        tertiary = role { tertiary() },
        onTertiary = role { onTertiary() },
        tertiaryContainer = role { tertiaryContainer() },
        onTertiaryContainer = role { onTertiaryContainer() },
        background = role { background() },
        onBackground = role { onBackground() },
        surface = role { surface() },
        onSurface = role { onSurface() },
        surfaceVariant = role { surfaceVariant() },
        onSurfaceVariant = role { onSurfaceVariant() },
        surfaceTint = role { primary() },
        inverseSurface = role { inverseSurface() },
        inverseOnSurface = role { inverseOnSurface() },
        error = role { error() },
        onError = role { onError() },
        errorContainer = role { errorContainer() },
        onErrorContainer = role { onErrorContainer() },
        outline = role { outline() },
        outlineVariant = role { outlineVariant() },
        scrim = role { scrim() },
        surfaceBright = role { surfaceBright() },
        surfaceDim = role { surfaceDim() },
        surfaceContainer = role { surfaceContainer() },
        surfaceContainerHigh = role { surfaceContainerHigh() },
        surfaceContainerHighest = role { surfaceContainerHighest() },
        surfaceContainerLow = role { surfaceContainerLow() },
        surfaceContainerLowest = role { surfaceContainerLowest() },
    )
}
