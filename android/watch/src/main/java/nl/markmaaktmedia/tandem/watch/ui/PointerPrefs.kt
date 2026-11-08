package nl.markmaaktmedia.tandem.watch.ui

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How the mouse on the wrist behaves. Kept on the watch: it is about this arm, not about the computer. */
object PointerPrefs {
    enum class Speed(val sensitivity: Float) { SLOW(0.6f), MEDIUM(1f), FAST(1.6f) }

    private const val FILE = "pointer"
    private val _speed = MutableStateFlow(Speed.MEDIUM)
    private val _pinch = MutableStateFlow(true)
    val speed: StateFlow<Speed> = _speed.asStateFlow()

    /**
     * On unless someone turns it off: a single pinch is told apart well since the detector learned from recordings on a real wrist, and off
     * by default it was gone after every new install of the app.
     */
    val pinch: StateFlow<Boolean> = _pinch.asStateFlow()

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        _speed.value = runCatching { Speed.valueOf(prefs.getString("speed", Speed.MEDIUM.name)!!) }.getOrDefault(Speed.MEDIUM)
        _pinch.value = prefs.getBoolean("pinch", true)
    }

    fun nextSpeed(context: Context) {
        val next = Speed.entries[(speed.value.ordinal + 1) % Speed.entries.size]
        _speed.value = next
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("speed", next.name).apply()
    }

    fun setPinch(context: Context, on: Boolean) {
        _pinch.value = on
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("pinch", on).apply()
    }
}
