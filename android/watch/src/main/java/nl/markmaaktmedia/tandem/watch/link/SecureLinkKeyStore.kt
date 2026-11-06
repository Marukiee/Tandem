package nl.markmaaktmedia.tandem.watch.link

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import nl.markmaaktmedia.tandem.watch.protocol.LinkKeyStore
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores the link key encrypted under a key that never leaves the Android Keystore. */
class SecureLinkKeyStore(context: Context) : LinkKeyStore {
    private val prefs = context.getSharedPreferences("link", Context.MODE_PRIVATE)

    override fun load(): ByteArray? {
        val stored = prefs.getString(PREF_KEY, null) ?: return null
        return try {
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, blob, 0, IV_BYTES))
            cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    override fun save(key: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val blob = cipher.iv + cipher.doFinal(key)
        prefs.edit().putString(PREF_KEY, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    override fun clear() {
        prefs.edit().remove(PREF_KEY).apply()
    }

    private fun wrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).run {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "tandem_watch_link"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val PREF_KEY = "wrapped_link_key"
        const val IV_BYTES = 12
    }
}
