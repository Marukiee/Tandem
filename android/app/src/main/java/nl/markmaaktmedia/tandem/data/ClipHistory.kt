package nl.markmaaktmedia.tandem.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** One thing that was on the clipboard: what it said, who it came from (empty for this phone) and when. */
data class ClipItem(val id: Long, val text: String, val from: String, val atMs: Long, val pinned: Boolean = false)

/**
 * The text that came to this phone's clipboard from other devices, and what this phone sent out. Android does not let an
 * app read the clipboard in the background, so this is not everything that was ever copied here: it is what passed through
 * Tandem. Kept in the app's own preferences, never sent anywhere, and the oldest go first once [LIMIT] is passed (a
 * pinned one stays).
 */
class ClipHistory(context: Context) {
    private val store = context.getSharedPreferences("clip_history", Context.MODE_PRIVATE)
    private val _items = MutableStateFlow(decode(store.getString(KEY, null)))
    val items: StateFlow<List<ClipItem>> = _items.asStateFlow()

    /** Puts [text] at the top. The same text again moves up instead of being listed twice, and keeps its pin. */
    fun record(text: String, from: String, now: Long = System.currentTimeMillis()) {
        if (text.isBlank()) return
        update { add(it, text, from, now) }
    }

    fun togglePin(id: Long) = update { list -> list.map { if (it.id == id) it.copy(pinned = !it.pinned) else it } }

    fun remove(id: Long) = update { list -> list.filterNot { it.id == id } }

    /** Everything goes, or everything but what is pinned. */
    fun clear(keepPinned: Boolean = true) = update { list -> if (keepPinned) list.filter { it.pinned } else emptyList() }

    @Synchronized
    private fun update(change: (List<ClipItem>) -> List<ClipItem>) {
        val next = change(_items.value)
        _items.value = next
        store.edit().putString(KEY, encode(next)).apply()
    }

    companion object {
        const val LIMIT = 100
        private const val KEY = "items"

        /** The list with [text] added at the top, in pure form so it can be tested without a phone. */
        fun add(list: List<ClipItem>, text: String, from: String, now: Long): List<ClipItem> {
            val earlier = list.firstOrNull { it.text == text }
            val item = ClipItem(
                id = maxOf(now, (list.maxOfOrNull { it.id } ?: 0L) + 1),
                text = text, from = from.ifEmpty { earlier?.from.orEmpty() }, atMs = now, pinned = earlier?.pinned ?: false,
            )
            val rest = list.filterNot { it.text == text }
            val unpinned = rest.filterNot { it.pinned }
            val dropped = unpinned.drop(LIMIT - 1 - rest.count { it.pinned }.coerceAtMost(LIMIT - 1)).toSet()
            return listOf(item) + rest.filterNot { it in dropped }
        }

        fun encode(list: List<ClipItem>): String = JSONArray().also { array ->
            list.forEach { array.put(JSONObject().put("id", it.id).put("text", it.text).put("from", it.from).put("at", it.atMs).put("pinned", it.pinned)) }
        }.toString()

        fun decode(text: String?): List<ClipItem> {
            if (text.isNullOrBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(text)
                (0 until array.length()).map { i ->
                    val o = array.getJSONObject(i)
                    ClipItem(o.getLong("id"), o.getString("text"), o.optString("from"), o.optLong("at"), o.optBoolean("pinned"))
                }
            }.getOrDefault(emptyList())
        }

        /** Words that all have to be in the text or in the name of the device, in any case. */
        fun search(list: List<ClipItem>, query: String): List<ClipItem> {
            val words = query.lowercase().split(' ').filter { it.isNotBlank() }
            if (words.isEmpty()) return list
            return list.filter { item -> words.all { item.text.lowercase().contains(it) || item.from.lowercase().contains(it) } }
        }
    }
}
