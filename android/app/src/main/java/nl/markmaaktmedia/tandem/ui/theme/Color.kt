package nl.markmaaktmedia.tandem.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The palette used when the user turns wallpaper colours off, or on a phone that
 * cannot provide them.
 *
 * Built around one idea: a cool indigo that reads as "thinking", with a warm rose as
 * the only other voice in the room. Two hues and nothing else, so the accent means
 * something when it does show up. The tilted capsule in the launcher icon is drawn in
 * the same indigo, which is what ties the icon to the app it opens.
 */
object TandemPalette {
    val Indigo10 = Color(0xFF0B0A2B)
    val Indigo20 = Color(0xFF1B1A4A)
    val Indigo30 = Color(0xFF2E2C6B)
    val Indigo40 = Color(0xFF454391)
    val Indigo50 = Color(0xFF5B5BD6)
    val Indigo70 = Color(0xFF9E9DF0)
    val Indigo80 = Color(0xFFBEBDF8)
    val Indigo90 = Color(0xFFE2E1FF)
    val Indigo95 = Color(0xFFF1F0FF)

    val Rose10 = Color(0xFF3A0720)
    val Rose20 = Color(0xFF561433)
    val Rose30 = Color(0xFF742C4B)
    val Rose40 = Color(0xFF934464)
    val Rose80 = Color(0xFFFFB0CB)
    val Rose90 = Color(0xFFFFD9E4)

    val Neutral0 = Color(0xFF000000)
    val Neutral6 = Color(0xFF0E0E13)
    val Neutral10 = Color(0xFF15141B)
    val Neutral12 = Color(0xFF1A1922)
    val Neutral17 = Color(0xFF232230)
    val Neutral22 = Color(0xFF2C2B3A)
    val Neutral24 = Color(0xFF302F3F)
    val Neutral80 = Color(0xFFC8C5D5)
    val Neutral90 = Color(0xFFE5E1F0)
    val Neutral95 = Color(0xFFF3F0FB)
    val Neutral98 = Color(0xFFFCFAFF)
    val Neutral100 = Color(0xFFFFFFFF)

    val Error40 = Color(0xFFBA1A1A)
    val Error80 = Color(0xFFFFB4AB)
    val Error90 = Color(0xFFFFDAD6)
    val Error10 = Color(0xFF410002)

    /** Reserved for the urgent badge, so nothing else is allowed to be this loud. */
    val Urgent = Color(0xFFE8613C)
    val UrgentContainerLight = Color(0xFFFFE3DA)
    val UrgentContainerDark = Color(0xFF5A2113)
}

val TandemLightColors = lightColorScheme(
    primary = TandemPalette.Indigo50,
    onPrimary = TandemPalette.Neutral100,
    primaryContainer = TandemPalette.Indigo90,
    onPrimaryContainer = TandemPalette.Indigo20,
    secondary = TandemPalette.Indigo40,
    onSecondary = TandemPalette.Neutral100,
    secondaryContainer = TandemPalette.Indigo95,
    onSecondaryContainer = TandemPalette.Indigo30,
    tertiary = TandemPalette.Rose40,
    onTertiary = TandemPalette.Neutral100,
    tertiaryContainer = TandemPalette.Rose90,
    onTertiaryContainer = TandemPalette.Rose20,
    background = TandemPalette.Neutral98,
    onBackground = TandemPalette.Neutral10,
    surface = TandemPalette.Neutral98,
    onSurface = TandemPalette.Neutral10,
    surfaceVariant = TandemPalette.Neutral95,
    onSurfaceVariant = Color(0xFF49475A),
    surfaceContainerLowest = TandemPalette.Neutral100,
    surfaceContainerLow = Color(0xFFFAF7FF),
    surfaceContainer = Color(0xFFF4F1FC),
    surfaceContainerHigh = Color(0xFFEEEBF7),
    surfaceContainerHighest = Color(0xFFE8E5F2),
    outline = Color(0xFF7B7890),
    outlineVariant = Color(0xFFCBC7DA),
    error = TandemPalette.Error40,
    onError = TandemPalette.Neutral100,
    errorContainer = TandemPalette.Error90,
    onErrorContainer = TandemPalette.Error10,
    inverseSurface = TandemPalette.Neutral17,
    inverseOnSurface = TandemPalette.Neutral95,
    inversePrimary = TandemPalette.Indigo80,
)

val TandemDarkColors = darkColorScheme(
    primary = TandemPalette.Indigo80,
    onPrimary = TandemPalette.Indigo20,
    primaryContainer = TandemPalette.Indigo30,
    onPrimaryContainer = TandemPalette.Indigo90,
    secondary = TandemPalette.Indigo70,
    onSecondary = TandemPalette.Indigo20,
    secondaryContainer = TandemPalette.Indigo30,
    onSecondaryContainer = TandemPalette.Indigo90,
    tertiary = TandemPalette.Rose80,
    onTertiary = TandemPalette.Rose20,
    tertiaryContainer = TandemPalette.Rose30,
    onTertiaryContainer = TandemPalette.Rose90,
    background = TandemPalette.Neutral10,
    onBackground = TandemPalette.Neutral90,
    surface = TandemPalette.Neutral10,
    onSurface = TandemPalette.Neutral90,
    surfaceVariant = TandemPalette.Neutral22,
    onSurfaceVariant = TandemPalette.Neutral80,
    surfaceContainerLowest = TandemPalette.Neutral6,
    surfaceContainerLow = TandemPalette.Neutral12,
    surfaceContainer = TandemPalette.Neutral17,
    surfaceContainerHigh = TandemPalette.Neutral22,
    surfaceContainerHighest = TandemPalette.Neutral24,
    outline = Color(0xFF908DA3),
    outlineVariant = Color(0xFF49475A),
    error = TandemPalette.Error80,
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = TandemPalette.Error90,
    inverseSurface = TandemPalette.Neutral90,
    inverseOnSurface = TandemPalette.Neutral17,
    inversePrimary = TandemPalette.Indigo50,
)
