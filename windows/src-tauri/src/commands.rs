//! What the interface can ask of the app. Each command is one thing a person can do: pair, send, accept, change a
//! choice. The interface never touches the engine itself.

use std::path::PathBuf;

use serde_json::{Value, json};
use tandem_core::ffi::{TandemMediaAction, TandemOutgoingFile, TandemShareOrigin};
use tauri::{AppHandle, Manager, State};
use tauri_plugin_autostart::ManagerExt as _;
use tauri_plugin_dialog::DialogExt;
use tauri_plugin_opener::OpenerExt;

use crate::state::AppState;
use crate::{capture, clip, events, logfile, media, model, settings, tray, update};

type Reply<T> = Result<T, String>;

fn shown(e: impl std::fmt::Display) -> String {
    e.to_string()
}

/// Everything a window needs to draw itself from nothing.
#[tauri::command]
pub fn get_state(app: AppHandle, state: State<'_, AppState>) -> Value {
    let engine = state.engine.read().unwrap().clone();
    let data = state.data.lock().unwrap();
    json!({
        "ready": engine.is_some(),
        "error": state.error.lock().unwrap().clone(),
        "self": engine.as_ref().map(|e| json!({ "id": e.id(), "name": e.name(), "port": e.port() })),
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
        "canShare": cfg!(windows),
        "platform": if cfg!(windows) { "windows" } else { "linux" },
        "canHost": cfg!(feature = "screen-host") && can_host(),
        "input": input_support(),
        "hasLid": crate::lid::present(),
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

#[tauri::command]
pub async fn ring(state: State<'_, AppState>, id: String, on: bool) -> Reply<()> {
    state.engine()?.ring(id, on).await.map_err(shown)
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

#[tauri::command]
pub async fn send_text(state: State<'_, AppState>, ids: Vec<String>, text: String) -> Reply<usize> {
    let url = model::is_url(&text);
    let reached = state.engine()?.send_text(ids, text, url, false).await.map_err(shown)?;
    Ok(reached.len())
}

// ---- Receiving ----------------------------------------------------------------------

fn forget_offer(app: &AppHandle, from: &str, offer: &str) {
    app.state::<AppState>().data.lock().unwrap().offers.retain(|o| !(o["from"] == from && o["offer"] == offer));
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
pub async fn notification_action(
    state: State<'_, AppState>,
    id: String,
    key: String,
    button: String,
    reply: Option<String>,
    dismiss: bool,
) -> Reply<()> {
    state.engine()?.notification_action(id, key, button, reply, dismiss).await.map_err(shown)
}

#[tauri::command]
pub fn clear_notifications(state: State<'_, AppState>) {
    state.data.lock().unwrap().notifications.clear();
}

// ---- Settings and the shell ---------------------------------------------------------

#[tauri::command]
pub fn set_settings(app: AppHandle, state: State<'_, AppState>, patch: Value) -> Value {
    {
        let mut current = state.settings.lock().unwrap();
        if let Some(v) = patch["closeToTray"].as_bool() {
            current.close_to_tray = v;
        }
        if let Some(v) = patch["copyCodes"].as_bool() {
            current.copy_codes = v;
        }
        if let Some(v) = patch["phoneNotifications"].as_bool() {
            current.phone_notifications = v;
        }
        if let Some(v) = patch["remoteInput"].as_bool() {
            if v && !current.remote_input && input_blocked().is_none() {
                crate::input::warm_up();
            }
            current.remote_input = v;
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
                crate::sound::stop_all();
            }
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
        if let Some(v) = patch["keepWhenLidClosed"].as_bool() {
            current.keep_when_lid_closed = v;
        }
        if let Some(v) = patch["systemMedia"].as_bool() {
            current.system_media = v;
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
    }
    settings::save(&app);
    // The switch for the media controls takes effect at once.
    media::refresh(&app);
    capture::configure(&app);
    crate::quickshare::configure(&app);
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
    app.state::<AppState>().settings.lock().unwrap().download_dir = text.clone();
    settings::save(&app);
    Ok(Some(text))
}

#[tauri::command]
pub fn open_path(app: AppHandle, path: String) -> Reply<()> {
    app.opener().open_path(path, None::<&str>).map_err(shown)
}

#[tauri::command]
pub fn reveal_path(app: AppHandle, path: String) -> Reply<()> {
    app.opener().reveal_item_in_dir(path).map_err(shown)
}

#[tauri::command]
pub fn open_downloads(app: AppHandle) -> Reply<()> {
    let dir = settings::download_dir(&app);
    let _ = std::fs::create_dir_all(&dir);
    app.opener().open_path(dir.to_string_lossy().into_owned(), None::<&str>).map_err(shown)
}

#[tauri::command]
pub fn show_main(app: AppHandle) {
    tray::show_main(&app);
}

#[tauri::command]
pub fn hide_panel(app: AppHandle) {
    if let Some(panel) = app.get_webview_window("panel") {
        let _ = panel.hide();
    }
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

#[tauri::command]
pub fn quit_app(app: AppHandle) {
    tray::quit_app(&app);
}


// ---- Backup, what is new, and what the desktop was asked ---------------------------------------------------------

/// The preferences of this computer into a file the person picks. Not the identity, the circle or the folders that are offered: only
/// the settings of this window.
#[tauri::command]
pub async fn settings_export(app: AppHandle) -> Reply<Option<String>> {
    let dialog = app.clone();
    let target = tauri::async_runtime::spawn_blocking(move || {
        dialog.dialog().file().add_filter("Tandem", &["json"]).set_file_name("tandem-settings.json").blocking_save_file()
    })
    .await
    .map_err(shown)?;
    let Some(path) = target.and_then(|t| t.into_path().ok()) else { return Ok(None) };
    let mut value = serde_json::to_value(settings::get(&app)).map_err(shown)?;
    // What belongs to this machine, not to the person.
    if let Some(map) = value.as_object_mut() {
        for key in ["downloadDir", "shareDevice", "shareEdge", "layout", "dismissedUpdate"] {
            map.remove(key);
        }
    }
    std::fs::write(&path, serde_json::to_string_pretty(&value).map_err(shown)?).map_err(shown)?;
    Ok(Some(path.to_string_lossy().into_owned()))
}

/// Settings from a file made by `settings_export`. What is not in the file stays as it is.
#[tauri::command]
pub async fn settings_import(app: AppHandle) -> Reply<Value> {
    let dialog = app.clone();
    let source = tauri::async_runtime::spawn_blocking(move || dialog.dialog().file().add_filter("Tandem", &["json"]).blocking_pick_file())
        .await
        .map_err(shown)?;
    let Some(path) = source.and_then(|s| s.into_path().ok()) else { return Ok(json!(settings::get(&app))) };
    let text = std::fs::read_to_string(&path).map_err(shown)?;
    let incoming: Value = serde_json::from_str(&text).map_err(|_| "that is not a file of Tandem settings".to_string())?;
    {
        let state = app.state::<AppState>();
        let mut current = state.settings.lock().unwrap();
        let mut merged = serde_json::to_value(&*current).map_err(shown)?;
        if let (Some(base), Some(new)) = (merged.as_object_mut(), incoming.as_object()) {
            for (key, value) in new {
                if base.contains_key(key) && !["downloadDir", "shareDevice", "shareEdge", "layout", "dismissedUpdate"].contains(&key.as_str()) {
                    base.insert(key.clone(), value.clone());
                }
            }
        }
        *current = serde_json::from_value(merged).map_err(|_| "that file does not fit these settings".to_string())?;
    }
    settings::save(&app);
    media::refresh(&app);
    capture::configure(&app);
    Ok(json!(settings::get(&app)))
}

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
