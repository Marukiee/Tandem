//! Messages over Bluetooth Low Energy for a computer that has no network to reach another device on (see docs/BLUETOOTH.md). The Linux
//! app does it through BlueZ (`tandem_winsys::ble`); Windows does not yet, so it says it cannot and the rest of the app goes on.

use tauri::AppHandle;

/// Whether this computer can take part: there is Bluetooth (an adapter, and BlueZ to talk to it) and this build knows how.
#[cfg(target_os = "linux")]
pub fn available() -> bool {
    static AVAILABLE: std::sync::OnceLock<bool> = std::sync::OnceLock::new();
    *AVAILABLE.get_or_init(tandem_winsys::ble::available)
}

#[cfg(not(target_os = "linux"))]
pub fn available() -> bool {
    false
}

#[cfg(not(target_os = "linux"))]
pub fn start(_app: &AppHandle) {}

/// Starts looking and waiting, as the state of the devices asks for it. Called once the engine runs.
#[cfg(target_os = "linux")]
pub fn start(app: &AppHandle) {
    use std::collections::HashSet;
    use std::sync::Arc;
    use std::time::Duration;

    use tandem_core::ffi::TandemPlatform;
    use tandem_winsys::ble::{Ble, Callbacks, Target};
    use tauri::Manager;

    if !available() {
        return;
    }
    let app = app.clone();
    std::thread::Builder::new()
        .name("tandem-ble-watch".into())
        .spawn(move || {
            let Ok(engine) = app.state::<crate::state::AppState>().engine() else { return };
            // The core is asynchronous; the threads of the Bluetooth module are not, so what is asked of it waits here.
            let (e_receive, e_hello, e_outbox, e_down, e_drop) = (engine.clone(), engine.clone(), engine.clone(), engine.clone(), engine.clone());
            let callbacks = Callbacks {
                receive: Box::new(move |link, chunk| tauri::async_runtime::block_on(e_receive.ble_receive(link.to_string(), chunk))),
                hello: Box::new(move |id, size| e_hello.ble_hello(id.to_string(), size as u32)),
                outbox: Box::new(move |id, size| e_outbox.ble_take_outbox(id.to_string(), size as u32)),
                link_down: Box::new(move |id| e_down.ble_link_down(id.to_string())),
                drop_link: Box::new(move |link| e_drop.ble_drop_link(link.to_string())),
                hint: Box::new(|id, hour| tandem_core::ffi::tandem_ble_hint(id.to_string(), hour)),
                nearby: Box::new(|_, _| {}),
            };
            let ble = match Ble::start(callbacks) {
                Ok(ble) => ble,
                Err(error) => return log::info!("Bluetooth messages are not available: {error}"),
            };
            // Two looks in a row must agree before anything is done, because a device that just dropped off the network usually comes
            // straight back.
            let mut before: HashSet<String> = HashSet::new();
            loop {
                std::thread::sleep(Duration::from_secs(3));
                let messages = crate::settings::get(&app).ble_messages;
                let away: HashSet<String> = if messages {
                    engine
                        .devices()
                        .iter()
                        .filter(|d| {
                            !d.online && (d.platform == TandemPlatform::Android || d.caps.iter().any(|c| c == "ble.link"))
                        })
                        .map(|d| d.id.clone())
                        .collect()
                } else {
                    HashSet::new()
                };
                let steady: Vec<String> = away.intersection(&before).cloned().collect();
                let devices = engine.devices();
                let is_phone = |id: &str| devices.iter().any(|d| d.id == id && d.platform == TandemPlatform::Android);
                // A phone is always the one that waits, so this computer looks for it. Of two computers the one with the lower id looks.
                let targets: Vec<Target> = steady
                    .iter()
                    .filter(|id| is_phone(id) || engine.ble_is_central_for(id.to_string()))
                    .map(|id| Target { id: id.clone(), can_link: engine.ble_ready(id.clone()) })
                    .collect();
                let waiting = steady.iter().any(|id| !is_phone(id) && !engine.ble_is_central_for(id.to_string()));
                ble.want(targets, waiting);
                before = away;
            }
        })
        .ok();
    let _ = Arc::new(());
}
