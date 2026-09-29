package nl.markmaaktmedia.tandem.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import nl.markmaaktmedia.tandem.ui.theme.Appearance
import nl.markmaaktmedia.tandem.ui.theme.ColourSeed
import nl.markmaaktmedia.tandem.ui.theme.PaletteStyle
import nl.markmaaktmedia.tandem.ui.theme.ThemeMode

/** Set by the first run and the update timer, not by a person. */
private val NotBackedUp = setOf("onboarded", "last_update_check")

private val Context.store: DataStore<Preferences> by preferencesDataStore(name = "tandem")

/** Everything a person can change, in one place. */
class TandemPrefs(private val context: Context) {

    private val data get() = context.store.data

    private object Keys {
        val onboarded = booleanPreferencesKey("onboarded")
        val themeMode = stringPreferencesKey("theme_mode")
        val seed = stringPreferencesKey("seed")
        val style = stringPreferencesKey("palette_style")
        val pureBlack = booleanPreferencesKey("pure_black")
        val deviceName = stringPreferencesKey("device_name")
        val mirrorNotifications = booleanPreferencesKey("mirror_notifications")
        val mirrorApps = stringSetPreferencesKey("mirror_apps")
        val mirrorAllApps = booleanPreferencesKey("mirror_all_apps")
        val mirrorExcluded = stringSetPreferencesKey("mirror_excluded")
        val screenshotPrompt = booleanPreferencesKey("screenshot_prompt")
        val callMirror = booleanPreferencesKey("call_mirror")
        val hotspotForMac = booleanPreferencesKey("hotspot_for_mac")
        val autoUpdateCheck = booleanPreferencesKey("auto_update_check")
        val lastUpdateCheck = longPreferencesKey("last_update_check")
        val copyCodes = booleanPreferencesKey("copy_codes")
    }

    val appearance: Flow<Appearance> = data.map { p ->
        Appearance(
            mode = p[Keys.themeMode]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            seed = ColourSeed.fromKey(p[Keys.seed]),
            style = PaletteStyle.fromKey(p[Keys.style]),
            pureBlack = p[Keys.pureBlack] ?: false,
        )
    }

    val onboarded: Flow<Boolean> = data.map { it[Keys.onboarded] ?: false }
    val deviceName: Flow<String?> = data.map { it[Keys.deviceName] }
    val mirrorNotifications: Flow<Boolean> = data.map { it[Keys.mirrorNotifications] ?: true }
    val mirrorAllApps: Flow<Boolean> = data.map { it[Keys.mirrorAllApps] ?: true }
    val mirrorApps: Flow<Set<String>> = data.map { it[Keys.mirrorApps] ?: emptySet() }
    /** With "all apps" on, these are the ones left out. */
    val mirrorExcluded: Flow<Set<String>> = data.map { it[Keys.mirrorExcluded] ?: emptySet() }
    val screenshotPrompt: Flow<Boolean> = data.map { it[Keys.screenshotPrompt] ?: true }
    val callMirror: Flow<Boolean> = data.map { it[Keys.callMirror] ?: true }
    val hotspotForMac: Flow<Boolean> = data.map { it[Keys.hotspotForMac] ?: false }
    val autoUpdateCheck: Flow<Boolean> = data.map { it[Keys.autoUpdateCheck] ?: true }
    val lastUpdateCheck: Flow<Long> = data.map { it[Keys.lastUpdateCheck] ?: 0L }
    val copyCodes: Flow<Boolean> = data.map { it[Keys.copyCodes] ?: true }

    /**
     * Every setting as JSON, for backup. Keys are read from the store itself so a new setting
     * is included without touching this. What is left out is either per-install state or
     * something that would be wrong on another phone: the identity and pairings live in the
     * engine's own storage and never pass through here.
     */
    suspend fun exportJson(language: String): String {
        val settings = JSONObject()
        for ((key, value) in data.first().asMap()) {
            if (key.name in NotBackedUp) continue
            when (value) {
                is Boolean, is String, is Long, is Int -> settings.put(key.name, value)
                is Set<*> -> settings.put(key.name, JSONArray(value.map { it.toString() }))
            }
        }
        return JSONObject().put("app", "tandem").put("format", 1).put("language", language).put("settings", settings).toString(2)
    }

    /** Applies a backup. Returns the language it asks for, or null when the file is not a Tandem backup. */
    suspend fun importJson(json: String): String? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (root.optString("app") != "tandem" || root.optInt("format") != 1) return null
        val settings = root.optJSONObject("settings") ?: return null
        context.store.edit { p ->
            val keys = settings.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                if (name in NotBackedUp) continue
                when (val value = settings.get(name)) {
                    is Boolean -> p[booleanPreferencesKey(name)] = value
                    is String -> p[stringPreferencesKey(name)] = value
                    is Number -> p[longPreferencesKey(name)] = value.toLong()
                    is JSONArray -> p[stringSetPreferencesKey(name)] = (0 until value.length()).map { value.getString(it) }.toSet()
                }
            }
        }
        return root.optString("language").ifBlank { "system" }
    }

    suspend fun snapshotMirrorApps(): Set<String> = mirrorApps.first()

    /** Whether notifications from this app go to the other devices, whichever mode is on. */
    suspend fun mirrors(packageName: String): Boolean {
        val p = data.first()
        return if (p[Keys.mirrorAllApps] ?: true) packageName !in (p[Keys.mirrorExcluded] ?: emptySet())
        else packageName in (p[Keys.mirrorApps] ?: emptySet())
    }

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        context.store.edit { it[key] = value }
    }

    suspend fun setOnboarded(value: Boolean) = set(Keys.onboarded, value)
    suspend fun setThemeMode(value: ThemeMode) = set(Keys.themeMode, value.name)
    suspend fun setSeed(value: ColourSeed) = set(Keys.seed, value.storageKey)
    suspend fun setStyle(value: PaletteStyle) = set(Keys.style, value.storageKey)
    suspend fun setPureBlack(value: Boolean) = set(Keys.pureBlack, value)
    suspend fun setDeviceName(value: String) = set(Keys.deviceName, value)
    suspend fun setMirrorNotifications(value: Boolean) = set(Keys.mirrorNotifications, value)
    suspend fun setMirrorAllApps(value: Boolean) = set(Keys.mirrorAllApps, value)
    suspend fun setScreenshotPrompt(value: Boolean) = set(Keys.screenshotPrompt, value)
    suspend fun setCallMirror(value: Boolean) = set(Keys.callMirror, value)
    suspend fun setHotspotForMac(value: Boolean) = set(Keys.hotspotForMac, value)
    suspend fun setAutoUpdateCheck(value: Boolean) = set(Keys.autoUpdateCheck, value)
    suspend fun setLastUpdateCheck(value: Long) = set(Keys.lastUpdateCheck, value)
    suspend fun setCopyCodes(value: Boolean) = set(Keys.copyCodes, value)

    /** Turns one app on or off. Which list changes depends on whether "all apps" is on. */
    suspend fun setMirrorApp(packageName: String, enabled: Boolean) {
        context.store.edit { p ->
            if (p[Keys.mirrorAllApps] ?: true) {
                val excluded = p[Keys.mirrorExcluded] ?: emptySet()
                p[Keys.mirrorExcluded] = if (enabled) excluded - packageName else excluded + packageName
            } else {
                val chosen = p[Keys.mirrorApps] ?: emptySet()
                p[Keys.mirrorApps] = if (enabled) chosen + packageName else chosen - packageName
            }
        }
    }
}
