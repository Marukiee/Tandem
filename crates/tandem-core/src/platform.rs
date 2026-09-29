//! What the core needs from the operating system and cannot do itself.

use std::io;
use std::path::{Path, PathBuf};

/// Reading files to send and placing files that arrived.
///
/// On desktop a source is a path. On Android it is a content URI and the app turns it
/// into a file descriptor, which is why the source is an opaque string.
pub trait FileStore: Send + Sync {
    fn open_read(&self, source: &str) -> io::Result<std::fs::File>;

    /// Moves a finished download from the private temp file to where the person
    /// will look for it. Returns the final location (a path or a content URI).
    fn store_download(&self, temp: &Path, name: &str, mime: &str) -> io::Result<String>;
}

pub struct DesktopFiles {
    pub download_dir: PathBuf,
}

impl FileStore for DesktopFiles {
    fn open_read(&self, source: &str) -> io::Result<std::fs::File> {
        std::fs::File::open(source)
    }

    fn store_download(&self, temp: &Path, name: &str, _mime: &str) -> io::Result<String> {
        std::fs::create_dir_all(&self.download_dir)?;
        let target = unique_path(&self.download_dir, &sanitize_name(name));
        if std::fs::rename(temp, &target).is_err() {
            // Different volume: copy, then drop the temp file.
            std::fs::copy(temp, &target)?;
            std::fs::remove_file(temp)?;
        }
        Ok(target.to_string_lossy().into_owned())
    }
}

/// A file name that is safe to create: no separators, no control characters, not
/// hidden, not empty.
pub fn sanitize_name(name: &str) -> String {
    let cleaned: String = name
        .chars()
        .map(|c| if c == '/' || c == '\\' || c == ':' || c.is_control() { '_' } else { c })
        .collect();
    let cleaned = cleaned.trim().trim_start_matches('.').to_string();
    let cleaned: String = cleaned.chars().take(180).collect();
    if cleaned.is_empty() { "file".to_string() } else { cleaned }
}

/// `name.ext`, then `name (1).ext`, `name (2).ext`, and so on.
pub fn unique_path(dir: &Path, name: &str) -> PathBuf {
    let candidate = dir.join(name);
    if !candidate.exists() {
        return candidate;
    }
    let (stem, ext) = match name.rfind('.') {
        Some(dot) if dot > 0 => (&name[..dot], &name[dot..]),
        _ => (name, ""),
    };
    for n in 1..10_000 {
        let candidate = dir.join(format!("{stem} ({n}){ext}"));
        if !candidate.exists() {
            return candidate;
        }
    }
    dir.join(format!("{stem}-{}{ext}", crate::ids::now_ms()))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn names_are_made_safe() {
        assert_eq!(sanitize_name("../../etc/passwd"), "_.._etc_passwd");
        assert_eq!(sanitize_name(".hidden"), "hidden");
        assert_eq!(sanitize_name("  "), "file");
        assert_eq!(sanitize_name("a:b.txt"), "a_b.txt");
    }

    #[test]
    fn clashes_get_a_counter() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::write(dir.path().join("photo.jpg"), b"x").unwrap();
        let next = unique_path(dir.path(), "photo.jpg");
        assert_eq!(next.file_name().unwrap(), "photo (1).jpg");
    }
}
