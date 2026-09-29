package nl.markmaaktmedia.tandem.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** How the app looks. All of it is a setting, none of it is baked in. */
data class Appearance(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val seed: ColourSeed = ColourSeed.WALLPAPER,
    val style: PaletteStyle = PaletteStyle.TONAL_SPOT,
    val pureBlack: Boolean = true,
)

/** Extra colours Material does not have a slot for. */
data class TandemExtraColors(
    val urgent: Color,
    val urgentContainer: Color,
    val onUrgentContainer: Color,
    val online: Color,
    /** True when the pure black surface is in use, so a component can skip its tint. */
    val isPureBlack: Boolean,
)

val LocalTandemExtraColors = staticCompositionLocalOf {
    TandemExtraColors(
        urgent = TandemPalette.Urgent,
        urgentContainer = TandemPalette.UrgentContainerLight,
        onUrgentContainer = TandemPalette.Neutral10,
        online = Color(0xFF3DDC97),
        isPureBlack = false,
    )
}

@Composable
fun TandemTheme(
    appearance: Appearance = Appearance(),
    applySystemBarStyle: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = when (appearance.mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current

    // The scheme is generated here from a seed colour instead of taken from
    // dynamicLightColorScheme, which only ever produces Tonal Spot. Going through the
    // generator is what makes the palette style a real setting.
    val seed: Color = when {
        appearance.seed != ColourSeed.WALLPAPER -> appearance.seed.seed
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> Color(context.getColor(android.R.color.system_accent1_500))
        else -> TandemPalette.Indigo50
    }
    val generated = buildColorScheme(seed = seed, style = appearance.style, dark = dark)
    val usePureBlack = dark && appearance.pureBlack
    val target = if (usePureBlack) generated.toPureBlack() else generated
    // Every role animates, so switching theme or palette crossfades the whole app.
    val scheme = target.animated()

    val extras = TandemExtraColors(
        urgent = TandemPalette.Urgent,
        urgentContainer = if (dark) TandemPalette.UrgentContainerDark else TandemPalette.UrgentContainerLight,
        onUrgentContainer = if (dark) TandemPalette.Neutral95 else TandemPalette.Neutral10,
        online = Color(0xFF3DDC97),
        isPureBlack = usePureBlack,
    )

    if (applySystemBarStyle) {
        val view = LocalView.current
        if (!view.isInEditMode) {
            SideEffect {
                val window = (view.context as? Activity)?.window ?: return@SideEffect
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
    }

    CompositionLocalProvider(LocalTandemExtraColors provides extras) {
        MaterialTheme(
            colorScheme = scheme,
            typography = TandemTypography,
            shapes = TandemShapes,
            content = content,
        )
    }
}

/** Pure black for the backdrop, with a short ladder of tinted greys for the containers. */
private fun ColorScheme.toPureBlack(): ColorScheme {
    fun tinted(alpha: Float) = surfaceContainerHighest.copy(alpha = alpha).compositeOverBlack()
    return copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceDim = Color.Black,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = tinted(0.05f),
        surfaceContainer = tinted(0.09f),
        surfaceContainerHigh = tinted(0.14f),
        surfaceContainerHighest = tinted(0.19f),
        surfaceVariant = tinted(0.12f),
        outlineVariant = tinted(0.26f),
    )
}

private fun Color.compositeOverBlack(): Color =
    Color(red = red * alpha, green = green * alpha, blue = blue * alpha, alpha = 1f)

@Composable
private fun ColorScheme.animated(): ColorScheme {
    val spec = TandemMotion.colourSpec<Color>()

    @Composable
    fun animate(target: Color, label: String) =
        animateColorAsState(targetValue = target, animationSpec = spec, label = label).value

    return copy(
        primary = animate(primary, "primary"),
        onPrimary = animate(onPrimary, "onPrimary"),
        primaryContainer = animate(primaryContainer, "primaryContainer"),
        onPrimaryContainer = animate(onPrimaryContainer, "onPrimaryContainer"),
        secondary = animate(secondary, "secondary"),
        onSecondary = animate(onSecondary, "onSecondary"),
        secondaryContainer = animate(secondaryContainer, "secondaryContainer"),
        onSecondaryContainer = animate(onSecondaryContainer, "onSecondaryContainer"),
        tertiary = animate(tertiary, "tertiary"),
        onTertiary = animate(onTertiary, "onTertiary"),
        tertiaryContainer = animate(tertiaryContainer, "tertiaryContainer"),
        onTertiaryContainer = animate(onTertiaryContainer, "onTertiaryContainer"),
        background = animate(background, "background"),
        onBackground = animate(onBackground, "onBackground"),
        surface = animate(surface, "surface"),
        onSurface = animate(onSurface, "onSurface"),
        surfaceVariant = animate(surfaceVariant, "surfaceVariant"),
        onSurfaceVariant = animate(onSurfaceVariant, "onSurfaceVariant"),
        surfaceContainerLowest = animate(surfaceContainerLowest, "containerLowest"),
        surfaceContainerLow = animate(surfaceContainerLow, "containerLow"),
        surfaceContainer = animate(surfaceContainer, "container"),
        surfaceContainerHigh = animate(surfaceContainerHigh, "containerHigh"),
        surfaceContainerHighest = animate(surfaceContainerHighest, "containerHighest"),
        outline = animate(outline, "outline"),
        outlineVariant = animate(outlineVariant, "outlineVariant"),
        inverseSurface = animate(inverseSurface, "inverseSurface"),
        inverseOnSurface = animate(inverseOnSurface, "inverseOnSurface"),
        inversePrimary = animate(inversePrimary, "inversePrimary"),
        error = animate(error, "error"),
        onError = animate(onError, "onError"),
        errorContainer = animate(errorContainer, "errorContainer"),
        onErrorContainer = animate(onErrorContainer, "onErrorContainer"),
    )
}
