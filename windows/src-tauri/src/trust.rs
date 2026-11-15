//! What each device of the circle may do on this computer (P6), for the part Devices of Settings. The core checks it; this only reads
//! and changes it.

use serde_json::{Value, json};
use tandem_core::ffi::trust::{TandemPermission, TandemTrustLevel};
use tauri::{AppHandle, State};

use crate::events;
use crate::state::AppState;

type Reply<T> = Result<T, String>;

fn shown(e: impl std::fmt::Display) -> String {
    e.to_string()
}

/// The codes the window uses, the same as the core puts in a status.
const PERMISSIONS: [(TandemPermission, &str); 12] = [
    (TandemPermission::FilesWithoutAsking, "files_without_asking"),
    (TandemPermission::Clipboard, "clipboard"),
    (TandemPermission::Messages, "messages"),
    (TandemPermission::BrowseFiles, "browse_files"),
    (TandemPermission::DeleteFiles, "delete_files"),
    (TandemPermission::SeeNotifications, "see_notifications"),
    (TandemPermission::Control, "control"),
    (TandemPermission::Unlock, "unlock"),
    (TandemPermission::Lock, "lock"),
    (TandemPermission::WifiPassword, "wifi_password"),
    (TandemPermission::Calls, "calls"),
    (TandemPermission::Restart, "restart"),
];

pub fn code(permission: TandemPermission) -> &'static str {
    PERMISSIONS.iter().find(|(p, _)| *p == permission).map_or("", |(_, c)| c)
}

fn permission(code: &str) -> Reply<TandemPermission> {
    PERMISSIONS.iter().find(|(_, c)| *c == code).map(|(p, _)| *p).ok_or_else(|| format!("unknown permission {code}"))
}

fn level_code(level: TandemTrustLevel) -> &'static str {
    match level {
        TandemTrustLevel::Own => "own",
        TandemTrustLevel::Limited => "limited",
        TandemTrustLevel::SendOnly => "send",
        TandemTrustLevel::Custom => "custom",
    }
}

/// Per other device: what it may not do here, its level, and what it lets this computer do (null for a device of an older version).
fn list(state: &AppState) -> Reply<Vec<Value>> {
    let engine = state.engine()?;
    let me = engine.id();
    let web = engine.trust_web();
    let codes = |list: Vec<TandemPermission>| list.into_iter().map(code).collect::<Vec<_>>();
    engine
        .devices()
        .into_iter()
        .filter(|d| d.id != me)
        .map(|d| {
            let denied = engine.denied_permissions(d.id.clone()).map_err(shown)?;
            let level = engine.trust_level(d.id.clone()).map_err(shown)?;
            let allows_me = web.iter().find(|e| e.from == d.id && e.to == me).and_then(|e| e.denied.clone()).map(codes);
            Ok(json!({ "id": d.id, "denied": codes(denied), "level": level_code(level), "allowsMe": allows_me }))
        })
        .collect()
}

#[tauri::command]
pub fn trust_list(state: State<'_, AppState>) -> Reply<Vec<Value>> {
    list(&state)
}

#[tauri::command]
pub fn trust_set(app: AppHandle, state: State<'_, AppState>, id: String, permission: String, allowed: bool) -> Reply<Vec<Value>> {
    state.engine()?.set_permission(id, self::permission(&permission)?, allowed).map_err(shown)?;
    // Three of them are also the switches of the device page.
    events::refresh_devices(&app);
    list(&state)
}

#[tauri::command]
pub fn trust_set_level(app: AppHandle, state: State<'_, AppState>, id: String, level: String) -> Reply<Vec<Value>> {
    let level = match level.as_str() {
        "own" => TandemTrustLevel::Own,
        "limited" => TandemTrustLevel::Limited,
        "send" => TandemTrustLevel::SendOnly,
        other => return Err(format!("unknown level {other}")),
    };
    state.engine()?.set_trust_level(id, level).map_err(shown)?;
    events::refresh_devices(&app);
    list(&state)
}
