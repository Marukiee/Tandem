//! What the interface can ask of the app. Each command is one thing a person can do: pair, send, accept, change a
//! choice. The interface never touches the engine itself.

use std::path::PathBuf;

use serde_json::{Value, json};
use tandem_core::ffi::{TandemMediaAction, TandemOutgoingFile, TandemShareOrigin, TandemSoundMode};
use tauri::{AppHandle, Emitter, Manager, State};
use tauri_plugin_autostart::ManagerExt as _;
use tauri_plugin_dialog::DialogExt;
use tauri_plugin_opener::OpenerExt;

use crate::state::{AppState, Held};
use crate::{capture, clip, events, logfile, media, model, settings, tray, update};

type Reply<T> = Result<T, String>;

fn shown(e: impl std::fmt::Display) -> String {
    e.to_string()
}

/// Everything a window needs to draw itself from nothing.
#[tauri::command]
pub fn get_state(app: AppHandle, window: tauri::WebviewWindow, state: State<'_, AppState>) -> Value {
    let engine = state.engine.read().unwrap().clone();
    let data = state.data.held();
    json!({
        "ready": engine.is_some(),
        "error": state.error.held().clone(),
        // The battery of this computer is for the rings of the panel, read now: the panel asks again each time it opens.
        "self": engine.as_ref().map(|e| json!({ "id": e.id(), "name": e.name(), "port": e.port(),
            "battery": tandem_winsys::battery().map(|b| json!({ "level": b.level, "charging": b.charging, "powerSave": b.saver })) })),
        "openDevice": if window.label() == "main" { OPEN_DEVICE.held().take() } else { None },
        "version": app.package_info().version.to_string(),
        "devices": data.devices,
        "transfers": data.transfers,
        "offers": data.offers,
        "notifications": data.notifications,
        "settings": settings::get(&app),
        "autostart": app.autolaunch().is_enabled().unwrap_or(false),
        "downloadDir": settings::download_dir(&app).to_string_lossy(),
        "systemLanguage": sys_locale::get_locale().unwrap_or_default(),
        "build": option_env!("TANDEM_BUILD").unwrap_or(""),
        "update": update::current(&app),
        "canShare": capture::can_share(),
        "platform": if cfg!(windows) { "windows" } else { "linux" },
        "canHost": cfg!(feature = "screen-host") && can_host(),
        "canBluetooth": crate::bluetooth::available(),
        "canExtend": cfg!(feature = "screen-host") && can_extend(),
        "input": input_support(),
        "hasLid": crate::lid::present(),
        "canKeepAwake": crate::keepawake::available(),
        "canUnlock": crate::unlock::available(),
    })
}

/// Why this system will not let Tandem move the pointer and press keys for another device: "wayland" or "no-display", and
/// nothing when it does. Windows always does. Linux does under X11; under Wayland an app may not do it unasked, and the X11
/// compatibility layer that is there only reaches old-style windows, so it would look as if it works while it does not.
pub fn input_blocked() -> Option<&'static str> {
    if cfg!(windows) {
        return None;
    }
    #[cfg(target_os = "linux")]
    {
        // A Wayland desktop lets a program press keys and move the pointer through its portal, which asks the person once.
        if tandem_winsys::portal::is_wayland() {
            return if tandem_winsys::portal::input_available() { None } else { Some("wayland") };
        }
    }
    let session = std::env::var("XDG_SESSION_TYPE").unwrap_or_default().to_lowercase();
    if session == "wayland" || (session.is_empty() && std::env::var_os("WAYLAND_DISPLAY").is_some()) {
        Some("wayland")
    } else if std::env::var_os("DISPLAY").is_none() {
        Some("no-display")
    } else {
        None
    }
}

#[cfg(feature = "screen-host")]
fn can_host() -> bool {
    crate::host::available()
}

#[cfg(not(feature = "screen-host"))]
fn can_host() -> bool {
    false
}

/// Whether this computer can make a screen for another device to show (Linux under Wayland where the desktop offers it).
#[cfg(feature = "screen-host")]
pub(crate) fn can_extend() -> bool {
    crate::host::extend_available()
}

#[cfg(not(feature = "screen-host"))]
pub(crate) fn can_extend() -> bool {
    false
}

fn input_support() -> Value {
    match input_blocked() {
        Some(why) => json!({ "ok": false, "why": why }),
        None => json!({ "ok": true, "why": "" }),
    }
}

#[tauri::command]
pub fn get_players(app: AppHandle) -> Value {
    json!({ "players": events::players_json(&app), "art": events::art_json(&app) })
}

// ---- Pairing and devices ------------------------------------------------------------

fn qr_svg(text: &str) -> String {
    use qrcode::{QrCode, render::svg};
    QrCode::new(text.as_bytes())
        .map(|code| {
            code.render::<svg::Color>()
                .min_dimensions(240, 240)
                .dark_color(svg::Color("#1d1b3a"))
                .light_color(svg::Color("#ffffff"))
                .quiet_zone(true)
                .build()
        })
        .unwrap_or_default()
}

#[tauri::command]
pub fn create_pairing(state: State<'_, AppState>) -> Reply<Value> {
    let offer = state.engine()?.create_pairing_offer().map_err(shown)?;
    Ok(json!({ "uri": offer.uri, "code": offer.code, "expiresAtMs": offer.expires_at_ms, "qr": qr_svg(&offer.uri) }))
}

#[tauri::command]
pub fn cancel_pairing(state: State<'_, AppState>) {
    if let Ok(engine) = state.engine() {
        engine.cancel_pairing_offer();
    }
}

#[tauri::command]
pub async fn pair(state: State<'_, AppState>, uri: String) -> Reply<String> {
    state.engine()?.pair_with_uri(uri.trim().to_string()).await.map_err(shown)
}

#[tauri::command]
pub async fn remove_device(state: State<'_, AppState>, id: String) -> Reply<()> {
    state.engine()?.remove_device(id).await.map_err(shown)
}

#[tauri::command]
pub async fn rename_self(state: State<'_, AppState>, name: String) -> Reply<()> {
    state.engine()?.rename_self(name).await.map_err(shown)
}

/// What the nights say together. `offset` is how many minutes the clock of this computer is ahead of UTC.
#[tauri::command]
pub fn health_insights(state: State<'_, AppState>, offset: i32) -> Reply<serde_json::Value> {
    Ok(crate::model::health_insights(&state.engine()?.health_insights(offset)))
}

/// The `count` days that end with the day of `until` (milliseconds since 1970), with their sums: a week further back, or a month.
#[tauri::command]
pub fn health_range(state: State<'_, AppState>, until: u64, count: u32, offset: i32) -> Reply<serde_json::Value> {
    Ok(crate::model::health_range(&state.engine()?.health_range(until, count, offset)))
}

/// All nights, workouts and steps as a table a spreadsheet can open. None when the person cancelled.
#[tauri::command]
pub async fn health_export(app: AppHandle, state: State<'_, AppState>, offset: i32) -> Reply<Option<String>> {
    let csv = state.engine()?.health_export_csv(offset);
    let dialog = app.clone();
    let target = tauri::async_runtime::spawn_blocking(move || {
        dialog.dialog().file().add_filter("CSV", &["csv"]).set_file_name("tandem-health.csv").blocking_save_file()
    })
    .await
    .map_err(shown)?;
    let Some(path) = target.and_then(|t| t.into_path().ok()) else { return Ok(None) };
    std::fs::write(&path, csv).map_err(shown)?;
    Ok(Some(path.to_string_lossy().into_owned()))
}

/// The nights and workouts on this computer, the newest first.
#[tauri::command]
pub fn health_records(state: State<'_, AppState>) -> Reply<Vec<serde_json::Value>> {
    Ok(state.engine()?.health_records().iter().map(crate::model::health_record).collect())
}

#[tauri::command]
pub fn set_device_settings(
    app: AppHandle,
    state: State<'_, AppState>,
    id: String,
    clipboard: bool,
    auto_accept: bool,
    notifications: bool,
) -> Reply<()> {
    state.engine()?.set_device_settings(id, clipboard, auto_accept, notifications).map_err(shown)?;
    events::refresh_devices(&app);
    Ok(())
}

/// Whether a device that is paired from now on starts as a guest: clipboard and notifications off, files asked about first.
#[tauri::command]
pub fn guests_by_default(state: State<'_, AppState>) -> Reply<bool> {
    Ok(state.engine()?.guests_by_default())
}

#[tauri::command]
pub fn set_guests_by_default(app: AppHandle, state: State<'_, AppState>, on: bool) -> Reply<()> {
    state.engine()?.set_guests_by_default(on).map_err(shown)?;
    events::refresh_devices(&app);
    Ok(())
}

/// Stops a transfer that is running, here and on the other device. `id` is the id of the row: "offer-index-in|out".
#[tauri::command]
pub async fn transfer_cancel(state: State<'_, AppState>, id: String, peer: String, incoming: bool) -> Reply<()> {
    let offer = id.split('-').next().and_then(|n| n.parse::<u64>().ok()).ok_or("that transfer is not known")?;
    state.engine()?.cancel_transfer(peer, offer, incoming).await.map_err(shown)
}

#[tauri::command]
pub async fn ring(state: State<'_, AppState>, id: String, on: bool) -> Reply<()> {
    state.engine()?.ring(id, on).await.map_err(shown)
}

/// The watch of a phone, which only that phone talks to.
#[tauri::command]
pub async fn ring_watch(state: State<'_, AppState>, id: String, on: bool) -> Reply<()> {
    state.engine()?.ring_watch(id, on).await.map_err(shown)
}

/// The heart rate of the watch of phone `id` now, or no longer: again every half minute while the page shows it.
#[tauri::command]
pub async fn watch_heart_live(state: State<'_, AppState>, id: String, on: bool) -> Reply<()> {
    state.engine()?.watch_heart_live(id, on).await.map_err(shown)
}

/// The phone keeps the name of its watch and tells everyone; empty is the plain name again.
#[tauri::command]
pub async fn rename_watch(state: State<'_, AppState>, id: String, name: String) -> Reply<()> {
    state.engine()?.rename_watch(id, name).await.map_err(shown)
}

/// The watch opens its mouse screen for this computer by itself, as the table mouse when `table`.
#[tauri::command]
pub async fn start_watch_mouse(state: State<'_, AppState>, id: String, table: Option<bool>) -> Reply<()> {
    state.engine()?.start_watch_mouse(id, table.unwrap_or(false)).await.map_err(shown)
}

/// The watch closes its mouse screen: this computer is done with it.
#[tauri::command]
pub async fn stop_watch_mouse(state: State<'_, AppState>, id: String) -> Reply<()> {
    state.engine()?.stop_watch_mouse(id).await.map_err(shown)
}

/// Pairing again is done on the phone.
#[tauri::command]
pub async fn unpair_watch(state: State<'_, AppState>, id: String) -> Reply<()> {
    state.engine()?.unpair_watch(id).await.map_err(shown)
}

/// What is set for the watch, kept on its phone: what is left out stays as it is. The phone's status says what came of it.
#[tauri::command]
pub async fn set_watch_settings(
    state: State<'_, AppState>,
    id: String,
    mouse: Option<bool>,
    sleep_quiet: Option<bool>,
    smart_wake: Option<bool>,
    heart_all_day: Option<bool>,
    step_goal: Option<u32>,
    wake_strength: Option<u8>,
) -> Reply<()> {
    let change = tandem_core::ffi::TandemWatchSettingsChange { mouse, sleep_quiet, smart_wake, heart_all_day, step_goal, wake_strength };
    state.engine()?.set_watch_settings(id, change).await.map_err(shown)
}

/// The button under the screens: the desktop may ask now for this computer's mouse and keyboard.
#[tauri::command]
pub fn capture_start(app: AppHandle) {
    capture::person_wants(&app);
}

/// Keep awake of another computer, switched from here.
#[tauri::command]
pub async fn lock_remote(state: State<'_, AppState>, id: String) -> Reply<()> {
    state.engine()?.lock(id).await.map_err(shown)
}

#[tauri::command]
pub async fn set_sound_mode(state: State<'_, AppState>, id: String, mode: String) -> Reply<()> {
    let mode = match mode.as_str() {
        "sound" => TandemSoundMode::Sound,
        "vibrate" => TandemSoundMode::Vibrate,
        "dnd" => TandemSoundMode::DoNotDisturb,
        _ => return Err("unknown sound mode".to_string()),
    };
    state.engine()?.set_sound_mode(id, mode).await.map_err(shown)
}

#[tauri::command]
pub async fn keep_awake_remote(state: State<'_, AppState>, id: String, on: bool) -> Reply<()> {
    state.engine()?.keep_awake(id, on).await.map_err(shown)
}

// ---- Sending ------------------------------------------------------------------------

fn mime_for(path: &std::path::Path) -> String {
    let ext = path.extension().and_then(|e| e.to_str()).unwrap_or("").to_lowercase();
    match ext.as_str() {
        "png" => "image/png",
        "jpg" | "jpeg" => "image/jpeg",
        "gif" => "image/gif",
        "webp" => "image/webp",
        "heic" => "image/heic",
        "pdf" => "application/pdf",
        "txt" | "md" | "log" => "text/plain",
        "html" | "htm" => "text/html",
        "json" => "application/json",
        "zip" => "application/zip",
        "mp3" => "audio/mpeg",
        "m4a" => "audio/mp4",
        "wav" => "audio/wav",
        "mp4" | "m4v" => "video/mp4",
        "mov" => "video/quicktime",
        "mkv" => "video/x-matroska",
        _ => "application/octet-stream",
    }
    .to_string()
}

pub async fn send_paths_to(state: &AppState, ids: Vec<String>, paths: Vec<PathBuf>) -> Reply<Value> {
    send_paths_with(state, ids, paths, TandemShareOrigin::Files).await
}

pub async fn send_paths_with(state: &AppState, ids: Vec<String>, paths: Vec<PathBuf>, origin: TandemShareOrigin) -> Reply<Value> {
    let engine = state.engine()?;
    let mut files = Vec::new();
    let mut folders = 0;
    for path in paths {
        let Ok(meta) = std::fs::metadata(&path) else { continue };
        if meta.is_dir() {
            folders += 1;
            continue;
        }
        files.push(TandemOutgoingFile {
            source: path.to_string_lossy().into_owned(),
            name: path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_else(|| "file".into()),
            size: meta.len(),
            mime: mime_for(&path),
        });
    }
    if files.is_empty() {
        return Ok(json!({ "sent": 0, "offline": 0, "folders": folders }));
    }
    let report = engine.send_files(ids, files, origin).await.map_err(shown)?;
    Ok(json!({ "sent": report.sent_to.len(), "offline": report.offline.len(), "folders": folders }))
}

#[tauri::command]
pub async fn send_paths(state: State<'_, AppState>, ids: Vec<String>, paths: Vec<String>) -> Reply<Value> {
    send_paths_to(&state, ids, paths.into_iter().map(PathBuf::from).collect()).await
}

#[tauri::command]
pub async fn pick_and_send(app: AppHandle, state: State<'_, AppState>, ids: Vec<String>) -> Reply<Value> {
    let picker = app.clone();
    let picked = tauri::async_runtime::spawn_blocking(move || picker.dialog().file().blocking_pick_files())
        .await
        .map_err(shown)?;
    let Some(files) = picked else { return Ok(json!({ "sent": 0, "offline": 0, "folders": 0 })) };
    let paths: Vec<PathBuf> = files.into_iter().filter_map(|f| f.into_path().ok()).collect();
    send_paths_to(&state, ids, paths).await
}

#[tauri::command]
pub async fn send_clipboard(state: State<'_, AppState>, ids: Vec<String>) -> Reply<usize> {
    let engine = state.engine()?;
    let text = tauri::async_runtime::spawn_blocking(clip::read).await.map_err(shown)?;
    let Some(text) = text else { return Err("no-text".to_string()) };
    let url = model::is_url(&text);
    let reached = engine.send_clipboard(ids, text, url).await.map_err(shown)?;
    Ok(reached.len())
}

// ---- Receiving ----------------------------------------------------------------------

fn forget_offer(app: &AppHandle, from: &str, offer: &str) {
    app.state::<AppState>().data.held().offers.retain(|o| !(o["from"] == from && o["offer"] == offer));
    let _ = tauri::Emitter::emit(app, "offer-gone", json!({ "from": from, "offer": offer }));
}

#[tauri::command]
pub fn accept_offer(app: AppHandle, state: State<'_, AppState>, from: String, offer: String) -> Reply<()> {
    let id: u64 = offer.parse().map_err(shown)?;
    state.engine()?.accept_offer(from.clone(), id).map_err(shown)?;
    forget_offer(&app, &from, &offer);
    Ok(())
}

#[tauri::command]
pub async fn decline_offer(app: AppHandle, state: State<'_, AppState>, from: String, offer: String) -> Reply<()> {
    let id: u64 = offer.parse().map_err(shown)?;
    state.engine()?.decline_offer(from.clone(), id).await.map_err(shown)?;
    forget_offer(&app, &from, &offer);
    Ok(())
}

// ---- Music and notifications --------------------------------------------------------

#[tauri::command]
pub async fn media_command(
    state: State<'_, AppState>,
    id: String,
    player: String,
    action: String,
    position_ms: Option<u64>,
) -> Reply<()> {
    let action = match action.as_str() {
        "play" => TandemMediaAction::Play,
        "pause" => TandemMediaAction::Pause,
        "toggle" => TandemMediaAction::Toggle,
        "next" => TandemMediaAction::Next,
        "previous" => TandemMediaAction::Previous,
        "seek" => TandemMediaAction::Seek,
        other => return Err(format!("unknown action {other}")),
    };
    state.engine()?.send_media_command(id, player, action, position_ms).await.map_err(shown)
}

#[tauri::command]
pub fn clear_notifications(state: State<'_, AppState>) {
    state.data.held().notifications.clear();
}

// ---- Settings and the shell ---------------------------------------------------------

#[tauri::command]
pub fn set_settings(app: AppHandle, state: State<'_, AppState>, patch: Value) -> Value {
    {
        let mut current = state.settings.held();
        if let Some(v) = patch["closeToTray"].as_bool() {
            current.close_to_tray = v;
        }
        if let Some(v) = patch["copyCodes"].as_bool() {
            current.copy_codes = v;
        }
        if let Some(v) = patch["phoneNotifications"].as_bool() {
            current.phone_notifications = v;
        }
        if let Some(v) = patch["batteryAlerts"].as_bool() {
            current.battery_alerts = v;
        }
        if let Some(v) = patch["pauseDuringCalls"].as_bool() {
            current.pause_during_calls = v;
        }
        if let Some(v) = patch["cameraToObs"].as_bool() {
            current.camera_to_obs = v;
            if let Ok(engine) = app.state::<AppState>().engine() {
                engine.set_camera_stream(if v { tandem_core::ffi::tandem_camera_stream_port() } else { 0 });
            }
        }
        if let Some(v) = patch["remoteInput"].as_bool() {
            if v && !current.remote_input && input_blocked().is_none() {
                crate::input::warm_up();
            }
            current.remote_input = v;
        }
        // A list of the devices that may, or nothing at all for everybody.
        if let Some(v) = patch.get("pointerAllowed") {
            current.pointer_allowed = v.as_array().map(|list| list.iter().filter_map(|d| d.as_str().map(str::to_string)).collect());
        }
        if let Some(v) = patch["shareDevice"].as_str() {
            current.share_device = v.to_string();
        }
        if let Some(v) = patch["shareEdge"].as_str() {
            if ["", "left", "right", "top", "bottom"].contains(&v) {
                current.share_edge = v.to_string();
            }
        }
        if let Some(v) = patch["quickShare"].as_bool() {
            current.quick_share = v;
        }
        if let Some(v) = patch["phoneSound"].as_bool() {
            current.phone_sound = v;
            if !v {
                crate::sound::stop_all(&app);
            }
        }
        if let Some(v) = patch["bleMessages"].as_bool() {
            current.ble_messages = v;
        }
        if let Some(v) = patch["autoTailscale"].as_bool() {
            current.auto_tailscale = v;
        }
        if let Some(v) = patch["clipHistory"].as_bool() {
            current.clip_history = v;
        }
        if let Some(v) = patch["clipLimit"].as_u64().filter(|v| [100, 250, 500, 1000].contains(v)) {
            current.clip_limit = v as u32;
        }
        if let Some(v) = patch["clipDays"].as_u64().filter(|v| [7, 30, 90, 365].contains(v)) {
            current.clip_days = v as u32;
        }
        if let Some(v) = patch["screenHost"].as_bool() {
            current.screen_host = v;
            if !v {
                #[cfg(feature = "screen-host")]
                crate::host::stop_all(&app);
            }
        }
        if let Some(v) = patch["soundDelay"].as_str().filter(|v| ["low", "normal", "smooth"].contains(v)) {
            current.sound_delay = v.to_string();
        }
        if let Some(v) = patch["screenSound"].as_str().filter(|v| ["there", "here"].contains(v)) {
            current.screen_sound = v.to_string();
        }
        if let Some(v) = patch["screenSoundAsk"].as_bool() {
            current.screen_sound_ask = v;
        }
        if patch["screenSoundForget"].as_bool() == Some(true) {
            current.screen_sound_devices.clear();
        }
        if let Some(v) = patch["pointerShare"].as_bool() {
            if v && !current.pointer_share {
                crate::side::mouse_turned_on(&app);
                crate::capture::person_wants(&app);
            }
            current.pointer_share = v;
        }
        if let Some(v) = patch["fileManagerSend"].as_bool() {
            current.file_manager_send = v;
        }
        if let Some(v) = patch["contactsSearch"].as_bool() {
            current.contacts_search = v;
        }
        let s = &mut *current;
        for (key, field) in [
            ("searchDevices", &mut s.search_devices),
            ("searchActions", &mut s.search_actions),
            ("searchMessages", &mut s.search_messages),
            ("searchClipboard", &mut s.search_clipboard),
            ("searchFiles", &mut s.search_files),
            ("searchSettings", &mut s.search_settings),
        ] {
            if let Some(v) = patch[key].as_bool() {
                *field = v;
            }
        }
        if let Some(v) = patch["keepWhenLidClosed"].as_bool() {
            current.keep_when_lid_closed = v;
        }
        if let Some(v) = patch["keepAwake"].as_bool() {
            current.keep_awake = v;
        }
        if let Some(v) = patch["keepAwakeLid"].as_bool() {
            current.keep_awake_lid = v;
        }
        if let Some(v) = patch["unlockFromAfar"].as_bool() {
            current.unlock_from_afar = v;
        }
        if let Some(v) = patch["systemMedia"].as_bool() {
            current.system_media = v;
        }
        if let Some(v) = patch["shareMedia"].as_bool() {
            current.share_media = v;
        }
        if let Some(v) = patch["autoUpdate"].as_bool() {
            current.auto_update = v;
        }
        if let Some(v) = patch["language"].as_str() {
            if ["auto", "en", "nl"].contains(&v) {
                current.language = v.to_string();
            }
        }
        if let Some(v) = patch["downloadDir"].as_str() {
            current.download_dir = v.to_string();
        }
        if let Some(v) = patch["focusShare"].as_bool() {
            current.focus_share = Some(v);
        }
        if let Some(v) = patch["focusFollowOnly"].as_bool() {
            current.focus_follow_only = v;
        }
        if let Some(v) = patch["sideCheck"].as_bool() {
            current.side_check = v;
        }
        if let Some(v) = patch["sideSound"].as_str().filter(|v| ["quiet", "audible"].contains(v)) {
            current.side_sound = v.to_string();
        }
        if let Some(v) = patch["sideCheckMinutes"].as_u64() {
            // 0 is never.
            current.side_check_minutes = (v as u32).min(60);
        }
        if let Some(v) = patch["tourSeen"].as_u64() {
            current.tour_seen = v as u32;
        }
        let ids = |v: &Value| v.as_array().map(|a| a.iter().filter_map(|x| x.as_str().map(String::from)).take(64).collect::<Vec<_>>());
        if let Some(v) = ids(&patch["tourLater"]) {
            current.tour_later = v;
        }
        if let Some(v) = ids(&patch["tourSkip"]) {
            current.tour_skip = v;
        }
        if let Some(v) = patch["tourRemindAt"].as_u64() {
            current.tour_remind_at = v;
        }
    }
    settings::save(&app);
    // The switch for the media controls takes effect at once.
    media::refresh(&app);
    capture::configure(&app);
    crate::quickshare::configure(&app);
    // The language is in the menus of the file manager too.
    crate::sendwith::refresh(&app);
    crate::contactsearch::refresh(&app);
    crate::focus::configure(&app);
    crate::keepawake::configure(&app);
    crate::unlock::announce(&app);
    json!(settings::get(&app))
}

#[tauri::command]
pub fn set_autostart(app: AppHandle, enabled: bool) -> Reply<bool> {
    let launcher = app.autolaunch();
    if enabled { launcher.enable() } else { launcher.disable() }.map_err(shown)?;
    Ok(launcher.is_enabled().unwrap_or(enabled))
}

#[tauri::command]
pub async fn choose_download_dir(app: AppHandle) -> Reply<Option<String>> {
    let picker = app.clone();
    let folder = tauri::async_runtime::spawn_blocking(move || picker.dialog().file().blocking_pick_folder())
        .await
        .map_err(shown)?;
    let Some(folder) = folder.and_then(|f| f.into_path().ok()) else { return Ok(None) };
    let text = folder.to_string_lossy().into_owned();
    app.state::<AppState>().settings.held().download_dir = text.clone();
    settings::save(&app);
    Ok(Some(text))
}

/// Files the app handed to a window that are not on the list of transfers: a file of another device opened from the page Files.
static HANDED_OUT: std::sync::Mutex<Vec<PathBuf>> = std::sync::Mutex::new(Vec::new());

/// Lets `open_path` open this file later, as one the app itself put there.
pub fn hand_out(path: &std::path::Path) {
    let mut list = HANDED_OUT.held();
    list.push(path.to_path_buf());
    // Only the last ones matter: they are what a person just opened.
    let excess = list.len().saturating_sub(64);
    list.drain(..excess);
}

/// Opens a file the app itself handed out: one on the list of transfers, one in the download folder, or one it just saved. Any other path
/// is refused, so a window that was made to ask for something else (a page that went wrong, a link) cannot start a program with it.
#[tauri::command]
pub fn open_path(app: AppHandle, path: String) -> Reply<()> {
    let mut known: Vec<PathBuf> = app.state::<AppState>().data.held().transfers.iter().filter_map(|t| t["location"].as_str()).map(PathBuf::from).collect();
    known.extend(HANDED_OUT.held().iter().cloned());
    if !may_open(std::path::Path::new(&path), &settings::download_dir(&app), &known) {
        log::warn!("refused to open a path the app did not hand out");
        return Err(crate::i18n::t(&app, "open_refused"));
    }
    app.opener().open_path(path, None::<&str>).map_err(shown)
}

/// Whether `path` is one of `known`, or lies inside `downloads`. Both sides are made whole first, so `..` or a link cannot lead out.
fn may_open(path: &std::path::Path, downloads: &std::path::Path, known: &[PathBuf]) -> bool {
    let Ok(path) = path.canonicalize() else { return false };
    if downloads.canonicalize().is_ok_and(|folder| path.starts_with(folder)) {
        return true;
    }
    known.iter().filter_map(|k| k.canonicalize().ok()).any(|k| k == path)
}

#[tauri::command]
pub fn reveal_path(app: AppHandle, path: String) -> Reply<()> {
    app.opener().reveal_item_in_dir(path).map_err(shown)
}

/// Takes transfers off the page Shared. One that is still running stays: it is stopped with its own button.
#[tauri::command]
pub fn transfers_remove(app: AppHandle, ids: Vec<String>) -> Reply<()> {
    let state = app.state::<AppState>();
    state.data.held().transfers.retain(|t| !(ids.iter().any(|id| t["id"] == id.as_str()) && t["state"] != "active"));
    Ok(())
}

/// Moves received files to the Trash (the Recycle Bin on Windows) and takes them off the list. A file that was sent is the person's
/// own original and is left alone. The answer is the ids that went; one that could not stays on the list.
#[tauri::command]
pub fn transfers_trash(app: AppHandle, ids: Vec<String>) -> Reply<Vec<String>> {
    let state = app.state::<AppState>();
    let found: Vec<(String, String)> = state
        .data
        .lock()
        .unwrap()
        .transfers
        .iter()
        .filter(|t| ids.iter().any(|id| t["id"] == id.as_str()) && t["incoming"] == true && t["state"] == "done")
        .filter_map(|t| Some((t["id"].as_str()?.to_string(), t["location"].as_str()?.to_string())))
        .collect();
    let moved: Vec<String> = found
        .into_iter()
        .filter(|(_, path)| to_trash(path).map_err(|e| log::warn!("could not move {path} to the trash: {e}")).is_ok())
        .map(|(id, _)| id)
        .collect();
    state.data.held().transfers.retain(|t| !moved.iter().any(|id| t["id"] == id.as_str()));
    Ok(moved)
}

/// Moves one file to the Trash with what the system has for it: `gio` on a Linux desktop, the Recycle Bin of Windows through
/// PowerShell. The path goes in an environment variable, never in the text of a command, so no name can become one.
fn to_trash(path: &str) -> Result<(), String> {
    #[cfg(target_os = "linux")]
    let mut command = {
        let mut command = std::process::Command::new("gio");
        command.args(["trash", "--", path]);
        command
    };
    #[cfg(windows)]
    let mut command = {
        use std::os::windows::process::CommandExt;
        let mut command = std::process::Command::new("powershell");
        command
            .args([
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                "Add-Type -AssemblyName Microsoft.VisualBasic; [Microsoft.VisualBasic.FileIO.FileSystem]::DeleteFile($env:TANDEM_TRASH, 'OnlyErrorDialogs', 'SendToRecycleBin')",
            ])
            .env("TANDEM_TRASH", path)
            // No console window flashing up.
            .creation_flags(0x0800_0000);
        command
    };
    #[cfg(not(any(target_os = "linux", windows)))]
    return Err(format!("no Trash for {path} here"));
    #[cfg(any(target_os = "linux", windows))]
    {
        let output = command.stdin(std::process::Stdio::null()).output().map_err(|e| e.to_string())?;
        if output.status.success() { Ok(()) } else { Err(String::from_utf8_lossy(&output.stderr).trim().to_string()) }
    }
}

#[tauri::command]
pub fn open_downloads(app: AppHandle) -> Reply<()> {
    let dir = settings::download_dir(&app);
    let _ = std::fs::create_dir_all(&dir);
    app.opener().open_path(dir.to_string_lossy().into_owned(), None::<&str>).map_err(shown)
}

/// The page the main window opens on when it is made, for a ring of the battery card in the panel clicked while there was no main
/// window to tell.
static OPEN_DEVICE: std::sync::Mutex<Option<String>> = std::sync::Mutex::new(None);

#[tauri::command]
pub fn show_main(app: AppHandle, device: Option<String>) {
    if let Some(id) = device {
        if app.get_webview_window("main").is_some() {
            let _ = app.emit_to("main", "open-device", id);
        } else {
            *OPEN_DEVICE.held() = Some(id);
        }
    }
    tray::show_main(&app);
}

#[tauri::command]
pub fn resize_panel(app: AppHandle, height: f64) {
    tray::resize_panel(&app, height);
}

/// A page of Tandem on GitHub, in the browser. Only those: the window has no business sending anyone elsewhere.
#[tauri::command]
pub fn open_url(app: AppHandle, url: String) -> Reply<()> {
    if !url.starts_with("https://github.com/Marukiee/Tandem") {
        return Err("That address is not opened from here".into());
    }
    app.opener().open_url(url, None::<&str>).map_err(shown)
}

/// Looks for a newer version now, on a button. Answers with how the update stands afterwards.
#[tauri::command]
pub async fn check_update(app: AppHandle) -> Value {
    let looking = app.clone();
    let _ = tauri::async_runtime::spawn_blocking(move || update::check(&looking, true)).await;
    update::current(&app)
}

#[tauri::command]
pub fn install_update(app: AppHandle) {
    update::install(&app);
}

#[tauri::command]
pub fn dismiss_update(app: AppHandle, version: String) {
    update::dismiss(&app, &version);
}

#[tauri::command]
pub fn open_logs(app: AppHandle) -> Reply<()> {
    let dir = logfile::folder(&app).ok_or("There is no folder for the log")?;
    std::fs::create_dir_all(&dir).map_err(shown)?;
    app.opener().open_path(dir.to_string_lossy(), None::<&str>).map_err(shown)
}

// ---- What is new, and what the desktop was asked (the backup is in `backup.rs`) ---------------------------------------------------------

const CHANGELOG: &str = include_str!("../../../changelog.json");

/// The newest versions with what was new in each, in both languages: the window picks its own.
#[tauri::command]
pub fn whats_new() -> Value {
    let all: Value = serde_json::from_str(CHANGELOG).unwrap_or(Value::Null);
    json!(all.as_array().map(|list| list.iter().take(15).cloned().collect::<Vec<_>>()).unwrap_or_default())
}

/// What the desktop was asked and said (the portals of a Wayland desktop), so the settings can show it and take it back.
#[tauri::command]
pub fn access_status(app: AppHandle) -> Value {
    let dir = app.path().app_data_dir().ok();
    let has = |name: &str| dir.as_ref().is_some_and(|d| d.join(name).exists());
    #[cfg(target_os = "linux")]
    let wayland = tandem_winsys::portal::is_wayland();
    #[cfg(not(target_os = "linux"))]
    let wayland = false;
    json!({
        "wayland": wayland,
        "inputAllowed": has("portal-input.token"),
        "screenAllowed": has("portal-screen.token"),
        "input": input_support(),
        "canHost": cfg!(feature = "screen-host") && can_host(),
    })
}

/// Takes back what the desktop was told: the next time it asks again.
#[tauri::command]
pub fn portal_forget(app: AppHandle) {
    if let Ok(dir) = app.path().app_data_dir() {
        for name in ["portal-input.token", "portal-screen.token"] {
            let _ = std::fs::remove_file(dir.join(name));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::may_open;
    use std::path::PathBuf;

    #[test]
    fn only_what_the_app_handed_out_is_opened() {
        let base = std::env::temp_dir().join(format!("tandem-open-test-{}", std::process::id()));
        let downloads = base.join("Downloads");
        std::fs::create_dir_all(downloads.join("sub")).unwrap();
        std::fs::write(downloads.join("sub").join("photo.jpg"), b"x").unwrap();
        std::fs::write(base.join("saved.txt"), b"x").unwrap();
        std::fs::write(base.join("other.sh"), b"x").unwrap();
        let known = vec![base.join("saved.txt")];
        assert!(may_open(&downloads.join("sub").join("photo.jpg"), &downloads, &known));
        assert!(may_open(&base.join("saved.txt"), &downloads, &known));
        assert!(!may_open(&base.join("other.sh"), &downloads, &known));
        // A way out of the download folder by `..` is seen for what it is.
        assert!(!may_open(&downloads.join("..").join("other.sh"), &downloads, &known));
        assert!(!may_open(&PathBuf::from("/no/such/file"), &downloads, &known));
        let _ = std::fs::remove_dir_all(&base);
    }
}
