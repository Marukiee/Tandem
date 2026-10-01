package nl.markmaaktmedia.tandem.update

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One release as it reads in the What's new list. Written by hand in changelog.json at the repo root. */
data class ChangeEntry(
    val version: String,
    val date: String,
    val title: String,
    val new: List<String>,
    val better: List<String>,
    val fixed: List<String>,
    val highlight: Boolean,
)

object Changelog {
    /** Newest first, in [language] ("nl" or anything else for English). Empty if the file is missing. */
    fun load(context: Context, language: String): List<ChangeEntry> = runCatching {
        val raw = context.assets.open("changelog.json").bufferedReader().use { it.readText() }
        val array = JSONArray(raw)
        (0 until array.length()).map { array.getJSONObject(it) }.map { entry ->
            val text = entry.optJSONObject(if (language == "nl") "nl" else "en") ?: entry.getJSONObject("en")
            ChangeEntry(
                version = entry.getString("version"),
                date = entry.optString("date"),
                title = text.optString("title"),
                new = text.strings("new"),
                better = text.strings("better"),
                fixed = text.strings("fixed"),
                highlight = entry.optBoolean("highlight"),
            )
        }
    }.getOrDefault(emptyList())

    private fun JSONObject.strings(key: String): List<String> =
        optJSONArray(key)?.let { array -> (0 until array.length()).map { array.getString(it) } } ?: emptyList()
}
