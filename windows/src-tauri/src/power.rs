//! How full the battery of this PC is, told to the other devices so the phone can show it next to the PC the way it
//! does for the Mac. A PC without a battery says nothing.

use std::time::Duration;

use tandem_core::ffi::{TandemBattery, TandemStatus};
use tauri::{AppHandle, Manager};

use crate::state::AppState;

pub fn start(app: AppHandle) {
    std::thread::Builder::new()
        .name("tandem-power".into())
        .spawn(move || {
            let mut told = None;
            loop {
                let now = tandem_winsys::battery();
                let mut wait = Duration::from_secs(60);
                if now.is_some() && now != told {
                    match app.state::<AppState>().engine() {
                        Ok(engine) => {
                            log::info!("the battery is at {:?}", now);
                            let battery = now.map(|b| TandemBattery { level: b.level, charging: b.charging, power_save: b.saver });
                            tauri::async_runtime::block_on(engine.update_status(TandemStatus { battery, ..Default::default() }));
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
