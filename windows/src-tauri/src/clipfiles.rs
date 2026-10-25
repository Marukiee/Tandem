//! The clipboard for pictures and files, next to the text in `clip`. A picture or files copied here go to the devices that share the
//! clipboard as files with the origin `Clipboard`, and what comes in that way is put on the clipboard here instead of being saved as
//! files that came in. Small enough only, as on the Mac: a copy never starts a large transfer by itself.

use std::collections::HashMap;
use std::hash::{DefaultHasher, Hash, Hasher};
use std::path::{Path, PathBuf};
use std::sync::{LazyLock, Mutex};
use std::time::{Duration, Instant};

use arboard::{Clipboard, ImageData};
use serde_json::Value;
use tandem_core::ffi::{TandemShareItem, TandemShareOrigin};
use tauri::{AppHandle, Manager};

use crate::state::AppState;
use crate::{commands, events, i18n};

pub const PICTURE_LIMIT: usize = 15 * 1024 * 1024;
pub const FILES_LIMIT: u64 = 50 * 1024 * 1024;

/// Offers that came in as a clipboard, with the files of each that are in so far.
static OFFERS: LazyLock<Mutex<HashMap<(String, u64), Vec<PathBuf>>>> = LazyLock::new(|| Mutex::new(HashMap::new()));

/// What is on the clipboard as far as this file knows: what it put there itself is not sent back, and what was seen is not sent twice.
struct Seen {
    applied: Option<u64>,
    applied_at: Option<Instant>,
    last: Option<u64>,
}
static SEEN: Mutex<Seen> = Mutex::new(Seen { applied: None, applied_at: None, last: None });

/// The last picture that was sent, by a copy or as a screenshot. Windows and GNOME save a screenshot and also put it on the clipboard,
/// and that is one screenshot: the second way sees it was just sent. The pixels are compared, and as a fallback the size, because the
/// copy Windows puts on the clipboard need not have the same alpha as the file that was saved.
struct Sent {
    hash: u64,
    size: (u32, u32),
    screenshot: bool,
    at: Instant,
}
static SENT: Mutex<Option<Sent>> = Mutex::new(None);

/// True when this picture was not just sent the other way; it then counts as sent now. Two copies of the same size are two pictures:
/// the size only counts between a copy and a screenshot.
fn claim_picture(hash: u64, size: (u32, u32), screenshot: bool) -> bool {
    let mut sent = SENT.lock().unwrap();
    let twice = sent.as_ref().is_some_and(|s| {
        s.at.elapsed() < Duration::from_secs(5) && (s.hash == hash || (s.size == size && s.screenshot != screenshot))
    });
    if !twice {
        *sent = Some(Sent { hash, size, screenshot, at: Instant::now() });
    }
    !twice
}

/// The clipboard that pictures and files are put on. It is kept: on Linux what is on the clipboard is served by the program that put it
/// there, for as long as that one holds it.
static BOARD: Mutex<Option<Clipboard>> = Mutex::new(None);

fn hash_of(bytes: &[u8]) -> u64 {
    let mut hasher = DefaultHasher::new();
    bytes.hash(&mut hasher);
    hasher.finish()
}

fn hash_paths(paths: &[PathBuf]) -> u64 {
    let mut hasher = DefaultHasher::new();
    paths.hash(&mut hasher);
    hasher.finish()
}

/// True the first time this content is seen, and not when this file put it there a moment ago.
fn is_new(hash: u64) -> bool {
    let mut seen = SEEN.lock().unwrap();
    let just_applied = seen.applied_at.is_some_and(|at| at.elapsed() < Duration::from_millis(1500));
    if seen.applied == Some(hash) || just_applied || seen.last == Some(hash) {
        seen.last = Some(hash);
        return false;
    }
    seen.last = Some(hash);
    true
}

fn applied(hash: u64) {
    let mut seen = SEEN.lock().unwrap();
    seen.applied = Some(hash);
    seen.applied_at = Some(Instant::now());
    seen.last = Some(hash);
}

/// The devices that share the clipboard with this computer and are there now.
fn targets(app: &AppHandle) -> Vec<String> {
    let state = app.state::<AppState>();
    let data = state.data.lock().unwrap();
    data.devices
        .iter()
        .filter(|d| d["online"].as_bool() == Some(true) && d["clipboard"].as_bool() != Some(false))
        .filter_map(|d| d["id"].as_str().map(str::to_string))
        .collect()
}

fn send(app: &AppHandle, paths: Vec<PathBuf>) {
    let ids = targets(app);
    if ids.is_empty() {
        return;
    }
    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        let state = app.state::<AppState>();
        if let Err(error) = commands::send_paths_with(&state, ids, paths, TandemShareOrigin::Clipboard).await {
            log::info!("the clipboard could not be sent: {error}");
        }
    });
}

// ---- Sending ----------------------------------------------------------------------------------------------------------------------

/// Files copied in the file manager. They also put their names on the clipboard as text, so this is looked at first. True when the
/// clipboard holds files, sent or not.
pub fn copied_files(app: &AppHandle, board: &mut Clipboard) -> bool {
    let Ok(paths) = board.get().file_list() else { return false };
    if paths.is_empty() {
        return false;
    }
    if !is_new(hash_paths(&paths)) {
        return true;
    }
    // A folder could be anything, and a copy should not start a large transfer: those stay here.
    let mut size = 0u64;
    for path in &paths {
        match std::fs::metadata(path) {
            Ok(meta) if meta.is_file() => size += meta.len(),
            _ => return true,
        }
    }
    if size <= FILES_LIMIT {
        send(app, paths);
    }
    true
}

/// A picture that was copied (from a browser, a screenshot tool, an image editor). True when there was one.
pub fn copied_picture(app: &AppHandle, board: &mut Clipboard) -> bool {
    let Ok(image) = board.get_image() else { return false };
    if !is_new(hash_of(&image.bytes)) {
        return true;
    }
    let Some(rgba) = image::RgbaImage::from_raw(image.width as u32, image.height as u32, image.bytes.into_owned()) else { return true };
    if targets(app).is_empty() || !claim_picture(hash_of(rgba.as_raw()), rgba.dimensions(), false) {
        return true;
    }
    let mut png = std::io::Cursor::new(Vec::new());
    if rgba.write_to(&mut png, image::ImageFormat::Png).is_err() || png.get_ref().len() > PICTURE_LIMIT {
        return true;
    }
    let dir = std::env::temp_dir().join("TandemClipboard");
    let _ = std::fs::create_dir_all(&dir);
    let file = dir.join(format!("{}.png", i18n::t(app, "picture")));
    if std::fs::write(&file, png.into_inner()).is_ok() {
        send(app, vec![file]);
    }
    true
}

/// A screenshot was saved here (see `screenshots`). It goes as the file it is, to the clipboards of the devices that share the
/// clipboard, when the switch for it is on.
pub fn screenshot_taken(app: &AppHandle, path: &Path) {
    if !crate::settings::get(app).send_screenshots || targets(app).is_empty() {
        return;
    }
    if std::fs::metadata(path).map(|m| m.len()).unwrap_or(u64::MAX) > FILES_LIMIT {
        return;
    }
    let Ok(decoded) = image::open(path) else { return };
    let rgba = decoded.to_rgba8();
    let hash = hash_of(rgba.as_raw());
    // What the clipboard watcher would make of the same picture, so it does not send it again.
    SEEN.lock().unwrap().last = Some(hash);
    if claim_picture(hash, rgba.dimensions(), true) {
        send(app, vec![path.to_path_buf()]);
    }
}

// ---- Receiving --------------------------------------------------------------------------------------------------------------------

/// An offer with the origin `Clipboard`: taken without asking from a device that shares the clipboard with this computer, when it is no
/// larger than a copy here may be. Anything else is asked about like other files.
pub fn offered(app: &AppHandle, from: &str, offer: u64, origin: TandemShareOrigin, items: &[TandemShareItem], device: Option<&Value>) -> bool {
    if !matches!(origin, TandemShareOrigin::Clipboard) || device.is_some_and(|d| d["clipboard"].as_bool() == Some(false)) {
        return false;
    }
    if items.iter().map(|i| i.size).sum::<u64>() > FILES_LIMIT {
        return false;
    }
    let Ok(engine) = app.state::<AppState>().engine() else { return false };
    if engine.accept_offer(from.to_string(), offer).is_err() {
        return false;
    }
    let mut offers = OFFERS.lock().unwrap();
    if offers.len() > 20 {
        offers.clear();
    }
    offers.insert((from.to_string(), offer), Vec::new());
    true
}

/// A file of such an offer is in: everything of it that came so far goes on the clipboard together.
pub fn finished(app: &AppHandle, peer: &str, offer: u64, location: &Option<String>, error: &Option<String>) -> bool {
    let files = {
        let mut offers = OFFERS.lock().unwrap();
        let Some(files) = offers.get_mut(&(peer.to_string(), offer)) else { return false };
        let (Some(path), None) = (location, error) else { return true };
        files.push(PathBuf::from(path));
        files.clone()
    };
    let name = events::device_name(app, peer);
    let picture = (files.len() == 1).then(|| picture_to_clipboard(&files[0]).ok()).flatten();
    // A single picture waits in the core's clipboard folder (see `kept_for_clipboard`). The clipboard holds the picture itself now, so
    // the file goes: screenshots from every device would otherwise pile up.
    if files.len() == 1 && waits_for_clipboard(&files[0]) {
        let _ = std::fs::remove_file(&files[0]);
        if let Some(folder) = files[0].parent() {
            let _ = std::fs::remove_dir(folder);
        }
    }
    let put = picture.is_some() || files_to_clipboard(&files).is_ok();
    if !put {
        return true;
    }
    let said = if files.len() == 1 {
        let file = files[0].file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
        i18n::t2(app, "file_on_clipboard", &file, &name)
    } else {
        i18n::t2(app, "files_on_clipboard", &files.len().to_string(), &name)
    };
    events::say(app, &said);
    true
}

fn waits_for_clipboard(file: &Path) -> bool {
    file.parent().and_then(Path::parent).and_then(Path::file_name).is_some_and(|n| n == tandem_core::transfer::CLIPBOARD_DIR)
}

fn with_board<T>(work: impl FnOnce(&mut Clipboard) -> Result<T, arboard::Error>) -> Result<T, String> {
    let mut board = BOARD.lock().unwrap();
    if board.is_none() {
        *board = Some(Clipboard::new().map_err(|e| e.to_string())?);
    }
    work(board.as_mut().unwrap()).map_err(|e| e.to_string())
}

/// A picture on the clipboard, so it pastes into a message or a document.
pub fn picture_to_clipboard(path: &Path) -> Result<(), String> {
    let decoded = image::open(path).map_err(|e| e.to_string())?.to_rgba8();
    let (width, height) = decoded.dimensions();
    let bytes = decoded.into_raw();
    applied(hash_of(&bytes));
    with_board(|board| board.set_image(ImageData { width: width as usize, height: height as usize, bytes: std::borrow::Cow::Owned(bytes) }))
}

fn files_to_clipboard(paths: &[PathBuf]) -> Result<(), String> {
    applied(hash_paths(paths));
    with_board(|board| board.set().file_list(paths))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_screenshot_that_is_also_copied_is_sent_once() {
        *SENT.lock().unwrap() = None;
        assert!(claim_picture(1, (1920, 1080), true));
        // The same pixels from the clipboard, and the copy Windows made with other alpha.
        assert!(!claim_picture(1, (1920, 1080), false));
        assert!(!claim_picture(2, (1920, 1080), false));
        // Another picture of another size is news, and so is a second copy of the same size.
        assert!(claim_picture(3, (800, 600), false));
        assert!(claim_picture(4, (800, 600), false));
    }
}
