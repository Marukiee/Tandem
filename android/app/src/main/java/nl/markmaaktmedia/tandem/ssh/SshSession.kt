package nl.markmaaktmedia.tandem.ssh

import android.content.Context
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.concurrent.Executors

/**
 * One login to another computer, for the terminal of the app. The keys of the phone live in the private folder of the app and the
 * computers that were seen are written to a file of known computers there, the way ssh does it: a computer that is new is trusted the
 * first time, and one that shows another key than before is refused.
 */
class SshSession(
    private val context: Context,
    private val onOutput: (ByteArray) -> Unit,
    private val onClosed: (String?) -> Unit,
) {
    private var session: Session? = null
    private var channel: ChannelShell? = null
    private var out: OutputStream? = null
    // Writes go through one thread, in the order they were made, and never on the thread of the screen.
    private val writer = Executors.newSingleThreadExecutor()
    @Volatile private var closed = false

    /** Blocks until the login worked or failed: call it from a background thread. Returns null on success, otherwise a reason. */
    fun connect(host: String, user: String, password: String?, cols: Int, rows: Int, port: Int = 22): String? {
        return try {
            val jsch = JSch()
            val known = File(context.filesDir, "ssh/known_hosts").apply { parentFile?.mkdirs(); if (!exists()) createNewFile() }
            jsch.setKnownHosts(known.path)
            SshKeys.load(context)?.let { jsch.addIdentity("tandem", it.privatePem, null, null) }
            val s = jsch.getSession(user, host, port)
            if (!password.isNullOrEmpty()) s.setPassword(password)
            s.setConfig("StrictHostKeyChecking", "ask")
            s.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
            s.userInfo = Trust
            s.serverAliveInterval = 20_000
            s.connect(15_000)
            val c = s.openChannel("shell") as ChannelShell
            c.setPtyType("xterm-256color", cols, rows, 0, 0)
            val input = c.inputStream
            out = c.outputStream
            c.connect(10_000)
            session = s
            channel = c
            Thread({
                val buffer = ByteArray(8192)
                var reason: String? = null
                try {
                    while (!closed) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (n > 0) onOutput(buffer.copyOf(n))
                    }
                } catch (e: Exception) {
                    if (!closed) reason = e.message
                }
                closeQuietly()
                onClosed(reason)
            }, "tandem-ssh").start()
            null
        } catch (e: Exception) {
            closeQuietly()
            reasonOf(e)
        }
    }

    fun write(bytes: ByteArray) {
        if (closed) return
        // The login can end between the check and here, and then the thread of writes is gone: that is not an error.
        runCatching {
            writer.execute {
                runCatching { out?.write(bytes); out?.flush() }
            }
        }
    }

    fun resize(cols: Int, rows: Int) {
        runCatching { channel?.setPtySize(cols, rows, 0, 0) }
    }

    fun close() {
        closeQuietly()
    }

    private fun closeQuietly() {
        closed = true
        runCatching { channel?.disconnect() }
        runCatching { session?.disconnect() }
        writer.shutdown()
    }

    private fun reasonOf(e: Exception): String {
        val text = e.message.orEmpty()
        return when {
            "Auth fail" in text || "Auth cancel" in text -> "auth"
            "HostKey has been changed" in text || "REMOTE HOST IDENTIFICATION HAS CHANGED" in text -> "changed"
            "timeout" in text.lowercase() || "timed out" in text.lowercase() -> "timeout"
            "Connection refused" in text -> "refused"
            else -> text.ifBlank { "failed" }
        }
    }

    /** Trust on first use: a computer that is new is accepted, a key that changed is not. */
    private object Trust : UserInfo {
        override fun getPassphrase(): String? = null
        override fun getPassword(): String? = null
        override fun promptPassword(message: String?): Boolean = false
        override fun promptPassphrase(message: String?): Boolean = false
        override fun promptYesNo(message: String?): Boolean {
            val text = message.orEmpty()
            return !(text.contains("CHANGED", ignoreCase = true) || text.contains("delete the old key", ignoreCase = true))
        }
        override fun showMessage(message: String?) = Unit
    }
}

/** The key of this phone, for logging in without a password: made on request, kept in the private folder of the app. */
class SshKey(val privatePem: ByteArray, val publicLine: String)

object SshKeys {
    private fun file(context: Context) = File(context.filesDir, "ssh/id_phone")

    fun load(context: Context): SshKey? {
        val f = file(context)
        if (!f.exists()) return null
        return runCatching {
            val pem = f.readBytes()
            val pair = KeyPair.load(JSch(), pem, null)
            val publicOut = ByteArrayOutputStream()
            pair.writePublicKey(publicOut, "tandem@phone")
            SshKey(pem, publicOut.toString(Charsets.UTF_8.name()).trim())
        }.getOrNull()
    }

    /**
     * Makes the key when there is none yet, and returns it. Ed25519 first, which is what people expect; a phone whose crypto does not do
     * it gets an ECDSA key, and failing that an RSA one, so there is always one.
     */
    fun ensure(context: Context): SshKey? {
        load(context)?.let { return it }
        for (type in intArrayOf(KeyPair.ED25519, KeyPair.ECDSA, KeyPair.RSA)) {
            val made = runCatching {
                val pair = when (type) {
                    KeyPair.ECDSA -> KeyPair.genKeyPair(JSch(), type, 256)
                    KeyPair.RSA -> KeyPair.genKeyPair(JSch(), type, 3072)
                    else -> KeyPair.genKeyPair(JSch(), type)
                }
                val privateOut = ByteArrayOutputStream()
                // The format of OpenSSH itself, which every type of key can be written in (the old PEM format has no Ed25519).
                pair.writeOpenSSHv1PrivateKey(privateOut, null)
                file(context).apply { parentFile?.mkdirs(); writeBytes(privateOut.toByteArray()) }
                pair.dispose()
                load(context)
            }.onFailure { android.util.Log.w("TandemSsh", "a key of type $type could not be made", it) }.getOrNull()
            if (made != null) return made
        }
        return null
    }
}
