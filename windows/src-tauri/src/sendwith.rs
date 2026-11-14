//! "Send with Tandem" in the file manager, as the share menu of a Mac has it: a right click on files offers the devices of the circle.
//! The file manager runs Tandem again with `--send` (and `--to <device>`); the second start hands its arguments to the running app
//! (`tauri_plugin_single_instance`), which sends. Without `--to` it asks which device in a card of its own (`send.html`), also when only
//! one is there: files never leave without the person choosing where. What each file manager needs is written into the user's own folders of it and kept up
//! to date with the devices: Files (GNOME) through its Python extensions when `nautilus-python` is there, which lists the devices that
//! are reachable at that moment, otherwise its scripts menu; Dolphin a service menu, Nemo actions; Windows a shortcut per device in
//! "Send to". Turned off in the settings, all of it is taken away again.
//!
//! A menu entry for one device carries `--key`, a key of this run of Tandem written into the menus, as the share sheet of the Mac does. A
//! start with `--to` and without the right key (from another program, or an entry left from an earlier run) asks in the card, with that
//! device first.

use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::Duration;

use serde_json::{json, Value};
use tandem_core::ffi::TandemShareOrigin;
use tauri::{AppHandle, Emitter, LogicalSize, Manager};

use crate::state::{AppState, Held};
use crate::{commands, events, i18n, settings};

/// The devices the menus were last written for, so nothing is written when nothing changed.
static WRITTEN: Mutex<Option<String>> = Mutex::new(None);
/// The files waiting for the person to choose a device in the card.
static PICK: Mutex<Vec<PathBuf>> = Mutex::new(Vec::new());
/// The device that was named by a start that could not prove it came from the menu, put first in the card.
static ASKED: Mutex<Option<String>> = Mutex::new(None);

/// The key of this run, which the menus pass with `--key`. It needs to be unknown outside this run, not to be strong for years:
/// `RandomState` is seeded from the randomness of the system, which the app has no crate of its own for.
fn run_key() -> &'static str {
    static KEY: std::sync::OnceLock<String> = std::sync::OnceLock::new();
    KEY.get_or_init(|| {
        use std::hash::{BuildHasher, Hasher};
        let nanos = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_nanos()).unwrap_or(0);
        (0..2u64)
            .map(|n| {
                let mut hasher = std::collections::hash_map::RandomState::new().build_hasher();
                hasher.write_u64(n);
                hasher.write_u128(nanos);
                hasher.write_u32(std::process::id());
                format!("{:016x}", hasher.finish())
            })
            .collect()
    })
}

/// Whether a start may send without asking: it names a device and carries the key of this run.
fn trusted(to: Option<&str>, key: Option<&str>) -> bool {
    to.is_some() && key == Some(run_key())
}

/// The arguments of a start of Tandem: when they ask to send files, they are sent (true).
pub fn handle_args(app: &AppHandle, args: &[String]) -> bool {
    let Some(at) = args.iter().position(|a| a == "--send") else { return false };
    let mut to = None;
    let mut key = None;
    let mut paths = Vec::new();
    let mut rest = args[at + 1..].iter();
    while let Some(arg) = rest.next() {
        if arg == "--to" {
            to = rest.next().cloned();
        } else if arg == "--key" {
            key = rest.next().cloned();
        } else if !arg.starts_with("--") {
            paths.push(PathBuf::from(arg));
        }
    }
    let files: Vec<PathBuf> = paths.into_iter().filter(|p| p.is_file()).collect();
    if files.is_empty() {
        return true;
    }
    if !trusted(to.as_deref(), key.as_deref()) {
        pick(app, files, to);
        return true;
    }
    let Some(to) = to else { return true };
    let app = app.clone();
    tauri::async_runtime::spawn(async move { send(&app, Some(to), files).await });
    true
}

const CARD_WIDTH: f64 = 392.0;

/// Shows the card that asks which device, in the middle of the screen. Files shared again before an answer take the place of the others.
fn pick(app: &AppHandle, files: Vec<PathBuf>, asked: Option<String>) {
    *PICK.held() = files;
    *ASKED.held() = asked;
    let rows = app.state::<AppState>().data.held().devices.len().max(1);
    let online = app.state::<AppState>().data.held().devices.iter().filter(|d| d["online"].as_bool() == Some(true)).count();
    // Head, buttons and room for a reason, plus a row per device and one for all of them.
    let height = 190.0 + 58.0 * (rows + usize::from(online > 1)) as f64;
    let _ = app.emit("send_pick", pick_state());
    let handle = app.clone();
    let _ = app.run_on_main_thread(move || {
        if let Some(window) = handle.get_webview_window("send-card") {
            let _ = window.set_size(LogicalSize::new(CARD_WIDTH, height));
            let _ = window.set_focus();
            return;
        }
        let _ = tauri::WebviewWindowBuilder::new(&handle, "send-card", tauri::WebviewUrl::App("send.html".into()))
            .title("Tandem")
            .inner_size(CARD_WIDTH, height)
            .decorations(false)
            .transparent(true)
            .resizable(false)
            .always_on_top(true)
            .skip_taskbar(true)
            .center()
            .focused(true)
            .build();
    });
}

/// How many files wait, and the name of the first, for the title of the card.
fn pick_state() -> Value {
    let files = PICK.held();
    json!({
        "asked": *ASKED.held(),
        "count": files.len(),
        "name": files.first().and_then(|f| f.file_name()).map(|n| n.to_string_lossy().into_owned()),
    })
}

#[tauri::command]
pub fn send_pick_state() -> Value {
    pick_state()
}

/// The answer of the card: the devices to send to, or none to send nothing.
#[tauri::command]
pub fn send_pick(app: AppHandle, to: Vec<String>) {
    let files = std::mem::take(&mut *PICK.held());
    *ASKED.held() = None;
    if let Some(window) = app.get_webview_window("send-card") {
        let _ = window.close();
    }
    if to.is_empty() || files.is_empty() {
        return;
    }
    tauri::async_runtime::spawn(async move {
        let count = files.len().to_string();
        let names = to.iter().map(|id| events::device_name(&app, id)).collect::<Vec<_>>().join(", ");
        let state = app.state::<AppState>();
        match commands::send_paths_with(&state, to, files, TandemShareOrigin::Files).await {
            Ok(_) => events::toast(&app, "Tandem", &i18n::t2(&app, "send_with_sent", &count, &names)),
            Err(error) => events::toast(&app, "Tandem", &error),
        }
    });
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
    let data = state.data.held();
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
        let data = state.data.held();
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
        let mut written = WRITTEN.held();
        if written.as_deref() == Some(key.as_str()) {
            return;
        }
        *written = Some(key);
    }
    let Some(program) = program() else { return };
    let label = i18n::t(app, "send_with");
    let away = i18n::t(app, "send_with_off");
    let devices: Vec<(String, String)> = devices.into_iter().map(|(id, name, _)| (id, name)).collect();
    std::thread::spawn(move || {
        #[cfg(target_os = "linux")]
        linux::write(on, &program, &label, &away, &devices, run_key());
        #[cfg(windows)]
        windows::write(on, &program, &devices, run_key());
        #[cfg(not(any(target_os = "linux", windows)))]
        let _ = (on, program, label, away, devices);
    });
}

/// Writes a file only when it is new or different, so a file manager that watches its folder is not woken for nothing.
#[cfg_attr(not(any(target_os = "linux", windows)), allow(dead_code))]
pub(crate) fn put(path: &Path, text: &str, executable: bool) {
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

    /// Where the extension of Files reads the devices and which of them can be sent to now.
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
        let list: Vec<_> = devices.iter().map(|(id, name, online)| serde_json::json!({ "id": id, "name": name, "online": online })).collect();
        put(&file, &serde_json::to_string(&list).unwrap_or_default(), false);
    }

    fn quoted(program: &Path) -> String {
        format!("\"{}\"", program.to_string_lossy().replace('\\', "\\\\").replace('"', "\\\""))
    }

    pub fn write(on: bool, program: &Path, label: &str, away: &str, devices: &[(String, String)], key: &str) {
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
            put(
                &extension,
                &EXTENSION
                    .replace("@PROGRAM@", &exe.replace('\\', "\\\\").replace('"', "\\\""))
                    .replace("@LABEL@", &label.replace('"', "\\\""))
                    .replace("@AWAY@", &away.replace('"', "\\\""))
                    .replace("@KEY@", key),
                false,
            );
        } else {
            let _ = std::fs::remove_file(&extension);
            put(&script, &format!("#!/bin/sh\n# Tandem: send with Tandem\nexec {} --send \"$@\"\n", quoted(program)), true);
        }
        // A device id goes into a command line, so only one that looks like an id is written.
        let devices: Vec<&(String, String)> = devices.iter().filter(|(id, _)| super::plain_id(id)).collect();
        if on_path("dolphin") && !devices.is_empty() {
            let mut text = format!(
                "[Desktop Entry]\nType=Service\nMimeType=application/octet-stream;all/allfiles;\nX-KDE-Submenu={}\nIcon=nl.markmaaktmedia.tandem\nActions={}\n",
                super::entry_value(label),
                (0..devices.len()).map(|n| format!("device{n};")).collect::<String>()
            );
            for (n, (id, name)) in devices.iter().enumerate() {
                text.push_str(&format!(
                    "\n[Desktop Action device{n}]\nName={}\nIcon=nl.markmaaktmedia.tandem\nExec={} --send --to {id} --key {key} %F\n",
                    super::entry_value(name),
                    quoted(program)
                ));
            }
            put(&dolphin, &text, true);
        } else {
            let _ = std::fs::remove_file(&dolphin);
        }
        if on_path("nemo") {
            for (n, (id, name)) in devices.iter().enumerate() {
                // Nemo also reads `%` in a name as the start of a token, such as %N for the name of the selected file.
                let text = format!(
                    "[Nemo Action]\nName={}\nExec={} --send --to {id} --key {key} %F\nIcon-Name=nl.markmaaktmedia.tandem\nSelection=notnone\nExtensions=nodirs;\n",
                    super::entry_value(&format!("{label}: {name}")).replace('%', "%%"),
                    quoted(program)
                );
                put(&nemo.join(format!("tandem-send-{n}.nemo_action")), &text, false);
            }
        }
    }

    /// The extension of Files: one entry with every device under it, read from the list Tandem keeps. One that cannot be reached now
    /// is grey and says so; with no device at all the entry opens the card of Tandem, which says what to do.
    const EXTENSION: &str = r#"# Tandem: send with Tandem. Written by Tandem; it is written again when the devices change.
import json, os, subprocess
from gi.repository import GObject, Nautilus

PROGRAM = "@PROGRAM@"
LABEL = "@LABEL@"
AWAY = "@AWAY@"
KEY = "@KEY@"


def devices():
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
        item = Nautilus.MenuItem(name="TandemSend::send", label=LABEL, icon="nl.markmaaktmedia.tandem")
        listed = devices()
        if not listed:
            item.connect("activate", self.send, paths, None)
            return [item]
        menu = Nautilus.Menu()
        item.set_submenu(menu)
        for device in listed:
            online = device.get("online", True)
            label = device["name"] if online else device["name"] + " (" + AWAY + ")"
            entry = Nautilus.MenuItem(name="TandemSend::" + device["id"], label=label, sensitive=online)
            entry.connect("activate", self.send, paths, device["id"])
            menu.append_item(entry)
        return [item]

    def send(self, _item, paths, device):
        command = [PROGRAM, "--send"] + (["--to", device, "--key", KEY] if device else []) + paths
        subprocess.Popen(command, start_new_session=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
"#;
}

#[cfg(windows)]
mod windows {
    use std::os::windows::process::CommandExt;
    use std::path::{Path, PathBuf};

    /// A shortcut per device in the "Send to" menu of Explorer, named after the device, made with the shell's own object for shortcuts.
    pub fn write(on: bool, program: &Path, devices: &[(String, String)], key: &str) {
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
        for (id, name) in devices.iter().filter(|(id, _)| super::plain_id(id)) {
            let link = folder.join(format!("{} (Tandem).lnk", crate::names::windows_safe(name)));
            if !shortcut(&link, program, id, key) {
                log::info!("send with: the shortcut in Send to for {id} could not be made");
            }
        }
    }

    /// The script never changes: the values reach it as environment variables, so no name can end a string in it and run
    /// something of its own. PowerShell also takes the curly apostrophe macOS puts in "Mark’s MacBook Pro" as a quote.
    const SHORTCUT: &str = "$ErrorActionPreference = 'Stop'; $l = (New-Object -ComObject WScript.Shell).CreateShortcut($env:TANDEM_LINK); \
        $l.TargetPath = $env:TANDEM_PROGRAM; $l.Arguments = '--send --to ' + $env:TANDEM_DEVICE + ' --key ' + $env:TANDEM_KEY; \
        $l.IconLocation = $env:TANDEM_PROGRAM + ',0'; $l.Save()";

    fn shortcut(link: &Path, program: &Path, id: &str, key: &str) -> bool {
        // No window of its own: CREATE_NO_WINDOW.
        std::process::Command::new("powershell")
            .args(["-NoProfile", "-NonInteractive", "-Command", SHORTCUT])
            .env("TANDEM_LINK", link)
            .env("TANDEM_PROGRAM", program)
            .env("TANDEM_DEVICE", id)
            .env("TANDEM_KEY", key)
            .creation_flags(0x0800_0000)
            .status()
            .is_ok_and(|s| s.success())
    }
}

/// Whether a device id is only letters, digits, `-` and `_`, as the ids of the core are: it goes into a command line.
#[cfg_attr(not(any(target_os = "linux", windows)), allow(dead_code))]
fn plain_id(id: &str) -> bool {
    !id.is_empty() && id.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
}

/// A value for a desktop entry (the files of Dolphin and Nemo): a backslash, a line break and a tab become the escapes the format
/// knows, so a name can never start a line or a key of its own. The core already takes them out of names; this is the second lock.
#[cfg_attr(not(target_os = "linux"), allow(dead_code))]
fn entry_value(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    for (n, c) in text.chars().enumerate() {
        match c {
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            // A space at the start would be dropped by the reader.
            ' ' if n == 0 => out.push_str("\\s"),
            c if c.is_control() => {}
            c => out.push(c),
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::{entry_value, plain_id, run_key, trusted};

    #[test]
    fn a_name_cannot_start_a_line_in_a_desktop_entry() {
        assert_eq!(entry_value("Mark’s MacBook Pro"), "Mark’s MacBook Pro");
        assert_eq!(entry_value("Mac\nExec=rm -rf ~"), "Mac\\nExec=rm -rf ~");
        assert_eq!(entry_value("a\\b\tc\r"), "a\\\\b\\tc\\r");
        assert_eq!(entry_value(" lead"), "\\slead");
        assert_eq!(entry_value("bell\u{7}"), "bell");
    }

    #[test]
    fn only_plain_ids_go_into_a_command() {
        assert!(plain_id("3f9a0c21b7"));
        assert!(!plain_id(""));
        assert!(!plain_id("x; rm -rf ~"));
        assert!(!plain_id("a'b"));
    }

    #[test]
    fn only_a_start_with_the_key_of_this_run_sends_without_asking() {
        let key = run_key();
        assert_eq!(key.len(), 32);
        assert_eq!(run_key(), key, "the key stays the same for the whole run");
        assert!(trusted(Some("dev1"), Some(key)));
        assert!(!trusted(Some("dev1"), None));
        assert!(!trusted(Some("dev1"), Some("0123456789abcdef0123456789abcdef")));
        assert!(!trusted(None, Some(key)), "without a device the card asks anyway");
    }
}
