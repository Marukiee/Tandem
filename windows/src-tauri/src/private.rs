//! Folders of this program's own, made for one occasion: a download of an update, a file of another device that is opened. A place with
//! a fixed name in the temporary folder can be made beforehand by another user of the computer, who then swaps what lands there.

use std::io::{self, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU32, Ordering};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use tauri::{AppHandle, Manager};

/// Where such folders go: the cache folder of the app, which belongs to the person, or the temporary folder when there is none.
pub fn base(app: &AppHandle) -> PathBuf {
    app.path().app_cache_dir().unwrap_or_else(|_| std::env::temp_dir())
}

/// A new, empty folder under `base` that only this user can enter. Folders with the same prefix older than a day are cleared first:
/// what was opened or installed from them is long done.
pub fn fresh_dir(base: &Path, prefix: &str) -> io::Result<PathBuf> {
    static COUNT: AtomicU32 = AtomicU32::new(0);
    std::fs::create_dir_all(base)?;
    sweep(base, prefix, Duration::from_secs(24 * 3600));
    let nanos = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.subsec_nanos()).unwrap_or(0);
    let names = (0..64).map(|_| format!("{prefix}-{}-{nanos:x}-{}", std::process::id(), COUNT.fetch_add(1, Ordering::Relaxed)));
    fresh_dir_from(base, names)
}

/// The first of `names` that can be made. Each is made with one call that fails when the name is taken, so a folder somebody put
/// there first is never used.
fn fresh_dir_from(base: &Path, names: impl Iterator<Item = String>) -> io::Result<PathBuf> {
    for name in names {
        let dir = base.join(name);
        match make(&dir) {
            Ok(()) => return Ok(dir),
            Err(e) if e.kind() == io::ErrorKind::AlreadyExists => continue,
            Err(e) => return Err(e),
        }
    }
    Err(io::Error::new(io::ErrorKind::AlreadyExists, "no free name for a folder of its own"))
}

#[cfg(unix)]
fn make(dir: &Path) -> io::Result<()> {
    use std::os::unix::fs::DirBuilderExt;
    std::fs::DirBuilder::new().mode(0o700).create(dir)
}

/// The temporary and cache folders of Windows are in the person's own profile, which other users cannot open.
#[cfg(not(unix))]
fn make(dir: &Path) -> io::Result<()> {
    std::fs::create_dir(dir)
}

fn sweep(base: &Path, prefix: &str, older_than: Duration) {
    let Ok(entries) = std::fs::read_dir(base) else { return };
    let start = format!("{prefix}-");
    for entry in entries.flatten() {
        let old = entry.metadata().and_then(|m| m.modified()).ok().and_then(|at| at.elapsed().ok()).is_some_and(|age| age > older_than);
        if old && entry.file_name().to_string_lossy().starts_with(&start) {
            let _ = std::fs::remove_dir_all(entry.path());
        }
    }
}

/// Writes `bytes` to a file that did not exist before, so never through a file or a link that was left in its place.
pub fn write_new(path: &Path, bytes: &[u8], #[allow(unused_variables)] mode: u32) -> io::Result<()> {
    let mut options = std::fs::OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(mode);
    }
    let mut file = options.open(path)?;
    file.write_all(bytes)?;
    file.sync_all()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn scratch(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("tandem-private-test-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn every_folder_is_new_and_a_name_that_is_taken_is_passed_by() {
        let base = scratch("fresh");
        let a = fresh_dir(&base, "Tandem-update").unwrap();
        let b = fresh_dir(&base, "Tandem-update").unwrap();
        assert_ne!(a, b);
        assert!(a.is_dir() && b.is_dir());
        // Somebody made the first name beforehand, with a file in it: that folder is left alone.
        std::fs::create_dir(base.join("taken")).unwrap();
        std::fs::write(base.join("taken").join("planted"), b"x").unwrap();
        let c = fresh_dir_from(&base, ["taken".to_string(), "free".to_string()].into_iter()).unwrap();
        assert_eq!(c, base.join("free"));
        assert!(fresh_dir_from(&base, ["taken".to_string()].into_iter()).is_err());
        let _ = std::fs::remove_dir_all(&base);
    }

    #[cfg(unix)]
    #[test]
    fn only_this_user_can_enter_the_folder() {
        use std::os::unix::fs::PermissionsExt;
        let base = scratch("mode");
        let dir = fresh_dir(&base, "Tandem-open").unwrap();
        assert_eq!(std::fs::metadata(&dir).unwrap().permissions().mode() & 0o777, 0o700);
        let _ = std::fs::remove_dir_all(&base);
    }

    #[test]
    fn a_new_file_is_never_written_through_one_that_is_there() {
        let base = scratch("write");
        let file = base.join("setup.exe");
        write_new(&file, b"checked", 0o755).unwrap();
        assert_eq!(std::fs::read(&file).unwrap(), b"checked");
        assert!(write_new(&file, b"other", 0o755).is_err());
        assert_eq!(std::fs::read(&file).unwrap(), b"checked");
        let _ = std::fs::remove_dir_all(&base);
    }
}
