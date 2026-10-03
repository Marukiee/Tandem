use std::collections::HashMap;
use std::sync::{Arc, Mutex, RwLock};
use std::time::Instant;

use serde_json::Value;
use tandem_core::ffi::{TandemEngine, TandemMediaPlayer};

use crate::settings::Settings;

/// Everything the windows share. The engine is None until it has started.
#[derive(Default)]
pub struct AppState {
    pub engine: RwLock<Option<Arc<TandemEngine>>>,
    pub error: Mutex<Option<String>>,
    pub settings: Mutex<Settings>,
    pub data: Mutex<Data>,
    pub panel_shown: Mutex<Option<Instant>>,
    /// The height the content of the panel asked for, in points, kept while the panel is hidden.
    pub panel_height: Mutex<Option<f64>>,
    /// Where the click was that asked for a panel that is being made, until the panel is there to open.
    pub pending_panel: Mutex<Option<tauri::PhysicalPosition<f64>>>,
}

/// What has happened since Tandem started, kept so a window that opens later can show it.
#[derive(Default)]
pub struct Data {
    pub devices: Vec<Value>,
    pub transfers: Vec<Value>,
    pub offers: Vec<Value>,
    pub notifications: Vec<Value>,
    pub players: HashMap<String, Vec<TandemMediaPlayer>>,
    pub art: HashMap<u64, String>,
    /// The text this PC put on the clipboard itself, so it is not sent back to where it came from.
    pub applied_clipboard: Option<String>,
}

impl AppState {
    pub fn engine(&self) -> Result<Arc<TandemEngine>, String> {
        self.engine.read().unwrap().clone().ok_or_else(|| {
            self.error.lock().unwrap().clone().unwrap_or_else(|| "Tandem is still starting".to_string())
        })
    }
}
