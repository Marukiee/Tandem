//! Unlocking this computer from another device whose person showed it is them (a fingerprint there). Linux only, through logind, and
//! only for a device that may always look at and control this computer. Off until switched on in the settings: the other device checks
//! the fingerprint, and this computer takes its word.

use std::sync::Mutex;
use std::time::Duration;

use tandem_core::ffi::{TandemMediaPermission, TandemStatus};
use tauri::{AppHandle, Manager};

use crate::state::AppState;
use crate::{events, i18n, settings};

/// logind is there to ask: only on Linux, and only where `loginctl` is.
pub fn available() -> bool {
    // Looked at every few seconds, and it does not change while the app runs.
    static THERE: std::sync::OnceLock<bool> = std::sync::OnceLock::new();
    *THERE.get_or_init(|| {
        cfg!(target_os = "linux") && std::process::Command::new("loginctl").arg("--version").output().is_ok_and(|out| out.status.success())
    })
}

fn offered(app: &AppHandle) -> bool {
    settings::get(app).unlock_from_afar && available()
}

pub fn start(app: AppHandle) {
    std::thread::Builder::new()
        .name("tandem-unlock".into())
        .spawn(move || loop {
            announce(&app);
            // Only while it is offered does the lock matter to anyone, so only then is logind asked.
            std::thread::sleep(Duration::from_secs(if offered(&app) { 2 } else { 30 }));
        })
        .ok();
}

/// What the other devices were last told: whether it can be unlocked from afar, and whether it is locked.
static TOLD: Mutex<Option<(Option<bool>, Option<bool>)>> = Mutex::new(None);

pub fn announce(app: &AppHandle) {
    let offer = offered(app);
    let now = (available().then_some(offer), Some(offer && crate::lid::locked()));
    let mut told = TOLD.lock().unwrap();
    if *told == Some(now) {
        return;
    }
    let Ok(engine) = app.state::<AppState>().engine() else { return };
    // The Bluetooth address goes with it, so a watch can be the mouse of this computer straight over Bluetooth.
    tauri::async_runtime::block_on(engine.update_status(TandemStatus {
        unlock: now.0,
        locked: now.1,
        lock: Some(can_lock()),
        bt_address: bluetooth_address(),
        ..Default::default()
    }));
    *told = Some(now);
}

/// This computer's Bluetooth address, "AA:BB:CC:DD:EE:FF". On Linux the first adapter says it in sysfs; on Windows that needs more of
/// the Windows API than this app has, so it is not given yet and the watch steers by way of the phone there.
fn bluetooth_address() -> Option<String> {
    #[cfg(target_os = "linux")]
    {
        let adapters = std::fs::read_dir("/sys/class/bluetooth").ok()?;
        for adapter in adapters.flatten() {
            if let Ok(address) = std::fs::read_to_string(adapter.path().join("address")) {
                let address = address.trim().to_uppercase();
                if address.len() == 17 && address != "00:00:00:00:00:00" {
                    return Some(address);
                }
            }
        }
        None
    }
    #[cfg(not(target_os = "linux"))]
    {
        None
    }
}

/// Another device asks to unlock. Everything is checked here again: the switch, the device, and that the screen is locked at all.
pub fn from_afar(app: &AppHandle, from: &str) {
    if !offered(app) || !crate::lid::locked() {
        return;
    }
    let Ok(engine) = app.state::<AppState>().engine() else { return };
    let Ok(policy) = engine.media_policy(from.to_string()) else { return };
    if policy.screen != TandemMediaPermission::Always || policy.control != TandemMediaPermission::Always {
        log::info!("unlock asked by a device that may not always look and control, ignored");
        return;
    }
    let done = std::process::Command::new("loginctl").args(["unlock-session", &session()]).status().is_ok_and(|s| s.success());
    if done {
        events::toast(app, "Tandem", &i18n::t1(app, "unlocked_by", &events::device_name(app, from)));
    } else {
        log::info!("loginctl could not unlock this session");
    }
    announce(app);
}

/// This computer can lock its screen when another device asks: logind on Linux, the system call on Windows.
pub fn can_lock() -> bool {
    cfg!(windows) || available()
}

/// Another device asks to lock this screen.
pub fn lock_now() {
    let done = if cfg!(windows) {
        std::process::Command::new("rundll32.exe").args(["user32.dll,LockWorkStation"]).status()
    } else {
        std::process::Command::new("loginctl").args(["lock-session", &session()]).status()
    };
    if !done.is_ok_and(|s| s.success()) {
        log::info!("this computer could not lock its screen");
    }
}

fn session() -> String {
    std::env::var("XDG_SESSION_ID").unwrap_or_else(|_| "auto".into())
}

