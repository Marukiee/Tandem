package nl.markmaaktmedia.tandem.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import nl.markmaaktmedia.tandem.BuildConfig
import nl.markmaaktmedia.tandem.data.TandemPrefs
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Checks the GitHub releases feed and, when the person asks, downloads the APK and
 * installs it over the running app.
 *
 * The same approach as MarkMaaktAI: no update service, no polling. The check runs
 * when the app starts, at most once a day, and when the button is pressed, so an
 * offline phone spends nothing on it. Two things are new: the download is checked
 * against the SHA-256 in the release, and the install goes through PackageInstaller,
 * so Android can apply later updates without another screen when it is allowed to.
 *
 * Updating without uninstalling depends on every build being signed with the same
 * key. Android refuses an APK signed by anyone else, which is also what keeps this
 * safe: the download cannot be swapped for another app.
 */
class UpdateRepository(
    private val context: Context,
    private val prefs: TandemPrefs,
) {
    private val client = OkHttpClient()
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val currentVersion: String get() = VersionComparator.normalise(BuildConfig.VERSION_NAME.removeSuffix("-debug"))

    val releasesPageUrl: String
        get() = "https://github.com/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/releases"

    /** On start-up: at most once a day, and only when the person has not switched it off. */
    suspend fun checkIfDue() {
        if (!prefs.autoUpdateCheck.first()) return
        val last = prefs.lastUpdateCheck.first()
        if (System.currentTimeMillis() - last < CHECK_INTERVAL_MS) return
        check(manual = false)
    }

    /**
     * [manual] is true for the button. A check nobody asked for stays quiet when it
     * fails, because an offline phone or a repo without releases is not news.
     */
    suspend fun check(manual: Boolean = true): ReleaseInfo? {
        _state.value = UpdateState.Checking
        val release = fetchLatest()
        prefs.setLastUpdateCheck(System.currentTimeMillis())
        if (release == null) {
            _state.value = if (manual && !noReleaseYet) UpdateState.Failed(FAILED_REASON) else if (noReleaseYet) UpdateState.UpToDate else UpdateState.Idle
            return null
        }
        return if (VersionComparator.isNewer(release.versionName, currentVersion)) {
            _state.value = UpdateState.Available(release)
            release
        } else {
            _state.value = UpdateState.UpToDate
            null
        }
    }

    fun reset() {
        _state.value = UpdateState.Idle
    }

    /** The feed answered 404: nothing has been published yet, which is not a failure. */
    @Volatile
    private var noReleaseYet = false

    private suspend fun fetchLatest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        noReleaseYet = false
        val request = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "Tandem/${BuildConfig.VERSION_NAME}")
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (response.code == 404) noReleaseYet = true
                if (!response.isSuccessful) return@use null
                val root = JSONObject(response.body?.string().orEmpty())
                val tag = root.optString("tag_name")
                if (tag.isBlank()) return@use null
                val assets = root.optJSONArray("assets")
                var apk: JSONObject? = null
                var sha: String? = null
                for (i in 0 until (assets?.length() ?: 0)) {
                    val asset = assets!!.getJSONObject(i)
                    val name = asset.optString("name")
                    if (name == APK_NAME) apk = asset
                    if (name == "$APK_NAME.sha256") sha = asset.optString("browser_download_url")
                }
                // Fall back to any APK, so a differently named asset still works.
                if (apk == null) {
                    for (i in 0 until (assets?.length() ?: 0)) {
                        val asset = assets!!.getJSONObject(i)
                        if (asset.optString("name").endsWith(".apk", ignoreCase = true)) { apk = asset; break }
                    }
                }
                ReleaseInfo(
                    tag = tag,
                    versionName = VersionComparator.normalise(tag),
                    title = root.optString("name").ifBlank { tag },
                    changelog = root.optString("body").trim(),
                    apkUrl = apk?.optString("browser_download_url"),
                    apkSizeBytes = apk?.optLong("size") ?: 0L,
                    sha256Url = sha,
                    htmlUrl = root.optString("html_url").ifBlank { releasesPageUrl },
                    publishedAt = root.optString("published_at"),
                )
            }
        }.getOrElse { error ->
            Log.w(TAG, "Could not read the releases feed", error)
            null
        }
    }

    /** Developer options: shows a fake update so the banner can be pressed through. */
    fun showPreview() {
        _state.value = UpdateState.Available(
            ReleaseInfo(
                tag = PREVIEW_TAG, versionName = "9.9.9", title = "Preview", changelog = "", apkUrl = null,
                apkSizeBytes = 0, sha256Url = null, htmlUrl = "", publishedAt = "",
            ),
        )
    }

    /** Downloads, verifies and hands the APK to the installer. One tap for the person. */
    suspend fun downloadAndInstall(release: ReleaseInfo) {
        if (release.tag == PREVIEW_TAG) {
            for (step in 0..20) {
                _state.value = UpdateState.Downloading(release, step / 20f)
                kotlinx.coroutines.delay(120)
            }
            _state.value = UpdateState.ReadyToInstall(release, PREVIEW_TAG)
            return
        }
        val file = download(release) ?: return
        install(file)
    }

    private suspend fun download(release: ReleaseInfo): File? {
        val url = release.apkUrl ?: run {
            _state.value = UpdateState.Failed("That release has no APK attached")
            return null
        }
        _state.value = UpdateState.Downloading(release, 0f)
        return withContext(Dispatchers.IO) {
            val target = File(updatesDir(), "Tandem-${release.versionName}.apk")
            runCatching {
                val digest = MessageDigest.getInstance("SHA-256")
                client.newCall(Request.Builder().url(url).header("User-Agent", "Tandem").build()).execute().use { response ->
                    if (!response.isSuccessful) error("Download failed with status ${response.code}")
                    val body = response.body ?: error("Empty download")
                    val total = body.contentLength().takeIf { it > 0 } ?: release.apkSizeBytes
                    target.outputStream().use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            var copied = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                digest.update(buffer, 0, read)
                                copied += read
                                if (total > 0) _state.value = UpdateState.Downloading(release, (copied.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
                release.sha256Url?.let { shaUrl ->
                    val expected = client.newCall(Request.Builder().url(shaUrl).build()).execute().use { it.body?.string().orEmpty() }
                        .trim().split(Regex("\\s+")).firstOrNull()?.lowercase()
                    val actual = digest.digest().joinToString("") { "%02x".format(it) }
                    check(expected == null || expected == actual) { "The download is damaged (checksum does not match)" }
                }
                cleanUpOldDownloads(keepFileName = target.name)
                _state.value = UpdateState.ReadyToInstall(release, target.absolutePath)
                target
            }.getOrElse { error ->
                target.delete()
                _state.value = UpdateState.Failed(error.message ?: FAILED_REASON)
                null
            }
        }
    }

    fun canRequestInstalls(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** Installs through a PackageInstaller session, so progress and errors come back to us. */
    suspend fun install(file: File) = withContext(Dispatchers.IO) {
        if (file.path == PREVIEW_TAG) {
            _state.value = UpdateState.Idle
            return@withContext
        }
        if (!canRequestInstalls()) {
            openInstallPermissionSettings()
            _state.value = UpdateState.Failed("Allow Tandem to install apps, then try again")
            return@withContext
        }
        runCatching {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setSize(file.length())
                if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("tandem.apk", 0, file.length()).use { output ->
                        input.copyTo(output, 64 * 1024)
                        session.fsync(output)
                    }
                }
                val intent = Intent(context, InstallResultReceiver::class.java).setAction(InstallResultReceiver.ACTION)
                val pending = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                session.commit(pending.intentSender)
            }
        }.onFailure { error ->
            Log.w(TAG, "install failed", error)
            _state.value = UpdateState.Failed(error.message ?: "Could not start the installer")
        }
    }

    fun reportInstallFailure(reason: String) {
        _state.value = UpdateState.Failed(reason)
    }

    fun cleanUpOldDownloads(keepFileName: String? = null) {
        runCatching { updatesDir().listFiles()?.forEach { if (it.name != keepFileName) it.delete() } }
    }

    private fun updatesDir(): File = File(context.cacheDir, "updates").apply { mkdirs() }

    private companion object {
        const val TAG = "UpdateRepository"
        const val FAILED_REASON = "Could not reach GitHub"
        const val APK_NAME = "Tandem.apk"
        private const val PREVIEW_TAG = "preview"

        /** Every time the app is opened, at most once a minute so switching back and forth does not hammer GitHub. */
        const val CHECK_INTERVAL_MS = 60 * 1000L
    }
}
