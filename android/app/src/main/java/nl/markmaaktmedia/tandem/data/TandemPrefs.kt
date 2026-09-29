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
import nl.markmaaktmedia.tandem.ui.theme.Appearance
import nl.markmaaktmedia.tandem.ui.theme.ColourSeed
import nl.markmaaktmedia.tandem.ui.theme.PaletteStyle
import nl.markmaaktmedia.tandem.ui.theme.ThemeMode

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
            pureBlack = p[Keys.pureBlack] ?: true,
        )
    }

    val onboarded: Flow<Boolean> = data.map { it[Keys.onboarded] ?: false }
    val deviceName: Flow<String?> = data.map { it[Keys.deviceName] }
    val mirrorNotifications: Flow<Boolean> = data.map { it[Keys.mirrorNotifications] ?: true }
    val mirrorAllApps: Flow<Boolean> = data.map { it[Keys.mirrorAllApps] ?: false }
    val mirrorApps: Flow<Set<String>> = data.map { it[Keys.mirrorApps] ?: emptySet() }
    val screenshotPrompt: Flow<Boolean> = data.map { it[Keys.screenshotPrompt] ?: true }
    val callMirror: Flow<Boolean> = data.map { it[Keys.callMirror] ?: true }
    val hotspotForMac: Flow<Boolean> = data.map { it[Keys.hotspotForMac] ?: false }
    val autoUpdateCheck: Flow<Boolean> = data.map { it[Keys.autoUpdateCheck] ?: true }
    val lastUpdateCheck: Flow<Long> = data.map { it[Keys.lastUpdateCheck] ?: 0L }
    val copyCodes: Flow<Boolean> = data.map { it[Keys.copyCodes] ?: true }

    suspend fun snapshotMirrorApps(): Set<String> = mirrorApps.first()

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

    suspend fun setMirrorApp(packageName: String, enabled: Boolean) {
        context.store.edit { p ->
            val current = p[Keys.mirrorApps] ?: emptySet()
            p[Keys.mirrorApps] = if (enabled) current + packageName else current - packageName
        }
    }
}
