//! Files that another computer dropped on the edge of its screen, to this one: that computer shares its pointer with this one, so it
//! was the person who put them here, at that moment. They are taken without asking and put down (the desktop on Windows, the downloads
//! folder on Linux), and a message says where, with a way to show them on Linux.

use std::collections::HashSet;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

use tandem_core::ffi::TandemShareOrigin;
use tauri::{AppHandle, Manager};

use crate::state::AppState;
use crate::{capture, events, i18n, input};

static OFFERS: Mutex<Option<HashSet<(String, u64)>>> = Mutex::new(None);

/// An offer of files came in. Taken here when it has the drag origin and comes from the computer that shares its pointer with this one.
pub fn offered(app: &AppHandle, from: &str, offer: u64, origin: TandemShareOrigin) -> bool {
    if !matches!(origin, TandemShareOrigin::Drag) || !(input::shared_is(from) || capture::is_remote(from)) {
        return false;
    }
    let Ok(engine) = app.state::<AppState>().engine() else { return false };
    if engine.accept_offer(from.to_string(), offer).is_err() {
        return false;
    }
    OFFERS.lock().unwrap().get_or_insert_with(HashSet::new).insert((from.to_string(), offer));
    true
}

/// A file of such an offer is in. True when it was one of ours and was dealt with here.
pub fn finished(app: &AppHandle, peer: &str, offer: u64, name: &str, location: &Option<String>, error: &Option<String>) -> bool {
    let ours = OFFERS.lock().unwrap().as_ref().is_some_and(|set| set.contains(&(peer.to_string(), offer)));
    if !ours {
        return false;
    }
    let from = events::device_name(app, peer);
    let (Some(path), None) = (location, error) else {
        events::say(app, &i18n::t1(app, "insert_failed", &from));
        return true;
    };
    // Still being dragged: the person has not let go yet, so it is put down when the button comes up and not under their hand.
    if input::left_down() {
        WAITING.lock().unwrap().push((from, name.to_string(), PathBuf::from(path)));
    } else {
        land(app, &from, name, Path::new(path));
    }
    true
}

/// Files that came in while the button was still down.
static WAITING: Mutex<Vec<(String, String, PathBuf)>> = Mutex::new(Vec::new());

/// The button came up: what arrived during the drag is put down now.
pub fn flush(app: &AppHandle) {
    let waiting: Vec<_> = std::mem::take(&mut *WAITING.lock().unwrap());
    for (from, name, path) in waiting {
        land(app, &from, &name, &path);
    }
}

fn land(app: &AppHandle, from: &str, name: &str, path: &Path) {
    // Windows shows its desktop and the file is where the drop was. Linux has no desktop that shows files (GNOME has none), so it goes to
    // the downloads folder, and is not opened: a window that comes up by itself is not what a drop is.
    let folder = if cfg!(windows) { app.path().desktop_dir().unwrap_or_else(|_| PathBuf::from(".")) } else { crate::settings::download_dir(app) };
    let placed = place(path, &folder);
    let body = i18n::t2(app, if cfg!(windows) { "drop_on_desktop" } else { "drop_in_downloads" }, name, from);
    #[cfg(target_os = "linux")]
    {
        let target = placed.clone();
        let buttons = [("show", i18n::t(app, "drop_show"))];
        let pairs: Vec<(&str, &str)> = buttons.iter().map(|(k, v)| (*k, v.as_str())).collect();
        let shown = tandem_winsys::notify::ask("Tandem", &body, &pairs, move |_| {
            let _ = tauri_plugin_opener::reveal_item_in_dir(&target);
        });
        if shown.is_none() {
            events::toast(app, "Tandem", &body);
        }
    }
    #[cfg(not(target_os = "linux"))]
    {
        events::toast(app, "Tandem", &body);
        let _ = &placed;
    }
}

/// Moves the file to the folder, under a name that is free there.
pub fn place(source: &Path, folder: &Path) -> PathBuf {
    let stem = source.file_stem().map(|s| s.to_string_lossy().into_owned()).unwrap_or_else(|| "file".into());
    let extension = source.extension().map(|e| e.to_string_lossy().into_owned());
    let mut target = folder.join(source.file_name().unwrap_or_default());
    let mut n = 2;
    while target.exists() {
        target = folder.join(match &extension {
            Some(ext) => format!("{stem} {n}.{ext}"),
            None => format!("{stem} {n}"),
        });
        n += 1;
    }
    match std::fs::rename(source, &target).or_else(|_| std::fs::copy(source, &target).and_then(|_| std::fs::remove_file(source))) {
        Ok(()) => target,
        Err(_) => source.to_path_buf(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_file_is_put_under_a_free_name() {
        let dir = std::env::temp_dir().join(format!("tandem-drag-{}", std::process::id()));
        let from = dir.join("in");
        let to = dir.join("desktop");
        std::fs::create_dir_all(&from).unwrap();
        std::fs::create_dir_all(&to).unwrap();
        std::fs::write(from.join("note.txt"), "one").unwrap();
        std::fs::write(to.join("note.txt"), "already there").unwrap();
        let placed = place(&from.join("note.txt"), &to);
        assert_eq!(placed.file_name().unwrap(), "note 2.txt");
        assert_eq!(std::fs::read_to_string(&placed).unwrap(), "one");
        assert_eq!(std::fs::read_to_string(to.join("note.txt")).unwrap(), "already there");
        let _ = std::fs::remove_dir_all(dir);
    }
}
