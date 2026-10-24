//! Files that another computer dropped on the edge of its screen, to this one: that computer shares its pointer with this one, so it
//! was the person who put them here, at that moment. They are taken without asking and put down (the desktop on Windows, the downloads
//! folder on Linux), and a message says where, with a way to show them on Linux. Files that came over in a drag that is still going
//! (the button held while the pointer crossed) are put down only when the button comes up here; when the pointer goes back first they
//! are thrown away, since they were never let go on this computer. They may come over in the meantime, so a drop is quick.

use std::collections::HashSet;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

use tandem_core::ffi::TandemShareOrigin;
use tauri::{AppHandle, Manager};

use crate::state::AppState;
use crate::{capture, events, i18n, input};

static OFFERS: Mutex<Option<HashSet<(String, u64)>>> = Mutex::new(None);

/// A drag that came over with the pointer and has not been let go here yet: its offers and the files of them that are in.
struct Carried {
    from: String,
    offers: HashSet<u64>,
    waiting: Vec<(String, String, PathBuf)>,
}
static CARRIED: Mutex<Option<Carried>> = Mutex::new(None);
/// Offers of a drag that went back without being let go: what still comes in of them is thrown away.
static DROPPED: Mutex<Vec<(String, u64)>> = Mutex::new(Vec::new());
/// Windows: the drag of the system went back to the other computer with the pointer, so its file is not put down.
#[cfg(windows)]
static ABANDONED: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

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
    if input::shared_is(from) && !input::left_up_since_enter() {
        let mut carried = CARRIED.lock().unwrap();
        match carried.as_mut().filter(|c| c.from == from) {
            Some(c) => {
                c.offers.insert(offer);
            }
            None => *carried = Some(Carried { from: from.to_string(), offers: HashSet::from([offer]), waiting: Vec::new() }),
        }
    }
    true
}

/// A file of such an offer is in. True when it was one of ours and was dealt with here.
pub fn finished(app: &AppHandle, peer: &str, offer: u64, name: &str, location: &Option<String>, error: &Option<String>) -> bool {
    let ours = OFFERS.lock().unwrap().as_ref().is_some_and(|set| set.contains(&(peer.to_string(), offer)));
    if !ours {
        return false;
    }
    {
        let mut dropped = DROPPED.lock().unwrap();
        if let Some(at) = dropped.iter().position(|(d, o)| d == peer && *o == offer) {
            if let Some(path) = location {
                let _ = std::fs::remove_file(path);
            }
            dropped.remove(at);
            return true;
        }
    }
    let from = events::device_name(app, peer);
    let (Some(path), None) = (location, error) else {
        events::say(app, &i18n::t1(app, "insert_failed", &from));
        return true;
    };
    if let Some(carried) = CARRIED.lock().unwrap().as_mut().filter(|c| c.from == peer && c.offers.contains(&offer)) {
        // Windows: the drag goes on as a drag of the system, so the file lands wherever the button comes up (a folder, a field of a
        // website). Not dropped anywhere: it is put away as before.
        #[cfg(windows)]
        {
            let (handle, who, label, file) = (app.clone(), from.clone(), name.to_string(), PathBuf::from(path));
            ABANDONED.store(false, std::sync::atomic::Ordering::SeqCst);
            let started = tandem_winsys::dragsource::start(vec![file.clone()], move |dropped| {
                if dropped {
                    return;
                }
                if ABANDONED.load(std::sync::atomic::Ordering::SeqCst) {
                    let _ = std::fs::remove_file(&file);
                } else {
                    land(&handle, &who, &label, &file);
                }
            });
            if started {
                return true;
            }
        }
        carried.waiting.push((from, name.to_string(), PathBuf::from(path)));
        return true;
    }
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
    // The button came up before the drag of the system had the press that was played for it: that press is let go too.
    #[cfg(windows)]
    tandem_winsys::dragsource::release();
    let mut waiting: Vec<_> = std::mem::take(&mut *WAITING.lock().unwrap());
    if let Some(carried) = CARRIED.lock().unwrap().take() {
        waiting.extend(carried.waiting);
    }
    for (from, name, path) in waiting {
        land(app, &from, &name, &path);
    }
}

/// The pointer of the other computer went back. A drag that came with it and was not let go here goes back with it: its files are
/// thrown away, and what is still coming of them is stopped.
pub fn shared_ended(app: &AppHandle) {
    #[cfg(windows)]
    if tandem_winsys::dragsource::active() {
        // The file of the drag of the system is thrown away too, not put down.
        ABANDONED.store(true, std::sync::atomic::Ordering::SeqCst);
        tandem_winsys::dragsource::cancel();
    }
    let Some(carried) = CARRIED.lock().unwrap().take() else { return };
    for (_, _, path) in &carried.waiting {
        let _ = std::fs::remove_file(path);
    }
    log::info!("pointer: a drag went back without being let go here, {} file(s) thrown away", carried.waiting.len());
    let mut dropped = DROPPED.lock().unwrap();
    if dropped.len() > 50 {
        dropped.clear();
    }
    dropped.extend(carried.offers.iter().map(|o| (carried.from.clone(), *o)));
    drop(dropped);
    let Ok(engine) = app.state::<AppState>().engine() else { return };
    for offer in carried.offers {
        let (engine, from) = (engine.clone(), carried.from.clone());
        tauri::async_runtime::spawn(async move {
            let _ = engine.cancel_transfer(from, offer, true).await;
        });
    }
}

fn land(app: &AppHandle, from: &str, name: &str, path: &Path) {
    // Windows puts it on the desktop, where the drop was. Linux has no desktop that shows files (GNOME has none), so it goes to the
    // downloads folder.
    let folder = if cfg!(windows) { app.path().desktop_dir().unwrap_or_else(|_| PathBuf::from(".")) } else { crate::settings::download_dir(app) };
    let placed = place(path, &folder);
    let body = i18n::t2(app, if cfg!(windows) { "drop_on_desktop" } else { "drop_in_downloads" }, name, from);
    // On Linux the desktop does not let an app bring the file manager to the front by itself: it only says the folder is ready, behind
    // the other windows. So one notice says where the file is, and a press on it opens the folder in front, with the file selected.
    #[cfg(target_os = "linux")]
    {
        let shown = placed.clone();
        let label = i18n::t(app, "show_in_folder");
        let buttons = [("default", ""), ("show", label.as_str())];
        let told = tandem_winsys::notify::tell("Tandem", &body, &buttons, move |key, token| {
            if key == "default" || key == "show" {
                tandem_winsys::notify::show_in_folder(&shown, token);
            }
        });
        if told.is_none() {
            events::toast(app, "Tandem", &body);
        }
    }
    // Windows does bring Explorer to the front, so the folder opens at once with the file selected.
    #[cfg(not(target_os = "linux"))]
    {
        events::toast(app, "Tandem", &body);
        if let Err(error) = tauri_plugin_opener::reveal_item_in_dir(&placed) {
            log::info!("the folder of {} could not be shown: {error}", placed.display());
        }
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
