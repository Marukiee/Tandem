//! The files of another device, looked at from this computer: its folders, and copying files from it into the download
//! folder. What the other device allows is decided there, so a refusal comes back as the reason it gives.

use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use serde_json::{json, Value};
use tandem_core::ffi::TandemFsProgress;
use tauri::{AppHandle, Emitter, State};

use crate::settings;
use crate::state::AppState;

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
        let mut last = self.last.lock().unwrap();
        if done < total && last.is_some_and(|at| at.elapsed() < Duration::from_millis(120)) {
            return;
        }
        *last = Some(Instant::now());
        let _ = self.app.emit("fs-progress", json!({ "name": self.name, "number": self.number, "count": self.count, "done": done, "total": total }));
    }
}

/// Copies files from the other device into the download folder. A folder is not copied yet, and says so.
#[tauri::command]
pub async fn fs_get(app: AppHandle, state: State<'_, AppState>, id: String, paths: Vec<String>, folders: Vec<bool>) -> Reply<Value> {
    let engine = state.engine()?;
    let folder = settings::download_dir(&app);
    std::fs::create_dir_all(&folder).map_err(shown)?;
    let mut saved = Vec::new();
    let mut skipped = 0usize;
    let count = paths.iter().enumerate().filter(|(index, _)| !folders.get(*index).copied().unwrap_or(false)).count();
    for (index, remote) in paths.iter().enumerate() {
        if folders.get(index).copied().unwrap_or(false) {
            skipped += 1;
            continue;
        }
        let name = local_name(remote)?;
        let target = free_name(&folder, &name);
        // The second lock: whatever the name, the file lands in the folder itself and nowhere else.
        if target.parent() != Some(folder.as_path()) {
            return Err(format!("refused the name {name:?} from the other device"));
        }
        let progress = Arc::new(CopyProgress { app: app.clone(), name: name.clone(), number: saved.len() + 1, count, last: Mutex::new(None) });
        engine.fs_download(id.clone(), remote.clone(), target.to_string_lossy().into_owned(), Some(progress)).await.map_err(shown)?;
        saved.push(target.to_string_lossy().into_owned());
    }
    Ok(json!({ "saved": saved, "skippedFolders": skipped }))
}

#[cfg(test)]
mod tests {
    use super::{free_name, local_name};

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
}
