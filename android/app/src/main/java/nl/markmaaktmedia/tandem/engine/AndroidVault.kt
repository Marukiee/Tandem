package nl.markmaaktmedia.tandem.engine

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import uniffi.tandem_core.TandemException
import uniffi.tandem_core.TandemVault
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the device identity encrypted with a key that never leaves the Android
 * Keystore. The key is not usable outside this phone, and with `allowBackup` off the
 * encrypted file does not travel to another phone either, so an identity is never
 * cloned by a restore.
 */
class AndroidVault(context: Context) : TandemVault {

    private val file = File(context.filesDir, "identity.enc")

    override fun load(): ByteArray? {
        if (!file.exists()) return null
        return try {
            val blob = file.readBytes()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob.copyOfRange(0, IV_SIZE)))
            cipher.doFinal(blob, IV_SIZE, blob.size - IV_SIZE)
        } catch (e: Exception) {
            // Throwing stops the engine. Quietly making a new identity would orphan
            // every pairing without telling anyone.
            throw TandemException.Failed("The stored identity could not be decrypted: ${e.message}")
        }
    }

    override fun save(secret: ByteArray) {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.doFinal(secret)
            val temp = File(file.parentFile, "identity.enc.tmp")
            temp.writeBytes(cipher.iv + encrypted)
            check(temp.renameTo(file)) { "could not move the identity into place" }
        } catch (e: Exception) {
            throw TandemException.Failed("The identity could not be saved: ${e.message}")
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ALIAS = "tandem-identity"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}
