package nl.markmaaktmedia.tandem

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import nl.markmaaktmedia.tandem.ui.theme.PaletteStyle
import nl.markmaaktmedia.tandem.ui.theme.appearanceScheme
import nl.markmaaktmedia.tandem.ui.theme.blendSchemes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The theme crossfade only covers the roles [blendSchemes] lists, and a role missing
 * from that list snaps on the first frame of a switch. Reading the roles off the real
 * class means a role added by a Material update fails here instead of flashing.
 */
class SchemeBlendTest {

    private val light = appearanceScheme(Color(0xFF5B5BD6), PaletteStyle.TONAL_SPOT, dark = false, pureBlack = false)
    private val dark = appearanceScheme(Color(0xFFB4761A), PaletteStyle.EXPRESSIVE, dark = true, pureBlack = true)

    /** Every Color property of ColorScheme. Color is an inline class, so its getters return long. */
    private fun roles(): List<java.lang.reflect.Method> =
        ColorScheme::class.java.declaredMethods.filter {
            it.name.startsWith("get") && it.name.contains('-') &&
                it.parameterCount == 0 && it.returnType == java.lang.Long.TYPE
        }.sortedBy { it.name }

    @Test
    fun everyRoleIsFoundByReflection() {
        assertTrue("expected the full Material 3 role set, found ${roles().size}", roles().size >= 35)
    }

    @Test
    fun everyRoleMovesWhenBlended() {
        val halfway = blendSchemes(light, dark, 0.5f)
        val stuck = roles().filter { role ->
            val before = role.invoke(light) as Long
            val after = role.invoke(dark) as Long
            val mid = role.invoke(halfway) as Long
            before != after && mid == before
        }.map { it.name }
        assertTrue("roles not blended: $stuck", stuck.isEmpty())
    }

    @Test
    fun endsAreExact() {
        val start = blendSchemes(light, dark, 0f)
        val end = blendSchemes(light, dark, 1f)
        roles().forEach { role ->
            assertEquals(role.name, role.invoke(light), role.invoke(start))
            assertEquals(role.name, role.invoke(dark), role.invoke(end))
        }
    }

    @Test
    fun pureBlackIsOffByDefault() {
        assertEquals(false, nl.markmaaktmedia.tandem.ui.theme.Appearance().pureBlack)
    }

    @Test
    fun pureBlackOnlyAppliesInDark() {
        val seed = Color(0xFF5B5BD6)
        val lightWith = appearanceScheme(seed, PaletteStyle.TONAL_SPOT, dark = false, pureBlack = true)
        val lightWithout = appearanceScheme(seed, PaletteStyle.TONAL_SPOT, dark = false, pureBlack = false)
        assertEquals(lightWithout.background, lightWith.background)
        val darkWith = appearanceScheme(seed, PaletteStyle.TONAL_SPOT, dark = true, pureBlack = true)
        val darkWithout = appearanceScheme(seed, PaletteStyle.TONAL_SPOT, dark = true, pureBlack = false)
        assertEquals(Color.Black, darkWith.background)
        assertNotEquals(Color.Black, darkWithout.background)
    }
}
