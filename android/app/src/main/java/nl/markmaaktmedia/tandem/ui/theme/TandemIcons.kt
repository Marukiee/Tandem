package nl.markmaaktmedia.tandem.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import nl.markmaaktmedia.tandem.R

/**
 * Every icon in the app, in one place: Material Symbols Rounded as vector drawables,
 * never `Icons.Rounded`. The bundled Compose set is the older drawing, with squarer
 * joins, and next to a rounded typeface it reads as another app's icons.
 */
object TandemIcons {
    // Navigation
    val Devices: Painter @Composable get() = painterResource(R.drawable.sym_devices)
    val DevicesFilled: Painter @Composable get() = painterResource(R.drawable.sym_devices_filled)
    val Transfers: Painter @Composable get() = painterResource(R.drawable.sym_swap_vert)
    val TransfersFilled: Painter @Composable get() = painterResource(R.drawable.sym_swap_vert_filled)
    val Settings: Painter @Composable get() = painterResource(R.drawable.sym_tune)
    val SettingsFilled: Painter @Composable get() = painterResource(R.drawable.sym_tune_filled)

    // Chrome
    val Back: Painter @Composable get() = painterResource(R.drawable.sym_arrow_back)
    val Close: Painter @Composable get() = painterResource(R.drawable.sym_close)
    val Stop: Painter @Composable get() = painterResource(R.drawable.sym_stop)
    val Terminal: Painter @Composable get() = painterResource(R.drawable.sym_terminal)
    val More: Painter @Composable get() = painterResource(R.drawable.sym_more_vert)
    val ChevronRight: Painter @Composable get() = painterResource(R.drawable.sym_chevron_right)
    val ChevronDown: Painter @Composable get() = painterResource(R.drawable.sym_keyboard_arrow_down)
    val Refresh: Painter @Composable get() = painterResource(R.drawable.sym_refresh)
    val Delete: Painter @Composable get() = painterResource(R.drawable.sym_delete)
    val Add: Painter @Composable get() = painterResource(R.drawable.sym_add)
    val Check: Painter @Composable get() = painterResource(R.drawable.sym_check)
    val CheckCircle: Painter @Composable get() = painterResource(R.drawable.sym_check_circle)
    val CheckCircleFilled: Painter @Composable get() = painterResource(R.drawable.sym_check_circle_filled)
    val Help: Painter @Composable get() = painterResource(R.drawable.sym_question_mark)
    val Info: Painter @Composable get() = painterResource(R.drawable.sym_info)
    val Error: Painter @Composable get() = painterResource(R.drawable.sym_error)
    val OpenInNew: Painter @Composable get() = painterResource(R.drawable.sym_open_in_new)
    val Idea: Painter @Composable get() = painterResource(R.drawable.sym_info)
    val Copy: Painter @Composable get() = painterResource(R.drawable.sym_content_copy)
    val Paste: Painter @Composable get() = painterResource(R.drawable.sym_content_paste)
    val Link: Painter @Composable get() = painterResource(R.drawable.sym_link)

    // Devices
    val Phone: Painter @Composable get() = painterResource(R.drawable.sym_smartphone)
    /** Quick Share: a ring with two arrows. Our own mark, not the logo of Google. */
    val QuickShare: Painter @Composable get() = painterResource(R.drawable.sym_quick_share)
    val Laptop: Painter @Composable get() = painterResource(R.drawable.sym_laptop_mac)
    val Desktop: Painter @Composable get() = painterResource(R.drawable.sym_computer)
    val Windows: Painter @Composable get() = painterResource(R.drawable.sym_desktop_windows)
    val DesktopMac: Painter @Composable get() = painterResource(R.drawable.sym_desktop_mac)
    val Sleep: Painter @Composable get() = painterResource(R.drawable.sym_bedtime)
    val Power: Painter @Composable get() = painterResource(R.drawable.sym_power_settings_new)
    val Tablet: Painter @Composable get() = painterResource(R.drawable.sym_tablet_android)
    val Watch: Painter @Composable get() = painterResource(R.drawable.sym_watch)
    val Tv: Painter @Composable get() = painterResource(R.drawable.sym_tv)

    // Actions
    val Send: Painter @Composable get() = painterResource(R.drawable.sym_send)
    val Share: Painter @Composable get() = painterResource(R.drawable.sym_share)
    val Upload: Painter @Composable get() = painterResource(R.drawable.sym_upload)
    val Download: Painter @Composable get() = painterResource(R.drawable.sym_download)
    val Folder: Painter @Composable get() = painterResource(R.drawable.sym_folder_open)
    val Pin: Painter @Composable get() = painterResource(R.drawable.sym_push_pin)
    val QrScan: Painter @Composable get() = painterResource(R.drawable.sym_qr_code_scanner)
    val QrShow: Painter @Composable get() = painterResource(R.drawable.sym_qr_code_2)
    val Call: Painter @Composable get() = painterResource(R.drawable.sym_call)
    val CallEnd: Painter @Composable get() = painterResource(R.drawable.sym_call_end)
    val Ring: Painter @Composable get() = painterResource(R.drawable.sym_notifications_active)
    val Screenshot: Painter @Composable get() = painterResource(R.drawable.sym_screenshot)
    val Image: Painter @Composable get() = painterResource(R.drawable.sym_image)
    val Camera: Painter @Composable get() = painterResource(R.drawable.sym_photo_camera)
    val FlashOn: Painter @Composable get() = painterResource(R.drawable.sym_flash_on)
    val FlashOff: Painter @Composable get() = painterResource(R.drawable.sym_flash_off)
    val FlashAuto: Painter @Composable get() = painterResource(R.drawable.sym_flash_auto)
    val FlipCamera: Painter @Composable get() = painterResource(R.drawable.sym_cameraswitch)
    val File: Painter @Composable get() = painterResource(R.drawable.sym_description)

    // Status
    val Battery: Painter @Composable get() = painterResource(R.drawable.sym_battery_full)
    val Bolt: Painter @Composable get() = painterResource(R.drawable.sym_bolt)
    val BoltFilled: Painter @Composable get() = painterResource(R.drawable.sym_bolt_filled)
    val Wifi: Painter @Composable get() = painterResource(R.drawable.sym_wifi)
    val Cellular: Painter @Composable get() = painterResource(R.drawable.sym_signal_cellular_alt)
    val Hotspot: Painter @Composable get() = painterResource(R.drawable.sym_wifi_tethering)
    val Bluetooth: Painter @Composable get() = painterResource(R.drawable.sym_bluetooth)
    val Lan: Painter @Composable get() = painterResource(R.drawable.sym_lan)
    val Hub: Painter @Composable get() = painterResource(R.drawable.sym_hub)
    val Dnd: Painter @Composable get() = painterResource(R.drawable.sym_do_not_disturb_on)

    // Remote
    val Mouse: Painter @Composable get() = painterResource(R.drawable.sym_mouse)
    val Touch: Painter @Composable get() = painterResource(R.drawable.sym_touch_app)
    val FitScreen: Painter @Composable get() = painterResource(R.drawable.sym_fit_screen)
    val CloudOff: Painter @Composable get() = painterResource(R.drawable.sym_cloud_off)
    val Drag: Painter @Composable get() = painterResource(R.drawable.sym_drag_pan)
    val Keyboard: Painter @Composable get() = painterResource(R.drawable.sym_keyboard)
    val Backspace: Painter @Composable get() = painterResource(R.drawable.sym_backspace)
    val Enter: Painter @Composable get() = painterResource(R.drawable.sym_keyboard_return)
    val Tab: Painter @Composable get() = painterResource(R.drawable.sym_tab)
    val ArrowUp: Painter @Composable get() = painterResource(R.drawable.sym_keyboard_arrow_up)
    val ArrowLeft: Painter @Composable get() = painterResource(R.drawable.sym_keyboard_arrow_left)
    val ArrowRight: Painter @Composable get() = painterResource(R.drawable.sym_keyboard_arrow_right)
    val Music: Painter @Composable get() = painterResource(R.drawable.sym_music_note)
    val SkipNext: Painter @Composable get() = painterResource(R.drawable.sym_skip_next)
    val SkipPrevious: Painter @Composable get() = painterResource(R.drawable.sym_skip_previous)
    val Play: Painter @Composable get() = painterResource(R.drawable.sym_play_arrow)
    val Pause: Painter @Composable get() = painterResource(R.drawable.sym_pause)
    val Next: Painter @Composable get() = painterResource(R.drawable.sym_skip_next)
    val Previous: Painter @Composable get() = painterResource(R.drawable.sym_skip_previous)
    val VolumeUp: Painter @Composable get() = painterResource(R.drawable.sym_volume_up)
    val VolumeDown: Painter @Composable get() = painterResource(R.drawable.sym_volume_down)
    val VolumeOff: Painter @Composable get() = painterResource(R.drawable.sym_volume_off)

    // Keys of the remote keyboard. The arrows are the drawn ones, not the chevrons above,
    // which read as "expand" rather than as a key.
    val KeyUp: Painter @Composable get() = painterResource(R.drawable.sym_arrow_upward)
    val KeyDown: Painter @Composable get() = painterResource(R.drawable.sym_arrow_downward)
    val KeyLeft: Painter @Composable get() = painterResource(R.drawable.sym_arrow_back)
    val KeyRight: Painter @Composable get() = painterResource(R.drawable.sym_arrow_forward)
    val KeyShift: Painter @Composable get() = painterResource(R.drawable.sym_shift)
    val KeyControl: Painter @Composable get() = painterResource(R.drawable.sym_keyboard_control_key)
    val KeyOption: Painter @Composable get() = painterResource(R.drawable.sym_keyboard_option_key)
    val KeyCommand: Painter @Composable get() = painterResource(R.drawable.sym_keyboard_command_key)

    // Settings
    val Notifications: Painter @Composable get() = painterResource(R.drawable.sym_notifications)
    val Palette: Painter @Composable get() = painterResource(R.drawable.sym_palette)
    val DarkMode: Painter @Composable get() = painterResource(R.drawable.sym_dark_mode)
    val Language: Painter @Composable get() = painterResource(R.drawable.sym_language)
    val Key: Painter @Composable get() = painterResource(R.drawable.sym_key)
    val Shield: Painter @Composable get() = painterResource(R.drawable.sym_shield)
    val Update: Painter @Composable get() = painterResource(R.drawable.sym_system_update)
    val Sync: Painter @Composable get() = painterResource(R.drawable.sym_sync)
    val Person: Painter @Composable get() = painterResource(R.drawable.sym_person)
    val Restart: Painter @Composable get() = painterResource(R.drawable.sym_restart_alt)
}
