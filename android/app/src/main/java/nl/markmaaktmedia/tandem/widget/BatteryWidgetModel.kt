package nl.markmaaktmedia.tandem.widget

import org.json.JSONArray
import org.json.JSONObject

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
 * The role a ring is drawn in. The colours themselves come from the wallpaper (Material You, see BatteryWidget.kt), so the widget looks like the
 * other widgets of a Pixel; only the warnings of a low battery are fixed hues. The Mac and the tray panel draw the same rings in their own language.
 */
enum class BatteryTone { Normal, Low, Critical, Away }

/** [lobes] waves around the edge, [depth] how deep they go as a part of the radius. */
enum class BatteryShape(val lobes: Int, val depth: Float) { Cookie(9, 0.11f), Clover(4, 0.26f) }

object BatteryWidgetColors {
    /** Low under 20, critical under 10. On the charger it is never a warning: it is getting better. */
    fun tone(level: Int, charging: Boolean, away: Boolean): BatteryTone = when {
        away -> BatteryTone.Away
        charging -> BatteryTone.Normal
        level < 10 -> BatteryTone.Critical
        level < 20 -> BatteryTone.Low
        else -> BatteryTone.Normal
    }

    /** Anything below 100 leaves an opening, so 97 does not look like a closed ring with a flaw. Degrees of the circle. */
    fun sweep(level: Int): Float = if (level >= 100) 360f else level.coerceIn(0, 100) / 100f * 0.945f * 360f

    /** The container under a device's icon, from the shapes of Material 3 Expressive: phones a cookie, computers a clover. */
    fun shapeOf(icon: String): BatteryShape = when (icon) {
        "phone", "tablet", "watch" -> BatteryShape.Cookie
        else -> BatteryShape.Clover
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

    /** The space between rows, in dp. */
    fun rowGap(size: Size): Int = if (size == Size.Small) 8 else 18

    /**
     * How tall one ring is with what is under it, in dp; keep in step with `Item` in BatteryWidget.kt. A line of text in a widget takes
     * about 1.8 times its size, with the padding Android puts around it (measured on a Pixel 7 emulator).
     */
    fun itemHeight(diameter: Int, names: Boolean): Float =
        diameter * 1.08f + maxOf(13f, diameter * 0.22f) * 1.8f + if (names) 22f else 0f

    /**
     * [layout] made to fit a widget of [width] by [height] dp (inside its padding). A launcher gives a widget any size between the steps, and
     * the steps only say how big the rings may be: smaller if the cell is, and without names when even small rings do not fit with them.
     */
    fun fit(size: Size, count: Int, width: Float, height: Float): Layout {
        val wanted = layout(size, count)
        val rows = (count + wanted.columns - 1) / wanted.columns
        val gaps = (rows - 1) * rowGap(size)
        fun fits(d: Int, names: Boolean) = rows * itemHeight(d, names) + gaps <= height
        val widest = ((width / wanted.columns) * 0.86f).toInt()
        var d = minOf(wanted.diameter, widest)
        var names = wanted.names
        while (d > 28 && !fits(d, names)) {
            if (names && d <= 56) names = false else d -= 2
        }
        return wanted.copy(diameter = d.coerceAtLeast(24), names = names)
    }
}
