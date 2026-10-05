//! Keeps the clipboard of this PC and the clipboards of the other devices in step: what is copied here is sent, and
//! what arrives is put on the clipboard without being sent back.

use std::time::Duration;

use arboard::Clipboard;
use tauri::{AppHandle, Manager};

use crate::{model, state::AppState};

pub fn start(app: AppHandle) {
    // Windows says when the clipboard changes, so nothing has to look at it every so often. Should it not accept the
    // listener, the clipboard is looked at every second and a half instead.
    let (changed_tx, changed_rx) = std::sync::mpsc::channel::<()>();
    let changed_tx = std::sync::Mutex::new(changed_tx);
    let listening = tandem_winsys::watch_clipboard(move || {
        let _ = changed_tx.lock().unwrap().send(());
    });
    log::info!("the clipboard is {}", if listening { "listened to" } else { "looked at by polling" });

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
            if listening {
                if changed_rx.recv().is_err() {
                    return;
                }
                // One copy makes more than one message, one for each format the program puts on the clipboard.
                std::thread::sleep(Duration::from_millis(120));
                while changed_rx.try_recv().is_ok() {}
            } else {
                std::thread::sleep(Duration::from_millis(1500));
            }
            let Some(now) = read_text(&mut board) else { continue };
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
            crate::history::record(&app, &now, "");
            let Ok(engine) = state.engine() else { continue };
            let url = model::is_url(&now);
            tauri::async_runtime::spawn(async move { engine.clipboard_changed(now, url).await });
        }
    });
}

/// The text on the clipboard. Whoever is writing to it holds it for a moment, so a lock is tried again a few times; a
/// picture or a file on it is no text, and not worth another try.
fn read_text(board: &mut Clipboard) -> Option<String> {
    for _ in 0..4 {
        match board.get_text() {
            Ok(text) => return Some(text),
            Err(arboard::Error::ContentNotAvailable) => return None,
            Err(_) => std::thread::sleep(Duration::from_millis(60)),
        }
    }
    None
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
