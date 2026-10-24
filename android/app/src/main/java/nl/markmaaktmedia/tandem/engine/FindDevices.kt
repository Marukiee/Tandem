package nl.markmaaktmedia.tandem.engine

import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.core.content.IntentCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.graph

/**
 * The computers this phone makes ring, as their own reports say (`Ringing`). A computer that never reports (an older Tandem) is let go
 * of after a while, a little after the two minutes a computer rings at most.
 */
object FindDevices {
    private const val LONGEST_MS = 135_000L
    private val _ringing = MutableStateFlow<Set<String>>(emptySet())
    val ringing: StateFlow<Set<String>> = _ringing
    private val fallbacks = mutableMapOf<String, Job>()

    fun ring(context: Context, id: String, on: Boolean) {
        mark(context, id, on)
        context.graph.scope.launch {
            runCatching { context.graph.host.engine?.ring(id, on) }.onFailure { mark(context, id, false) }
        }
    }

    fun reported(context: Context, id: String, on: Boolean) = mark(context, id, on)

    private fun mark(context: Context, id: String, on: Boolean) {
        synchronized(fallbacks) {
            fallbacks.remove(id)?.cancel()
            _ringing.update { if (on) it + id else it - id }
            if (on) fallbacks[id] = context.graph.scope.launch { delay(LONGEST_MS); mark(context, id, false) }
        }
    }
}

/**
 * The sound this phone rings with when another device looks for it: one from the system's own ringtones and alarms, picked with its
 * picker. Kept in a small file of its own and not in the backup, because the address of a sound only means something on this phone.
 */
object FindSound {
    private const val FILE = "find"
    private const val KEY = "sound"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun picked(context: Context): Uri? = prefs(context).getString(KEY, null)?.let(Uri::parse)

    fun set(context: Context, uri: Uri?) {
        prefs(context).edit().apply { if (uri == null) remove(KEY) else putString(KEY, uri.toString()) }.apply()
    }

    /** What rang before there was a choice: the alarm of the system, which carries further than a ringtone. */
    fun fallback(): Uri? = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

    fun title(context: Context): String {
        val uri = picked(context) ?: return context.getString(R.string.find_sound_default)
        return runCatching { RingtoneManager.getRingtone(context, uri)?.getTitle(context) }.getOrNull() ?: context.getString(R.string.find_sound_default)
    }

    /** The system's picker of ringtones and alarms. It plays a sound when it is tapped, which is the preview. */
    fun pickIntent(context: Context): Intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
        .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM or RingtoneManager.TYPE_RINGTONE)
        .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, context.getString(R.string.find_sound_title))
        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
        .putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, fallback())
        .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, picked(context) ?: fallback())

    /** What the picker gave back. The default entry means no choice of its own. */
    fun fromPicker(context: Context, data: Intent?) {
        val uri = data?.let { IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java) } ?: return
        set(context, uri.takeUnless { RingtoneManager.isDefault(it) || it == fallback() })
    }
}
