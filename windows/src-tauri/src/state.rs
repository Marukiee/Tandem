use std::collections::HashMap;
use std::sync::{Arc, Mutex, MutexGuard, PoisonError, RwLock};
use std::time::Instant;

use serde_json::Value;
use tandem_core::ffi::{TandemEngine, TandemMediaPlayer};

use crate::settings::Settings;

/// A lock that is taken where events arrive (from the core, the tray, the windows). A panic while another thread held it leaves it
/// poisoned, and `lock().unwrap()` would then panic in every later event, which lays the whole app flat. The data is still whole enough
/// for what these locks guard (lists and settings), so the lock is taken all the same.
pub trait Held<T> {
    fn held(&self) -> MutexGuard<'_, T>;
}

impl<T> Held<T> for Mutex<T> {
    fn held(&self) -> MutexGuard<'_, T> {
        self.lock().unwrap_or_else(PoisonError::into_inner)
    }
}

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
        self.engine.read().unwrap_or_else(PoisonError::into_inner).clone().ok_or_else(|| {
            self.error.held().clone().unwrap_or_else(|| "Tandem is still starting".to_string())
        })
    }
}

#[cfg(test)]
mod tests {
    use super::Held;
    use std::sync::{Arc, Mutex};

    #[test]
    fn a_lock_stays_usable_after_a_panic_while_it_was_held() {
        let list = Arc::new(Mutex::new(vec![1]));
        let other = list.clone();
        let _ = std::thread::spawn(move || {
            let _guard = other.lock().unwrap();
            panic!("a handler failed");
        })
        .join();
        assert!(list.is_poisoned());
        list.held().push(2);
        assert_eq!(*list.held(), vec![1, 2]);
    }
}
