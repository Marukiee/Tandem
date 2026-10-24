//! Being found, and finding the others. A device that asks this computer to ring (`Ring`) gets the chosen sound of the system over and
//! over, loud, with a card that says who is looking, until it is stopped here (the card, a key or a click in it, the notification on
//! Linux), by the device that asked, or after two minutes. Whoever asked hears when it rings and when it stopped (`Ringing`). The Wi-Fi
//! this computer is on goes in its status, so another device can say where it was last seen.

use std::path::PathBuf;
use std::sync::Mutex;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Duration;

use serde_json::{Value, json};
use tandem_core::ffi::{TandemNetKind, TandemNetwork, TandemStatus};
use tandem_winsys::find as system;
use tauri::{AppHandle, Emitter, LogicalPosition, LogicalSize, Manager};

use crate::{events, i18n, settings, state::AppState};

pub const DURATION: Duration = Duration::from_secs(120);
const PREVIEW: Duration = Duration::from_secs(4);
const CARD_WIDTH: f64 = 392.0;
const CARD_HEIGHT: f64 = 196.0;

struct Ringing {
    asker: String,
    /// Which ask this is: the two minutes of an earlier ask do not end a later one.
    round: u64,
    before: Option<system::Before>,
    #[cfg(target_os = "linux")]
    notification: Option<u32>,
}

static RINGING: Mutex<Option<Ringing>> = Mutex::new(None);
static ROUND: AtomicU64 = AtomicU64::new(1);
static PREVIEWS: AtomicU64 = AtomicU64::new(0);

/// The sound that was picked in Settings, or the one of the system that is meant to be heard from another room.
fn chosen(app: &AppHandle) -> Option<PathBuf> {
    let picked = PathBuf::from(settings::get(app).ring_sound);
    if picked.is_file() { Some(picked) } else { system::default_sound() }
}

pub fn can_ring(app: &AppHandle) -> bool {
    chosen(app).is_some_and(|path| system::can_play(&path))
}

/// `Ring` from another device. The work happens on a thread of its own: raising the volume starts programs on Linux, and the events of
/// the engine should not wait for that.
pub fn ring(app: &AppHandle, from: String, on: bool) {
    let app = app.clone();
    std::thread::spawn(move || if on { start(&app, &from) } else { stop(&app) });
}

fn start(app: &AppHandle, from: &str) {
    let asker = events::device_name(app, from);
    let round = ROUND.fetch_add(1, Ordering::SeqCst);
    {
        let mut ringing = RINGING.lock().unwrap();
        match ringing.as_mut() {
            Some(already) => {
                already.asker = asker.clone();
                already.round = round;
            }
            None => {
                let before = system::raise(0.75);
                let played = chosen(app).is_some_and(|path| system::play(&path, false));
                if !played {
                    log::warn!("no sound could be played to be found; the card shows anyway");
                }
                *ringing = Some(Ringing {
                    asker: asker.clone(),
                    round,
                    before,
                    #[cfg(target_os = "linux")]
                    notification: None,
                });
            }
        }
    }
    let title = i18n::t1(app, "find_title", &asker);
    let body = i18n::t(app, "find_body");
    // A window cannot always come to the front by itself (Wayland), so the desktop is told too.
    #[cfg(target_os = "linux")]
    {
        let stopper = app.clone();
        let stop_label = i18n::t(app, "find_stop");
        let number = tandem_winsys::notify::ask(&title, &body, &[("stop", stop_label.as_str())], move |_| stop(&stopper));
        let old = RINGING.lock().unwrap().as_mut().and_then(|r| std::mem::replace(&mut r.notification, number));
        if let Some(old) = old {
            tandem_winsys::notify::close(old);
        }
    }
    #[cfg(not(target_os = "linux"))]
    events::toast(app, &title, &body);
    let _ = app.emit("find-card", card_text(app));
    let handle = app.clone();
    let _ = app.run_on_main_thread(move || card(&handle, true));
    report(app, true);

    let later = app.clone();
    std::thread::spawn(move || {
        std::thread::sleep(DURATION);
        if RINGING.lock().unwrap().as_ref().is_some_and(|r| r.round == round) {
            stop(&later);
        }
    });
}

pub fn stop(app: &AppHandle) {
    let Some(ended) = RINGING.lock().unwrap().take() else { return };
    system::stop();
    if let Some(before) = ended.before {
        system::restore(before);
    }
    #[cfg(target_os = "linux")]
    if let Some(number) = ended.notification {
        tandem_winsys::notify::close(number);
    }
    let handle = app.clone();
    let _ = app.run_on_main_thread(move || card(&handle, false));
    report(app, false);
}

fn report(app: &AppHandle, on: bool) {
    if let Ok(engine) = app.state::<AppState>().engine() {
        tauri::async_runtime::spawn(async move { engine.ringing(on).await });
    }
}

fn card_text(app: &AppHandle) -> Value {
    let asker = RINGING.lock().unwrap().as_ref().map(|r| r.asker.clone());
    match asker {
        Some(asker) => json!({ "title": i18n::t1(app, "find_title", &asker), "body": i18n::t(app, "find_body"), "stop": i18n::t(app, "find_stop") }),
        None => Value::Null,
    }
}

/// The card is a small window in the bottom right corner, like the question whether a device may look at the screen.
fn card(app: &AppHandle, show: bool) {
    let existing = app.get_webview_window("find-card");
    if !show {
        if let Some(window) = existing {
            let _ = window.close();
        }
        return;
    }
    let window = match existing {
        Some(window) => window,
        None => {
            let built = tauri::WebviewWindowBuilder::new(app, "find-card", tauri::WebviewUrl::App("find.html".into()))
                .title("Tandem")
                .inner_size(CARD_WIDTH, CARD_HEIGHT)
                .decorations(false)
                .resizable(false)
                .always_on_top(true)
                .skip_taskbar(true)
                // Focused, so a key pressed by whoever found the computer reaches it and stops the sound.
                .focused(true)
                .build();
            let Ok(window) = built else { return };
            window
        }
    };
    let _ = window.set_size(LogicalSize::new(CARD_WIDTH, CARD_HEIGHT));
    if let Ok(Some(monitor)) = app.primary_monitor() {
        let scale = monitor.scale_factor();
        let size = monitor.size();
        let (width, height) = (size.width as f64 / scale, size.height as f64 / scale);
        let _ = window.set_position(LogicalPosition::new(width - CARD_WIDTH - 16.0, height - CARD_HEIGHT - 64.0));
    }
    let _ = window.set_focus();
}

#[tauri::command]
pub fn find_card(app: AppHandle) -> Value {
    card_text(&app)
}

#[tauri::command]
pub fn find_stop(app: AppHandle) {
    std::thread::spawn(move || stop(&app));
}

/// The sounds to pick from, and which one plays now.
#[tauri::command]
pub fn find_sounds(app: AppHandle) -> Value {
    let list: Vec<Value> = system::gallery()
        .into_iter()
        .map(|s| json!({ "path": s.path.to_string_lossy(), "name": s.name, "group": s.group }))
        .collect();
    json!({ "sounds": list, "chosen": chosen(&app).map(|p| p.to_string_lossy().into_owned()), "ready": can_ring(&app) })
}

/// Picks the sound, and plays it once so it can be heard.
#[tauri::command]
pub fn find_choose(app: AppHandle, path: String) {
    settings::update(&app, |s| s.ring_sound = path.clone());
    find_preview(path);
}

/// Plays a sound once, at the volume the computer is at, for a few seconds at most.
#[tauri::command]
pub fn find_preview(path: String) {
    if RINGING.lock().unwrap().is_some() {
        return;
    }
    let preview = PREVIEWS.fetch_add(1, Ordering::SeqCst) + 1;
    if !system::play(std::path::Path::new(&path), true) {
        return;
    }
    std::thread::spawn(move || {
        std::thread::sleep(PREVIEW);
        if PREVIEWS.load(Ordering::SeqCst) == preview && RINGING.lock().unwrap().is_none() {
            system::stop();
        }
    });
}

/// Says the Wi-Fi this computer is on in its status, when it changes. Looked at every minute.
pub fn start_whereabouts(app: &AppHandle) {
    let app = app.clone();
    std::thread::Builder::new()
        .name("tandem-wifi".into())
        .spawn(move || {
            let mut told: Option<Option<String>> = None;
            loop {
                let mut wait = Duration::from_secs(60);
                let now = system::wifi_name();
                // Nothing to say until a name is known; after that, also that it is gone, so the old one is not kept.
                if told.as_ref() != Some(&now) && (told.is_some() || now.is_some()) {
                    match app.state::<AppState>().engine() {
                        Ok(engine) => {
                            let network = TandemNetwork {
                                kind: if now.is_some() { TandemNetKind::Wifi } else { TandemNetKind::Other },
                                ssid: now.clone(),
                                metered: false,
                                roaming: false,
                                signal: None,
                            };
                            tauri::async_runtime::block_on(engine.update_status(TandemStatus { network: Some(network), ..Default::default() }));
                            told = Some(now);
                        }
                        // The engine is still starting.
                        Err(_) => wait = Duration::from_secs(3),
                    }
                }
                std::thread::sleep(wait);
            }
        })
        .ok();
}
