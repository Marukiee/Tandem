package nl.markmaaktmedia.tandem

import androidx.compose.runtime.saveable.SaverScope
import nl.markmaaktmedia.tandem.ui.NavSaver
import nl.markmaaktmedia.tandem.ui.Route
import nl.markmaaktmedia.tandem.ui.routeKey
import nl.markmaaktmedia.tandem.ui.screens.settings.SettingsCatalog
import nl.markmaaktmedia.tandem.ui.screens.settings.SettingsPageId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The catalog is data that has to agree with two XML files and with the pages, and nothing else checks that: a
 * missing keyword string is a build error, but a keyword string nobody uses, or one language that lacks an entry,
 * only shows up as a search that silently finds nothing.
 */
class SettingsCatalogTest {
    private fun names(path: String): Set<String> {
        val text = File(path).readText()
        return Regex("""name="(settings_kw_[a-z_]+)"""").findAll(text).map { it.groupValues[1] }.toSet()
    }

    private val english = "src/main/res/values/strings_settings.xml"
    private val dutch = "src/main/res/values-nl/strings_settings.xml"

    @Test fun everyEntryHasAUniqueId() {
        val ids = SettingsCatalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun everyRowAPageCanLightUpIsKnownByOneEntryOnly() {
        val keys = SettingsCatalog.all.mapNotNull { it.focus }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test fun everyEntryHasKeywordsInBothLanguagesAndNothingElseDoes() {
        val expected = SettingsCatalog.all.map { "settings_kw_${it.id}" }.toSet()
        assertEquals(expected, names(english))
        assertEquals(expected, names(dutch))
    }

    @Test fun theKeywordListsAreNotEmpty() {
        listOf(english, dutch).forEach { path ->
            Regex("""name="settings_kw_[a-z_]+">([^<]*)<""").findAll(File(path).readText()).forEach {
                assertTrue("${it.value} in $path", it.groupValues[1].isNotBlank())
            }
        }
    }

    @Test fun theStringFilesHoldNoDashesAndAreInSync() {
        listOf(english, dutch).forEach { path ->
            val text = File(path).readText()
            assertFalse(path, text.contains('—') || text.contains('–'))
        }
        fun all(path: String) = Regex("""<(?:string|plurals) name="([a-z_]+)"""").findAll(File(path).readText()).map { it.groupValues[1] }.toSet()
        // The one string that is not translated is a web address.
        assertEquals(all(english) - "settings_github", all(dutch))
    }

    @Test fun everyPageComesBackFromARotation() {
        val scope = SaverScope { true }
        val routes = listOf(Route.Home) + SettingsPageId.entries.map { Route.SettingsPage(it) } + Route.Appearance
        val saved = listOf("2") + routes.map(::routeKey)
        val restored = NavSaver.restore(saved)!!
        assertEquals(routes, restored.stack.toList())
        assertEquals(2, restored.tab)
        // And the stack goes through the saver in the same shape it comes out of it.
        assertEquals(saved, with(NavSaver) { scope.save(restored) })
    }

    @Test fun aPageKeyThatIsNotKnownFallsBackToHome() {
        val restored = NavSaver.restore(listOf("0", "settings:nowhere"))!!
        assertEquals(listOf<Route>(Route.Home), restored.stack.toList())
    }
}
