package nl.markmaaktmedia.tandem.update

/** A release as published on GitHub, reduced to what the banner needs. */
data class ReleaseInfo(
    val tag: String,
    val versionName: String,
    val title: String,
    val changelog: String,
    val apkUrl: String?,
    val apkSizeBytes: Long,
    val sha256Url: String?,
    val htmlUrl: String,
    val publishedAt: String,
)

/** Where the update flow currently is. Drives the banner and the settings row. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data class Downloading(val release: ReleaseInfo, val progress: Float) : UpdateState
    data class ReadyToInstall(val release: ReleaseInfo, val filePath: String) : UpdateState

    /** The download is waiting for the "install unknown apps" switch; it carries on when the person comes back. */
    data class NeedsPermission(val release: ReleaseInfo, val filePath: String? = null) : UpdateState

    /** Handed to the installer, which is showing its own confirmation or working. */
    data class Installing(val release: ReleaseInfo) : UpdateState

    /**
     * The installer is waiting for a tap and Android did not let it ask: the app was not in front. The banner has the
     * button, and a notification says so as well.
     */
    data class AwaitingConfirmation(val release: ReleaseInfo) : UpdateState

    /** [release] is set when trying again can pick up where it stopped, without asking GitHub again. */
    data class Failed(val reason: String, val release: ReleaseInfo? = null) : UpdateState
}

/**
 * Compares two version names the way a person would read them, so "1.10.0" beats
 * "1.9.0". Anything after the numbers (a "-rc1" suffix, say) counts as older than
 * the plain release with the same numbers.
 */
object VersionComparator {

    /**
     * The newest release that has [file]. A release can stand there without it, while it is being built or because its
     * build failed. That is no news, and it must not hide the release before it.
     */
    fun newest(releases: org.json.JSONArray, file: String): org.json.JSONObject? {
        var newest: org.json.JSONObject? = null
        for (i in 0 until releases.length()) {
            val release = releases.getJSONObject(i)
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue
            val assets = release.optJSONArray("assets") ?: continue
            if ((0 until assets.length()).none { assets.getJSONObject(it).optString("name") == file }) continue
            if (newest == null || isNewer(release.optString("tag_name"), newest.optString("tag_name"))) newest = release
        }
        return newest
    }

    fun isNewer(candidate: String, current: String): Boolean =
        compare(candidate, current) > 0

    fun compare(left: String, right: String): Int {
        val leftParts = numericParts(left)
        val rightParts = numericParts(right)
        val size = maxOf(leftParts.size, rightParts.size)
        for (index in 0 until size) {
            val a = leftParts.getOrElse(index) { 0 }
            val b = rightParts.getOrElse(index) { 0 }
            if (a != b) return a.compareTo(b)
        }
        // Same numbers: a build with a suffix is a pre-release of the plain one.
        val leftSuffix = suffix(left)
        val rightSuffix = suffix(right)
        return when {
            leftSuffix == rightSuffix -> 0
            leftSuffix.isEmpty() -> 1
            rightSuffix.isEmpty() -> -1
            else -> leftSuffix.compareTo(rightSuffix)
        }
    }

    fun normalise(raw: String): String = raw.trim().removePrefix("v").removePrefix("V")

    private fun numericParts(raw: String): List<Int> =
        normalise(raw)
            .substringBefore('-')
            .split('.')
            .map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

    private fun suffix(raw: String): String =
        normalise(raw).substringAfter('-', "").lowercase()
}
