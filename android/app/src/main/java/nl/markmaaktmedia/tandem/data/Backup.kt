package nl.markmaaktmedia.tandem.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * A device in a backup, with its settings in the words of its own app. `kind` says whose settings they are: "android", "mac" or
 * "desktop" (the app for Linux and Windows). Only the same kind fits a device.
 */
data class BackupDevice(
    val index: Int,
    val deviceId: String?,
    val name: String,
    val kind: String,
    val platform: String,
    val model: String?,
    val language: String?,
    val settings: JSONObject,
) {
    val fits: Boolean get() = kind == Backup.KIND
}

/**
 * One file with the settings of all devices of the person. Each device writes its own part (`entry`), the one that makes the
 * backup asks the others for theirs, and a device that was reset finds itself again in the file by its model or name, since a new
 * identity has a new id. Identities, pairings and passwords are never in it.
 */
object Backup {
    const val KIND = "android"
    private const val FORMAT = 3

    fun entry(myId: String, name: String, model: String, language: String, settings: JSONObject): JSONObject =
        JSONObject().put("id", myId).put("name", name).put("kind", KIND).put("platform", "android").put("model", model)
            .put("language", language).put("settings", settings)

    /** This phone's part and the parts the others sent, which are kept as they came when they look like one. */
    fun file(mine: JSONObject, others: List<String>, made: String): String {
        val devices = JSONArray().put(mine)
        for (part in others) {
            val other = runCatching { JSONObject(part) }.getOrNull() ?: continue
            if (other.optJSONObject("settings") != null && other.optString("kind").isNotEmpty()) devices.put(other)
        }
        return JSONObject().put("app", "Tandem").put("format", FORMAT).put("made", made).put("devices", devices).toString(2)
    }

    /** The devices in a file, or null when it is no backup. A file of an older version holds one device. */
    fun devices(text: String): List<BackupDevice>? {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val list = root.optJSONArray("devices")
        if (list != null && root.optString("app").equals("tandem", ignoreCase = true)) {
            val devices = (0 until list.length()).mapNotNull { i ->
                val d = list.optJSONObject(i) ?: return@mapNotNull null
                val settings = d.optJSONObject("settings") ?: return@mapNotNull null
                val kind = d.optString("kind").ifEmpty { return@mapNotNull null }
                BackupDevice(
                    i, d.optString("id").ifEmpty { null }, d.optString("name").ifEmpty { kind }, kind,
                    d.optString("platform").ifEmpty { kind }, d.optString("model").ifEmpty { null },
                    d.optString("language").ifEmpty { null }, settings,
                )
            }
            return devices.ifEmpty { null }
        }
        val settings = root.optJSONObject("settings")
        return when {
            root.optString("app") == "tandem" && settings != null -> listOf(
                BackupDevice(0, null, settings.optString("device_name").ifEmpty { "Android" }, KIND, "android", null,
                    root.optString("language").ifEmpty { null }, settings),
            )
            root.optString("app") == "Tandem" && settings != null -> listOf(
                BackupDevice(0, null, settings.optString("deviceName").ifEmpty { "Mac" }, "mac", "mac", null, null, settings),
            )
            // A file of the computer app is its settings themselves, without a wrapper.
            !root.has("app") && root.has("closeToTray") -> listOf(BackupDevice(0, null, "", "desktop", "linux", null, null, root))
            else -> null
        }
    }

    /**
     * Which device of a backup is this phone: the same id when it kept its identity, else the one of the same model, else the one
     * with the same name, else the only phone. Two phones of the same model leave the choice to the person.
     */
    fun recognise(devices: List<BackupDevice>, myId: String, model: String, name: String): Int? {
        val fitting = devices.filter { it.fits }
        fitting.firstOrNull { it.deviceId == myId }?.let { return it.index }
        fitting.filter { it.model == model }.singleOrNull()?.let { return it.index }
        fitting.filter { it.name.equals(name, ignoreCase = true) }.singleOrNull()?.let { return it.index }
        return fitting.singleOrNull()?.index
    }
}
