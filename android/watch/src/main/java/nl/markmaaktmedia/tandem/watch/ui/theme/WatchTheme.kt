package nl.markmaaktmedia.tandem.watch.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Typography
import androidx.wear.compose.material3.dynamicColorScheme
import nl.markmaaktmedia.tandem.watch.R

/*
 * The values of app/.../ui/theme, translated to the Material 3 of Wear OS. The watch cannot share those files: they
 * are built on phone Material 3, which has other colour slots and another type scale. Keep the two in step by hand.
 */

private object TandemPalette {
    val Indigo20 = Color(0xFF1B1A4A)
    val Indigo30 = Color(0xFF2E2C6B)
    val Indigo40 = Color(0xFF454391)
    val Indigo70 = Color(0xFF9E9DF0)
    val Indigo80 = Color(0xFFBEBDF8)
    val Indigo90 = Color(0xFFE2E1FF)
    val Rose20 = Color(0xFF561433)
    val Rose30 = Color(0xFF742C4B)
    val Rose40 = Color(0xFF934464)
    val Rose80 = Color(0xFFFFB0CB)
    val Rose90 = Color(0xFFFFD9E4)
    val Neutral0 = Color(0xFF000000)
    val Neutral12 = Color(0xFF1A1922)
    val Neutral17 = Color(0xFF232230)
    val Neutral22 = Color(0xFF2C2B3A)
    val Neutral80 = Color(0xFFC8C5D5)
    val Neutral90 = Color(0xFFE5E1F0)
    val Error80 = Color(0xFFFFB4AB)
    val Error90 = Color(0xFFFFDAD6)
}

/** The online green of TandemTheme, for what means "this works". */
val TandemOnline = Color(0xFF3DDC97)

/** Tandem's dark palette on true black: on an OLED watch, black pixels are off and cost no battery. */
private val TandemWatchColors = ColorScheme(
    primary = TandemPalette.Indigo80,
    primaryDim = TandemPalette.Indigo70,
    primaryContainer = TandemPalette.Indigo30,
    onPrimary = TandemPalette.Indigo20,
    onPrimaryContainer = TandemPalette.Indigo90,
    secondary = TandemPalette.Indigo70,
    secondaryDim = TandemPalette.Indigo40,
    secondaryContainer = TandemPalette.Indigo30,
    onSecondary = TandemPalette.Indigo20,
    onSecondaryContainer = TandemPalette.Indigo90,
    tertiary = TandemPalette.Rose80,
    tertiaryDim = TandemPalette.Rose40,
    tertiaryContainer = TandemPalette.Rose30,
    onTertiary = TandemPalette.Rose20,
    onTertiaryContainer = TandemPalette.Rose90,
    surfaceContainerLow = TandemPalette.Neutral12,
    surfaceContainer = TandemPalette.Neutral17,
    surfaceContainerHigh = TandemPalette.Neutral22,
    onSurface = TandemPalette.Neutral90,
    onSurfaceVariant = TandemPalette.Neutral80,
    outline = Color(0xFF908DA3),
    outlineVariant = Color(0xFF49475A),
    background = TandemPalette.Neutral0,
    onBackground = TandemPalette.Neutral90,
    error = TandemPalette.Error80,
    errorDim = Color(0xFFBA1A1A),
    errorContainer = Color(0xFF93000A),
    onError = Color(0xFF690005),
    onErrorContainer = TandemPalette.Error90,
)

/** Google Sans Flex with the rounded axis all the way up, the same setting that makes the phone's type match its icons. */
@OptIn(ExperimentalTextApi::class)
private fun flex(weight: FontWeight) = Font(
    resId = R.font.gflex_variable,
    weight = weight,
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight.weight),
        FontVariation.Setting("ROND", 100f),
    ),
)

private val GoogleSansRounded = FontFamily(
    flex(FontWeight.Normal),
    flex(FontWeight.Medium),
    flex(FontWeight.SemiBold),
    flex(FontWeight.Bold),
)

object TandemMotion {
    private val Standard = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

    fun <T> fadeSpec(): FiniteAnimationSpec<T> = tween(durationMillis = 260, easing = Standard)

    /** For a shape that changes: a little past the mark and back, the character of Material You. */
    fun <T> shapeSpec(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow)
}

/** Follows the watch face where the watch offers that, as the phone follows the wallpaper; otherwise Tandem's own colours. */
@Composable
fun TandemWatchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = dynamicColorScheme(LocalContext.current) ?: TandemWatchColors,
        typography = Typography(defaultFontFamily = GoogleSansRounded),
        content = content,
    )
}
