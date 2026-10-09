//! Turns Tailscale on when a device of the circle cannot be reached on the network this computer is on, and Tailscale is what would
//! reach it. The core says so (`TailscaleNeeded`) only for a device that does not answer on the local network, that is known by a
//! Tailscale address, while this computer has none of its own. A device on the same network never gets here. The person can turn this
//! off in the settings; Tailscale itself, the sign-in and the network stay Tailscale's business, this only runs `tailscale up`.

use std::path::PathBuf;
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

use serde_json::Value;
use tauri::AppHandle;

use crate::{events, i18n, settings};

/// What Tailscale says about itself.
#[derive(Debug, PartialEq, Eq)]
pub enum Backend {
    Running,
    /// Installed, signed in and off.
    Stopped,
    /// Wants a sign-in first.
    NeedsLogin,
    Other,
}

fn candidates() -> Vec<PathBuf> {
    let mut found = vec![PathBuf::from("tailscale")];
    if cfg!(windows) {
        found.push(PathBuf::from(r"C:\Program Files\Tailscale\tailscale.exe"));
        found.push(PathBuf::from(r"C:\Program Files (x86)\Tailscale\tailscale.exe"));
    } else {
        found.extend(["/usr/bin/tailscale", "/usr/sbin/tailscale", "/usr/local/bin/tailscale", "/opt/homebrew/bin/tailscale"].map(PathBuf::from));
    }
    found
}

fn run(program: &PathBuf, args: &[&str]) -> Option<(bool, String)> {
    let mut command = Command::new(program);
    command.args(args).stdin(Stdio::null());
    // The libraries of an AppImage must not leak into a program of the system.
    tandem_winsys::system_env(&mut command);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        // No console window flashing up.
        command.creation_flags(0x0800_0000);
    }
    let output = command.output().ok()?;
    let text = format!("{}{}", String::from_utf8_lossy(&output.stdout), String::from_utf8_lossy(&output.stderr));
    Some((output.status.success(), text))
}

/// The command line tool of Tailscale, when it is installed.
fn cli() -> Option<PathBuf> {
    candidates().into_iter().find(|p| run(p, &["version"]).is_some_and(|(ok, _)| ok))
}

/// The state in the output of `tailscale status --json`.
pub fn backend_of(json: &str) -> Backend {
    let Ok(value) = serde_json::from_str::<Value>(json) else { return Backend::Other };
    match value["BackendState"].as_str() {
        Some("Running") => Backend::Running,
        Some("Stopped") => Backend::Stopped,
        Some("NeedsLogin") | Some("NeedsMachineAuth") => Backend::NeedsLogin,
        _ => Backend::Other,
    }
}

/// Whether a failure of `tailscale up` was the system not letting this person change Tailscale (Linux, where that takes an operator).
pub fn lacks_permission(text: &str) -> bool {
    let text = text.to_lowercase();
    text.contains("access denied") || text.contains("permission denied") || text.contains("operator") || text.contains("must be root")
}

static BUSY: AtomicBool = AtomicBool::new(false);

/// The core says a device cannot be reached without Tailscale. Turns it on, if the person allowed that and it is off.
pub fn needed(app: &AppHandle, device: &str) {
    if !settings::get(app).auto_tailscale || BUSY.swap(true, Ordering::SeqCst) {
        return;
    }
    let (app, name) = (app.clone(), events::device_name(app, device));
    std::thread::Builder::new()
        .name("tandem-tailscale".into())
        .spawn(move || {
            turn_on(&app, &name);
            BUSY.store(false, Ordering::SeqCst);
        })
        .ok();
}

fn turn_on(app: &AppHandle, name: &str) {
    let Some(cli) = cli() else { return };
    let state = run(&cli, &["status", "--json"]).map(|(_, text)| backend_of(&text)).unwrap_or(Backend::Other);
    match state {
        Backend::Stopped => {
            // A time limit, so a sign-in that is asked for does not keep this waiting for ever.
            let Some((ok, text)) = run(&cli, &["up", "--timeout=15s"]) else { return };
            if ok {
                events::toast(app, &i18n::t(app, "ts_on_title"), &i18n::t1(app, "ts_on_body", name));
            } else if lacks_permission(&text) {
                events::toast(app, &i18n::t(app, "ts_perm_title"), &i18n::t(app, "ts_perm_body"));
            }
        }
        Backend::NeedsLogin => events::toast(app, &i18n::t(app, "ts_login_title"), &i18n::t1(app, "ts_login_body", name)),
        Backend::Running | Backend::Other => {}
    }
    // Give the network a moment to come up before the core tries again (it notices the new address by itself).
    std::thread::sleep(Duration::from_millis(300));
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_state_is_read_from_the_status() {
        assert_eq!(backend_of(r#"{"BackendState":"Running","Self":{}}"#), Backend::Running);
        assert_eq!(backend_of(r#"{"BackendState":"Stopped"}"#), Backend::Stopped);
        assert_eq!(backend_of(r#"{"BackendState":"NeedsLogin"}"#), Backend::NeedsLogin);
        assert_eq!(backend_of(r#"{"BackendState":"Starting"}"#), Backend::Other);
        assert_eq!(backend_of("not json"), Backend::Other);
    }

    #[test]
    fn a_refusal_of_the_system_is_recognised() {
        assert!(lacks_permission("Access denied: prefs write access denied\nUse 'sudo tailscale up'"));
        assert!(lacks_permission("checkprefs access denied; to fix, run: sudo tailscale set --operator=mark"));
        assert!(!lacks_permission("backend error: no network"));
    }
}
