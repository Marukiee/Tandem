//! Keeps the clipboard of this PC and the clipboards of the other devices in step: what is copied here is sent, and
//! what arrives is put on the clipboard without being sent back.

use std::time::Duration;

use arboard::Clipboard;
use tauri::{AppHandle, Manager};

use crate::{model, state::AppState};

pub fn start(app: AppHandle) {
    std::thread::spawn(move || {
        let mut board = loop {
            match Clipboard::new() {
                Ok(board) => break board,
                Err(_) => std::thread::sleep(Duration::from_secs(2)),
            }
        };
        // What is on the clipboard when Tandem starts is not news.
        let mut last = board.get_text().ok();
        loop {
            std::thread::sleep(Duration::from_millis(700));
            // The clipboard is locked now and then by whoever is writing to it; the next look gets it.
            let Ok(now) = board.get_text() else { continue };
            if last.as_deref() == Some(now.as_str()) {
                continue;
            }
            last = Some(now.clone());
            if now.trim().is_empty() {
                continue;
            }
            let state = app.state::<AppState>();
            if state.data.lock().unwrap().applied_clipboard.as_deref() == Some(now.as_str()) {
                continue;
            }
            let Ok(engine) = state.engine() else { continue };
            let url = model::is_url(&now);
            tauri::async_runtime::spawn(async move { engine.clipboard_changed(now, url).await });
        }
    });
}

/// Puts text on the clipboard that came from another device, and remembers it so it is not sent back.
pub fn apply(app: &AppHandle, text: &str) {
    app.state::<AppState>().data.lock().unwrap().applied_clipboard = Some(text.to_string());
    if let Ok(mut board) = Clipboard::new() {
        let _ = board.set_text(text.to_string());
    }
}

pub fn read() -> Option<String> {
    Clipboard::new().ok()?.get_text().ok().filter(|t| !t.trim().is_empty())
}
