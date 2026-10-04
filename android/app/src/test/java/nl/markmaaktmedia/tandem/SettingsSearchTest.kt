package nl.markmaaktmedia.tandem

import nl.markmaaktmedia.tandem.ui.screens.settings.SearchEntry
import nl.markmaaktmedia.tandem.ui.screens.settings.SettingsSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {
    private fun entry(
        id: String,
        title: String,
        subtitle: String? = null,
        keywords: String = "",
        category: String = "Category",
        page: String? = null,
    ) = SearchEntry(id, title, subtitle, SettingsSearch.parseKeywords(keywords), category, page)

    private val catalog = listOf(
        entry("mode", "Light or dark", keywords = "dark mode, night, theme, thema, donker", category = "Look and language", page = "Appearance"),
        entry("black", "Pure black", "Saves battery on an OLED screen", "amoled, oled, black, dark", "Look and language", "Appearance"),
        entry("language", "Language", keywords = "taal, english, dutch, nederlands", category = "Look and language"),
        entry("version", "Installed version", keywords = "build, release", category = "Updates and backup"),
        entry("auto", "Check for updates automatically", "Every time you open the app", "update, upgrade", "Updates and backup"),
        entry("check", "Check now", keywords = "update, refresh", category = "Updates and backup"),
        entry("hotspot", "Hotspot", "Let your Mac use this phone", "tethering, wifi, internet", "Hotspot"),
        entry("roaming", "Allow roaming", keywords = "abroad, data", category = "Hotspot"),
    )

    private fun ids(query: String, entries: List<SearchEntry> = catalog) = SettingsSearch.search(entries, query).map { it.entry.id }

    @Test fun anEmptyQueryFindsNothing() {
        assertEquals(emptyList<String>(), ids(""))
        assertEquals(emptyList<String>(), ids("   "))
    }

    @Test fun caseDoesNotMatter() {
        assertEquals(ids("hotspot"), ids("HOTSPOT"))
        assertEquals("hotspot", ids("HotSpot").first())
    }

    @Test fun accentsDoNotMatterInTheQueryOrInTheText() {
        val dutch = listOf(entry("version", "Geïnstalleerde versie"), entry("other", "Taal"))
        assertEquals(listOf("version"), ids("geinstalleerd", dutch))
        assertEquals(listOf("version"), ids("geïnstalleerd", dutch))
        assertEquals(listOf("version"), ids("GEÏNSTALLEERD", dutch))
    }

    @Test fun foldingKeepsEveryLengthSoAMatchCanBePaintedOnTheOriginal() {
        listOf("Geïnstalleerde versie", "Ångström", "naïef café", "Straße", "").forEach {
            assertEquals(it.length, SettingsSearch.fold(it).length)
        }
        assertEquals("geinstalleerde", SettingsSearch.fold("Geïnstalleerde"))
    }

    @Test fun aTitleComesFirstThenAKeywordThenTheCategory() {
        // "update" is in the title of one row, a keyword of another and only the category of the third.
        assertEquals(listOf("auto", "check", "version"), ids("update"))
    }

    @Test fun aTitleMatchBeatsAKeywordMatchWhateverTheKeywordsAdd() {
        val entries = listOf(
            entry("many", "Something else", keywords = "dark, dark mode, darkness, darkroom, dark theme"),
            entry("title", "Dark side"),
        )
        assertEquals(listOf("title", "many"), ids("dark", entries))
    }

    @Test fun theStartOfAWordBeatsTheMiddleOfOne() {
        val entries = listOf(entry("inside", "Backup"), entry("start", "Up to date"))
        assertEquals(listOf("start", "inside"), ids("up", entries))
    }

    @Test fun anExactTitleBeatsALongerOne() {
        val entries = listOf(entry("long", "Hotspot data limit"), entry("exact", "Hotspot"))
        assertEquals(listOf("exact", "long"), ids("hotspot", entries))
    }

    @Test fun severalWordsMustAllMatchInAnyOrder() {
        assertEquals(listOf("mode"), ids("dark light"))
        assertEquals(listOf("mode"), ids("light dark"))
        assertEquals(emptyList<String>(), ids("dark hotspot"))
    }

    @Test fun twoWordsFindAKeywordWithTwoWords() {
        assertEquals("mode", ids("dark mode").first())
    }

    @Test fun extraSpacesAndDoubledWordsChangeNothing() {
        assertEquals(ids("dark mode"), ids("  dark   mode "))
        assertEquals(ids("dark"), ids("dark dark"))
    }

    @Test fun aWordTypedHalfWayAlreadyFinds() {
        assertEquals("hotspot", ids("hot").first())
        assertEquals("language", ids("lang").first())
    }

    @Test fun synonymsInBothLanguagesFindTheRow() {
        assertEquals("language", ids("taal").first())
        assertEquals("language", ids("nederlands").first())
        assertEquals("mode", ids("donker").first())
        assertEquals("hotspot", ids("tethering").first())
    }

    @Test fun theCategoryAndThePageFindTheirEntriesBelowTheTitleMatches() {
        val found = ids("appearance")
        assertEquals(listOf("mode", "black"), found)
        val byCategory = ids("hotspot")
        assertEquals("hotspot", byCategory.first())
        assertTrue("roaming" in byCategory)
    }

    @Test fun theDescriptionIsSearchedButCountsLeast() {
        val found = ids("oled")
        assertEquals(listOf("black"), found)
        val entries = listOf(entry("about", "Pure black", "Saves battery on a screen"), entry("title", "Battery"))
        assertEquals(listOf("title", "about"), ids("battery", entries))
    }

    @Test fun nothingMatchingGivesAnEmptyList() {
        assertEquals(emptyList<String>(), ids("zebra"))
    }

    @Test fun equalScoresKeepTheOrderOfTheCatalog() {
        val entries = listOf(entry("b", "Tile"), entry("a", "Tile"), entry("c", "Tile"))
        assertEquals(listOf("b", "a", "c"), ids("tile", entries))
    }

    @Test fun theTitleMatchIsReportedInIndexesOfTheOriginalTitle() {
        val hit = SettingsSearch.search(listOf(entry("v", "Geïnstalleerde versie")), "geinst").single()
        assertEquals(listOf(0..5), hit.titleMatch)
        val two = SettingsSearch.search(listOf(entry("v", "Geïnstalleerde versie")), "versie geinst").single()
        assertEquals(listOf(0..5, 15..20), two.titleMatch)
    }

    @Test fun matchesThatTouchAreOneRange() {
        val hit = SettingsSearch.search(listOf(entry("v", "Dark mode")), "dar ark").single()
        assertEquals(listOf(0..3), hit.titleMatch)
    }

    @Test fun aMatchOnlyInAKeywordPaintsNothingInTheTitle() {
        val hit = SettingsSearch.search(catalog, "night").single()
        assertEquals("mode", hit.entry.id)
        assertEquals(emptyList<IntRange>(), hit.titleMatch)
    }

    @Test fun keywordsAreSplitOnCommasAndTrimmed() {
        assertEquals(listOf("dark mode", "night", "thema"), SettingsSearch.parseKeywords(" dark mode , night,, thema ;"))
        assertEquals(emptyList<String>(), SettingsSearch.parseKeywords(""))
    }

    @Test fun aTitleCoveringTheWholeQueryBeatsOneCoveringPartOfIt() {
        val entries = listOf(
            entry("part", "Dark", keywords = "light mode"),
            entry("all", "Light or dark mode"),
        )
        assertEquals(listOf("all", "part"), ids("dark mode", entries))
    }
}
