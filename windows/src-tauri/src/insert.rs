//! Insert from phone: this computer asks a phone for a photo or a scanned document, the phone makes it, and what comes back is put on
//! the clipboard of this computer, ready to paste. The picture arrives as an ordinary file with the origin `Capture`, which this
//! file recognises by the request it belongs to, takes without asking, and then puts on the clipboard.

use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{LazyLock, Mutex};

use tandem_core::ffi::{TandemCaptureKind, TandemCaptureWhy, TandemShareOrigin};
use tauri::{AppHandle, Manager, State};

use crate::state::AppState;
use crate::{events, i18n};

type Reply<T> = Result<T, String>;

/// The requests that were made and have not been answered, and which offer of files answered which request.
static PENDING: LazyLock<Mutex<Vec<u64>>> = LazyLock::new(|| Mutex::new(Vec::new()));
static OFFERS: LazyLock<Mutex<HashMap<(String, u64), u64>>> = LazyLock::new(|| Mutex::new(HashMap::new()));
static NEXT: AtomicU64 = AtomicU64::new(1);

/// Asks a phone for a photo (`photo`), a scan of a document (`document`) or a picture from its gallery (`picture`).
#[tauri::command]
pub async fn capture_request(app: AppHandle, state: State<'_, AppState>, id: String, kind: String) -> Reply<()> {
    let kind = match kind.as_str() {
        "photo" => TandemCaptureKind::Photo,
        "document" => TandemCaptureKind::Document,
        "picture" => TandemCaptureKind::Picture,
        _ => return Err("not a kind of picture".into()),
    };
    let request = (events::now_ms() << 8) | (NEXT.fetch_add(1, Ordering::Relaxed) & 0xff);
    PENDING.lock().unwrap().push(request);
    let result = state.engine()?.request_capture(id.clone(), request, kind).await.map_err(|e| e.to_string());
    if result.is_err() {
        PENDING.lock().unwrap().retain(|r| *r != request);
    } else {
        events::say(&app, &i18n::t1(&app, "insert_waiting", &events::device_name(&app, &id)));
    }
    result
}

/// A phone offers files. When they are the answer to a request of this computer they are taken at once.
pub fn offered(app: &AppHandle, from: &str, offer: u64, origin: TandemShareOrigin) -> bool {
    let TandemShareOrigin::Capture { request } = origin else { return false };
    let mut pending = PENDING.lock().unwrap();
    let Some(at) = pending.iter().position(|r| *r == request) else { return false };
    pending.remove(at);
    drop(pending);
    OFFERS.lock().unwrap().insert((from.to_string(), offer), request);
    if let Ok(engine) = app.state::<AppState>().engine() {
        let _ = engine.accept_offer(from.to_string(), offer);
    }
    true
}

/// A file finished. When it was the answer to a request, it goes on the clipboard (a picture) or is reported (anything else).
pub fn finished(app: &AppHandle, peer: &str, offer: u64, location: &Option<String>, error: &Option<String>) -> bool {
    if OFFERS.lock().unwrap().remove(&(peer.to_string(), offer)).is_none() {
        return false;
    }
    let name = events::device_name(app, peer);
    let (Some(path), None) = (location, error) else {
        events::say(app, &i18n::t1(app, "insert_failed", &name));
        return true;
    };
    match picture_to_clipboard(path) {
        Ok(()) => events::say(app, &i18n::t1(app, "insert_done", &name)),
        Err(_) => events::say(app, &i18n::t2(app, "insert_saved", &name, path)),
    }
    true
}

/// The phone said it did not make one.
pub fn cancelled(app: &AppHandle, from: &str, why: TandemCaptureWhy) {
    PENDING.lock().unwrap().clear();
    let key = match why {
        TandemCaptureWhy::Refused => "insert_refused",
        TandemCaptureWhy::Unavailable => "insert_unavailable",
        _ => "insert_cancelled",
    };
    events::say(app, &i18n::t1(app, key, &events::device_name(app, from)));
}

fn picture_to_clipboard(path: &str) -> Result<(), String> {
    let decoded = image::open(path).map_err(|e| e.to_string())?.to_rgba8();
    let (width, height) = decoded.dimensions();
    arboard::Clipboard::new()
        .and_then(|mut board| {
            board.set_image(arboard::ImageData { width: width as usize, height: height as usize, bytes: std::borrow::Cow::Owned(decoded.into_raw()) })
        })
        .map_err(|e| e.to_string())
}
