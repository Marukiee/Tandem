package nl.markmaaktmedia.tandem.hotspot

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CompletableDeferred

/**
 * Where [ShellStarter] hands over the binder of the shell service it runs. Only the shell or root can reach it: the manifest asks a
 * permission no ordinary app holds, and the caller is checked here as well.
 */
class ShellBinderProvider : ContentProvider() {
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD) return null
        val caller = Binder.getCallingUid()
        if (caller != SHELL_UID && caller != ROOT_UID) return null
        val service = extras?.getBinder(EXTRA_SERVICE) ?: return null
        Log.i(TAG, "The shell service is here")
        waiting?.complete(service)
        return Bundle().apply { putBinder(EXTRA_CLIENT, client) }
    }

    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        const val AUTHORITY_SUFFIX = "shell"
        const val METHOD = "shellService"
        const val EXTRA_SERVICE = "service"
        const val EXTRA_CLIENT = "client"
        private const val SHELL_UID = 2000
        private const val ROOT_UID = 0
        private const val TAG = "ShellBinderProvider"

        /** Lives as long as this process: the shell process ends when it dies. */
        private val client = Binder()

        @Volatile
        private var waiting: CompletableDeferred<IBinder>? = null

        /** Waits for the next binder a [ShellStarter] hands over. Call before starting it. */
        fun expect(): CompletableDeferred<IBinder> = CompletableDeferred<IBinder>().also { waiting = it }
    }
}
