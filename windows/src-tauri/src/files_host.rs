//! Which folders of this computer other devices of the circle may look at, and what they may do there. The choices are kept and
//! enforced by the core (see `files.rs` in the core); this file only shows them and changes them, for all devices at once.

use serde_json::{Value, json};
use tandem_core::ffi::{TandemFilePolicy, TandemShare};
use tauri::{AppHandle, State};
use tauri_plugin_dialog::DialogExt;

use crate::state::AppState;

type Reply<T> = Result<T, String>;

fn as_json(policy: &TandemFilePolicy) -> Value {
    json!({
        "enabled": policy.enabled,
        "write": policy.write,
        "delete": policy.delete,
        "hidden": policy.hidden,
        "shares": policy.shares.iter().map(|s| json!({ "name": s.name, "path": s.path, "write": s.write })).collect::<Vec<_>>(),
    })
}

#[tauri::command]
pub fn files_policy(state: State<'_, AppState>) -> Reply<Value> {
    Ok(as_json(&state.engine()?.file_default_policy()))
}

/// A change to the choices: `enabled`, `write`, `delete`, `hidden`, `removeShare` (an index) or `shareWrite` (an index and a bool).
#[tauri::command]
pub fn files_update(state: State<'_, AppState>, patch: Value) -> Reply<Value> {
    let engine = state.engine()?;
    let mut policy = engine.file_default_policy();
    if let Some(v) = patch["enabled"].as_bool() {
        policy.enabled = v;
    }
    if let Some(v) = patch["write"].as_bool() {
        policy.write = v;
    }
    if let Some(v) = patch["delete"].as_bool() {
        policy.delete = v;
    }
    if let Some(v) = patch["hidden"].as_bool() {
        policy.hidden = v;
    }
    if let Some(at) = patch["removeShare"].as_u64() {
        if (at as usize) < policy.shares.len() {
            policy.shares.remove(at as usize);
        }
    }
    if let (Some(at), Some(write)) = (patch["shareWrite"]["index"].as_u64(), patch["shareWrite"]["write"].as_bool()) {
        if let Some(share) = policy.shares.get_mut(at as usize) {
            share.write = write;
        }
    }
    engine.set_file_default_policy(policy.clone()).map_err(|e| e.to_string())?;
    Ok(as_json(&policy))
}

/// Asks for a folder and offers it. The name it goes by is the name of the folder, made different from the others when it has to be.
#[tauri::command]
pub async fn files_add_folder(app: AppHandle, state: State<'_, AppState>) -> Reply<Value> {
    let picker = app.clone();
    let folder = tauri::async_runtime::spawn_blocking(move || picker.dialog().file().blocking_pick_folder())
        .await
        .map_err(|e| e.to_string())?;
    let engine = state.engine()?;
    let mut policy = engine.file_default_policy();
    let Some(path) = folder.and_then(|f| f.into_path().ok()) else { return Ok(as_json(&policy)) };
    let path_text = path.to_string_lossy().into_owned();
    if policy.shares.iter().any(|s| s.path == path_text) {
        return Ok(as_json(&policy));
    }
    let base = path.file_name().map(|n| n.to_string_lossy().into_owned()).filter(|n| !n.is_empty()).unwrap_or_else(|| "Folder".into());
    let mut name = base.clone();
    let mut n = 2;
    while policy.shares.iter().any(|s| s.name == name) {
        name = format!("{base} {n}");
        n += 1;
    }
    policy.shares.push(TandemShare { name, path: path_text, write: false });
    // Offering a folder is the point of adding it, so what is offered is on.
    policy.enabled = true;
    engine.set_file_default_policy(policy.clone()).map_err(|e| e.to_string())?;
    Ok(as_json(&policy))
}
