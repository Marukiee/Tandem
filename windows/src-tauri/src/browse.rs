//! The files of another device, looked at from this computer: its folders, copying files and folders from it into the download folder,
//! opening one of its files here, and putting files of this computer into one of its folders. What the other device allows is decided
//! there, so a refusal comes back as the reason it gives.

use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use serde_json::{json, Value};
use tandem_core::ffi::{TandemEngine, TandemFsProgress};
use tauri::{AppHandle, Emitter, State};
use tauri_plugin_dialog::DialogExt;
use tauri_plugin_opener::OpenerExt;

use crate::settings;
use crate::state::{AppState, Held};

type Reply<T> = Result<T, String>;

fn shown(e: impl std::fmt::Display) -> String {
    e.to_string()
}

/// The folders the other device shares with this one.
#[tauri::command]
pub async fn fs_roots(state: State<'_, AppState>, id: String) -> Reply<Value> {
    let roots = state.engine()?.fs_roots(id).await.map_err(shown)?;
    Ok(json!(roots.iter().map(|r| json!({ "name": r.name, "write": r.write })).collect::<Vec<_>>()))
}

/// What is in a folder: folders first, then files, each by name.
#[tauri::command]
pub async fn fs_list(state: State<'_, AppState>, id: String, path: String) -> Reply<Value> {
    let mut items = state.engine()?.fs_list(id, path).await.map_err(shown)?;
    items.sort_by(|a, b| b.dir.cmp(&a.dir).then_with(|| a.name.to_lowercase().cmp(&b.name.to_lowercase())));
    Ok(json!(items
        .iter()
        .map(|e| json!({ "name": e.name, "dir": e.dir, "size": e.size, "modifiedMs": e.modified_ms, "readonly": e.readonly }))
        .collect::<Vec<_>>()))
}

/// The last part of a path from the listing of another device, as a name in a folder here. That device chose the name, so one that
/// climbs out of the folder or names another place (`..`, a backslash, a drive letter, a control character) is refused.
fn local_name(remote: &str) -> Reply<String> {
    let name = remote.rsplit('/').next().unwrap_or("");
    let bytes = name.as_bytes();
    let drive = bytes.len() >= 2 && bytes[0].is_ascii_alphabetic() && bytes[1] == b':';
    if name.is_empty() || name == "." || name == ".." || name.contains('\\') || drive || name.chars().any(char::is_control) {
        return Err(format!("refused the name {name:?} from the other device"));
    }
    // A colon would name a stream of another file on Windows, and some characters cannot be in a name there at all.
    #[cfg(windows)]
    return Ok(crate::names::windows_safe(name));
    #[cfg(not(windows))]
    Ok(name.to_string())
}

/// A name that is free in the folder: `photo.jpg`, then `photo (2).jpg`, and so on.
fn free_name(folder: &Path, name: &str) -> PathBuf {
    let first = folder.join(name);
    if !first.exists() {
        return first;
    }
    let (stem, ext) = match name.rsplit_once('.') {
        Some((stem, ext)) if !stem.is_empty() => (stem.to_string(), format!(".{ext}")),
        _ => (name.to_string(), String::new()),
    };
    (2..)
        .map(|n| folder.join(format!("{stem} ({n}){ext}")))
        .find(|candidate| !candidate.exists())
        .unwrap_or(first)
}

/// How far a copy is, as `fs-progress` for the bar on the page of the files: the file, which one of how many, and its bytes.
struct CopyProgress {
    app: AppHandle,
    name: String,
    number: usize,
    count: usize,
    last: Mutex<Option<Instant>>,
}

impl TandemFsProgress for CopyProgress {
    fn progress(&self, done: u64, total: u64) {
        // A few times a second is enough for a bar, and the window is not flooded on a fast network.
        let mut last = self.last.held();
        if done < total && last.is_some_and(|at| at.elapsed() < Duration::from_millis(120)) {
            return;
        }
        *last = Some(Instant::now());
        let _ = self.app.emit("fs-progress", json!({ "name": self.name, "number": self.number, "count": self.count, "done": done, "total": total }));
    }
}

/// How deep a folder of the other device is followed, and how many things are copied from it at most: a device that lies could
/// otherwise hand out folders without end.
const MAX_DEPTH: usize = 32;
const MAX_ITEMS: usize = 50_000;

/// A name from a listing of the other device: one part, never a path.
fn listed_name(name: &str) -> Reply<String> {
    if name.contains('/') {
        return Err(format!("refused the name {name:?} from the other device"));
    }
    local_name(name)
}

/// `name` in `folder`, after the second lock: whatever the name, the path is an item of the folder itself and nothing deeper or higher.
fn inside(folder: &Path, name: &str) -> Reply<PathBuf> {
    let target = folder.join(name);
    let mut parts = Path::new(name).components();
    let one_part = matches!((parts.next(), parts.next()), (Some(std::path::Component::Normal(_)), None));
    if !one_part || target.parent() != Some(folder) {
        return Err(format!("refused the name {name:?} from the other device"));
    }
    Ok(target)
}

/// Copies files and folders from the other device into the download folder, folders with everything in them.
#[tauri::command]
pub async fn fs_get(app: AppHandle, state: State<'_, AppState>, id: String, paths: Vec<String>, folders: Vec<bool>) -> Reply<Value> {
    let engine = state.engine()?;
    let folder = settings::download_dir(&app);
    std::fs::create_dir_all(&folder).map_err(shown)?;
    let mut saved = Vec::new();
    let any_folder = folders.iter().any(|f| *f);
    // With a folder among them the number of files is not known beforehand, and the bar shows only the file.
    let count = if any_folder { 0 } else { paths.len() };
    let mut number = 0usize;
    for (index, remote) in paths.iter().enumerate() {
        let name = local_name(remote)?;
        let target = free_name(&folder, &name);
        if target.parent() != Some(folder.as_path()) {
            return Err(format!("refused the name {name:?} from the other device"));
        }
        if folders.get(index).copied().unwrap_or(false) {
            get_folder(&app, &engine, &id, remote, &target, &mut number).await?;
        } else {
            number += 1;
            get_file(&app, &engine, &id, remote, &target, &name, number, count).await?;
        }
        saved.push(target.to_string_lossy().into_owned());
    }
    Ok(json!({ "saved": saved, "files": number }))
}

#[allow(clippy::too_many_arguments)]
async fn get_file(app: &AppHandle, engine: &TandemEngine, id: &str, remote: &str, target: &Path, name: &str, number: usize, count: usize) -> Reply<()> {
    let progress = Arc::new(CopyProgress { app: app.clone(), name: name.to_string(), number, count, last: Mutex::new(None) });
    engine.fs_download(id.to_string(), remote.to_string(), target.to_string_lossy().into_owned(), Some(progress)).await.map_err(shown)?;
    crate::zone::mark_from_elsewhere(target);
    Ok(())
}

/// A folder of the other device with everything in it, a level at a time. Every name goes through the same checks as a single file.
async fn get_folder(app: &AppHandle, engine: &TandemEngine, id: &str, remote: &str, local: &Path, number: &mut usize) -> Reply<()> {
    std::fs::create_dir(local).map_err(shown)?;
    let mut queue = std::collections::VecDeque::from([(remote.to_string(), local.to_path_buf(), 0usize)]);
    let mut seen = 0usize;
    while let Some((remote, local, depth)) = queue.pop_front() {
        for item in engine.fs_list(id.to_string(), remote.clone()).await.map_err(shown)? {
            seen += 1;
            if seen > MAX_ITEMS {
                return Err(format!("the folder {remote:?} holds more than {MAX_ITEMS} things, so the copy stopped"));
            }
            let name = listed_name(&item.name)?;
            let target = inside(&local, &name)?;
            let path = format!("{}/{}", remote.trim_end_matches('/'), item.name);
            if item.dir {
                if depth + 1 > MAX_DEPTH {
                    return Err(format!("the folder {remote:?} goes deeper than {MAX_DEPTH} levels, so the copy stopped"));
                }
                std::fs::create_dir(&target).map_err(shown)?;
                queue.push_back((path, target, depth + 1));
            } else {
                *number += 1;
                get_file(app, engine, id, &path, &target, &name, *number, 0).await?;
            }
        }
    }
    Ok(())
}

/// Kinds that start something when opened, rather than show it: programs, installers, scripts, shortcuts, and disk images (a mounted
/// image hands out its files without the mark of the internet). Windows drops dots and spaces at the end of a name, so `x.exe.` is an
/// `.exe` there.
pub fn runs_something(name: &str) -> bool {
    const KINDS: &[&str] = &[
        // Windows
        "exe", "com", "scr", "pif", "bat", "cmd", "ps1", "psm1", "vbs", "vbe", "js", "jse", "wsf", "wsh", "hta", "msi", "msp", "msc",
        "lnk", "url", "reg", "cpl", "inf", "scf", "chm", "appref-ms", "application", "settingcontent-ms", "library-ms", "iso", "img",
        "vhd", "vhdx", "appx", "msix", "appxbundle", "msixbundle",
        // Linux, and what runs on both
        "sh", "bash", "zsh", "csh", "ksh", "fish", "run", "bin", "desktop", "appimage", "deb", "rpm", "flatpak", "flatpakref",
        "flatpakrepo", "snap", "py", "pyw", "pl", "rb", "jar", "elf", "out",
    ];
    let name = name.trim_end_matches(['.', ' ']);
    name.rsplit_once('.').is_some_and(|(stem, ext)| !stem.is_empty() && KINDS.contains(&ext.to_ascii_lowercase().as_str()))
}

/// Opens a file of the other device: copied into a folder of its own that only this user can enter, marked as from elsewhere, and handed
/// to the program the system has for it. A kind that can start a program is only opened once the person said so (`confirmed`): it comes
/// from another device and would otherwise run with one click. The answer is `{ "ask": true }` while that is still to be asked.
#[tauri::command]
pub async fn fs_open(app: AppHandle, state: State<'_, AppState>, id: String, path: String, confirmed: bool) -> Reply<Value> {
    let name = local_name(&path)?;
    if runs_something(&name) && !confirmed {
        return Ok(json!({ "ask": true, "name": name }));
    }
    let engine = state.engine()?;
    let base = crate::private::base(&app);
    let dir = crate::private::fresh_dir(&base, "Tandem-open").map_err(shown)?;
    let target = inside(&dir, &name)?;
    get_file(&app, &engine, &id, &path, &target, &name, 1, 1).await?;
    crate::commands::hand_out(&target);
    app.opener().open_path(target.to_string_lossy().into_owned(), None::<&str>).map_err(shown)?;
    Ok(json!({ "opened": true }))
}

/// Puts files and folders of this computer into a folder of the other device. A name that is taken there gets a number instead of
/// replacing what is there. Inside a folder, hidden files and links are left out, as the Mac does: a link could lead anywhere on this
/// computer, or round in a circle.
#[tauri::command]
pub async fn fs_put(app: AppHandle, state: State<'_, AppState>, id: String, folder: String, paths: Vec<String>) -> Reply<Value> {
    let engine = state.engine()?;
    let mut taken: Vec<String> = engine.fs_list(id.clone(), folder.clone()).await.map_err(shown)?.into_iter().map(|e| e.name).collect();
    let base = folder.trim_end_matches('/').to_string();
    let count = paths.len();
    let mut sent = 0usize;
    for (index, local) in paths.iter().enumerate() {
        let local = PathBuf::from(local);
        let Some(name) = local.file_name().map(|n| n.to_string_lossy().into_owned()) else { continue };
        let name = free_remote_name(&name, &taken);
        taken.push(name.clone());
        let remote = format!("{base}/{name}");
        // What the person chose is followed, also when it is a link; what is inside a folder is not.
        let meta = std::fs::metadata(&local).map_err(shown)?;
        if meta.is_dir() {
            put_folder(&app, &engine, &id, &local, &remote, &mut sent).await?;
        } else {
            sent += 1;
            put_file(&app, &engine, &id, &local, &remote, &name, index + 1, count).await?;
        }
    }
    Ok(json!({ "sent": sent }))
}

#[allow(clippy::too_many_arguments)]
async fn put_file(app: &AppHandle, engine: &TandemEngine, id: &str, local: &Path, remote: &str, name: &str, number: usize, count: usize) -> Reply<()> {
    let progress = Arc::new(CopyProgress { app: app.clone(), name: name.to_string(), number, count, last: Mutex::new(None) });
    engine.fs_upload(id.to_string(), local.to_string_lossy().into_owned(), remote.to_string(), false, Some(progress)).await.map_err(shown)
}

async fn put_folder(app: &AppHandle, engine: &TandemEngine, id: &str, local: &Path, remote: &str, sent: &mut usize) -> Reply<()> {
    let mut queue = std::collections::VecDeque::from([(local.to_path_buf(), remote.to_string())]);
    while let Some((local, remote)) = queue.pop_front() {
        engine.fs_mkdir(id.to_string(), remote.clone()).await.map_err(shown)?;
        let mut items: Vec<_> = std::fs::read_dir(&local).map_err(shown)?.flatten().collect();
        items.sort_by_key(|e| e.file_name());
        for item in items {
            let name = item.file_name().to_string_lossy().into_owned();
            let Ok(kind) = item.file_type() else { continue };
            if name.starts_with('.') || kind.is_symlink() {
                continue;
            }
            let path = format!("{remote}/{name}");
            if kind.is_dir() {
                queue.push_back((item.path(), path));
            } else if kind.is_file() {
                *sent += 1;
                put_file(app, engine, id, &item.path(), &path, &name, 0, 0).await?;
            }
        }
    }
    Ok(())
}

/// A name that is free among `taken` in the folder of the other device, numbered the way `free_name` numbers one here.
fn free_remote_name(name: &str, taken: &[String]) -> String {
    if !taken.iter().any(|t| t == name) {
        return name.to_string();
    }
    let (stem, ext) = match name.rsplit_once('.') {
        Some((stem, ext)) if !stem.is_empty() => (stem.to_string(), format!(".{ext}")),
        _ => (name.to_string(), String::new()),
    };
    (2..).map(|n| format!("{stem} ({n}){ext}")).find(|candidate| !taken.iter().any(|t| t == candidate)).unwrap_or_else(|| name.to_string())
}

/// Files of this computer chosen in the dialog of the system, put into the folder of the other device that is open.
#[tauri::command]
pub async fn fs_put_pick(app: AppHandle, state: State<'_, AppState>, id: String, folder: String) -> Reply<Value> {
    let picker = app.clone();
    let chosen = tauri::async_runtime::spawn_blocking(move || picker.dialog().file().blocking_pick_files()).await.map_err(shown)?;
    let paths: Vec<String> = chosen.unwrap_or_default().into_iter().filter_map(|f| f.into_path().ok()).map(|p| p.to_string_lossy().into_owned()).collect();
    if paths.is_empty() {
        return Ok(json!({ "sent": 0 }));
    }
    fs_put(app, state, id, folder, paths).await
}

#[cfg(test)]
mod tests {
    use super::{free_name, free_remote_name, inside, listed_name, local_name, runs_something};

    #[test]
    fn a_name_from_another_device_stays_in_the_folder() {
        assert_eq!(local_name("/Pictures/photo.jpg").unwrap(), "photo.jpg");
        assert_eq!(local_name("notes.txt").unwrap(), "notes.txt");
        for bad in ["/Pictures/..", "/a/.", "/a/", "", "..\\..\\evil.exe", "C:evil.exe", "/a/c:\\x", "/a/line\nbreak"] {
            assert!(local_name(bad).is_err(), "{bad:?} should be refused");
        }
    }

    #[test]
    fn a_taken_name_gets_a_number_before_the_extension() {
        let dir = std::env::temp_dir().join(format!("tandem-browse-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        assert_eq!(free_name(&dir, "photo.jpg"), dir.join("photo.jpg"));
        std::fs::write(dir.join("photo.jpg"), b"x").unwrap();
        assert_eq!(free_name(&dir, "photo.jpg"), dir.join("photo (2).jpg"));
        std::fs::write(dir.join("photo (2).jpg"), b"x").unwrap();
        assert_eq!(free_name(&dir, "photo.jpg"), dir.join("photo (3).jpg"));
        std::fs::write(dir.join("notes"), b"x").unwrap();
        assert_eq!(free_name(&dir, "notes"), dir.join("notes (2)"));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_name_inside_a_folder_of_the_other_device_is_one_part() {
        assert_eq!(listed_name("photo.jpg").unwrap(), "photo.jpg");
        for bad in ["a/b", "../x", "..", ".", "", "x\\y", "C:x"] {
            assert!(listed_name(bad).is_err(), "{bad:?} should be refused");
        }
        let folder = std::path::Path::new("/tmp/folder");
        assert_eq!(inside(folder, "a.txt").unwrap(), folder.join("a.txt"));
        assert!(inside(folder, "..").is_err());
        assert!(inside(folder, "a/b").is_err());
    }

    #[test]
    fn a_kind_that_starts_a_program_is_asked_about() {
        for risky in ["setup.exe", "SETUP.EXE", "run.sh", "x.exe.", "x.exe ", "Tandem.AppImage", "app.desktop", "link.lnk", "disk.iso", "a.tar.ps1"] {
            assert!(runs_something(risky), "{risky:?} should be asked about");
        }
        for plain in ["photo.jpg", "notes.txt", "exe", ".sh", "movie.mp4", "report.pdf"] {
            assert!(!runs_something(plain), "{plain:?} opens without a question");
        }
    }

    #[test]
    fn a_name_taken_on_the_other_device_gets_a_number() {
        let taken = vec!["photo.jpg".to_string(), "photo (2).jpg".to_string(), "notes".to_string()];
        assert_eq!(free_remote_name("new.jpg", &taken), "new.jpg");
        assert_eq!(free_remote_name("photo.jpg", &taken), "photo (3).jpg");
        assert_eq!(free_remote_name("notes", &taken), "notes (2)");
    }
}
