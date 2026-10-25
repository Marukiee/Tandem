//! A screenshot taken on this computer goes to the clipboards of the devices that share the clipboard with it, as a copied picture
//! does (`clipfiles`). The system does not say that it took one, so the folder it saves them in is watched: the Screenshots folder of
//! Windows (Windows key with Print Screen, and the Snipping Tool when it saves), and on Linux the Screenshots folder in Pictures
//! (GNOME, KDE). Windows and GNOME also put the screenshot on the clipboard; `clipfiles` sees that it is the same picture and sends
//! it once.

use std::path::{Path, PathBuf};
use std::time::Duration;

use tauri::{AppHandle, Manager};

pub fn start(app: &AppHandle) {
    let folders = folders(app);
    if folders.is_empty() {
        return;
    }
    let handle = app.clone();
    let watching = tandem_winsys::folderwatch::watch(folders.clone(), move |path| {
        let Some(root) = folders.iter().find(|f| path.parent() == Some(f.as_path())) else { return };
        if !counts(root, &path, &pictures_root(&handle)) {
            return;
        }
        let app = handle.clone();
        // Off the thread that is told about the folder, which should hear the next one.
        std::thread::spawn(move || {
            if let Some(path) = settled(&path) {
                crate::clipfiles::screenshot_taken(&app, &path);
            }
        });
    });
    log::info!("screenshots are {}", if watching { "watched" } else { "not watched (no folder)" });
}

fn pictures_root(app: &AppHandle) -> Option<PathBuf> {
    app.path().picture_dir().ok()
}

/// The folders screenshots land in. On Linux the Pictures folder itself too: GNOME before version 42 saved them there.
fn folders(app: &AppHandle) -> Vec<PathBuf> {
    #[cfg_attr(not(any(windows, target_os = "linux")), allow(unused_mut))]
    let mut list = Vec::new();
    #[cfg(windows)]
    {
        let _ = app;
        list.extend(tandem_winsys::folderwatch::screenshots_folder());
    }
    #[cfg(target_os = "linux")]
    if let Some(pictures) = pictures_root(app) {
        list.push(pictures.clone());
        for name in screenshot_folder_names() {
            let folder = pictures.join(name);
            if !list.contains(&folder) {
                list.push(folder);
            }
        }
    }
    #[cfg(not(any(windows, target_os = "linux")))]
    let _ = app;
    list
}

/// GNOME names the folder in the language of the desktop ("Screenshots" is translated), so the translation is asked of the system
/// as GNOME itself would get it. KDE's Spectacle uses "Screenshots".
#[cfg(target_os = "linux")]
fn screenshot_folder_names() -> Vec<String> {
    let mut names = vec!["Screenshots".to_string()];
    let translated = std::process::Command::new("gettext")
        .args(["-d", "gnome-shell", "Screenshots"])
        .output()
        .ok()
        .and_then(|out| String::from_utf8(out.stdout).ok())
        .map(|t| t.trim().to_string())
        .filter(|t| !t.is_empty() && !t.contains('/'));
    names.extend(translated);
    names.dedup();
    names
}

/// A picture, and in the Pictures folder itself only one that is named as a screenshot: the other pictures there are not.
fn counts(root: &Path, path: &Path, pictures: &Option<PathBuf>) -> bool {
    let name = path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
    if name.starts_with('.') || !is_picture(path) {
        return false;
    }
    if pictures.as_deref() == Some(root) {
        return name.to_lowercase().starts_with("screenshot");
    }
    true
}

fn is_picture(path: &Path) -> bool {
    let ext = path.extension().map(|e| e.to_string_lossy().to_lowercase()).unwrap_or_default();
    matches!(ext.as_str(), "png" | "jpg" | "jpeg" | "webp" | "bmp")
}

/// Windows says a file is there the moment it is made: the picture counts once it has stopped growing and can be read.
fn settled(path: &Path) -> Option<PathBuf> {
    let mut last = None;
    for _ in 0..20 {
        let size = std::fs::metadata(path).ok().map(|m| m.len());
        if size.is_some() && size == last && size != Some(0) {
            return Some(path.to_path_buf());
        }
        last = size;
        std::thread::sleep(Duration::from_millis(120));
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_pictures_count_and_in_pictures_only_screenshots() {
        let pictures = Some(PathBuf::from("/home/me/Pictures"));
        let root = Path::new("/home/me/Pictures");
        let shots = Path::new("/home/me/Pictures/Screenshots");
        assert!(counts(shots, &shots.join("Screenshot From 2026-10-25 10-00-00.png"), &pictures));
        assert!(counts(shots, &shots.join("Schermafdruk van 2026-10-25.png"), &pictures));
        assert!(!counts(shots, &shots.join(".goutputstream-ABC123"), &pictures));
        assert!(!counts(shots, &shots.join("notes.txt"), &pictures));
        assert!(counts(root, &root.join("Screenshot from 2020-01-01.png"), &pictures));
        assert!(!counts(root, &root.join("holiday.jpg"), &pictures));
    }
}
