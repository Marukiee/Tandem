//! What is underway and incoming (P3b): every transfer that runs, waits for a yes, waits in the queue for a device that is away (P3), or
//! failed and can be sent again, read from the journal of the core. Transfers that stopped halfway are in `halfway.rs`. See
//! docs/TRANSFERS.md, which the Mac (`UnderwayTransfers.swift`) and Android follow too.

use std::collections::HashSet;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Mutex, OnceLock};
use std::time::Duration;

use serde_json::{Value, json};
use tandem_core::ffi::{TandemItemState, TandemShareOrigin, TandemTransfer, TandemTransferPeer, TandemTransferStage};
use tauri::{AppHandle, Emitter, Manager, State};

use crate::state::{AppState, Held};

#[derive(Default)]
struct Book {
    rows: Vec<Value>,
    /// Failed rows the person sent again or hid.
    put_away: HashSet<String>,
}

fn book() -> &'static Mutex<Book> {
    static BOOK: OnceLock<Mutex<Book>> = OnceLock::new();
    BOOK.get_or_init(Mutex::default)
}

fn ended(state: TandemItemState) -> bool {
    !matches!(state, TandemItemState::Waiting | TandemItemState::Running | TandemItemState::Interrupted)
}

/// What a transfer with one device is to the person, or none when it needs no row.
fn kind(transfer: &TandemTransfer, peer: &TandemTransferPeer) -> Option<&'static str> {
    // Taken by the clipboard, a drop or Insert from phone: those show themselves, and a question here would only puzzle.
    if transfer.incoming && matches!(transfer.origin, TandemShareOrigin::Clipboard | TandemShareOrigin::Drag | TandemShareOrigin::Capture { .. }) {
        return None;
    }
    if transfer.halfway {
        return None;
    }
    let open = peer.items.iter().any(|i| !ended(i.state));
    match peer.stage {
        TandemTransferStage::Queued => return Some("queued"),
        TandemTransferStage::Offered => return Some("waiting"),
        TandemTransferStage::Accepted if open => return Some("running"),
        _ => {}
    }
    // Only what was sent from here can be sent again; a file that came in failed is on the list below with its reason.
    let failed = peer.items.iter().any(|i| i.state == TandemItemState::Failed);
    (!transfer.incoming && failed).then_some("failed")
}

fn row_id(transfer: &TandemTransfer, peer: &TandemTransferPeer) -> String {
    format!("{}-{}-{}", if transfer.incoming { "in" } else { "out" }, transfer.offer, peer.device)
}

/// One row per transfer and device, so an outgoing offer to several devices can be stopped for each on its own.
fn row(transfer: &TandemTransfer, peer: &TandemTransferPeer, kind: &str) -> Value {
    let failed_row = kind == "failed";
    let mut shown: Vec<usize> = (0..transfer.items.len().min(peer.items.len()))
        .filter(|&i| if failed_row { peer.items[i].state == TandemItemState::Failed } else { !ended(peer.items[i].state) })
        .collect();
    if shown.is_empty() {
        shown = (0..transfer.items.len()).collect();
    }
    let name = shown.first().map(|&i| transfer.items[i].name.clone()).unwrap_or_default();
    let (done, total) = transfer.items.iter().zip(&peer.items).fold((0u64, 0u64), |(done, total), (item, progress)| {
        let got = if progress.state == TandemItemState::Done { item.size } else { progress.done.min(item.size) };
        (done + got, total + item.size)
    });
    let error = peer.items.iter().find(|i| i.state == TandemItemState::Failed).and_then(|i| i.error.clone());
    json!({
        "id": row_id(transfer, peer),
        "offer": transfer.offer.to_string(),
        "peer": peer.device,
        "incoming": transfer.incoming,
        "kind": kind,
        "name": name,
        "more": shown.len().saturating_sub(1),
        "done": done,
        "total": total,
        "error": error,
        "createdMs": transfer.created_ms,
    })
}

fn rows_of(found: &[TandemTransfer], put_away: &HashSet<String>) -> Vec<Value> {
    found
        .iter()
        .flat_map(|transfer| {
            transfer.peers.iter().filter_map(move |peer| {
                let kind = kind(transfer, peer)?;
                Some((row_id(transfer, peer), row(transfer, peer, kind)))
            })
        })
        .filter(|(id, _)| !put_away.contains(id))
        .map(|(_, value)| value)
        .collect()
}

/// After the start, on every event about a transfer and when a device comes or goes.
pub fn refresh(app: &AppHandle) {
    let Ok(engine) = app.state::<AppState>().engine() else { return };
    let list = {
        // The lock is taken before the journal is read: two threads that refresh at once cannot write an older list last.
        let mut book = book().held();
        let found = engine.transfers();
        let list = rows_of(&found, &book.put_away);
        // What is no longer in the journal needs no memory of having been hidden.
        let live: HashSet<String> = found.iter().flat_map(|t| t.peers.iter().map(move |p| row_id(t, p))).collect();
        book.put_away.retain(|id| live.contains(id));
        if book.rows == list {
            return;
        }
        book.rows = list.clone();
        list
    };
    let _ = app.emit("underway", list);
}

/// Progress comes ten times a second per file: the rows are read at most every half second.
pub fn soon(app: &AppHandle) {
    static WAITING: AtomicBool = AtomicBool::new(false);
    if WAITING.swap(true, Ordering::SeqCst) {
        return;
    }
    let app = app.clone();
    std::thread::spawn(move || {
        std::thread::sleep(Duration::from_millis(500));
        WAITING.store(false, Ordering::SeqCst);
        refresh(&app);
    });
}

#[tauri::command]
pub fn underway_list() -> Vec<Value> {
    book().held().rows.clone()
}

/// Stops it for that one device; the others an outgoing offer went to keep going (R-B8). For a queued offer it is "do not send".
#[tauri::command]
pub async fn underway_stop(app: AppHandle, state: State<'_, AppState>, peer: String, offer: String, incoming: bool) -> Result<(), String> {
    let offer: u64 = offer.parse().map_err(|_| "that transfer is not known".to_string())?;
    state.engine()?.cancel_transfer(peer, offer, incoming).await.map_err(|e| e.to_string())?;
    refresh(&app);
    Ok(())
}

/// Takes a failed row off the list without sending it again.
#[tauri::command]
pub fn underway_hide(app: AppHandle, id: String) {
    book().held().put_away.insert(id);
    refresh(&app);
}

/// Sends the files of a failed row once more, as a new offer to the same device. `gone` when one of them is no longer there.
#[tauri::command]
pub async fn underway_send_again(app: AppHandle, state: State<'_, AppState>, peer: String, offer: String) -> Result<Value, String> {
    let offer: u64 = offer.parse().map_err(|_| "that transfer is not known".to_string())?;
    let found = state.engine()?.transfers();
    let paths: Vec<PathBuf> = found
        .iter()
        .find(|t| !t.incoming && t.offer == offer)
        .and_then(|t| {
            let to = t.peers.iter().find(|p| p.device == peer)?;
            Some(
                t.items
                    .iter()
                    .zip(&to.items)
                    .filter(|(item, progress)| progress.state == TandemItemState::Failed && !item.source.is_empty())
                    .map(|(item, _)| PathBuf::from(&item.source))
                    .collect(),
            )
        })
        .unwrap_or_default();
    if paths.is_empty() || paths.iter().any(|p| !p.is_file()) {
        return Ok(json!({ "sent": 0, "queued": 0, "offline": 0, "folders": 0, "gone": true }));
    }
    let result = crate::commands::send_paths_with(&state, vec![peer.clone()], paths, TandemShareOrigin::Files).await?;
    underway_hide(app, format!("out-{offer}-{peer}"));
    Ok(result)
}

#[cfg(test)]
mod tests {
    use super::*;
    use tandem_core::ffi::{TandemTransferItem, TandemTransferProgress};

    fn transfer(incoming: bool, origin: TandemShareOrigin, stage: TandemTransferStage, states: &[(TandemItemState, Option<&str>)]) -> TandemTransfer {
        TandemTransfer {
            offer: 7,
            incoming,
            origin,
            created_ms: 1,
            updated_ms: 2,
            items: (0..states.len()).map(|i| TandemTransferItem { name: format!("f{i}"), size: 100, mime: String::new(), source: String::new() }).collect(),
            peers: vec![TandemTransferPeer {
                device: "phone".into(),
                stage,
                offered_ms: 0,
                items: states
                    .iter()
                    .map(|(state, error)| TandemTransferProgress { state: *state, done: 40, error: error.map(str::to_string), location: None })
                    .collect(),
            }],
            held: false,
            halfway: false,
        }
    }

    fn kinds(t: &TandemTransfer) -> Vec<&'static str> {
        t.peers.iter().filter_map(|p| kind(t, p)).collect()
    }

    #[test]
    fn a_row_has_the_kind_the_person_needs() {
        use TandemItemState::*;
        use TandemTransferStage::*;
        let files = TandemShareOrigin::Files;
        assert_eq!(kinds(&transfer(false, files.clone(), Queued, &[(Waiting, None)])), ["queued"]);
        assert_eq!(kinds(&transfer(false, files.clone(), Offered, &[(Waiting, None)])), ["waiting"]);
        assert_eq!(kinds(&transfer(true, files.clone(), Accepted, &[(Running, None), (Done, None)])), ["running"]);
        assert_eq!(kinds(&transfer(false, files.clone(), Closed, &[(Done, None), (Failed, Some("connection lost"))])), ["failed"]);
        // Done, declined or failed on the way in: no row.
        assert!(kinds(&transfer(false, files.clone(), Closed, &[(Done, None)])).is_empty());
        assert!(kinds(&transfer(true, files.clone(), Closed, &[(Failed, Some("no space"))])).is_empty());
        // Taken by the clipboard or a drop, and stopped halfway (that has its own rows): none.
        assert!(kinds(&transfer(true, TandemShareOrigin::Clipboard, Offered, &[(Waiting, None)])).is_empty());
        assert!(kinds(&transfer(true, TandemShareOrigin::Drag, Accepted, &[(Running, None)])).is_empty());
        let mut halfway = transfer(true, files, Accepted, &[(Interrupted, None)]);
        halfway.halfway = true;
        assert!(kinds(&halfway).is_empty());
    }

    #[test]
    fn a_failed_row_names_the_files_that_failed_and_a_hidden_one_is_gone() {
        use TandemItemState::*;
        let t = transfer(false, TandemShareOrigin::Files, TandemTransferStage::Closed, &[(Done, None), (Failed, Some("gone")), (Failed, Some("changed"))]);
        let none = HashSet::new();
        let rows = rows_of(std::slice::from_ref(&t), &none);
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0]["name"], "f1");
        assert_eq!(rows[0]["more"], 1);
        assert_eq!(rows[0]["error"], "gone");
        assert_eq!(rows[0]["done"], 100 + 40 + 40);
        assert_eq!(rows[0]["total"], 300);
        let hidden: HashSet<String> = ["out-7-phone".to_string()].into();
        assert!(rows_of(std::slice::from_ref(&t), &hidden).is_empty());
    }
}
