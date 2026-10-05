//! The files of another device, looked at from this computer: its folders, and copying files from it into the download
//! folder. What the other device allows is decided there, so a refusal comes back as the reason it gives.

use std::path::{Path, PathBuf};

use serde_json::{json, Value};
use tauri::{AppHandle, State};

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

/// Copies files from the other device into the download folder. A folder is not copied yet, and says so.
#[tauri::command]
pub async fn fs_get(app: AppHandle, state: State<'_, AppState>, id: String, paths: Vec<String>, folders: Vec<bool>) -> Reply<Value> {
    let engine = state.engine()?;
    let folder = settings::download_dir(&app);
    std::fs::create_dir_all(&folder).map_err(shown)?;
    let mut saved = Vec::new();
    let mut skipped = 0usize;
    for (index, remote) in paths.iter().enumerate() {
        if folders.get(index).copied().unwrap_or(false) {
            skipped += 1;
            continue;
        }
        let name = remote.rsplit('/').next().unwrap_or("file");
        let target = free_name(&folder, name);
        engine.fs_download(id.clone(), remote.clone(), target.to_string_lossy().into_owned(), None).await.map_err(shown)?;
        saved.push(target.to_string_lossy().into_owned());
    }
    Ok(json!({ "saved": saved, "skippedFolders": skipped }))
}

#[cfg(test)]
mod tests {
    use super::free_name;

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
