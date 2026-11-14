//! How much room is left where files arrive, told to the other devices. A device that sends something bigger than that is asked
//! first whether to send anyway, and this computer refuses what does not fit before anything is fetched (P5). Without this number
//! neither happens, so it is told as soon as the engine runs and again when it changes.

use std::time::Duration;

use tandem_core::ffi::TandemStatus;
use tauri::{AppHandle, Manager};

use crate::state::AppState;

/// What is told when the disk cannot be read. The core only takes over a number that is there and cannot be told "unknown" again, so an
/// old number would keep refusing files; this one is more than any disk holds, so nothing is refused for lack of room.
const UNKNOWN: u64 = u64::MAX;

pub fn start(app: AppHandle) {
    std::thread::Builder::new()
        .name("tandem-storage".into())
        .spawn(move || {
            let mut told = None;
            loop {
                let folder = crate::settings::download_dir(&app);
                // A folder that is not there may be on a disk that is not plugged in; the disk below it would be another one.
                let now = if folder.exists() { tandem_winsys::diskspace::free_bytes(&folder) } else { None };
                let mut wait = Duration::from_secs(60);
                if let Some(number) = to_tell(now, told) {
                    match app.state::<AppState>().engine() {
                        Ok(engine) => {
                            log::info!("{:?} bytes are free in {}", now, folder.display());
                            tauri::async_runtime::block_on(engine.update_status(TandemStatus { free_storage: Some(number), ..Default::default() }));
                            told = Some(number);
                        }
                        // The engine is still starting.
                        Err(_) => wait = Duration::from_secs(3),
                    }
                }
                std::thread::sleep(wait);
            }
        })
        .ok();
}

/// The number to tell the other devices now, if it is not the one they have. A download in progress changes the number all the time;
/// they need it to within a few megabytes.
fn to_tell(now: Option<u64>, told: Option<u64>) -> Option<u64> {
    const STEP: u64 = 8 * 1024 * 1024;
    match (now, told) {
        (Some(a), Some(b)) if b != UNKNOWN && a.abs_diff(b) < STEP => None,
        (Some(a), _) => Some(a),
        // It could be read before and now cannot: the old number must not stay.
        (None, Some(b)) if b != UNKNOWN => Some(UNKNOWN),
        (None, _) => None,
    }
}

#[cfg(test)]
mod tests {
    use super::{to_tell, UNKNOWN};

    #[test]
    fn small_changes_are_not_told_again() {
        assert_eq!(to_tell(Some(1_000_000_000), Some(1_000_100_000)), None);
        assert_eq!(to_tell(Some(1_000_000_000), Some(900_000_000)), Some(1_000_000_000));
        assert_eq!(to_tell(Some(5), None), Some(5));
    }

    #[test]
    fn a_disk_that_cannot_be_read_any_more_does_not_keep_its_old_number() {
        assert_eq!(to_tell(None, Some(5_000_000_000)), Some(UNKNOWN));
        // Told once is enough, and a disk that never could be read says nothing.
        assert_eq!(to_tell(None, Some(UNKNOWN)), None);
        assert_eq!(to_tell(None, None), None);
        // And when it can be read again the real number is told.
        assert_eq!(to_tell(Some(5_000_000_000), Some(UNKNOWN)), Some(5_000_000_000));
    }
}
