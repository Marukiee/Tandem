//! The few choices that belong to this PC. They live in a small file next to the identity of the device.

use std::path::PathBuf;

use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Manager};

use crate::state::AppState;

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct Settings {
    /// Where received files go. Empty is the Tandem folder inside Downloads.
    pub download_dir: String,
    /// The close button hides the window and leaves Tandem in the tray.
    pub close_to_tray: bool,
    /// A code in a text message from the phone goes straight to the clipboard.
    pub copy_codes: bool,
    /// Show the phone's notifications as Windows notifications.
    pub phone_notifications: bool,
    /// "auto" follows Windows, otherwise "en" or "nl".
    pub language: String,
    /// The phone's trackpad and keyboard move the pointer and type on this PC. Off until the person turns it on,
    /// because Windows has no permission to ask for: this is the only door.
    pub remote_input: bool,
    /// The computer whose screen sits next to this one for a shared mouse and keyboard, and on which side (left, right, top, bottom).
    pub share_device: String,
    pub share_edge: String,
    /// Quick Share: this PC can be found by other devices on the network and send and receive with them. Off until it is turned on.
    pub quick_share: bool,
    /// Look for a newer Tandem now and then, and say so when there is one.
    pub auto_update: bool,
    /// The version whose banner the person sent away with Later.
    pub dismissed_update: String,
    /// What the phone plays shows in the media controls of Windows (by the volume, on the lock screen), and the media
    /// keys of the keyboard control it.
    pub system_media: bool,
    /// Play the sound of a phone here when it sends it.
    pub phone_sound: bool,
}

impl Default for Settings {
    fn default() -> Self {
        Settings {
            download_dir: String::new(),
            close_to_tray: true,
            copy_codes: true,
            phone_notifications: true,
            language: "auto".into(),
            remote_input: false,
            share_device: String::new(),
            share_edge: String::new(),
            quick_share: false,
            system_media: true,
            phone_sound: true,
            auto_update: true,
            dismissed_update: String::new(),
        }
    }
}

fn file(app: &AppHandle) -> Option<PathBuf> {
    app.path().app_data_dir().ok().map(|dir| dir.join("settings.json"))
}

pub fn load(app: &AppHandle) {
    let Some(path) = file(app) else { return };
    let Ok(text) = std::fs::read_to_string(path) else { return };
    if let Ok(settings) = serde_json::from_str::<Settings>(&text) {
        *app.state::<AppState>().settings.lock().unwrap() = settings;
    }
}

pub fn save(app: &AppHandle) {
    let Some(path) = file(app) else { return };
    let settings = app.state::<AppState>().settings.lock().unwrap().clone();
    if let Some(dir) = path.parent() {
        let _ = std::fs::create_dir_all(dir);
    }
    if let Ok(text) = serde_json::to_string_pretty(&settings) {
        let _ = std::fs::write(path, text);
    }
}

pub fn set_dismissed_update(app: &AppHandle, version: &str) {
    app.state::<AppState>().settings.lock().unwrap().dismissed_update = version.to_string();
    save(app);
}

pub fn get(app: &AppHandle) -> Settings {
    app.state::<AppState>().settings.lock().unwrap().clone()
}

/// Where a file that arrives is put.
pub fn download_dir(app: &AppHandle) -> PathBuf {
    let chosen = get(app).download_dir;
    if !chosen.trim().is_empty() {
        return PathBuf::from(chosen);
    }
    app.path().download_dir().unwrap_or_else(|_| PathBuf::from(".")).join("Tandem")
}
