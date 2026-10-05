//! Quick Share on this PC (see docs/QUICKSHARE.md in the repository): other devices with Quick Share send files and links here, and
//! this PC sends to them. Off until the person turns it on, in Settings or in the menu of the tray icon, because while it is on
//! this PC can be found by everyone on the same network.
//!
//! The service is in the core. This is the part that shows it: the state for the windows, the card in the corner while someone
//! sends something, and the switch in the tray.

use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use serde::Serialize;
use serde_json::{Value, json};
use tandem_core::quickshare::service::{Peer, QuickShare, Sink};
use tandem_core::quickshare::transfer::{DeviceKind, Introduction, Outcome, Outgoing, Received, TextKind};
use tauri::menu::CheckMenuItem;
use tauri::{AppHandle, Emitter, LogicalPosition, LogicalSize, Manager, State, Wry};
use tauri_plugin_dialog::DialogExt;
use tauri_plugin_opener::OpenerExt;

use crate::{clip, i18n, settings, state::AppState};

static SERVICE: Mutex<Option<QuickShare>> = Mutex::new(None);
static VIEW: Mutex<View> = Mutex::new(View { peers: Vec::new(), incoming: Vec::new(), outgoing: Vec::new(), problem: None });
static TRAY: Mutex<Option<CheckMenuItem<Wry>>> = Mutex::new(None);

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct PeerView {
    id: String,
    name: String,
    kind: &'static str,
}

#[derive(Clone, Serialize)]
struct NamedView {
    name: String,
    size: u64,
}

#[derive(Clone, Serialize)]
struct TextView {
    kind: &'static str,
    title: String,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct InView {
    id: u64,
    sender: String,
    pin: String,
    files: Vec<NamedView>,
    texts: Vec<TextView>,
    accepted: bool,
    done: u64,
    total: u64,
    saved: Option<Vec<String>>,
    link: Option<String>,
    failure: Option<String>,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct OutView {
    id: u64,
    peer_name: String,
    pin: Option<String>,
    done: u64,
    total: u64,
    /// sending, sent, refused or failed.
    state: &'static str,
}

struct View {
    peers: Vec<PeerView>,
    incoming: Vec<InView>,
    outgoing: Vec<OutView>,
    problem: Option<String>,
}

fn kind_name(kind: DeviceKind) -> &'static str {
    match kind {
        DeviceKind::Phone => "phone",
        DeviceKind::Tablet => "tablet",
        DeviceKind::Laptop => "laptop",
        DeviceKind::Unknown => "other",
    }
}

fn text_kind_name(kind: TextKind) -> &'static str {
    match kind {
        TextKind::Url => "url",
        TextKind::Address => "address",
        TextKind::Phone => "phone",
        TextKind::Text => "text",
    }
}

fn snapshot(app: &AppHandle) -> Value {
    let view = VIEW.lock().unwrap();
    json!({
        "enabled": settings::get(app).quick_share,
        "peers": view.peers,
        "incoming": view.incoming,
        "outgoing": view.outgoing,
        "problem": view.problem,
    })
}

/// Tells the windows what changed, and shows or puts away the card in the corner.
fn refresh(app: &AppHandle) {
    let _ = app.emit("quickshare", snapshot(app));
    let count = VIEW.lock().unwrap().incoming.len();
    let handle = app.clone();
    let _ = app.run_on_main_thread(move || card(&handle, count));
    if let Some(item) = TRAY.lock().unwrap().as_ref() {
        let _ = item.set_checked(settings::get(app).quick_share);
    }
}

/// The card is a small window of its own in the bottom right corner, as high as the cards in it.
fn card(app: &AppHandle, count: usize) {
    let existing = app.get_webview_window("qs-card");
    if count == 0 {
        if let Some(window) = existing {
            let _ = window.close();
        }
        return;
    }
    let height = 24.0 + count as f64 * 208.0;
    match existing {
        Some(window) => {
            let _ = window.set_size(LogicalSize::new(392.0, height));
            place(app, &window, height);
        }
        None => {
            let built = tauri::WebviewWindowBuilder::new(app, "qs-card", tauri::WebviewUrl::App("qs.html".into()))
                .title("Quick Share")
                .inner_size(392.0, height)
                .decorations(false)
                .resizable(false)
                .always_on_top(true)
                .skip_taskbar(true)
                .focused(false)
                .build();
            if let Ok(window) = built {
                place(app, &window, height);
            }
        }
    }
}

fn place(app: &AppHandle, window: &tauri::WebviewWindow, height: f64) {
    let Ok(Some(monitor)) = app.primary_monitor() else { return };
    let scale = monitor.scale_factor();
    let size = monitor.size();
    let (width_points, height_points) = (size.width as f64 / scale, size.height as f64 / scale);
    let _ = window.set_position(LogicalPosition::new(width_points - 392.0 - 16.0, height_points - height - 64.0));
}

// ---- What the service says ----------------------------------------------------------------------------------------------------

struct Hears {
    app: AppHandle,
}

impl Sink for Hears {
    fn peer_found(&self, peer: Peer) {
        // A device that does not say who it is is not visible to everyone, and nothing can be sent to it.
        if peer.name.trim().is_empty() {
            return;
        }
        let found = PeerView { id: peer.id.clone(), name: peer.name.clone(), kind: kind_name(peer.kind) };
        {
            let mut view = VIEW.lock().unwrap();
            view.peers.retain(|p| p.id != found.id);
            view.peers.push(found);
        }
        refresh(&self.app);
    }

    fn peer_lost(&self, id: String) {
        VIEW.lock().unwrap().peers.retain(|p| p.id != id);
        refresh(&self.app);
    }

    fn incoming(&self, id: u64, introduction: Introduction) {
        let item = InView {
            id,
            sender: introduction.sender.clone(),
            pin: introduction.pin.clone(),
            total: introduction.files.iter().map(|f| f.size).sum(),
            files: introduction.files.iter().map(|f| NamedView { name: f.name.clone(), size: f.size }).collect(),
            texts: introduction.texts.iter().map(|t| TextView { kind: text_kind_name(t.kind), title: t.title.clone() }).collect(),
            accepted: false,
            done: 0,
            saved: None,
            link: None,
            failure: None,
        };
        VIEW.lock().unwrap().incoming.push(item);
        refresh(&self.app);
    }

    fn progress(&self, id: u64, done: u64, total: u64) {
        {
            let mut view = VIEW.lock().unwrap();
            if let Some(item) = view.incoming.iter_mut().find(|i| i.id == id) {
                item.done = done;
            }
            if let Some(item) = view.outgoing.iter_mut().find(|o| o.id == id) {
                item.done = done;
                item.total = total;
            }
        }
        refresh(&self.app);
    }

    fn received(&self, id: u64, received: Received) {
        let link = received.texts.iter().find(|t| t.kind == TextKind::Url).map(|t| t.text.trim().to_string());
        // What was sent as text goes on the clipboard, so it can be pasted at once.
        if let Some(last) = received.texts.last() {
            clip::apply(&self.app, &last.text);
        }
        {
            let mut view = VIEW.lock().unwrap();
            if let Some(item) = view.incoming.iter_mut().find(|i| i.id == id) {
                item.saved = Some(received.files.iter().map(|p| p.to_string_lossy().into_owned()).collect());
                item.done = item.total;
                item.link = link;
            }
        }
        refresh(&self.app);
        later(&self.app, id, 12);
    }

    fn pin(&self, id: u64, pin: String) {
        if let Some(item) = VIEW.lock().unwrap().outgoing.iter_mut().find(|o| o.id == id) {
            item.pin = Some(pin);
        }
        refresh(&self.app);
    }

    fn sent(&self, id: u64, outcome: Outcome) {
        if let Some(item) = VIEW.lock().unwrap().outgoing.iter_mut().find(|o| o.id == id) {
            item.state = if outcome == Outcome::Refused { "refused" } else { "sent" };
        }
        refresh(&self.app);
        later(&self.app, id, 8);
    }

    fn failed(&self, id: u64, reason: String) {
        {
            let mut view = VIEW.lock().unwrap();
            if let Some(item) = view.incoming.iter_mut().find(|i| i.id == id) {
                item.failure = Some(reason.clone());
            }
            if let Some(item) = view.outgoing.iter_mut().find(|o| o.id == id) {
                item.state = "failed";
            }
        }
        refresh(&self.app);
        later(&self.app, id, 8);
    }
}

/// Takes a finished transfer off the screen after a while.
fn later(app: &AppHandle, id: u64, seconds: u64) {
    let app = app.clone();
    std::thread::spawn(move || {
        std::thread::sleep(Duration::from_secs(seconds));
        remove(&app, id);
    });
}

fn remove(app: &AppHandle, id: u64) {
    {
        let mut view = VIEW.lock().unwrap();
        view.incoming.retain(|i| i.id != id);
        view.outgoing.retain(|o| o.id != id);
    }
    refresh(app);
}

// ---- Starting and stopping -------------------------------------------------------------------------------------------------

fn own_name(app: &AppHandle) -> String {
    app.state::<AppState>().engine().map(|e| e.name()).unwrap_or_else(|_| "Tandem".to_string())
}

fn own_kind() -> DeviceKind {
    DeviceKind::Laptop
}

/// Makes what is running match the setting. Called when the setting changes and when the engine is ready.
pub fn configure(app: &AppHandle) {
    let wanted = settings::get(app).quick_share;
    let running = SERVICE.lock().unwrap().is_some();
    if wanted && !running {
        let name = own_name(app);
        let sink = Arc::new(Hears { app: app.clone() });
        let handle = app.clone();
        tauri::async_runtime::spawn(async move {
            match QuickShare::start(&name, own_kind(), sink).await {
                Ok(service) => {
                    *SERVICE.lock().unwrap() = Some(service);
                    VIEW.lock().unwrap().problem = None;
                }
                Err(e) => VIEW.lock().unwrap().problem = Some(e.to_string()),
            }
            refresh(&handle);
        });
    } else if !wanted && running {
        if let Some(mut service) = SERVICE.lock().unwrap().take() {
            service.stop();
        }
        {
            let mut view = VIEW.lock().unwrap();
            view.peers.clear();
            view.incoming.clear();
        }
    }
    refresh(app);
}

/// The menu item of the tray: made once, kept so it can follow the setting.
pub fn tray_item(app: &AppHandle) -> tauri::Result<CheckMenuItem<Wry>> {
    let item = CheckMenuItem::with_id(app, "quickshare", i18n::t(app, "quick_share"), true, settings::get(app).quick_share, None::<&str>)?;
    *TRAY.lock().unwrap() = Some(item.clone());
    Ok(item)
}

/// A click on the item of the tray, or the switch in Settings.
pub fn toggle(app: &AppHandle) {
    let on = !settings::get(app).quick_share;
    set_enabled(app, on);
}

pub fn set_enabled(app: &AppHandle, on: bool) {
    app.state::<AppState>().settings.lock().unwrap().quick_share = on;
    settings::save(app);
    configure(app);
}

// ---- Commands -------------------------------------------------------------------------------------------------------------------

#[tauri::command]
pub fn qs_state(app: AppHandle) -> Value {
    snapshot(&app)
}

/// The answer of the person to an incoming transfer.
#[tauri::command]
pub fn qs_respond(app: AppHandle, id: u64, accept: bool) {
    let folder = if accept { Some(settings::download_dir(&app)) } else { None };
    if let Some(service) = SERVICE.lock().unwrap().as_ref() {
        service.respond(id, folder);
    }
    if accept {
        if let Some(item) = VIEW.lock().unwrap().incoming.iter_mut().find(|i| i.id == id) {
            item.accepted = true;
        }
        refresh(&app);
    } else {
        remove(&app, id);
    }
}

#[tauri::command]
pub fn qs_dismiss(app: AppHandle, id: u64) {
    remove(&app, id);
}

fn start_files(app: &AppHandle, peer: &str, paths: Vec<PathBuf>) -> Result<(), String> {
    let files: Vec<Outgoing> = paths.into_iter().map(Outgoing::from_path).collect::<std::io::Result<_>>().map_err(|e| e.to_string())?;
    let total: u64 = files.iter().map(|f| f.size).sum();
    let name = VIEW.lock().unwrap().peers.iter().find(|p| p.id == peer).map(|p| p.name.clone()).unwrap_or_default();
    let guard = SERVICE.lock().unwrap();
    let service = guard.as_ref().ok_or("Quick Share is off")?;
    let id = service.send(peer, &own_name(app), own_kind(), files).map_err(|e| e.to_string())?;
    VIEW.lock().unwrap().outgoing.push(OutView { id, peer_name: name, pin: None, done: 0, total, state: "sending" });
    drop(guard);
    refresh(app);
    Ok(())
}

/// Picks files with the dialog of the system and sends them to a device that was found.
#[tauri::command]
pub async fn qs_pick_and_send(app: AppHandle, peer: String) -> Result<(), String> {
    let picker = app.clone();
    let picked = tauri::async_runtime::spawn_blocking(move || picker.dialog().file().blocking_pick_files()).await.map_err(|e| e.to_string())?;
    let Some(files) = picked else { return Ok(()) };
    start_files(&app, &peer, files.into_iter().filter_map(|f| f.into_path().ok()).collect())
}

#[tauri::command]
pub fn qs_send_paths(app: AppHandle, peer: String, paths: Vec<String>) -> Result<(), String> {
    start_files(&app, &peer, paths.into_iter().map(PathBuf::from).collect())
}

/// Sends the text on the clipboard as a link or a note.
#[tauri::command]
pub async fn qs_send_clipboard(app: AppHandle, state: State<'_, AppState>, peer: String) -> Result<(), String> {
    let _ = state;
    let text = tauri::async_runtime::spawn_blocking(clip::read).await.map_err(|e| e.to_string())?;
    let Some(text) = text else { return Err("no-text".to_string()) };
    let name = VIEW.lock().unwrap().peers.iter().find(|p| p.id == peer).map(|p| p.name.clone()).unwrap_or_default();
    let guard = SERVICE.lock().unwrap();
    let service = guard.as_ref().ok_or("Quick Share is off")?;
    let id = service.send_text(&peer, &own_name(&app), own_kind(), text.clone()).map_err(|e| e.to_string())?;
    VIEW.lock().unwrap().outgoing.push(OutView { id, peer_name: name, pin: None, done: 0, total: text.len() as u64, state: "sending" });
    drop(guard);
    refresh(&app);
    Ok(())
}

/// Opens a link that came in, in the browser of the system.
#[tauri::command]
pub fn qs_open_link(app: AppHandle, url: String) -> Result<(), String> {
    if !(url.starts_with("http://") || url.starts_with("https://")) {
        return Err("only web links are opened".to_string());
    }
    app.opener().open_url(url, None::<&str>).map_err(|e| e.to_string())
}
