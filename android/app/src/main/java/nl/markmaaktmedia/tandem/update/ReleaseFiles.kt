package nl.markmaaktmedia.tandem.update

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

/**
 * Where a file of a GitHub release can be had: through the API first, the plain download link second. Either can be down on its
 * own: on 2026-11-08 every plain link gave a 504 for a while, while the API handed out the same files.
 */
object ReleaseFiles {
    fun links(asset: JSONObject?): List<String> =
        listOf("url", "browser_download_url").mapNotNull { asset?.optString(it)?.takeIf(String::isNotBlank) }

    /** The first of these that answers with a 200, open for reading, or null when none does. */
    fun open(client: OkHttpClient, urls: List<String>, agent: String): Response? {
        for (url in urls) {
            val request = Request.Builder().url(url).header("User-Agent", agent).apply {
                // Through the API a file is asked for as the file itself, not as its description.
                if (url.startsWith("https://api.github.com/")) header("Accept", "application/octet-stream")
            }.build()
            val response = runCatching { client.newCall(request).execute() }.getOrNull() ?: continue
            if (response.isSuccessful) return response
            response.close()
        }
        return null
    }
}
