//! Being told when a file appears in a folder, by the system instead of by looking every so often: inotify on Linux,
//! `ReadDirectoryChangesW` on Windows. Used for the folder that screenshots are saved in.

use std::path::PathBuf;

/// Calls `on_new` with the path of each file that appears in one of `folders`. On Linux that is once it is written and closed, or
/// moved in; on Windows the moment it is made, so the caller waits until it can be read whole. A folder that is not there yet is
/// picked up when it is made inside another one of the list. True when at least one folder is watched.
pub fn watch(folders: Vec<PathBuf>, on_new: impl Fn(PathBuf) + Send + Sync + 'static) -> bool {
    imp::watch(folders, on_new)
}

/// The Screenshots folder of Windows (where the Print Screen key with the Windows key and the Snipping Tool save), which can be
/// moved, to OneDrive for one. Made when it is not there yet, as Windows itself would.
pub fn screenshots_folder() -> Option<PathBuf> {
    imp::screenshots_folder()
}

#[cfg(target_os = "linux")]
mod imp {
    use std::collections::HashMap;
    use std::ffi::{CString, OsStr};
    use std::os::unix::ffi::OsStrExt;
    use std::path::PathBuf;

    pub fn watch(folders: Vec<PathBuf>, on_new: impl Fn(PathBuf) + Send + Sync + 'static) -> bool {
        let fd = unsafe { libc::inotify_init1(libc::IN_CLOEXEC) };
        if fd < 0 {
            return false;
        }
        let add = move |dir: &PathBuf| -> Option<i32> {
            let path = CString::new(dir.as_os_str().as_bytes()).ok()?;
            let mask = libc::IN_CLOSE_WRITE | libc::IN_MOVED_TO | libc::IN_CREATE;
            let wd = unsafe { libc::inotify_add_watch(fd, path.as_ptr(), mask) };
            (wd >= 0).then_some(wd)
        };
        let mut watched: HashMap<i32, PathBuf> = HashMap::new();
        for dir in &folders {
            if let Some(wd) = add(dir) {
                watched.insert(wd, dir.clone());
            }
        }
        if watched.is_empty() {
            unsafe { libc::close(fd) };
            return false;
        }
        let started = std::thread::Builder::new().name("tandem-folders".into()).spawn(move || {
            let mut buffer = vec![0u8; 64 * 1024];
            loop {
                let read = unsafe { libc::read(fd, buffer.as_mut_ptr().cast(), buffer.len()) };
                if read <= 0 {
                    if std::io::Error::last_os_error().kind() == std::io::ErrorKind::Interrupted {
                        continue;
                    }
                    return;
                }
                let mut offset = 0usize;
                let header = std::mem::size_of::<libc::inotify_event>();
                while offset + header <= read as usize {
                    let event: libc::inotify_event = unsafe { std::ptr::read_unaligned(buffer.as_ptr().add(offset).cast()) };
                    let name_start = offset + header;
                    let name_end = (name_start + event.len as usize).min(read as usize);
                    offset = name_end;
                    let raw = &buffer[name_start..name_end];
                    let raw = &raw[..raw.iter().position(|b| *b == 0).unwrap_or(raw.len())];
                    let Some(dir) = watched.get(&event.wd).cloned() else { continue };
                    if raw.is_empty() {
                        continue;
                    }
                    let path = dir.join(OsStr::from_bytes(raw));
                    if event.mask & libc::IN_ISDIR != 0 {
                        // A folder of the list that was not there yet (GNOME makes its Screenshots folder with the first screenshot).
                        if event.mask & libc::IN_CREATE != 0 && folders.contains(&path) {
                            if let Some(wd) = add(&path) {
                                watched.insert(wd, path);
                            }
                        }
                        continue;
                    }
                    // A file that is only made is not written yet; it comes again when it is closed.
                    if event.mask & (libc::IN_CLOSE_WRITE | libc::IN_MOVED_TO) != 0 {
                        on_new(path);
                    }
                }
            }
        });
        started.is_ok()
    }

    pub fn screenshots_folder() -> Option<PathBuf> {
        None
    }
}

#[cfg(windows)]
mod imp {
    use std::os::windows::ffi::{OsStrExt, OsStringExt};
    use std::path::PathBuf;
    use std::sync::Arc;

    use windows::Win32::Foundation::{CloseHandle, HANDLE};
    use windows::Win32::Storage::FileSystem::{
        CreateFileW, FILE_ACTION_ADDED, FILE_ACTION_RENAMED_NEW_NAME, FILE_FLAG_BACKUP_SEMANTICS, FILE_LIST_DIRECTORY,
        FILE_NOTIFY_CHANGE_FILE_NAME, FILE_NOTIFY_INFORMATION, FILE_SHARE_DELETE, FILE_SHARE_READ, FILE_SHARE_WRITE, OPEN_EXISTING,
        ReadDirectoryChangesW,
    };
    use windows::Win32::System::Com::CoTaskMemFree;
    use windows::Win32::UI::Shell::{FOLDERID_Screenshots, KF_FLAG_CREATE, SHGetKnownFolderPath};
    use windows::core::PCWSTR;

    pub fn watch(folders: Vec<PathBuf>, on_new: impl Fn(PathBuf) + Send + Sync + 'static) -> bool {
        let on_new = Arc::new(on_new);
        let mut any = false;
        for dir in folders {
            let wide: Vec<u16> = dir.as_os_str().encode_wide().chain(std::iter::once(0)).collect();
            let handle = unsafe {
                CreateFileW(
                    PCWSTR(wide.as_ptr()),
                    FILE_LIST_DIRECTORY.0,
                    FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                    None,
                    OPEN_EXISTING,
                    FILE_FLAG_BACKUP_SEMANTICS,
                    None,
                )
            };
            let Ok(handle) = handle else { continue };
            let on_new = on_new.clone();
            // A handle is a number the thread may hold; it is not shared with anything else.
            let raw = handle.0 as isize;
            let started = std::thread::Builder::new().name("tandem-folder".into()).spawn(move || {
                let handle = HANDLE(raw as *mut core::ffi::c_void);
                // u32 for the alignment that the records need.
                let mut buffer = vec![0u32; 16 * 1024];
                loop {
                    let mut returned = 0u32;
                    let ok = unsafe {
                        ReadDirectoryChangesW(
                            handle,
                            buffer.as_mut_ptr().cast(),
                            (buffer.len() * 4) as u32,
                            false,
                            FILE_NOTIFY_CHANGE_FILE_NAME,
                            Some(&mut returned),
                            None,
                            None,
                        )
                    };
                    if ok.is_err() {
                        unsafe {
                            let _ = CloseHandle(handle);
                        }
                        return;
                    }
                    let bytes = buffer.as_ptr() as *const u8;
                    let mut offset = 0usize;
                    while returned > 0 && offset < returned as usize {
                        let info = unsafe { &*(bytes.add(offset) as *const FILE_NOTIFY_INFORMATION) };
                        let length = info.FileNameLength as usize / 2;
                        let name = unsafe { std::slice::from_raw_parts(info.FileName.as_ptr(), length) };
                        if info.Action == FILE_ACTION_ADDED || info.Action == FILE_ACTION_RENAMED_NEW_NAME {
                            on_new(dir.join(std::ffi::OsString::from_wide(name)));
                        }
                        if info.NextEntryOffset == 0 {
                            break;
                        }
                        offset += info.NextEntryOffset as usize;
                    }
                }
            });
            any |= started.is_ok();
        }
        any
    }

    pub fn screenshots_folder() -> Option<PathBuf> {
        unsafe {
            let path = SHGetKnownFolderPath(&FOLDERID_Screenshots, KF_FLAG_CREATE, None).ok()?;
            let text = path.to_string().ok();
            CoTaskMemFree(Some(path.0 as *const core::ffi::c_void));
            text.map(PathBuf::from)
        }
    }
}

#[cfg(not(any(target_os = "linux", windows)))]
mod imp {
    use std::path::PathBuf;

    pub fn watch(_folders: Vec<PathBuf>, _on_new: impl Fn(PathBuf) + Send + Sync + 'static) -> bool {
        false
    }

    pub fn screenshots_folder() -> Option<PathBuf> {
        None
    }
}
