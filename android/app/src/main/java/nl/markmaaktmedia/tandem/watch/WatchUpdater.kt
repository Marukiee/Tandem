package nl.markmaaktmedia.tandem.watch

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.util.Log
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.BuildConfig
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.watch.protocol.Message
import nl.markmaaktmedia.tandem.watch.protocol.WatchUpdate
import okhttp3.OkHttpClient
import okhttp3.Request
import nl.markmaaktmedia.tandem.update.ReleaseFiles
import org.json.JSONObject

/** How bringing the watch app up to the version of this phone stands, for the page of the watch. */
sealed interface WatchAppState {
    /** The watch did not say its version: its app is older than this. */
    data object Unknown : WatchAppState
    data class UpToDate(val name: String) : WatchAppState
    /** Put on by hand from a build with another key, which no update can install over. */
    data class OtherSigner(val name: String) : WatchAppState
    data class Fetching(val name: String) : WatchAppState
    data class Sending(val name: String, val progress: Float) : WatchAppState
    /** The watch has it all and installs it once the recording ends. */
    data class Waiting(val name: String) : WatchAppState
    data class Installing(val name: String) : WatchAppState
    /** Android on the watch wants a tap. */
    data class Confirm(val name: String) : WatchAppState
    data class Failed(val name: String, val reason: String) : WatchAppState
}

/**
 * Brings the watch app along with this one: when the watch says it has an older version signed by the same key as this app, the
 * watch app of this very version is fetched from its release, checked, and sent in parts that the watch asks for one by one (see
 * [WatchUpdate]). The watch keeps what came, so a link that drops goes on where it stopped. Everything but the download runs on the
 * thread of the bridge.
 */
class WatchUpdater(
    private val context: Context,
    private val scope: CoroutineScope,
    private val handler: Handler,
    private val send: (Message) -> Unit,
    /** The mouse or a call uses the link now: a part waits, a pointer that stutters is worse than an update that takes longer. */
    private val busy: () -> Boolean,
) {
    private val _state = MutableStateFlow<WatchAppState>(WatchAppState.Unknown)
    val state: StateFlow<WatchAppState> = _state.asStateFlow()

    private val client = OkHttpClient()
    private val ownCode = BuildConfig.VERSION_CODE.toLong()
    private val ownName = BuildConfig.VERSION_NAME
    private val ownSigner: String by lazy { signerOf(context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)) }

    /** The watch app of this version, checked. */
    private var file: ByteArray? = null
    private var fetching = false
    private var want: Message.UpdateWant? = null
    /** It would not install on the watch: not again until this app starts again, or each connect sends it all once more. */
    private var failed = false
    private val nextPart = Runnable { sendPart() }

    fun onVersion(message: Message.AppVersion) {
        when {
            message.code >= ownCode -> _state.value = WatchAppState.UpToDate(message.name)
            message.signer.isEmpty() || message.signer != ownSigner -> _state.value = WatchAppState.OtherSigner(message.name)
            failed -> Unit
            else -> offer()
        }
    }

    fun onWant(message: Message.UpdateWant) {
        want = message
        sendPart()
    }

    fun onState(message: Message.UpdateState) {
        _state.value = when (message.state) {
            WatchUpdate.WAITING -> WatchAppState.Waiting(ownName)
            WatchUpdate.INSTALLING -> WatchAppState.Installing(ownName)
            WatchUpdate.CONFIRM -> WatchAppState.Confirm(ownName)
            WatchUpdate.UP_TO_DATE -> WatchAppState.UpToDate(ownName)
            else -> {
                failed = true
                want = null
                WatchAppState.Failed(ownName, message.detail)
            }
        }
    }

    /** From the row on the page of the watch, after it failed. The watch says its version at once if it is connected. */
    fun retry(ready: Boolean) {
        failed = false
        if (ready) offer()
    }

    /** The link dropped: what was asked for is asked again after the next connect. */
    fun onDisconnected() {
        want = null
        handler.removeCallbacks(nextPart)
    }

    private fun offer() {
        val apk = file
        if (apk != null) {
            send(Message.UpdateOffer(ownCode, ownName, apk.size.toLong(), WatchUpdate.sha256(apk)))
            return
        }
        if (fetching) return
        fetching = true
        _state.value = WatchAppState.Fetching(ownName)
        scope.launch(Dispatchers.IO) {
            val loaded = runCatching { load() }
            handler.post {
                fetching = false
                loaded.onSuccess {
                    file = it
                    send(Message.UpdateOffer(ownCode, ownName, it.size.toLong(), WatchUpdate.sha256(it)))
                }.onFailure {
                    // Not remembered as failed: offline now is not offline at the next connect, which tries again.
                    Log.w(TAG, "Could not get the watch app of $ownName", it)
                    _state.value = WatchAppState.Failed(ownName, it.message ?: context.getString(R.string.watch_app_unreachable))
                }
            }
        }
    }

    private fun sendPart() {
        handler.removeCallbacks(nextPart)
        val wanted = want ?: return
        val apk = file ?: return
        if (busy()) {
            handler.postDelayed(nextPart, BUSY_WAIT_MS)
            return
        }
        val part = WatchUpdate.partFor(wanted, ownCode, apk) ?: return
        want = null
        send(part)
        _state.value = WatchAppState.Sending(ownName, (part.offset + part.bytes.size).toFloat() / apk.size)
    }

    /** The file of an earlier try, or the one of the release of this version. Either way checked before anything is sent. */
    private fun load(): ByteArray {
        val dir = File(context.filesDir, "watch-update").apply { mkdirs() }
        val kept = File(dir, "Tandem-watch-$ownCode.apk")
        if (kept.exists() && runCatching { verify(kept) }.isSuccess) return kept.readBytes()
        dir.listFiles()?.forEach { it.delete() }

        val release = get("https://api.github.com/repos/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/releases/tags/v$ownName").use { response ->
            if (response.code == 404) error(context.getString(R.string.watch_app_no_release, ownName))
            check(response.isSuccessful) { context.getString(R.string.watch_app_unreachable) }
            JSONObject(response.body?.string().orEmpty())
        }
        val assets = release.optJSONArray("assets")
        fun urls(name: String): List<String> = ReleaseFiles.links(
            (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }.firstOrNull { it.optString("name") == name },
        )
        val apkUrls = urls(APK_NAME).ifEmpty { error(context.getString(R.string.watch_app_no_release, ownName)) }
        val shaUrls = urls("$APK_NAME.sha256").ifEmpty { error(context.getString(R.string.watch_app_no_release, ownName)) }
        val agent = "Tandem/$ownName"

        val bytes = (ReleaseFiles.open(client, apkUrls, agent) ?: error(context.getString(R.string.watch_app_unreachable))).use { response ->
            response.body?.bytes() ?: error(context.getString(R.string.watch_app_unreachable))
        }
        val expected = (ReleaseFiles.open(client, shaUrls, agent) ?: error(context.getString(R.string.watch_app_unreachable)))
            .use { it.body?.string().orEmpty() }.trim().split(Regex("\\s+")).firstOrNull()?.lowercase()
        check(expected == WatchUpdate.hex(MessageDigest.getInstance("SHA-256").digest(bytes))) { context.getString(R.string.watch_app_damaged) }
        val partial = File(dir, "$ownCode.part")
        partial.writeBytes(bytes)
        verify(partial)
        partial.renameTo(kept)
        return bytes
    }

    /** The watch app, of this version, signed like this app: anything else would only fail on the watch after minutes of sending. */
    private fun verify(apk: File) {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: error(context.getString(R.string.watch_app_damaged))
        check(info.packageName == WATCH_PACKAGE && info.longVersionCode == ownCode) { context.getString(R.string.watch_app_damaged) }
        val signer = signerOf(info)
        check(signer.isEmpty() || signer == ownSigner) { context.getString(R.string.watch_app_other_key) }
    }

    private fun get(url: String) = client.newCall(
        Request.Builder().url(url).header("Accept", "application/vnd.github+json").header("User-Agent", "Tandem/$ownName").build(),
    ).execute()

    private fun signerOf(info: android.content.pm.PackageInfo): String {
        val cert = info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray() ?: return ""
        return WatchUpdate.hex(MessageDigest.getInstance("SHA-256").digest(cert))
    }

    private companion object {
        const val TAG = "TandemWatchUpdate"
        const val APK_NAME = "Tandem-watch.apk"
        const val WATCH_PACKAGE = "nl.markmaaktmedia.tandem.watch"
        const val BUSY_WAIT_MS = 2_000L
    }
}
