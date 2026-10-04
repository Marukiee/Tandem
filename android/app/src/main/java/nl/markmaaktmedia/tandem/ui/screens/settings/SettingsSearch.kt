package nl.markmaaktmedia.tandem.ui.screens.settings

import java.text.Normalizer

/**
 * One thing a person can look for. Everything is plain text in the language that is shown, because that is the
 * language the person types in: the catalog resolves the string resources, this file only compares.
 */
data class SearchEntry(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    /** Other words for the same thing. Searching for "dark mode" has to find a row called "Light or dark". */
    val keywords: List<String> = emptyList(),
    val category: String,
    /** The page inside the category, when the entry does not sit on the category's own page. */
    val page: String? = null,
)

/**
 * A match. [titleMatch] are the ranges of [SearchEntry.title] to emphasise, in indexes of the original title (the
 * folding below never changes a length, so they line up).
 */
data class SearchHit(val entry: SearchEntry, val score: Int, val titleMatch: List<IntRange>)

object SettingsSearch {

    /**
     * Lower case without accents, one character for one character. The length has to stay the same so a match found in
     * the folded text can be painted on the original. A letter that does not fall apart into a base letter and a mark
     * (like the German sharp s) is kept as it is.
     */
    fun fold(text: String): String {
        val out = StringBuilder(text.length)
        for (c in text) out.append(foldChar(c))
        return out.toString()
    }

    private fun foldChar(c: Char): Char {
        val lower = c.lowercaseChar()
        if (lower.code < 0x80) return lower
        val parts = Normalizer.normalize(lower.toString(), Normalizer.Form.NFD)
        return parts.firstOrNull { Character.getType(it) != Character.NON_SPACING_MARK.toInt() } ?: lower
    }

    /** The words of a query, folded, without doubles. A query of only spaces has none. */
    fun tokens(query: String): List<String> =
        fold(query).split(Regex("\\s+")).filter { it.isNotEmpty() }.distinct()

    /** Keywords live in one string resource, separated by commas, so a translator edits one line per entry. */
    fun parseKeywords(raw: String): List<String> =
        raw.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * The entries that match every word of the query, best first.
     *
     * A word counts where it starts a word of the title (strongest), sits somewhere inside the title, equals or starts
     * a keyword, or turns up in the category, the page or the description (weakest). Entries are sorted in three
     * steps: first by how much of the query the title covers (all of it, some of it, none), then by score, then by
     * their place in [entries]. The first step is the rule that a title match always beats a match elsewhere, however
     * many keywords the other one has.
     */
    fun search(entries: List<SearchEntry>, query: String): List<SearchHit> {
        val words = tokens(query)
        if (words.isEmpty()) return emptyList()
        val whole = words.joinToString(" ")

        class Ranked(val hit: SearchHit, val tier: Int, val order: Int)

        val ranked = ArrayList<Ranked>()
        entries.forEachIndexed { order, entry ->
            val title = fold(entry.title)
            val keywords = entry.keywords.map(::fold)
            val where = fold(listOfNotNull(entry.category, entry.page).joinToString(" "))
            val about = entry.subtitle?.let(::fold).orEmpty()

            var score = 0
            var inTitle = 0
            val ranges = ArrayList<IntRange>()
            for (word in words) {
                val inTitleAt = title.wordIndex(word)
                val strongest = maxOf(
                    if (inTitleAt >= 0) (if (title.startsWordAt(inTitleAt)) TitleWord else TitleInside) else 0,
                    keywords.maxOfOrNull { it.keywordScore(word) } ?: 0,
                    where.textScore(word, WhereWord, WhereInside),
                    about.textScore(word, AboutWord, AboutInside),
                )
                if (strongest == 0) return@forEachIndexed
                if (inTitleAt >= 0) {
                    inTitle++
                    ranges += inTitleAt until inTitleAt + word.length
                }
                score += strongest
            }
            // A title that holds the whole query is worth more than its words added up, so "dark mode" finds a row
            // with both words in its name before a row that only has one of them.
            if (inTitle == words.size) score += AllInTitle
            if (title == whole) score += ExactTitle else if (title.startsWith(whole)) score += TitleStartsWith

            val tier = when (inTitle) {
                words.size -> 0
                0 -> 2
                else -> 1
            }
            ranked += Ranked(SearchHit(entry, score, merge(ranges)), tier, order)
        }
        return ranked.sortedWith(compareBy<Ranked> { it.tier }.thenByDescending { it.hit.score }.thenBy { it.order }).map { it.hit }
    }

    /** Where the word first starts a word of the text, else where it first occurs, else -1. */
    private fun String.wordIndex(word: String): Int {
        var from = 0
        var inside = -1
        while (true) {
            val at = indexOf(word, from)
            if (at < 0) return inside
            if (startsWordAt(at)) return at
            if (inside < 0) inside = at
            from = at + 1
        }
    }

    private fun String.startsWordAt(index: Int) = index == 0 || !this[index - 1].isLetterOrDigit()

    private fun String.keywordScore(word: String): Int = when {
        this == word -> KeywordExact
        else -> textScore(word, KeywordWord, KeywordInside)
    }

    private fun String.textScore(word: String, atWordStart: Int, inside: Int): Int {
        val at = wordIndex(word)
        return when {
            at < 0 -> 0
            startsWordAt(at) -> atWordStart
            else -> inside
        }
    }

    private fun merge(ranges: List<IntRange>): List<IntRange> {
        if (ranges.size < 2) return ranges
        val sorted = ranges.sortedBy { it.first }
        val out = ArrayList<IntRange>()
        var current = sorted.first()
        for (next in sorted.drop(1)) {
            current = if (next.first <= current.last + 1) current.first..maxOf(current.last, next.last) else {
                out += current
                next
            }
        }
        out += current
        return out
    }

    private const val TitleWord = 100
    private const val TitleInside = 55
    private const val KeywordExact = 70
    private const val KeywordWord = 45
    private const val KeywordInside = 25
    private const val WhereWord = 15
    private const val WhereInside = 8
    private const val AboutWord = 12
    private const val AboutInside = 6
    private const val AllInTitle = 40
    private const val ExactTitle = 60
    private const val TitleStartsWith = 30
}
