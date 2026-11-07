package nl.markmaaktmedia.tandem.watch.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.concurrent.thread
import nl.markmaaktmedia.tandem.watch.R
import nl.markmaaktmedia.tandem.watch.link.WatchLink
import nl.markmaaktmedia.tandem.watch.protocol.Message
import nl.markmaaktmedia.tandem.watch.protocol.WatchUpdate
import nl.markmaaktmedia.tandem.watch.track.TrackingService

/**
 * Brings this app up to the version of the phone app (see [WatchUpdate]). The file is kept as the parts come in, so a link that drops
 * goes on where it stopped, and it is only installed while nothing is recorded: an install restarts the app, and that would end a night.
 * All of it runs on the thread of the link, except the install itself.
 */
object SelfUpdate {
    private data class Offer(val code: Long, val name: String, val size: Long, val sha: String)

    /** Damaged files in a row before it stops asking for this version. */
    private var damaged = 0
    private var waitingForIdle = false
    /** An install is under way: the phone offers again at each connect, and two sessions for one file would get in each other's way. */
    private var installing = false
    /** Android asked for a tap and nobody gave it yet. */
    private var confirming = false

    private fun dir(context: Context) = File(context.filesDir, "update").apply { mkdirs() }
    private fun apk(context: Context) = File(dir(context), "Tandem-watch.apk")
    private fun offerFile(context: Context) = File(dir(context), "offer")

    /** A version that would not install: it is not asked for again, or every connect would send the whole file once more. */
    private fun failedFile(context: Context) = File(dir(context), "failed")

    fun installedCode(context: Context): Long = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    /** The certificate this app is signed with: only an app with the same one installs over it, so the phone checks it first. */
    fun signer(context: Context): String {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val cert = info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray() ?: return ""
        return WatchUpdate.hex(WatchUpdate.sha256(cert))
    }

    /** After each connect: what runs here, for the phone. What came in earlier but waited for a recording to end goes in now. */
    fun onReady(context: Context) {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        WatchLink.sendUpdate(Message.AppVersion(info.longVersionCode, info.versionName.orEmpty(), signer(context)))
        val offer = readOffer(context) ?: return
        when {
            offer.code <= info.longVersionCode -> clear(context)
            apk(context).length() == offer.size -> installWhenIdle(context, offer)
        }
    }

    fun onOffer(context: Context, message: Message.UpdateOffer) {
        if (message.code <= installedCode(context)) {
            clear(context)
            WatchLink.sendUpdate(Message.UpdateState(message.code, WatchUpdate.UP_TO_DATE))
            return
        }
        if (failedFile(context).readTextOrNull()?.trim() == message.code.toString()) {
            WatchLink.sendUpdate(Message.UpdateState(message.code, WatchUpdate.FAILED, context.getString(R.string.update_failed_before)))
            return
        }
        val offer = Offer(message.code, message.name, message.size, WatchUpdate.hex(message.sha256))
        if (readOffer(context) != offer) {
            clear(context)
            writeOffer(context, offer)
        }
        val have = apk(context).length()
        if (have >= offer.size) finish(context, offer) else WatchLink.sendUpdate(Message.UpdateWant(offer.code, have))
    }

    fun onPart(context: Context, part: Message.UpdatePart) {
        val offer = readOffer(context) ?: return
        if (part.code != offer.code) return
        val file = apk(context)
        // A part that does not follow on: say again where it stands, the phone sends from there.
        if (part.offset != file.length()) {
            WatchLink.sendUpdate(Message.UpdateWant(offer.code, file.length()))
            return
        }
        FileOutputStream(file, true).use { output ->
            output.write(part.bytes)
            output.fd.sync()
        }
        if (file.length() < offer.size) WatchLink.sendUpdate(Message.UpdateWant(offer.code, file.length())) else finish(context, offer)
    }

    /** The whole file is here: checked before anything is installed. */
    private fun finish(context: Context, offer: Offer) {
        val file = apk(context)
        if (file.length() != offer.size || sha256(file) != offer.sha) {
            file.delete()
            damaged++
            if (damaged >= MAX_DAMAGED) {
                damaged = 0
                fail(context, offer.code, context.getString(R.string.update_damaged), final = true)
            } else {
                WatchLink.sendUpdate(Message.UpdateWant(offer.code, 0))
            }
            return
        }
        damaged = 0
        installWhenIdle(context, offer)
    }

    private fun installWhenIdle(context: Context, offer: Offer) {
        if (installing) {
            WatchLink.sendUpdate(Message.UpdateState(offer.code, if (confirming) WatchUpdate.CONFIRM else WatchUpdate.INSTALLING))
            return
        }
        if (TrackingService.recording) {
            waitingForIdle = true
            WatchLink.sendUpdate(Message.UpdateState(offer.code, WatchUpdate.WAITING))
            return
        }
        waitingForIdle = false
        installing = true
        WatchLink.sendUpdate(Message.UpdateState(offer.code, WatchUpdate.INSTALLING))
        val app = context.applicationContext
        // Off the thread of the link: writing a few megabytes into the installer would hold up everything else.
        thread(name = "TandemSelfUpdate") { install(app, offer) }
    }

    /** A recording ended. */
    fun idle(context: Context) {
        if (!waitingForIdle) return
        WatchLink.post {
            val offer = readOffer(context) ?: return@post
            if (apk(context).length() == offer.size) installWhenIdle(context, offer)
        }
    }

    private fun install(context: Context, offer: Offer) {
        runCatching {
            val file = apk(context)
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(file.length())
                // An app that updates itself may do so without a tap: it has the permission for it in its manifest.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("Tandem-watch.apk", 0, file.length()).use { output ->
                        input.copyTo(output, 64 * 1024)
                        session.fsync(output)
                    }
                }
                val result = Intent(context, UpdateResultReceiver::class.java).putExtra(UpdateResultReceiver.EXTRA_CODE, offer.code)
                val pending = PendingIntent.getBroadcast(context, id, result, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                session.commit(pending.intentSender)
            }
        }.onFailure { error ->
            Log.w(TAG, "Could not start the install", error)
            WatchLink.post { fail(context, offer.code, error.message ?: error.javaClass.simpleName, final = false) }
        }
    }

    /** Android wants a tap for this one: a notification that opens its question. */
    fun askToConfirm(context: Context, code: Long, confirm: Intent?) {
        confirming = true
        WatchLink.sendUpdate(Message.UpdateState(code, WatchUpdate.CONFIRM))
        if (confirm == null) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.channel_update), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 0, confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(
            NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_tandem)
                .setContentTitle(context.getString(R.string.update_confirm_title))
                .setContentText(context.getString(R.string.update_confirm_text, readOffer(context)?.name.orEmpty()))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setLocalOnly(true)
                .build(),
        )
    }

    /**
     * [final]: it can never install, like an app signed with another key; the file goes and the version is not asked for again.
     * Otherwise, say a tap that was not given, the file stays and the next connect tries again without sending it once more.
     */
    fun fail(context: Context, code: Long, detail: String, final: Boolean) {
        Log.w(TAG, "Update to $code failed: $detail")
        installing = false
        confirming = false
        if (final) {
            clear(context)
            failedFile(context).writeText(code.toString())
        }
        WatchLink.sendUpdate(Message.UpdateState(code, WatchUpdate.FAILED, detail))
    }

    fun clear(context: Context) {
        installing = false
        confirming = false
        waitingForIdle = false
        apk(context).delete()
        offerFile(context).delete()
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun readOffer(context: Context): Offer? = offerFile(context).readTextOrNull()?.lines()?.takeIf { it.size >= 4 }?.let {
        runCatching { Offer(it[0].toLong(), it[1], it[2].toLong(), it[3]) }.getOrNull()
    }

    private fun writeOffer(context: Context, offer: Offer) {
        offerFile(context).writeText("${offer.code}\n${offer.name}\n${offer.size}\n${offer.sha}\n")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return WatchUpdate.hex(digest.digest())
    }

    private fun File.readTextOrNull(): String? = if (exists()) runCatching { readText() }.getOrNull() else null

    private const val TAG = "TandemWatchUpdate"
    private const val CHANNEL = "update"
    /** Its own: 300 is the recording, 310 the question about a nap, 350 the music. */
    private const val NOTIFICATION_ID = 500
    private const val MAX_DAMAGED = 3
}
