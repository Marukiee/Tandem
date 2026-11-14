//! Incoming transfers that stopped halfway before this start (P2). Nothing goes on by itself: each waits on the page Shared, and in a
//! notification where the desktop allows buttons, until the person says go on or throw away. See docs/TRANSFERS.md, which the Mac
//! (`HalfwayTransfers.swift`) and Android follow too.

use std::collections::{HashMap, HashSet};
use std::sync::{Mutex, OnceLock};

use serde_json::{json, Value};
use tauri::{AppHandle, Emitter, Manager};

use crate::state::{AppState, Held};
use crate::{events, i18n};

#[derive(Default)]
struct Book {
    waiting: Vec<Value>,
    /// Thrown away by the person: its files end as cancelled, which needs no row of its own.
    thrown_away: HashSet<String>,
    /// Asked about once per start in a notification; the page keeps asking.
    told: HashSet<String>,
    /// The number the desktop gave a notification, to take it away when the question is answered.
    notices: HashMap<String, u32>,
}

fn book() -> &'static Mutex<Book> {
    static BOOK: OnceLock<Mutex<Book>> = OnceLock::new();
    BOOK.get_or_init(Mutex::default)
}

fn key(from: &str, offer: u64) -> String {
    format!("{from}-{offer}")
}

/// After the start and whenever a device connects, so Go on is ready as soon as the sender is.
pub fn refresh(app: &AppHandle) {
    let Ok(engine) = app.state::<AppState>().engine() else { return };
    let found = engine.resumable_transfers();
    let list: Vec<Value> = found
        .iter()
        .map(|t| {
            let open: Vec<_> = t.items.iter().filter(|i| !i.ended).collect();
            let first = open.first().copied().or(t.items.first()).map(|i| i.name.clone()).unwrap_or_default();
            json!({
                "from": t.from,
                "offer": t.offer.to_string(),
                "name": first,
                "more": open.len().saturating_sub(1),
                "done": t.items.iter().map(|i| i.done.min(i.size)).sum::<u64>(),
                "total": t.items.iter().map(|i| i.size).sum::<u64>(),
                "updatedMs": t.updated_ms,
            })
        })
        .collect();
    let fresh: Vec<Value> = {
        let mut book = book().held();
        book.waiting = list.clone();
        list.iter().filter(|t| book.told.insert(key(t["from"].as_str().unwrap_or_default(), offer_of(t)))).cloned().collect()
    };
    let _ = app.emit("halfway", &list);
    for transfer in &fresh {
        notify(app, transfer);
    }
}

fn offer_of(transfer: &Value) -> u64 {
    transfer["offer"].as_str().and_then(|o| o.parse().ok()).unwrap_or(0)
}

fn sender_name(app: &AppHandle, from: &str) -> String {
    match events::device_name(app, from).as_str() {
        "?" => i18n::t(app, "halfway_other_device"),
        name => name.to_string(),
    }
}

fn title(app: &AppHandle, transfer: &Value) -> String {
    let name = transfer["name"].as_str().unwrap_or_default();
    match transfer["more"].as_u64().unwrap_or(0) {
        0 => name.to_string(),
        more => i18n::t2(app, "halfway_more", name, &more.to_string()),
    }
}

fn notify(app: &AppHandle, transfer: &Value) {
    let from = transfer["from"].as_str().unwrap_or_default().to_string();
    let offer = offer_of(transfer);
    let head = i18n::t1(app, "halfway_title", &sender_name(app, &from));
    let body = i18n::t1(app, "halfway_body", &title(app, transfer));
    // Only a desktop with a notification service that takes buttons can ask in the notice itself; elsewhere the page asks.
    #[cfg(target_os = "linux")]
    {
        let go = i18n::t(app, "halfway_go_on");
        let throw = i18n::t(app, "halfway_throw_away");
        let handle = app.clone();
        let number = tandem_winsys::notify::tell(&head, &body, &[("default", ""), ("goon", go.as_str()), ("throw", throw.as_str())], {
            let from = from.clone();
            move |pick, _| match pick {
                "goon" => answer(&handle, &from, offer, true),
                "throw" => answer(&handle, &from, offer, false),
                _ => crate::tray::show_main(&handle),
            }
        });
        if let Some(number) = number {
            book().held().notices.insert(key(&from, offer), number);
            return;
        }
    }
    events::toast(app, &head, &body);
}

/// What a button of the notification answered.
#[cfg(target_os = "linux")]
fn answer(app: &AppHandle, from: &str, offer: u64, go_on: bool) {
    let known = book().held().waiting.iter().any(|t| t["from"] == from && offer_of(t) == offer);
    if !known {
        crate::tray::show_main(app);
        return;
    }
    let result = if go_on { resume(app, from, offer) } else { discard(app, from, offer) };
    if let Err(text) = result {
        events::toast(app, "Tandem", &text);
    }
}

fn resume(app: &AppHandle, from: &str, offer: u64) -> Result<(), String> {
    // A second click on a button that was just answered: the row is going, there is nothing to say.
    if !book().held().waiting.iter().any(|t| t["from"] == from && offer_of(t) == offer) {
        return Ok(());
    }
    // The button is grey while the sender is away, and says why when it is used.
    if events::device(app, from).and_then(|d| d["online"].as_bool()) != Some(true) {
        return Err(i18n::t1(app, "halfway_away_toast", &sender_name(app, from)));
    }
    let engine = app.state::<AppState>().engine()?;
    match engine.resume_transfer(from.to_string(), offer) {
        Ok(()) => {
            settle(app, from, offer);
            Ok(())
        }
        // The page showed the sender as connected or the transfer as waiting a moment too long: the rows are read again, and the
        // person gets words in their own language instead of the ones of the core.
        Err(error) => {
            log::warn!("could not go on with the transfer {offer} from {from}: {error}");
            refresh(app);
            let connected = events::device(app, from).and_then(|d| d["online"].as_bool()) == Some(true);
            Err(i18n::t1(app, if connected { "halfway_gone" } else { "halfway_away_toast" }, &sender_name(app, from)))
        }
    }
}

fn discard(app: &AppHandle, from: &str, offer: u64) -> Result<(), String> {
    // Without the engine nothing can be thrown away: the row stays, and the person is told why the button did nothing.
    let engine = app.state::<AppState>().engine().map_err(|_| i18n::t(app, "halfway_no_engine"))?;
    book().held().thrown_away.insert(key(from, offer));
    settle(app, from, offer);
    let (app, from) = (app.clone(), from.to_string());
    tauri::async_runtime::spawn(async move {
        let _ = engine.discard_transfer(from, offer).await;
        refresh(&app);
    });
    Ok(())
}

/// Takes the transfer off the page and its notification away.
fn settle(app: &AppHandle, from: &str, offer: u64) {
    let (list, notice) = {
        let mut book = book().held();
        book.waiting.retain(|t| !(t["from"] == from && offer_of(t) == offer));
        (book.waiting.clone(), book.notices.remove(&key(from, offer)))
    };
    #[cfg(target_os = "linux")]
    if let Some(number) = notice {
        tandem_winsys::notify::close(number);
    }
    #[cfg(not(target_os = "linux"))]
    let _ = notice;
    let _ = app.emit("halfway", &list);
}

/// A file of a transfer that was thrown away ended as cancelled: the person chose that, so it gets no row on the page Shared.
pub fn swallows(from: &str, offer: u64, error: &Option<String>) -> bool {
    error.as_deref() == Some("cancelled") && book().held().thrown_away.contains(&key(from, offer))
}

#[tauri::command]
pub fn halfway_list() -> Vec<Value> {
    book().held().waiting.clone()
}

#[tauri::command]
pub fn halfway_go_on(app: AppHandle, from: String, offer: String) -> Result<(), String> {
    resume(&app, &from, offer.parse().map_err(|_| "bad offer".to_string())?)
}

#[tauri::command]
pub fn halfway_throw_away(app: AppHandle, from: String, offer: String) -> Result<(), String> {
    discard(&app, &from, offer.parse().map_err(|_| "bad offer".to_string())?)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_a_cancelled_end_of_a_thrown_away_transfer_is_swallowed() {
        book().held().thrown_away.insert(key("phone", 7));
        assert!(swallows("phone", 7, &Some("cancelled".into())));
        assert!(!swallows("phone", 7, &Some("gone".into())));
        assert!(!swallows("phone", 7, &None));
        assert!(!swallows("phone", 8, &Some("cancelled".into())));
        assert!(!swallows("laptop", 7, &Some("cancelled".into())));
    }
}
