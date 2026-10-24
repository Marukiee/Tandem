//! Focus the same on every device (see `focus.rs` in the core). This computer tells the core what its Do Not Disturb is, whatever turned
//! it on, and the core works out what is news for the others. When another device turns its Focus on or off, this one follows where its
//! desktop lets it (see `tandem_winsys::focus`).

use serde_json::{Value, json};
use tandem_core::focus::{CAP_FOLLOW, CAP_SHARE};
use tandem_winsys::focus;
use tauri::{AppHandle, Manager};

use crate::{settings, state::AppState};

/// What this computer can promise the others, for its hello.
pub fn caps() -> Vec<String> {
    let can = focus::abilities();
    let mut caps = Vec::new();
    if can.read {
        caps.push(CAP_SHARE.to_string());
    }
    if can.set {
        caps.push(CAP_FOLLOW.to_string());
    }
    caps
}

/// The switch as it is: on by default where the desktop has a Do Not Disturb that can be read.
pub fn on(app: &AppHandle) -> bool {
    settings::get(app).focus_share.unwrap_or_else(|| focus::abilities().read)
}

/// Says to the core what this computer does now, after a start and after the switch or the direction changed.
pub fn configure(app: &AppHandle) {
    let Ok(engine) = app.state::<AppState>().engine() else { return };
    let can = focus::abilities();
    let on = on(app);
    engine.set_focus_sharing(on && can.read && !settings::get(app).focus_follow_only, on && can.set);
    if !on {
        focus::release();
    }
}

pub fn start(app: &AppHandle) {
    configure(app);
    let Some(now) = focus::is_on() else { return };
    report(app, now);
    let app = app.clone();
    focus::watch(move |now| report(&app, now));
}

fn report(app: &AppHandle, now: bool) {
    if let Ok(engine) = app.state::<AppState>().engine() {
        tauri::async_runtime::block_on(engine.focus_local(now, String::new()));
    }
}

/// Another device turned its Focus on or off. The core only says so while this computer follows.
pub fn follow(on: bool) {
    if !focus::set(on) {
        log::warn!("could not turn Do Not Disturb {}", if on { "on" } else { "off" });
    }
}

/// What the desktop lets Tandem do, for the page of the settings.
#[tauri::command]
pub fn focus_info() -> Value {
    let can = focus::abilities();
    json!({ "read": can.read, "set": can.set })
}
