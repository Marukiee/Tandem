package nl.markmaaktmedia.tandem.widget

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** One ring of the battery widget. [icon] is a key of `DeviceIconChoice`, or one of the platform icons (see [BatteryWidgetIcons]). */
data class WidgetBattery(
    val id: String,
    val name: String,
    val icon: String,
    val level: Int,
    val charging: Boolean,
    /** Not connected now: the last level that was heard, shown dimmed. */
    val away: Boolean,
)

/** What the widget needs of a device of the engine, so the logic can be tested without the core. */
data class WidgetDeviceInput(
    val id: String,
    val name: String,
    val icon: String,
    val online: Boolean,
    val level: Int?,
    val charging: Boolean,
)

/**
 * The colours are fixed, the same in dark and light and on every system: a dark card like the Batteries widget of iOS. Keep in step with
 * `BatteryWidgetStyle` in `macos/Sources/Tandem/UI/BatteryWidgetView.swift` and with `windows/ui/js/batteries.js`.
 */
object BatteryWidgetColors {
    const val BACKGROUND = 0xFF1C1C1E.toInt()
    const val TRACK = 0x29FFFFFF
    const val TEXT = 0xFFFFFFFF.toInt()
    const val SECONDARY = 0x99FFFFFF.toInt()
    const val GREEN = 0xFF30D158.toInt()
    const val YELLOW = 0xFFFFD60A.toInt()
    const val RED = 0xFFFF453A.toInt()
    const val AWAY_ALPHA = 0.4f

    /** A battery on the charger is green whatever its level, as on iOS: it is getting better. */
    fun tint(level: Int, charging: Boolean): Int = when {
        charging -> GREEN
        level < 10 -> RED
        level < 20 -> YELLOW
        else -> GREEN
    }

    /** Anything below 100 leaves an opening, so 97 does not look like a closed ring with a flaw. Degrees of the circle. */
    fun sweep(level: Int): Float = if (level >= 100) 360f else level.coerceIn(0, 100) / 100f * 0.945f * 360f

    /**
     * [color] at [alpha] times its own opacity, laid on the card and made opaque. A widget tints its pictures in a way that darkens a see
     * through colour instead of letting the card show through it, so every colour is given solid.
     */
    fun solid(color: Int, alpha: Float = 1f): Int {
        val a = (color ushr 24) / 255f * alpha.coerceIn(0f, 1f)
        fun channel(shift: Int): Int {
            val top = (color shr shift) and 0xFF
            val under = (BACKGROUND shr shift) and 0xFF
            return (top * a + under * (1 - a)).roundToInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}

object BatteryWidgetLogic {
    /**
     * The other devices for the widget, in the order it shows them: connected first, then by name. One that has gone away keeps its last
     * level, dimmed; the engine forgets that level when the app restarts, the file of the widget does not. One that left the circle goes.
     */
    fun merge(previous: List<WidgetBattery>, devices: List<WidgetDeviceInput>): List<WidgetBattery> {
        val known = previous.associateBy { it.id }
        return devices.mapNotNull { d ->
            when {
                d.level != null -> WidgetBattery(d.id, d.name, d.icon, d.level.coerceIn(0, 100), d.charging && d.online, !d.online)
                else -> known[d.id]?.copy(name = d.name, icon = d.icon, charging = false, away = true)
            }
        }.sortedWith(compareBy<WidgetBattery>({ it.away }, { it.name.lowercase() }))
    }

    /** A home screen widget is drawn again on every update, and that costs: a level that moves by one waits for the next one that shows. */
    fun differsVisibly(old: List<WidgetBattery>, new: List<WidgetBattery>): Boolean {
        if (old.map { it.id } != new.map { it.id }) return true
        return old.zip(new).any { (a, b) ->
            a.name != b.name || a.icon != b.icon || a.charging != b.charging || a.away != b.away ||
                band(a.level) != band(b.level) || kotlin.math.abs(a.level - b.level) >= 5
        }
    }

    fun band(level: Int): Int = when {
        level < 10 -> 0
        level < 20 -> 1
        level >= 100 -> 3
        else -> 2
    }

    fun encode(devices: List<WidgetBattery>, selfName: String?): String {
        val array = JSONArray()
        devices.forEach {
            array.put(
                JSONObject().put("id", it.id).put("name", it.name).put("icon", it.icon).put("level", it.level)
                    .put("charging", it.charging).put("away", it.away),
            )
        }
        return JSONObject().put("devices", array).put("self", selfName ?: "").toString()
    }

    /** The devices and the name of this phone, from what [encode] wrote. Anything unreadable is an empty widget, never a crash. */
    fun decode(text: String?): Pair<List<WidgetBattery>, String?> = runCatching {
        val root = JSONObject(text ?: return Pair(emptyList(), null))
        val array = root.optJSONArray("devices") ?: JSONArray()
        val list = (0 until array.length()).map { array.getJSONObject(it) }.map {
            WidgetBattery(
                it.getString("id"), it.optString("name"), it.optString("icon"), it.optInt("level"),
                it.optBoolean("charging"), it.optBoolean("away"),
            )
        }
        Pair(list, root.optString("self").ifEmpty { null })
    }.getOrDefault(Pair(emptyList(), null))

    /** How the rings are laid out for the number of devices and the size of the widget: columns and diameter in dp. */
    data class Layout(val columns: Int, val diameter: Int, val names: Boolean)

    enum class Size(val capacity: Int) { Small(4), Medium(4), Large(6) }

    // Fewer devices get bigger rings, so a widget with two of them is not mostly empty card. The same steps as on the Mac.
    fun layout(size: Size, count: Int): Layout = when (size) {
        Size.Small -> when (count) {
            1 -> Layout(1, 76, true)
            2 -> Layout(2, 56, false)
            else -> Layout(2, 46, false)
        }
        Size.Medium -> Layout(count.coerceAtLeast(1), 58, false)
        Size.Large -> when (count) {
            1 -> Layout(1, 140, true)
            2, 3 -> Layout(count, 96, true)
            4 -> Layout(2, 92, true)
            else -> Layout(3, 80, true)
        }
    }
}
