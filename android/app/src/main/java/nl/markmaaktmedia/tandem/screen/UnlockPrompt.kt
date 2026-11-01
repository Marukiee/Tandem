package nl.markmaaktmedia.tandem.screen

import android.app.Activity
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.widget.Toast
import nl.markmaaktmedia.tandem.R

/**
 * Asks the person holding the phone to show it is them before a locked computer is unlocked with the password it keeps. A fingerprint,
 * or the phone's own PIN when it has no fingerprint. A phone without any screen lock cannot vouch for anyone, so it does not unlock.
 */
object UnlockPrompt {
    private const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    fun ask(activity: Activity, computer: String, onYes: () -> Unit, onNo: () -> Unit): CancellationSignal? {
        val manager = activity.getSystemService(BiometricManager::class.java)
        if (manager?.canAuthenticate(AUTHENTICATORS) != BiometricManager.BIOMETRIC_SUCCESS) {
            Toast.makeText(activity, R.string.screen_unlock_no_lock, Toast.LENGTH_LONG).show()
            onNo()
            return null
        }
        val cancel = CancellationSignal()
        BiometricPrompt.Builder(activity)
            .setTitle(activity.getString(R.string.screen_unlock_title, computer))
            .setSubtitle(activity.getString(R.string.screen_unlock_sub))
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
            .authenticate(cancel, activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onYes()
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onNo()
            })
        return cancel
    }
}
