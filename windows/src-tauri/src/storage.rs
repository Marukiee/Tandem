//! How much room is left where files arrive, told to the other devices. A device that sends something bigger than that is asked
//! first whether to send anyway, and this computer refuses what does not fit before anything is fetched (P5). Without this number
//! neither happens, so it is told as soon as the engine runs and again when it changes.

use std::time::Duration;

use tandem_core::ffi::TandemStatus;
use tauri::{AppHandle, Manager};

use crate::state::AppState;

pub fn start(app: AppHandle) {
    std::thread::Builder::new()
        .name("tandem-storage".into())
        .spawn(move || {
            let mut told = None;
            loop {
                let folder = crate::settings::download_dir(&app);
                // A chosen folder may not exist yet: the disk is the one of the nearest folder that does.
                let probe = folder.ancestors().find(|p| p.exists()).unwrap_or(&folder);
                let now = tandem_winsys::diskspace::free_bytes(probe);
                let mut wait = Duration::from_secs(60);
                if now.is_some() && !same_enough(now, told) {
                    match app.state::<AppState>().engine() {
                        Ok(engine) => {
                            log::info!("{:?} bytes are free in {}", now, folder.display());
                            tauri::async_runtime::block_on(engine.update_status(TandemStatus { free_storage: now, ..Default::default() }));
                            told = now;
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

/// A download in progress changes the number all the time; the other devices need it to within a few megabytes.
fn same_enough(now: Option<u64>, told: Option<u64>) -> bool {
    const STEP: u64 = 8 * 1024 * 1024;
    match (now, told) {
        (Some(a), Some(b)) => a.abs_diff(b) < STEP,
        (None, None) => true,
        _ => false,
    }
}

#[cfg(test)]
mod tests {
    use super::same_enough;

    #[test]
    fn small_changes_are_not_told_again() {
        assert!(same_enough(Some(1_000_000_000), Some(1_000_100_000)));
        assert!(!same_enough(Some(1_000_000_000), Some(900_000_000)));
        assert!(!same_enough(Some(5), None));
    }
}
