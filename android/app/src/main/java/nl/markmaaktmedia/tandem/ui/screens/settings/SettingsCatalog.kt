package nl.markmaaktmedia.tandem.ui.screens.settings

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import nl.markmaaktmedia.tandem.R
import nl.markmaaktmedia.tandem.ui.Route
import nl.markmaaktmedia.tandem.ui.theme.TandemIcons

/** The pages of Settings that are made for this overview. The older screens keep their own routes. */
enum class SettingsPageId(val key: String) {
    Look("look"),
    Sharing("sharing"),
    Notifications("notifications"),
    Updates("updates"),
    About("about"),
    ;

    companion object {
        fun fromKey(key: String): SettingsPageId? = entries.firstOrNull { it.key == key }
    }
}

/** What the overview lists, in this order. A category that is one screen opens that screen. */
internal enum class SettingsCategory(
    @StringRes val title: Int,
    val icon: @Composable () -> Painter,
    val route: Route,
) {
    Look(R.string.settings_look, { TandemIcons.Palette }, Route.SettingsPage(SettingsPageId.Look)),
    Sharing(R.string.settings_cat_sharing, { TandemIcons.Devices }, Route.SettingsPage(SettingsPageId.Sharing)),
    Notifications(R.string.settings_notifications, { TandemIcons.Notifications }, Route.SettingsPage(SettingsPageId.Notifications)),
    Permissions(R.string.settings_access, { TandemIcons.Shield }, Route.Access),
    Hotspot(R.string.settings_hotspot, { TandemIcons.Hotspot }, Route.Hotspot),
    Files(R.string.files_title, { TandemIcons.Folder }, Route.FileAccess),
    Updates(R.string.settings_cat_updates, { TandemIcons.Update }, Route.SettingsPage(SettingsPageId.Updates)),
    About(R.string.settings_cat_about, { TandemIcons.Info }, Route.SettingsPage(SettingsPageId.About)),
}

/**
 * The keys of the rows a search can take you to. The catalog says "go to this key" and the page puts the same key on
 * its row, so they are written once, here.
 */
internal object FocusKeys {
    const val AppearanceMode = "appearance.mode"
    const val AppearanceBlack = "appearance.black"
    const val AppearanceAccent = "appearance.accent"
    const val AppearanceStyle = "appearance.style"
    const val Language = "look.language"

    const val PhoneName = "sharing.name"
    const val PhoneId = "sharing.id"
    const val Screenshot = "sharing.screenshot"
    const val Ble = "sharing.ble"
    const val ClipTile = "sharing.tile"
    const val MediaShare = "sharing.music"
    const val Speaker = "sharing.speaker"

    const val Mirror = "notif.mirror"
    const val Codes = "notif.codes"
    const val Calls = "notif.calls"

    const val PermNotifications = "perm.notifications"
    const val PermBattery = "perm.battery"
    const val PermPhotos = "perm.photos"
    const val PermListener = "perm.listener"
    const val PermPhone = "perm.phone"
    const val PermBluetooth = "perm.bluetooth"
    const val PermCamera = "perm.camera"
    const val PermInstall = "perm.install"

    const val HotspotSetup = "hotspot.setup"
    const val HotspotShare = "hotspot.share"
    const val HotspotRoaming = "hotspot.roaming"
    const val HotspotTest = "hotspot.test"
    const val HotspotUsage = "hotspot.usage"
    const val HotspotLimit = "hotspot.limit"
    const val HotspotMethod = "hotspot.method"
    const val HotspotGuide = "hotspot.guide"

    const val FilesPermission = "files.permission"
    const val FilesEnabled = "files.enabled"
    const val FilesWrite = "files.write"
    const val FilesDelete = "files.delete"
    const val FilesHidden = "files.hidden"
    const val FilesMax = "files.max"
    const val FilesFolder = "files.folder"
    const val FilesActivity = "files.activity"

    const val Version = "updates.version"
    const val AutoUpdate = "updates.auto"
    const val CheckUpdate = "updates.check"
    const val BackupExport = "backup.export"
    const val BackupImport = "backup.import"

    const val Source = "about.source"
    const val Reset = "about.reset"
}

/**
 * One setting that can be found. The words come from string resources (the title and the description are the very
 * strings the row shows, the keywords are one string per entry), so the search works in the language of the app.
 *
 * [route] is the page that opens and [focus] the row on it to scroll to. [via] is a page to put underneath, so that
 * Back from a leaf screen goes up to the page that normally leads to it instead of straight to the overview.
 * [page] names the leaf screen when the entry lives there rather than on the page of its category.
 */
internal class SettingsEntry(
    val id: String,
    @StringRes val title: Int,
    @StringRes val keywords: Int,
    val category: SettingsCategory,
    val route: Route,
    val icon: @Composable () -> Painter,
    @StringRes val subtitle: Int? = null,
    @StringRes val page: Int? = null,
    val focus: String? = null,
    val via: Route? = null,
)

internal object SettingsCatalog {
    private val look = Route.SettingsPage(SettingsPageId.Look)
    private val sharing = Route.SettingsPage(SettingsPageId.Sharing)
    private val notifications = Route.SettingsPage(SettingsPageId.Notifications)

    val all: List<SettingsEntry> = listOf(
        // Look and language
        SettingsEntry("look", R.string.settings_look, R.string.settings_kw_look, SettingsCategory.Look, look, { TandemIcons.Palette }),
        SettingsEntry(
            "look_appearance", R.string.settings_appearance, R.string.settings_kw_look_appearance, SettingsCategory.Look, Route.Appearance,
            { TandemIcons.Palette }, subtitle = R.string.settings_appearance_sub, via = look,
        ),
        SettingsEntry(
            "look_mode", R.string.appearance_mode, R.string.settings_kw_look_mode, SettingsCategory.Look, Route.Appearance,
            { TandemIcons.DarkMode }, page = R.string.settings_appearance, focus = FocusKeys.AppearanceMode, via = look,
        ),
        SettingsEntry(
            "look_black", R.string.appearance_black, R.string.settings_kw_look_black, SettingsCategory.Look, Route.Appearance,
            { TandemIcons.DarkMode }, subtitle = R.string.appearance_black_sub, page = R.string.settings_appearance, focus = FocusKeys.AppearanceBlack, via = look,
        ),
        SettingsEntry(
            "look_accent", R.string.appearance_accent, R.string.settings_kw_look_accent, SettingsCategory.Look, Route.Appearance,
            { TandemIcons.Palette }, page = R.string.settings_appearance, focus = FocusKeys.AppearanceAccent, via = look,
        ),
        SettingsEntry(
            "look_style", R.string.appearance_style, R.string.settings_kw_look_style, SettingsCategory.Look, Route.Appearance,
            { TandemIcons.Palette }, page = R.string.settings_appearance, focus = FocusKeys.AppearanceStyle, via = look,
        ),
        SettingsEntry(
            "look_language", R.string.settings_language, R.string.settings_kw_look_language, SettingsCategory.Look, look,
            { TandemIcons.Language }, focus = FocusKeys.Language,
        ),

        // Devices and sharing
        SettingsEntry("sharing", R.string.settings_cat_sharing, R.string.settings_kw_sharing, SettingsCategory.Sharing, sharing, { TandemIcons.Devices }),
        SettingsEntry(
            "sharing_name", R.string.settings_name, R.string.settings_kw_sharing_name, SettingsCategory.Sharing, sharing,
            { TandemIcons.Phone }, focus = FocusKeys.PhoneName,
        ),
        SettingsEntry(
            "sharing_id", R.string.settings_id, R.string.settings_kw_sharing_id, SettingsCategory.Sharing, sharing,
            { TandemIcons.Key }, focus = FocusKeys.PhoneId,
        ),
        SettingsEntry(
            "sharing_screenshot", R.string.settings_screenshot, R.string.settings_kw_sharing_screenshot, SettingsCategory.Sharing, sharing,
            { TandemIcons.Screenshot }, subtitle = R.string.settings_screenshot_sub, focus = FocusKeys.Screenshot,
        ),
        SettingsEntry(
            "sharing_ble", R.string.settings_ble_messages, R.string.settings_kw_sharing_ble, SettingsCategory.Sharing, sharing,
            { TandemIcons.Bluetooth }, subtitle = R.string.settings_ble_messages_sub, focus = FocusKeys.Ble,
        ),
        SettingsEntry(
            "sharing_tile", R.string.settings_clip_tile, R.string.settings_kw_sharing_tile, SettingsCategory.Sharing, sharing,
            { TandemIcons.Paste }, subtitle = R.string.settings_clip_tile_sub, focus = FocusKeys.ClipTile,
        ),
        SettingsEntry(
            "sharing_music", R.string.settings_media_share, R.string.settings_kw_sharing_music, SettingsCategory.Sharing, sharing,
            { TandemIcons.Music }, subtitle = R.string.settings_media_share_sub, focus = FocusKeys.MediaShare,
        ),
        SettingsEntry(
            "sharing_media_apps", R.string.settings_media_apps, R.string.settings_kw_sharing_media_apps, SettingsCategory.Sharing, Route.MediaApps,
            { TandemIcons.Devices }, subtitle = R.string.settings_media_apps_sub, via = sharing,
        ),
        SettingsEntry(
            "sharing_speaker", R.string.settings_audio_output, R.string.settings_kw_sharing_speaker, SettingsCategory.Sharing, sharing,
            { TandemIcons.VolumeUp }, subtitle = R.string.settings_audio_output_sub, focus = FocusKeys.Speaker,
        ),

        // Notifications and calls
        SettingsEntry("notif", R.string.settings_notifications, R.string.settings_kw_notif, SettingsCategory.Notifications, notifications, { TandemIcons.Notifications }),
        SettingsEntry(
            "notif_mirror", R.string.settings_mirror, R.string.settings_kw_notif_mirror, SettingsCategory.Notifications, notifications,
            { TandemIcons.Notifications }, subtitle = R.string.settings_mirror_sub, focus = FocusKeys.Mirror,
        ),
        SettingsEntry(
            "notif_apps", R.string.settings_mirror_apps, R.string.settings_kw_notif_apps, SettingsCategory.Notifications, Route.MirrorApps,
            { TandemIcons.Devices }, subtitle = R.string.settings_mirror_apps_sub, via = notifications,
        ),
        SettingsEntry(
            "notif_codes", R.string.settings_codes, R.string.settings_kw_notif_codes, SettingsCategory.Notifications, notifications,
            { TandemIcons.Key }, subtitle = R.string.settings_codes_sub, focus = FocusKeys.Codes,
        ),
        SettingsEntry(
            "notif_calls", R.string.settings_calls, R.string.settings_kw_notif_calls, SettingsCategory.Notifications, notifications,
            { TandemIcons.Call }, subtitle = R.string.settings_calls_sub, focus = FocusKeys.Calls,
        ),

        // Permissions
        SettingsEntry(
            "perm", R.string.settings_access_row, R.string.settings_kw_perm, SettingsCategory.Permissions, Route.Access,
            { TandemIcons.Shield }, subtitle = R.string.settings_access_sub,
        ),
        SettingsEntry(
            "perm_notifications", R.string.perm_notifications, R.string.settings_kw_perm_notifications, SettingsCategory.Permissions, Route.Access,
            { TandemIcons.Notifications }, subtitle = R.string.perm_notifications_why, focus = FocusKeys.PermNotifications,
        ),
        SettingsEntry(
            "perm_battery", R.string.perm_battery, R.string.settings_kw_perm_battery, SettingsCategory.Permissions, Route.Access,
            { TandemIcons.Battery }, subtitle = R.string.perm_battery_why, focus = FocusKeys.PermBattery,
        ),
        SettingsEntry(
            "perm_photos", R.string.perm_photos, R.string.settings_kw_perm_photos, SettingsCategory.Permissions, Route.Access,
            { TandemIcons.Screenshot }, subtitle = R.string.perm_photos_why, focus = FocusKeys.PermPhotos,
        ),
        SettingsEntry(
            "perm_listener", R.string.perm_listener, R.string.settings_kw_perm_listener, SettingsCategory.Permissions, Route.Access,
            { TandemIcons.Devices }, subtitle = R.string.perm_listener_why, focus = FocusKeys.PermListener,
        ),
        SettingsEntry(
            "perm_phone", R.string.perm_phone, R.string.settings_kw_perm_phone, SettingsCategory.Permissions, Route.Access,
            { TandemIcons.Call }, subtitle = R.string.perm_phone_why, focus = FocusKeys.PermPhone,
        ),
        SettingsEntry(
            "perm_bluetooth", R.string.perm_bluetooth, R.string.settings_kw_perm_bluetooth, SettingsCategory.Permissions, Route.Access,
            { TandemIcons.Bluetooth }, subtitle = R.string.perm_bluetooth_why, focus = FocusKeys.PermBluetooth,
        ),
        SettingsEntry(
            "perm_camera", R.string.perm_camera, R.string.settings_kw_perm_camera, SettingsCategory.Permissions, Route.Access,
            { TandemIcons.QrScan }, subtitle = R.string.perm_camera_why, focus = FocusKeys.PermCamera,
        ),
        SettingsEntry(
            "perm_install", R.string.perm_install, R.string.settings_kw_perm_install, SettingsCategory.Permissions, Route.Access,
            { TandemIcons.Update }, subtitle = R.string.perm_install_why, focus = FocusKeys.PermInstall,
        ),

        // Hotspot
        SettingsEntry(
            "hotspot", R.string.settings_hotspot, R.string.settings_kw_hotspot, SettingsCategory.Hotspot, Route.Hotspot,
            { TandemIcons.Hotspot }, subtitle = R.string.hotspot_page_intro,
        ),
        SettingsEntry(
            "hotspot_setup", R.string.hotspot_section_setup, R.string.settings_kw_hotspot_setup, SettingsCategory.Hotspot, Route.Hotspot,
            { TandemIcons.CheckCircle }, focus = FocusKeys.HotspotSetup,
        ),
        SettingsEntry(
            "hotspot_share", R.string.settings_hotspot_for_mac, R.string.settings_kw_hotspot_share, SettingsCategory.Hotspot, Route.Hotspot,
            { TandemIcons.Hotspot }, subtitle = R.string.settings_hotspot_for_mac_sub, focus = FocusKeys.HotspotShare,
        ),
        SettingsEntry(
            "hotspot_roaming", R.string.hotspot_roaming, R.string.settings_kw_hotspot_roaming, SettingsCategory.Hotspot, Route.Hotspot,
            { TandemIcons.Cellular }, subtitle = R.string.hotspot_roaming_sub, focus = FocusKeys.HotspotRoaming,
        ),
        SettingsEntry(
            "hotspot_test", R.string.hotspot_test, R.string.settings_kw_hotspot_test, SettingsCategory.Hotspot, Route.Hotspot,
            { TandemIcons.Refresh }, subtitle = R.string.hotspot_test_sub, focus = FocusKeys.HotspotTest,
        ),
        SettingsEntry(
            "hotspot_usage", R.string.hotspot_usage_title, R.string.settings_kw_hotspot_usage, SettingsCategory.Hotspot, Route.Hotspot,
            { TandemIcons.Cellular }, focus = FocusKeys.HotspotUsage,
        ),
        SettingsEntry(
            "hotspot_limit", R.string.hotspot_data_limit, R.string.settings_kw_hotspot_limit, SettingsCategory.Hotspot, Route.Hotspot,
            { TandemIcons.Cellular }, focus = FocusKeys.HotspotLimit,
        ),
        SettingsEntry(
            "hotspot_method", R.string.hotspot_method, R.string.settings_kw_hotspot_method, SettingsCategory.Hotspot, Route.Hotspot,
            { TandemIcons.Hotspot }, focus = FocusKeys.HotspotMethod,
        ),
        SettingsEntry(
            "hotspot_guide", R.string.hotspot_setup, R.string.settings_kw_hotspot_guide, SettingsCategory.Hotspot, Route.Hotspot,
            { TandemIcons.OpenInNew }, subtitle = R.string.hotspot_setup_sub, focus = FocusKeys.HotspotGuide,
        ),

        // Files
        SettingsEntry(
            "files", R.string.files_title, R.string.settings_kw_files, SettingsCategory.Files, Route.FileAccess,
            { TandemIcons.Folder }, subtitle = R.string.files_row_sub,
        ),
        SettingsEntry(
            "files_permission", R.string.files_permission_title, R.string.settings_kw_files_permission, SettingsCategory.Files, Route.FileAccess,
            { TandemIcons.Folder }, focus = FocusKeys.FilesPermission,
        ),
        SettingsEntry(
            "files_enabled", R.string.files_enabled, R.string.settings_kw_files_enabled, SettingsCategory.Files, Route.FileAccess,
            { TandemIcons.Folder }, subtitle = R.string.files_enabled_sub, focus = FocusKeys.FilesEnabled,
        ),
        SettingsEntry(
            "files_write", R.string.files_write, R.string.settings_kw_files_write, SettingsCategory.Files, Route.FileAccess,
            { TandemIcons.Upload }, subtitle = R.string.files_write_sub, focus = FocusKeys.FilesWrite,
        ),
        SettingsEntry(
            "files_delete", R.string.files_delete, R.string.settings_kw_files_delete, SettingsCategory.Files, Route.FileAccess,
            { TandemIcons.Delete }, subtitle = R.string.files_delete_sub, focus = FocusKeys.FilesDelete,
        ),
        SettingsEntry(
            "files_hidden", R.string.files_hidden, R.string.settings_kw_files_hidden, SettingsCategory.Files, Route.FileAccess,
            { TandemIcons.File }, subtitle = R.string.files_hidden_sub, focus = FocusKeys.FilesHidden,
        ),
        SettingsEntry(
            "files_max", R.string.files_max_upload, R.string.settings_kw_files_max, SettingsCategory.Files, Route.FileAccess,
            { TandemIcons.Download }, focus = FocusKeys.FilesMax,
        ),
        SettingsEntry(
            "files_folders", R.string.files_add_folder, R.string.settings_kw_files_folders, SettingsCategory.Files, Route.FileAccess,
            { TandemIcons.Add }, subtitle = R.string.files_add_folder_sub, focus = FocusKeys.FilesFolder,
        ),
        SettingsEntry(
            "files_activity", R.string.files_activity, R.string.settings_kw_files_activity, SettingsCategory.Files, Route.FileAccess,
            { TandemIcons.Info }, focus = FocusKeys.FilesActivity,
        ),

        // Updates and backup
        SettingsEntry("updates", R.string.settings_cat_updates, R.string.settings_kw_updates, SettingsCategory.Updates, Route.SettingsPage(SettingsPageId.Updates), { TandemIcons.Update }),
        SettingsEntry(
            "updates_version", R.string.settings_version, R.string.settings_kw_updates_version, SettingsCategory.Updates,
            Route.SettingsPage(SettingsPageId.Updates), { TandemIcons.Info }, focus = FocusKeys.Version,
        ),
        SettingsEntry(
            "updates_auto", R.string.settings_auto_update, R.string.settings_kw_updates_auto, SettingsCategory.Updates,
            Route.SettingsPage(SettingsPageId.Updates), { TandemIcons.Update }, subtitle = R.string.settings_auto_update_sub, focus = FocusKeys.AutoUpdate,
        ),
        SettingsEntry(
            "updates_check", R.string.settings_check_update, R.string.settings_kw_updates_check, SettingsCategory.Updates,
            Route.SettingsPage(SettingsPageId.Updates), { TandemIcons.Refresh }, focus = FocusKeys.CheckUpdate,
        ),
        SettingsEntry(
            "backup_export", R.string.backup_export, R.string.settings_kw_backup_export, SettingsCategory.Updates,
            Route.SettingsPage(SettingsPageId.Updates), { TandemIcons.Upload }, subtitle = R.string.backup_export_sub, focus = FocusKeys.BackupExport,
        ),
        SettingsEntry(
            "backup_import", R.string.backup_import, R.string.settings_kw_backup_import, SettingsCategory.Updates,
            Route.SettingsPage(SettingsPageId.Updates), { TandemIcons.Download }, subtitle = R.string.backup_import_sub, focus = FocusKeys.BackupImport,
        ),

        // About and developer
        SettingsEntry("about", R.string.settings_cat_about, R.string.settings_kw_about, SettingsCategory.About, Route.SettingsPage(SettingsPageId.About), { TandemIcons.Info }),
        SettingsEntry(
            "about_changelog", R.string.changelog_title, R.string.settings_kw_about_changelog, SettingsCategory.About, Route.Changelog,
            { TandemIcons.Update }, subtitle = R.string.changelog_sub, via = Route.SettingsPage(SettingsPageId.About),
        ),
        SettingsEntry(
            "about_source", R.string.settings_github, R.string.settings_kw_about_source, SettingsCategory.About,
            Route.SettingsPage(SettingsPageId.About), { TandemIcons.OpenInNew }, subtitle = R.string.settings_license, focus = FocusKeys.Source,
        ),
        SettingsEntry(
            "about_developer", R.string.dev_title, R.string.settings_kw_about_developer, SettingsCategory.About, Route.Developer,
            { TandemIcons.Info }, subtitle = R.string.dev_sub, via = Route.SettingsPage(SettingsPageId.About),
        ),
        SettingsEntry(
            "about_reset", R.string.settings_reset, R.string.settings_kw_about_reset, SettingsCategory.About,
            Route.SettingsPage(SettingsPageId.About), { TandemIcons.Restart }, subtitle = R.string.settings_reset_sub, focus = FocusKeys.Reset,
        ),
    )

    /** The catalog in the language shown now. Looked up by [SearchEntry.id] to find what to open. */
    fun index(context: Context): SettingsIndex {
        val entries = all.map { entry ->
            SearchEntry(
                id = entry.id,
                title = context.getString(entry.title),
                subtitle = entry.subtitle?.let(context::getString),
                keywords = SettingsSearch.parseKeywords(context.getString(entry.keywords)),
                category = context.getString(entry.category.title),
                page = entry.page?.let(context::getString),
            )
        }
        return SettingsIndex(entries, all.associateBy { it.id })
    }
}

internal class SettingsIndex(val entries: List<SearchEntry>, private val byId: Map<String, SettingsEntry>) {
    fun target(id: String): SettingsEntry? = byId[id]
}
