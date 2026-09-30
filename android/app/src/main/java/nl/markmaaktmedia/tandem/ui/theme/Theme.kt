package nl.markmaaktmedia.tandem.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.material3.ColorScheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** How the app looks. All of it is a setting, none of it is baked in. */
data class Appearance(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val seed: ColourSeed = ColourSeed.WALLPAPER,
    val style: PaletteStyle = PaletteStyle.TONAL_SPOT,
    val pureBlack: Boolean = false,
)

/**
 * The appearance the theme was given, for the one screen that edits it. A plain
 * compositionLocalOf, not a static one: only readers recompose when it changes, where
 * a static local would rebuild the whole app on every palette tap.
 */
val LocalAppearance = compositionLocalOf { Appearance() }

/** Whether this appearance is dark right now, which for System depends on the phone. */
@Composable
fun Appearance.isDark(): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/**
 * The saved appearance as state.
 *
 * The first value is read before the first frame. Starting from `Appearance()` and
 * letting the saved value arrive a moment later meant every launch opened in the
 * default look and then faded into the real one, which is the flash this avoids. One
 * small DataStore read on the main thread is the price, and it is paid once.
 */
@Composable
fun Flow<Appearance>.collectAppearance(): Appearance {
    val initial = remember(this) { runBlocking { first() } }
    return collectAsState(initial = initial).value
}

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

private fun blendExtras(from: TandemExtraColors, to: TandemExtraColors, fraction: Float) = TandemExtraColors(
    urgent = lerp(from.urgent, to.urgent, fraction),
    urgentContainer = lerp(from.urgentContainer, to.urgentContainer, fraction),
    onUrgentContainer = lerp(from.onUrgentContainer, to.onUrgentContainer, fraction),
    online = lerp(from.online, to.online, fraction),
    isPureBlack = if (fraction < 0.5f) from.isPureBlack else to.isPureBlack,
)

/** The scheme and the extras that go with it, so both are blended by the same progress. */
private class Look(val scheme: ColorScheme, val extras: TandemExtraColors)

private fun blendLooks(from: Look, to: Look, fraction: Float) = Look(
    scheme = blendSchemes(from.scheme, to.scheme, fraction),
    extras = blendExtras(from.extras, to.extras, fraction),
)

@Composable
fun TandemTheme(
    appearance: Appearance = Appearance(),
    applySystemBarStyle: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = appearance.isDark()
    val context = LocalContext.current

    // The scheme is generated from a seed colour instead of taken from
    // dynamicLightColorScheme, which only ever produces Tonal Spot. Going through the
    // generator is what makes the palette style a real setting.
    val seed = appearance.seed.resolve(context)
    val usePureBlack = dark && appearance.pureBlack

    val target = remember(seed, appearance.style, dark, usePureBlack) {
        Look(
            scheme = appearanceScheme(seed, appearance.style, dark, usePureBlack),
            extras = TandemExtraColors(
                urgent = TandemPalette.Urgent,
                urgentContainer = if (dark) TandemPalette.UrgentContainerDark else TandemPalette.UrgentContainerLight,
                onUrgentContainer = if (dark) TandemPalette.Neutral95 else TandemPalette.Neutral10,
                // The bright green vanishes on a light surface, so light theme gets a deeper one.
                online = if (dark) Color(0xFF3DDC97) else Color(0xFF0B7A4B),
                isPureBlack = usePureBlack,
            ),
        )
    }
    // One progress value carries every role, so a switch is a single soft transition.
    val look = rememberBlended(target, ::blendLooks)
    val scheme = look.scheme

    if (applySystemBarStyle) {
        val view = LocalView.current
        if (!view.isInEditMode) {
            // Follows the animated background, so the icons flip half way through the
            // fade instead of a beat before the surface has started to change.
            val lightBackground = scheme.background.luminance() > 0.5f
            SideEffect {
                val window = (view.context as? Activity)?.window ?: return@SideEffect
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = lightBackground
                    isAppearanceLightNavigationBars = lightBackground
                }
            }
        }
    }

    CompositionLocalProvider(
        LocalTandemExtraColors provides look.extras,
        LocalAppearance provides appearance,
        // Text and icons with no colour of their own read this, and its default is
        // black. Anything not inside a Surface used to keep that black on a dark
        // background, so the theme sets it once at the root.
        LocalContentColor provides scheme.onBackground,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = TandemTypography,
            shapes = TandemShapes,
            content = content,
        )
    }
}
