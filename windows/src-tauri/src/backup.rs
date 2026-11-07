//! One backup file with the settings of all devices of the person.
//!
//! Each device writes its own part: what it is (name, platform, model) and its own settings, in the words of its own app. The one
//! that makes the backup asks the connected devices for theirs. A computer that was reset gets a new identity and so a new id, so it
//! finds itself again in the file by its host name, else by its name; when that is not clear the person picks. Identities, the circle
//! and passwords are never in it.

use std::collections::HashMap;
use std::sync::{LazyLock, Mutex};
use std::time::Duration;

use serde_json::{Value, json};
use tauri::{AppHandle, Manager};
use tauri_plugin_dialog::DialogExt;

use crate::state::AppState;
use crate::{capture, media, settings};

/// Whose settings these are. The Mac and the phone write "mac" and "android"; theirs do not fit here.
const KIND: &str = "desktop";
const FORMAT: u64 = 3;
/// What belongs to this machine, not to the person.
const LOCAL: [&str; 5] = ["downloadDir", "shareDevice", "shareEdge", "layout", "dismissedUpdate"];

/// The parts other devices sent, by the id of the ask and then by device.
static PARTS: LazyLock<Mutex<HashMap<u64, HashMap<String, String>>>> = LazyLock::new(|| Mutex::new(HashMap::new()));
/// The devices of the backup that is open, until the person picks one.
static OPENED: LazyLock<Mutex<Vec<Value>>> = LazyLock::new(|| Mutex::new(Vec::new()));

type Reply<T> = Result<T, String>;

fn shown(e: impl std::fmt::Display) -> String {
    e.to_string()
}

fn host_name() -> Option<String> {
    hostname::get().ok().and_then(|h| h.into_string().ok())
}

fn own_settings(app: &AppHandle) -> Value {
    let mut value = serde_json::to_value(settings::get(app)).unwrap_or(Value::Null);
    if let Some(map) = value.as_object_mut() {
        for key in LOCAL {
            map.remove(key);
        }
    }
    value
}

/// This computer's part of a backup.
pub fn entry(app: &AppHandle) -> Value {
    let engine = app.state::<AppState>().engine().ok();
    json!({
        "id": engine.as_ref().map(|e| e.id()).unwrap_or_default(),
        "name": engine.as_ref().map(|e| e.name()).unwrap_or_default(),
        "kind": KIND,
        "platform": if cfg!(windows) { "windows" } else { "linux" },
        "model": host_name(),
        "settings": own_settings(app),
    })
}

/// Another device makes a backup of all devices and asks for this computer's part.
pub fn asked(app: &AppHandle, from: String, id: u64) {
    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        let Ok(engine) = app.state::<AppState>().engine() else { return };
        let _ = engine.answer_backup(from, id, entry(&app).to_string()).await;
    });
}

pub fn part(from: String, id: u64, settings: String) {
    if let Some(parts) = PARTS.lock().unwrap().get_mut(&id) {
        parts.insert(from, settings);
    }
}

/// Asks the connected devices for their parts and waits for them, four seconds at most: one older than this never answers. Returns
/// the parts and the names of the devices that are not in them.
async fn collect(app: &AppHandle) -> (Vec<String>, Vec<String>) {
    let Ok(engine) = app.state::<AppState>().engine() else { return (vec![], vec![]) };
    let devices = engine.devices();
    let offline: Vec<String> = devices.iter().filter(|d| !d.online).map(|d| d.name.clone()).collect();
    let online: Vec<(String, String)> = devices.iter().filter(|d| d.online).map(|d| (d.id.clone(), d.name.clone())).collect();
    if online.is_empty() {
        return (vec![], offline);
    }
    let ask = rand_id();
    PARTS.lock().unwrap().insert(ask, HashMap::new());
    let reached = engine.ask_backup(online.iter().map(|(id, _)| id.clone()).collect(), ask).await.unwrap_or_default();
    let wanted = reached.len();
    let _ = tauri::async_runtime::spawn_blocking(move || {
        for _ in 0..40 {
            if PARTS.lock().unwrap().get(&ask).map_or(0, HashMap::len) >= wanted {
                break;
            }
            std::thread::sleep(Duration::from_millis(100));
        }
    })
    .await;
    let parts = PARTS.lock().unwrap().remove(&ask).unwrap_or_default();
    let mut missing = offline;
    missing.extend(online.iter().filter(|(id, _)| !parts.contains_key(id)).map(|(_, name)| name.clone()));
    (parts.into_values().collect(), missing)
}

fn rand_id() -> u64 {
    use std::hash::{BuildHasher, Hasher};
    let mut hasher = std::collections::hash_map::RandomState::new().build_hasher();
    hasher.write_u128(std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_nanos()));
    hasher.finish().max(1)
}

/// This computer's part and those of the others, which are kept as they came when they look like one.
pub fn file(mine: Value, others: &[String]) -> Value {
    let mut devices = vec![mine];
    for part in others {
        if let Ok(other) = serde_json::from_str::<Value>(part) {
            if other["settings"].is_object() && other["kind"].as_str().is_some_and(|k| !k.is_empty()) {
                devices.push(other);
            }
        }
    }
    json!({ "app": "Tandem", "format": FORMAT, "devices": devices })
}

/// The devices in a file, each with `kind`, `name`, `platform`, `model`, `id` and `settings`, or None when it is no backup. A file
/// of an older version holds one device.
pub fn devices(text: &str) -> Option<Vec<Value>> {
    let root: Value = serde_json::from_str(text).ok()?;
    let one = |kind: &str, platform: &str, name: Option<&str>, settings: &Value| {
        Some(vec![json!({ "kind": kind, "platform": platform, "name": name.unwrap_or(""), "settings": settings })])
    };
    if let (Some(list), Some(app)) = (root["devices"].as_array(), root["app"].as_str()) {
        if !app.eq_ignore_ascii_case("tandem") {
            return None;
        }
        let devices: Vec<Value> =
            list.iter().filter(|d| d["settings"].is_object() && d["kind"].as_str().is_some_and(|k| !k.is_empty())).cloned().collect();
        return (!devices.is_empty()).then_some(devices);
    }
    match root["app"].as_str() {
        Some("tandem") if root["settings"].is_object() => one("android", "android", root["settings"]["device_name"].as_str(), &root["settings"]),
        Some("Tandem") if root["settings"].is_object() => one("mac", "mac", root["settings"]["deviceName"].as_str(), &root["settings"]),
        // A file of this app from before: its settings themselves, without a wrapper.
        None if root.get("closeToTray").is_some() => one(KIND, if cfg!(windows) { "windows" } else { "linux" }, None, &root),
        _ => None,
    }
}

/// Which device of a backup is this computer: the same id when it kept its identity, else the one with the same host name, else
/// the one with the same name, else the only computer. Two that look alike leave the choice to the person.
pub fn recognise(devices: &[Value], my_id: &str, model: Option<&str>, name: &str) -> Option<usize> {
    let fitting: Vec<(usize, &Value)> = devices.iter().enumerate().filter(|(_, d)| d["kind"] == KIND).collect();
    if let Some((i, _)) = fitting.iter().find(|(_, d)| !my_id.is_empty() && d["id"] == my_id) {
        return Some(*i);
    }
    let only = |matches: Vec<usize>| (matches.len() == 1).then(|| matches[0]);
    if let Some(model) = model {
        if let Some(i) = only(fitting.iter().filter(|(_, d)| d["model"] == model).map(|(i, _)| *i).collect()) {
            return Some(i);
        }
    }
    let named = fitting.iter().filter(|(_, d)| d["name"].as_str().is_some_and(|n| n.eq_ignore_ascii_case(name))).map(|(i, _)| *i).collect();
    only(named).or_else(|| only(fitting.iter().map(|(i, _)| *i).collect()))
}

/// Saves the settings of this computer and of the connected devices in a file the person picks. Returns where, and who is not in it.
#[tauri::command]
pub async fn settings_export(app: AppHandle) -> Reply<Option<Value>> {
    let (parts, missing) = collect(&app).await;
    let dialog = app.clone();
    let target = tauri::async_runtime::spawn_blocking(move || {
        dialog.dialog().file().add_filter("Tandem", &["json"]).set_file_name("tandem-backup.json").blocking_save_file()
    })
    .await
    .map_err(shown)?;
    let Some(path) = target.and_then(|t| t.into_path().ok()) else { return Ok(None) };
    let value = file(entry(&app), &parts);
    std::fs::write(&path, serde_json::to_string_pretty(&value).map_err(shown)?).map_err(shown)?;
    Ok(Some(json!({ "path": path.to_string_lossy(), "missing": missing })))
}

/// Opens a backup: its devices (without their settings) and which one is this computer, for the person to confirm or change.
#[tauri::command]
pub async fn settings_import(app: AppHandle) -> Reply<Option<Value>> {
    let dialog = app.clone();
    let source = tauri::async_runtime::spawn_blocking(move || dialog.dialog().file().add_filter("Tandem", &["json"]).blocking_pick_file())
        .await
        .map_err(shown)?;
    let Some(path) = source.and_then(|s| s.into_path().ok()) else { return Ok(None) };
    let text = std::fs::read_to_string(&path).map_err(shown)?;
    let list = devices(&text).ok_or_else(|| "that is not a Tandem backup".to_string())?;
    let engine = app.state::<AppState>().engine().ok();
    let me = recognise(
        &list,
        &engine.as_ref().map(|e| e.id()).unwrap_or_default(),
        host_name().as_deref(),
        &engine.as_ref().map(|e| e.name()).unwrap_or_default(),
    );
    let shown_list: Vec<Value> = list
        .iter()
        .map(|d| json!({ "name": d["name"], "platform": d["platform"], "model": d["model"], "kind": d["kind"], "fits": d["kind"] == KIND }))
        .collect();
    *OPENED.lock().unwrap() = list;
    Ok(Some(json!({ "devices": shown_list, "recognised": me })))
}

/// Puts back the settings of the device of the opened backup the person picked. What is not in it stays as it is.
#[tauri::command]
pub fn settings_restore(app: AppHandle, index: usize) -> Reply<Value> {
    let incoming = {
        let opened = OPENED.lock().unwrap();
        let device = opened.get(index).ok_or("that backup is no longer open")?;
        if device["kind"] != KIND {
            return Err("those settings are of another kind of device".into());
        }
        device["settings"].clone()
    };
    {
        let state = app.state::<AppState>();
        let mut current = state.settings.lock().unwrap();
        let mut merged = serde_json::to_value(&*current).map_err(shown)?;
        if let (Some(base), Some(new)) = (merged.as_object_mut(), incoming.as_object()) {
            for (key, value) in new {
                if base.contains_key(key) && !LOCAL.contains(&key.as_str()) {
                    base.insert(key.clone(), value.clone());
                }
            }
        }
        *current = serde_json::from_value(merged).map_err(|_| "that file does not fit these settings".to_string())?;
    }
    OPENED.lock().unwrap().clear();
    settings::save(&app);
    media::refresh(&app);
    capture::configure(&app);
    Ok(json!(settings::get(&app)))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn me(id: &str, name: &str, model: &str) -> Value {
        json!({ "id": id, "name": name, "kind": KIND, "platform": "linux", "model": model, "settings": { "closeToTray": true } })
    }

    #[test]
    fn a_reset_computer_finds_itself_by_its_host_name_though_its_id_is_new() {
        let phone = r#"{"id":"P","name":"Xperia","kind":"android","platform":"android","settings":{}}"#.to_string();
        let backup = file(me("OLD", "Desk", "desk-host"), &[phone, "nope".into()]);
        let list = devices(&backup.to_string()).unwrap();
        assert_eq!(list.len(), 2);
        assert_eq!(recognise(&list, "NEW", Some("desk-host"), "Other"), Some(0));
        assert_eq!(recognise(&list[1..], "NEW", Some("desk-host"), "Desk"), None);
    }

    #[test]
    fn two_alike_are_told_apart_by_name_or_left_to_the_person() {
        let backup = file(me("A", "Work", "same"), &[me("B", "Home", "same").to_string()]);
        let list = devices(&backup.to_string()).unwrap();
        assert_eq!(recognise(&list, "NEW", Some("same"), "home"), Some(1));
        assert_eq!(recognise(&list, "NEW", Some("same"), "Laptop"), None);
        assert_eq!(recognise(&list, "B", None, ""), Some(1));
    }

    #[test]
    fn a_file_of_an_older_version_is_one_device_of_its_own_kind() {
        assert_eq!(devices(r#"{"closeToTray":true}"#).unwrap()[0]["kind"], KIND);
        assert_eq!(devices(r#"{"app":"tandem","format":1,"settings":{"device_name":"X"}}"#).unwrap()[0]["name"], "X");
        assert_eq!(devices(r#"{"app":"Tandem","format":2,"settings":{}}"#).unwrap()[0]["kind"], "mac");
        assert!(devices(r#"{"hello":1}"#).is_none());
    }
}
