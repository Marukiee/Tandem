//! "Send with Tandem" in the file manager, as the share menu of a Mac has it: a right click on files offers the devices of the circle.
//! The file manager runs Tandem again with `--send` (and `--to <device>`); the second start hands its arguments to the running app
//! (`tauri_plugin_single_instance`), which sends. What each file manager needs is written into the user's own folders of it and kept up
//! to date with the devices: Files (GNOME) through its Python extensions when `nautilus-python` is there, which lists the devices that
//! are reachable at that moment, otherwise its scripts menu; Dolphin a service menu, Nemo actions; Windows a shortcut per device in
//! "Send to". Turned off in the settings, all of it is taken away again.

use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::Duration;

use tandem_core::ffi::TandemShareOrigin;
use tauri::{AppHandle, Manager};

use crate::state::AppState;
use crate::{commands, events, i18n, settings};

/// The devices the menus were last written for, so nothing is written when nothing changed.
static WRITTEN: Mutex<Option<String>> = Mutex::new(None);

/// The arguments of a start of Tandem: when they ask to send files, they are sent (true).
pub fn handle_args(app: &AppHandle, args: &[String]) -> bool {
    let Some(at) = args.iter().position(|a| a == "--send") else { return false };
    let mut to = None;
    let mut paths = Vec::new();
    let mut rest = args[at + 1..].iter();
    while let Some(arg) = rest.next() {
        if arg == "--to" {
            to = rest.next().cloned();
        } else if !arg.starts_with("--") {
            paths.push(PathBuf::from(arg));
        }
    }
    let files: Vec<PathBuf> = paths.into_iter().filter(|p| p.is_file()).collect();
    if files.is_empty() {
        return true;
    }
    let app = app.clone();
    tauri::async_runtime::spawn(async move { send(&app, to, files).await });
    true
}

async fn send(app: &AppHandle, to: Option<String>, files: Vec<PathBuf>) {
    // Tandem may just have been started by the file manager: the devices need a moment to be found.
    let mut targets = Vec::new();
    for _ in 0..30 {
        targets = online(app, to.as_deref());
        if !targets.is_empty() {
            break;
        }
        let _ = tauri::async_runtime::spawn_blocking(|| std::thread::sleep(Duration::from_millis(200))).await;
    }
    let count = files.len().to_string();
    if targets.is_empty() {
        let name = to.as_deref().map(|id| events::device_name(app, id)).unwrap_or_default();
        let text = if name.is_empty() { i18n::t(app, "send_with_none") } else { i18n::t1(app, "send_with_away", &name) };
        events::toast(app, "Tandem", &text);
        return;
    }
    let names = targets.iter().map(|id| events::device_name(app, id)).collect::<Vec<_>>().join(", ");
    let state = app.state::<AppState>();
    match commands::send_paths_with(&state, targets, files, TandemShareOrigin::Files).await {
        Ok(_) => events::toast(app, "Tandem", &i18n::t2(app, "send_with_sent", &count, &names)),
        Err(error) => events::toast(app, "Tandem", &error),
    }
}

/// The reachable devices to send to: the one asked for, or all of them when none was named.
fn online(app: &AppHandle, to: Option<&str>) -> Vec<String> {
    let state = app.state::<AppState>();
    let data = state.data.lock().unwrap();
    data.devices
        .iter()
        .filter(|d| d["online"].as_bool() == Some(true))
        .filter_map(|d| d["id"].as_str())
        .filter(|id| to.is_none_or(|wanted| wanted == *id))
        .map(str::to_string)
        .collect()
}

/// The program the file manager starts: the AppImage itself when Tandem runs from one (the program inside it lives in a folder that
/// is gone after Tandem quits).
fn program() -> Option<PathBuf> {
    std::env::var_os("APPIMAGE").map(PathBuf::from).or_else(|| std::env::current_exe().ok())
}

/// Writes the menus for the devices there are now, or takes them away when the setting is off. Called at the start and whenever the
/// devices change.
pub fn refresh(app: &AppHandle) {
    let on = settings::get(app).file_manager_send;
    let devices: Vec<(String, String, bool)> = {
        let state = app.state::<AppState>();
        let data = state.data.lock().unwrap();
        data.devices
            .iter()
            .filter_map(|d| Some((d["id"].as_str()?.to_string(), d["name"].as_str()?.to_string(), d["online"].as_bool() == Some(true))))
            .collect()
    };
    // The list of what is reachable changes often; the menus that list every device only when a device comes, goes or is renamed.
    let key = format!("{on}|{}|{}", i18n::t(app, "send_with"), devices.iter().map(|(id, name, _)| format!("{id}={name}")).collect::<Vec<_>>().join(";"));
    #[cfg(target_os = "linux")]
    linux::reachable(on, &devices);
    {
        let mut written = WRITTEN.lock().unwrap();
        if written.as_deref() == Some(key.as_str()) {
            return;
        }
        *written = Some(key);
    }
    let Some(program) = program() else { return };
    let label = i18n::t(app, "send_with");
    let devices: Vec<(String, String)> = devices.into_iter().map(|(id, name, _)| (id, name)).collect();
    std::thread::spawn(move || {
        #[cfg(target_os = "linux")]
        linux::write(on, &program, &label, &devices);
        #[cfg(windows)]
        windows::write(on, &program, &devices);
        #[cfg(not(any(target_os = "linux", windows)))]
        let _ = (on, program, label, devices);
    });
}

/// Writes a file only when it is new or different, so a file manager that watches its folder is not woken for nothing.
#[cfg_attr(not(any(target_os = "linux", windows)), allow(dead_code))]
fn put(path: &Path, text: &str, executable: bool) {
    if std::fs::read_to_string(path).ok().as_deref() == Some(text) {
        return;
    }
    if let Some(dir) = path.parent() {
        let _ = std::fs::create_dir_all(dir);
    }
    if std::fs::write(path, text).is_err() {
        log::info!("send with: {} could not be written", path.display());
        return;
    }
    #[cfg(unix)]
    if executable {
        use std::os::unix::fs::PermissionsExt;
        let _ = std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o755));
    }
    #[cfg(not(unix))]
    let _ = executable;
}

#[cfg(target_os = "linux")]
mod linux {
    use std::path::{Path, PathBuf};

    use super::put;

    fn data_home() -> Option<PathBuf> {
        std::env::var_os("XDG_DATA_HOME")
            .map(PathBuf::from)
            .or_else(|| std::env::var_os("HOME").map(|h| Path::new(&h).join(".local/share")))
    }

    /// Where the extension of Files reads which devices can be sent to now.
    fn reachable_file() -> Option<PathBuf> {
        let base = std::env::var_os("XDG_CACHE_HOME")
            .map(PathBuf::from)
            .or_else(|| std::env::var_os("HOME").map(|h| Path::new(&h).join(".cache")))?;
        Some(base.join("tandem").join("send-targets.json"))
    }

    fn on_path(program: &str) -> bool {
        std::env::var_os("PATH").is_some_and(|paths| std::env::split_paths(&paths).any(|dir| dir.join(program).is_file()))
    }

    /// Whether Files can load extensions written in Python (the package `nautilus-python`).
    fn nautilus_python() -> bool {
        ["/usr/lib64/nautilus/extensions-4", "/usr/lib/nautilus/extensions-4", "/usr/lib/x86_64-linux-gnu/nautilus/extensions-4", "/usr/lib/aarch64-linux-gnu/nautilus/extensions-4"]
            .iter()
            .any(|dir| Path::new(dir).join("libnautilus-python.so").exists())
    }

    pub fn reachable(on: bool, devices: &[(String, String, bool)]) {
        let Some(file) = reachable_file() else { return };
        if !on {
            let _ = std::fs::remove_file(file);
            return;
        }
        let list: Vec<_> = devices.iter().filter(|(_, _, online)| *online).map(|(id, name, _)| serde_json::json!({ "id": id, "name": name })).collect();
        put(&file, &serde_json::to_string(&list).unwrap_or_default(), false);
    }

    fn quoted(program: &Path) -> String {
        format!("\"{}\"", program.to_string_lossy().replace('\\', "\\\\").replace('"', "\\\""))
    }

    pub fn write(on: bool, program: &Path, label: &str, devices: &[(String, String)]) {
        let Some(data) = data_home() else { return };
        let extension = data.join("nautilus-python/extensions/tandem-send.py");
        let script = data.join("nautilus/scripts").join(label);
        let dolphin = data.join("kio/servicemenus/tandem-send.desktop");
        let nemo = data.join("nemo/actions");
        // What earlier versions or another language left behind goes first.
        if let Ok(entries) = std::fs::read_dir(&nemo) {
            for entry in entries.flatten() {
                if entry.file_name().to_string_lossy().starts_with("tandem-send-") {
                    let _ = std::fs::remove_file(entry.path());
                }
            }
        }
        if let Ok(entries) = std::fs::read_dir(data.join("nautilus/scripts")) {
            for entry in entries.flatten() {
                let text = std::fs::read_to_string(entry.path()).unwrap_or_default();
                if text.contains("# Tandem: send with Tandem") && entry.path() != script {
                    let _ = std::fs::remove_file(entry.path());
                }
            }
        }
        if !on {
            for path in [&extension, &script, &dolphin] {
                let _ = std::fs::remove_file(path);
            }
            return;
        }
        let exe = program.to_string_lossy();
        if nautilus_python() {
            let _ = std::fs::remove_file(&script);
            put(&extension, &EXTENSION.replace("@PROGRAM@", &exe.replace('\\', "\\\\").replace('"', "\\\"")).replace("@LABEL@", &label.replace('"', "\\\"")), false);
        } else {
            let _ = std::fs::remove_file(&extension);
            put(&script, &format!("#!/bin/sh\n# Tandem: send with Tandem\nexec {} --send \"$@\"\n", quoted(program)), true);
        }
        if on_path("dolphin") && !devices.is_empty() {
            let mut text = format!(
                "[Desktop Entry]\nType=Service\nMimeType=application/octet-stream;all/allfiles;\nX-KDE-Submenu={label}\nIcon=nl.markmaaktmedia.tandem\nActions={}\n",
                (0..devices.len()).map(|n| format!("device{n};")).collect::<String>()
            );
            for (n, (id, name)) in devices.iter().enumerate() {
                text.push_str(&format!("\n[Desktop Action device{n}]\nName={name}\nIcon=nl.markmaaktmedia.tandem\nExec={} --send --to {id} %F\n", quoted(program)));
            }
            put(&dolphin, &text, true);
        } else {
            let _ = std::fs::remove_file(&dolphin);
        }
        if on_path("nemo") {
            for (n, (id, name)) in devices.iter().enumerate() {
                let text = format!(
                    "[Nemo Action]\nName={label}: {name}\nExec={} --send --to {id} %F\nIcon-Name=nl.markmaaktmedia.tandem\nSelection=notnone\nExtensions=nodirs;\n",
                    quoted(program)
                );
                put(&nemo.join(format!("tandem-send-{n}.nemo_action")), &text, false);
            }
        }
    }

    /// The extension of Files: one entry with the devices that are reachable right now, read from the list Tandem keeps.
    const EXTENSION: &str = r#"# Tandem: send with Tandem. Written by Tandem; it is written again when the devices change.
import json, os, subprocess
from gi.repository import GObject, Nautilus

PROGRAM = "@PROGRAM@"
LABEL = "@LABEL@"


def reachable():
    path = os.path.join(os.environ.get("XDG_CACHE_HOME") or os.path.expanduser("~/.cache"), "tandem", "send-targets.json")
    try:
        with open(path) as f:
            return json.load(f)
    except Exception:
        return []


class TandemSend(GObject.GObject, Nautilus.MenuProvider):
    def get_file_items(self, *args):
        files = args[-1]
        paths = [f.get_location().get_path() for f in files if f.get_uri_scheme() == "file" and not f.is_directory()]
        if not paths or not os.path.exists(PROGRAM):
            return []
        devices = reachable()
        item = Nautilus.MenuItem(name="TandemSend::send", label=LABEL, icon="nl.markmaaktmedia.tandem")
        if len(devices) > 1:
            menu = Nautilus.Menu()
            item.set_submenu(menu)
            for device in devices:
                entry = Nautilus.MenuItem(name="TandemSend::" + device["id"], label=device["name"])
                entry.connect("activate", self.send, paths, device["id"])
                menu.append_item(entry)
        else:
            item.connect("activate", self.send, paths, devices[0]["id"] if devices else None)
        return [item]

    def send(self, _item, paths, device):
        command = [PROGRAM, "--send"] + (["--to", device] if device else []) + paths
        subprocess.Popen(command, start_new_session=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
"#;
}

#[cfg(windows)]
mod windows {
    use std::os::windows::process::CommandExt;
    use std::path::{Path, PathBuf};

    /// A shortcut per device in the "Send to" menu of Explorer, named after the device, made with the shell's own object for shortcuts.
    pub fn write(on: bool, program: &Path, devices: &[(String, String)]) {
        let Some(folder) = std::env::var_os("APPDATA").map(|a| PathBuf::from(a).join("Microsoft\\Windows\\SendTo")) else { return };
        if let Ok(entries) = std::fs::read_dir(&folder) {
            for entry in entries.flatten() {
                if entry.file_name().to_string_lossy().ends_with(" (Tandem).lnk") {
                    let _ = std::fs::remove_file(entry.path());
                }
            }
        }
        if !on || devices.is_empty() {
            return;
        }
        let quote = |s: &str| s.replace('\'', "''");
        let mut script = String::from("$s = New-Object -ComObject WScript.Shell;");
        for (id, name) in devices {
            let clean: String = name.chars().filter(|c| !"\\/:*?\"<>|".contains(*c)).collect();
            let link = folder.join(format!("{clean} (Tandem).lnk"));
            script.push_str(&format!(
                "$l = $s.CreateShortcut('{}'); $l.TargetPath = '{}'; $l.Arguments = '--send --to {}'; $l.IconLocation = '{},0'; $l.Save();",
                quote(&link.to_string_lossy()),
                quote(&program.to_string_lossy()),
                quote(id),
                quote(&program.to_string_lossy())
            ));
        }
        // No window of its own: CREATE_NO_WINDOW.
        let made = std::process::Command::new("powershell")
            .args(["-NoProfile", "-NonInteractive", "-Command", &script])
            .creation_flags(0x0800_0000)
            .status();
        if !made.is_ok_and(|s| s.success()) {
            log::info!("send with: the shortcuts in Send to could not be made");
        }
    }
}
