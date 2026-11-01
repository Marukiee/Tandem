//! Keep awake: a laptop that is left alone, or closed, stays reachable from the other devices. One switch holds it for as long as it is
//! on; a device that looks at or uses this screen (`host.rs`) holds it on its own for as long as it does. Windows gets "system required"
//! and, for the lid, a lid close action of "do nothing" in the active power plan, put back afterwards. Linux gets an inhibitor of logind.

use std::sync::Mutex;
use std::time::Duration;

use tauri::{AppHandle, Emitter};

use crate::{events, i18n, settings};

/// Below this, on the battery, the switch turns itself off: a closed laptop in a bag that never sleeps would run flat.
const LOW_BATTERY: u8 = 15;

/// One request to stay awake. Dropping it gives it back.
struct Hold {
    #[cfg(windows)]
    _awake: tandem_winsys::StayAwake,
    #[cfg(target_os = "linux")]
    child: std::process::Child,
    #[cfg(target_os = "linux")]
    lid: bool,
}

#[cfg(target_os = "linux")]
impl Drop for Hold {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

struct Held {
    switch: Option<Hold>,
    hosting: Option<Hold>,
}

static HELD: Mutex<Held> = Mutex::new(Held { switch: None, hosting: None });

/// Whether this system can keep itself awake: Windows always, Linux with systemd.
pub fn available() -> bool {
    #[cfg(target_os = "linux")]
    {
        std::env::var_os("PATH").is_some_and(|paths| std::env::split_paths(&paths).any(|dir| dir.join("systemd-inhibit").is_file()))
    }
    #[cfg(not(target_os = "linux"))]
    {
        cfg!(windows)
    }
}

#[cfg(windows)]
fn hold(_lid: bool, _why: &str) -> Option<Hold> {
    Some(Hold { _awake: tandem_winsys::StayAwake::new() })
}

#[cfg(target_os = "linux")]
fn hold(lid: bool, why: &str) -> Option<Hold> {
    use std::process::{Command, Stdio};
    let what = if lid { "idle:sleep:handle-lid-switch" } else { "idle:sleep" };
    let mut command = Command::new("systemd-inhibit");
    // `cat` on a pipe instead of `sleep infinity`: when Tandem goes away in any way, a crash too, the pipe closes, `cat` ends and the
    // inhibitor goes with it. A sleep that outlived Tandem would keep a closed laptop awake until its battery is empty.
    command
        .args([format!("--what={what}"), "--who=Tandem".into(), format!("--why={why}"), "--mode=block".into(), "cat".into()])
        .stdin(Stdio::piped())
        .stdout(Stdio::null())
        .stderr(Stdio::null());
    tandem_winsys::system_env(&mut command);
    match command.spawn() {
        Ok(child) => Some(Hold { child, lid }),
        Err(error) => {
            log::warn!("systemd-inhibit did not start: {error}");
            None
        }
    }
}

#[cfg(not(any(windows, target_os = "linux")))]
fn hold(_lid: bool, _why: &str) -> Option<Hold> {
    None
}

/// Puts back a lid action that an earlier run changed and could not put back itself (it crashed), then does what the switch says, and
/// watches the battery.
pub fn start(app: AppHandle) {
    #[cfg(windows)]
    lid::restore(&app);
    configure(&app);
    std::thread::Builder::new()
        .name("tandem-keepawake".into())
        .spawn(move || {
            loop {
                std::thread::sleep(Duration::from_secs(60));
                guard(&app);
            }
        })
        .ok();
}

/// Does what the settings say now. Off the thread of the window: `powercfg` takes a moment.
pub fn configure(app: &AppHandle) {
    let app = app.clone();
    std::thread::spawn(move || {
        apply(&app);
        guard(&app);
    });
}

fn apply(app: &AppHandle) {
    let s = settings::get(app);
    let lid = s.keep_awake && s.keep_awake_lid;
    let mut held = HELD.lock().unwrap();
    #[cfg(target_os = "linux")]
    if held.switch.as_ref().is_some_and(|h| h.lid != lid) {
        held.switch = None;
    }
    if s.keep_awake && held.switch.is_none() {
        held.switch = hold(lid, "Keep awake is on");
    } else if !s.keep_awake {
        held.switch = None;
    }
    #[cfg(windows)]
    if lid {
        lid::stay_open(app);
    } else {
        lid::restore(app);
    }
    #[cfg(not(windows))]
    let _ = lid;
    drop(held);
    crate::tray::rebuild(app);
}

/// On the battery and almost empty: let it sleep again, and say so.
fn guard(app: &AppHandle) {
    if !settings::get(app).keep_awake {
        return;
    }
    let Some(battery) = tandem_winsys::battery() else { return };
    if battery.charging || battery.level > LOW_BATTERY {
        return;
    }
    log::info!("keep awake goes off, the battery is at {}%", battery.level);
    settings::update(app, |s| s.keep_awake = false);
    apply(app);
    events::toast(app, "Tandem", &i18n::t1(app, "keep_awake_battery", &battery.level.to_string()));
    let _ = app.emit("settings", settings::get(app));
}

/// Another device looks at or uses this screen (`on`), or no device does any more.
pub fn hosting(on: bool) {
    let mut held = HELD.lock().unwrap();
    if on && held.hosting.is_none() {
        held.hosting = hold(false, "Another device is using this screen");
    } else if !on {
        held.hosting = None;
    }
}

/// Tandem quits: everything is given back.
pub fn quit(app: &AppHandle) {
    let mut held = HELD.lock().unwrap();
    held.switch = None;
    held.hosting = None;
    #[cfg(windows)]
    lid::restore(app);
    #[cfg(not(windows))]
    let _ = app;
}

/// The lid close action of the active power plan. What it was is kept in a file until it is put back, so a crash cannot lose it.
#[cfg(windows)]
mod lid {
    use std::path::PathBuf;
    use std::process::Command;

    use tauri::{AppHandle, Manager};

    fn file(app: &AppHandle) -> Option<PathBuf> {
        app.path().app_data_dir().ok().map(|dir| dir.join("lid-action.json"))
    }

    fn powercfg(args: &[&str]) -> Option<String> {
        use std::os::windows::process::CommandExt;
        let mut command = Command::new("powercfg");
        // No console window flashing up.
        command.args(args).creation_flags(0x0800_0000);
        let output = command.output().ok()?;
        if !output.status.success() {
            log::warn!("powercfg {args:?} failed: {}", String::from_utf8_lossy(&output.stderr));
            return None;
        }
        Some(String::from_utf8_lossy(&output.stdout).into_owned())
    }

    fn set(ac: u32, dc: u32) -> bool {
        let (ac, dc) = (ac.to_string(), dc.to_string());
        powercfg(&["/setacvalueindex", "SCHEME_CURRENT", "SUB_BUTTONS", "LIDACTION", &ac]).is_some()
            && powercfg(&["/setdcvalueindex", "SCHEME_CURRENT", "SUB_BUTTONS", "LIDACTION", &dc]).is_some()
            && powercfg(&["/setactive", "SCHEME_CURRENT"]).is_some()
    }

    /// The lid does nothing when it closes. A file that is kept already means it was done, and after a crash the lid already does
    /// nothing, so what it did is only read when there is no file.
    pub fn stay_open(app: &AppHandle) {
        let Some(path) = file(app) else { return };
        if path.exists() {
            return;
        }
        let Some((ac, dc)) = powercfg(&["/q", "SCHEME_CURRENT", "SUB_BUTTONS", "LIDACTION"]).as_deref().and_then(super::current_lid_action) else {
            return;
        };
        if ac == 0 && dc == 0 {
            return;
        }
        if let Some(dir) = path.parent() {
            let _ = std::fs::create_dir_all(dir);
        }
        if std::fs::write(&path, serde_json::json!({ "ac": ac, "dc": dc }).to_string()).is_ok() {
            set(0, 0);
        }
    }

    /// Puts back what the lid did, when something was kept.
    pub fn restore(app: &AppHandle) {
        let Some(path) = file(app) else { return };
        let Ok(text) = std::fs::read_to_string(&path) else { return };
        let saved: serde_json::Value = serde_json::from_str(&text).unwrap_or_default();
        let value = |key: &str| saved[key].as_u64().map(|v| v as u32);
        let (Some(ac), Some(dc)) = (value("ac"), value("dc")) else {
            let _ = std::fs::remove_file(&path);
            return;
        };
        if set(ac, dc) {
            let _ = std::fs::remove_file(&path);
        }
    }
}

/// The current lid close action for the charger and the battery, from `powercfg /q ... LIDACTION`. Its words are in the language of
/// Windows, but the only numbers written as `0x...` are those two, in that order (the possible values are written as `000`).
#[cfg_attr(not(windows), allow(dead_code))]
fn current_lid_action(text: &str) -> Option<(u32, u32)> {
    let values: Vec<u32> = text.split_whitespace().filter_map(|w| w.strip_prefix("0x")).filter_map(|h| u32::from_str_radix(h, 16).ok()).collect();
    match values.as_slice() {
        [.., ac, dc] => Some((*ac, *dc)),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::current_lid_action;

    #[test]
    fn the_lid_action_is_read_in_any_language() {
        let english = "Power Scheme GUID: 381b4222-f694-41f0-9685-ff5bb260df2e  (Balanced)\r\n  Subgroup GUID: 4f971e89-eebd-4455-a8de-9e59040e7347  (Power buttons and lid)\r\n    GUID Alias: SUB_BUTTONS\r\n    Power Setting GUID: 5ca83367-6e45-459f-a27b-476b1d01c936  (Lid close action)\r\n      GUID Alias: LIDACTION\r\n      Possible Setting Index: 000\r\n      Possible Setting Friendly Name: Do nothing\r\n      Possible Setting Index: 001\r\n      Possible Setting Friendly Name: Sleep\r\n    Current AC Power Setting Index: 0x00000001\r\n    Current DC Power Setting Index: 0x00000002\r\n";
        assert_eq!(current_lid_action(english), Some((1, 2)));
        let dutch = "      Mogelijke instellingsindex: 000\r\n    Index van huidige wisselstroominstelling: 0x00000003\r\n    Index van huidige gelijkstroominstelling: 0x00000001\r\n";
        assert_eq!(current_lid_action(dutch), Some((3, 1)));
        assert_eq!(current_lid_action("nothing here"), None);
    }
}
