//! How much room is left on the disk a folder lives on. A transfer that would not fit is refused up front (P5), and for that the
//! app tells the other devices this number.

use std::path::Path;

/// The bytes a person may still write below `folder`, which is not always the whole free space of the disk (quotas). `None` when the
/// system cannot say, and then nothing is refused for lack of room.
#[cfg(windows)]
pub fn free_bytes(folder: &Path) -> Option<u64> {
    use std::os::windows::ffi::OsStrExt;
    use windows::Win32::Storage::FileSystem::GetDiskFreeSpaceExW;
    use windows::core::PCWSTR;

    let wide: Vec<u16> = folder.as_os_str().encode_wide().chain(std::iter::once(0)).collect();
    let mut available = 0u64;
    unsafe { GetDiskFreeSpaceExW(PCWSTR(wide.as_ptr()), Some(&mut available), None, None) }.ok()?;
    Some(available)
}

#[cfg(target_os = "linux")]
pub fn free_bytes(folder: &Path) -> Option<u64> {
    use std::os::unix::ffi::OsStrExt;

    let path = std::ffi::CString::new(folder.as_os_str().as_bytes()).ok()?;
    let mut stat = std::mem::MaybeUninit::<libc::statvfs>::zeroed();
    // SAFETY: `path` is a valid C string and `stat` is a buffer of the size the call fills.
    if unsafe { libc::statvfs(path.as_ptr(), stat.as_mut_ptr()) } != 0 {
        return None;
    }
    // SAFETY: the call succeeded, so it filled the struct.
    let stat = unsafe { stat.assume_init() };
    // `f_bavail` is what an ordinary user may use; `f_bfree` also counts the part reserved for root.
    Some((stat.f_bavail as u64).saturating_mul(stat.f_frsize as u64))
}

#[cfg(not(any(windows, target_os = "linux")))]
pub fn free_bytes(_folder: &Path) -> Option<u64> {
    None
}

#[cfg(all(test, any(windows, target_os = "linux")))]
mod tests {
    use super::*;

    #[test]
    fn a_folder_that_exists_has_a_free_space_and_one_that_does_not_has_none() {
        let here = std::env::temp_dir();
        assert!(free_bytes(&here).is_some_and(|bytes| bytes > 0));
        assert_eq!(free_bytes(&here.join("tandem-no-such-folder-for-the-test")), None);
    }
}
