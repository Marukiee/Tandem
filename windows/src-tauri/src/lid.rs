//! The lid of a laptop. With the lid closed nobody is looking at the screen, so nothing may keep using this computer from another one:
//! a pointer that was sent here comes home at once, and does not come over while the lid is down. A person who works with the lid
//! closed on purpose (the laptop on a dock, with another screen) turns that on in the settings.

use std::time::Duration;

use tauri::AppHandle;

use crate::settings;

/// Whether this machine has a lid that can be asked about.
pub fn present() -> bool {
    static PRESENT: std::sync::OnceLock<bool> = std::sync::OnceLock::new();
    *PRESENT.get_or_init(|| tandem_winsys::lid_closed().is_some())
}

/// Whether other computers must stay away from this one now.
pub fn blocked(app: &AppHandle) -> bool {
    !settings::get(app).keep_when_lid_closed && tandem_winsys::lid_closed() == Some(true)
}

/// Watches the lid. When it closes while another computer has the pointer, the pointer goes back.
pub fn start(app: AppHandle) {
    if !present() {
        return;
    }
    std::thread::Builder::new()
        .name("tandem-lid".into())
        .spawn(move || {
            let mut was_closed = false;
            loop {
                std::thread::sleep(Duration::from_millis(600));
                let closed = tandem_winsys::lid_closed() == Some(true);
                if closed && !settings::get(&app).keep_when_lid_closed {
                    crate::input::shared_stop_here();
                    if !was_closed {
                        #[cfg(feature = "screen-host")]
                        crate::host::stop_all(&app);
                    }
                }
                was_closed = closed;
            }
        })
        .ok();
}
