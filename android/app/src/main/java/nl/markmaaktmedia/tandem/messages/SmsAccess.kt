package nl.markmaaktmedia.tandem.messages

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/** What Messages needs of Android, in one place. */
object SmsAccess {
    /** Reading and sending. Both are restricted on Android 13 and up for an app that did not come from a store. */
    val permissions = arrayOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS)

    /** A phone without a SIM slot (a tablet) has no messages to give. */
    fun available(context: Context) = context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING)

    fun granted(context: Context) = permissions.all { granted(context, it) }

    /** Reading alone: enough to notice a text that came in, and to pass on the code in it. */
    fun canRead(context: Context) = granted(context, Manifest.permission.READ_SMS)

    /** Names for the numbers. Asked for with the calls; without it a conversation shows its number. */
    fun contacts(context: Context) = granted(context, Manifest.permission.READ_CONTACTS)

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
