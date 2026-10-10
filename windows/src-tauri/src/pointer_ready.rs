//! Tells the computers that can share their pointer with this one whether this one can take it right now. Without it they send the
//! pointer over and get it straight back (a locked screen, a desktop that said no, a computer that is not allowed), and the person
//! sees a pointer that jumps and a message. With it their edge stays a wall until this computer can take the pointer, and opens the
//! moment it can, with no wait in between.
//!
//! `Size` says it can (it also says how big this screen is), `Release` says it cannot. Said when it changes, when a computer comes
//! online, and now and then again, so a computer that missed it, or was wrong about it, is put right.

use std::collections::HashMap;
use std::time::{Duration, Instant};

use tandem_core::ffi::{TandemPlatform, TandemPointerShare};
use tauri::{AppHandle, Manager};

use crate::state::AppState;
use crate::{arrange, commands, input, lid, settings};

const TICK: Duration = Duration::from_millis(1500);
const REPEAT: Duration = Duration::from_secs(10);

/// Whether a pointer that came over now would be played here, as far as it can be known without asking the desktop anything.
fn ready(app: &AppHandle, device: &str) -> bool {
    settings::pointer_allowed(app, device) && commands::input_blocked().is_none() && !lid::blocked(app) && input::could_take()
}

pub fn start(app: AppHandle) {
    std::thread::Builder::new()
        .name("tandem-pointer-ready".into())
        .spawn(move || {
            let mut told: HashMap<String, (bool, Instant)> = HashMap::new();
            let mut was_locked = lid::locked();
            loop {
                std::thread::sleep(TICK);
                let locked = lid::locked();
                if was_locked && !locked {
                    // The screen was locked and is not now: that may be why the desktop said no, so it is asked again.
                    input::unlocked();
                }
                was_locked = locked;
                let Ok(engine) = app.state::<AppState>().engine() else { continue };
                let devices = engine.devices();
                // A computer that went away is told again when it is back.
                told.retain(|id, _| devices.iter().any(|d| &d.id == id && d.online));
                for device in devices.iter().filter(|d| {
                    d.online
                        && d.caps.iter().any(|c| c == "pointer.ping")
                        && matches!(d.platform, TandemPlatform::MacOs | TandemPlatform::Windows | TandemPlatform::Linux)
                }) {
                    // While it has the pointer it knows well enough.
                    if input::shared_is(&device.id) {
                        continue;
                    }
                    let now_ready = ready(&app, &device.id);
                    let stale = told.get(&device.id).is_none_or(|(was, at)| *was != now_ready || at.elapsed() > REPEAT);
                    if !stale {
                        continue;
                    }
                    told.insert(device.id.clone(), (now_ready, Instant::now()));
                    let message = if now_ready {
                        let (width, height) = arrange::own_size();
                        TandemPointerShare::Size { width: width.max(1) as u32, height: height.max(1) as u32 }
                    } else {
                        TandemPointerShare::Release
                    };
                    let (engine, id) = (engine.clone(), device.id.clone());
                    tauri::async_runtime::spawn(async move {
                        let _ = engine.send_pointer_share(id, message).await;
                    });
                }
            }
        })
        .ok();
}
