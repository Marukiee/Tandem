//! The files of a phone as a place in the sidebar of the file manager, like the File Provider place on the Mac.
//!
//! Linux: the core serves the files as WebDAV on the loopback address with a password made for it (as for the Mac's
//! drive), GVfs mounts that address with `gio mount`, and a line in GTK's bookmarks puts it in the sidebar of Files,
//! Nemo, Thunar, Caja and every GTK file chooser. The server gets a new port each time it starts, so the mount and the
//! bookmark follow it: they are made while the phone is online and taken away when it goes, and a phone that has it
//! turned on gets them back when it returns. Which phones have it on is kept in `sidebar.json` in the app's data.
//!
//! Windows: the Cloud Files API would do this, which is not built yet (docs/ROADMAP.md); the button says so.

use serde_json::Value;
use tauri::{AppHandle, State};

use crate::state::AppState;

/// Whether the phone's files are in the sidebar (turned on), and turning it on or off.
#[tauri::command]
pub async fn files_sidebar(app: AppHandle, state: State<'_, AppState>, id: String, on: Option<bool>) -> Result<Value, String> {
    #[cfg(target_os = "linux")]
    {
        let engine = state.engine()?;
        if let Some(on) = on {
            linux::set(&app, &engine, &id, on).await?;
        }
        Ok(Value::Bool(linux::wanted(&app).contains(&id)))
    }
    #[cfg(not(target_os = "linux"))]
    {
        let _ = (state, id);
        if on == Some(true) {
            return Err(crate::i18n::t(&app, "sidebar_not_on_windows"));
        }
        Ok(Value::Bool(false))
    }
}

/// Called whenever the devices change: phones that have it on get their place back when they come, and lose it when they go.
pub fn refresh(app: &AppHandle) {
    #[cfg(target_os = "linux")]
    linux::refresh(app);
    #[cfg(not(target_os = "linux"))]
    let _ = app;
}

/// Before the app quits: the servers stop with it, so their places go.
pub fn stop_all(app: &AppHandle) {
    #[cfg(target_os = "linux")]
    linux::stop_all(app);
    #[cfg(not(target_os = "linux"))]
    let _ = app;
}

#[cfg(target_os = "linux")]
mod linux {
    use std::collections::HashMap;
    use std::io::Write;
    use std::path::PathBuf;
    use std::process::{Command, Stdio};
    use std::sync::{Arc, Mutex};
    use std::time::{Duration, Instant};

    use tandem_core::ffi::TandemEngine;
    use tauri::{AppHandle, Manager};

    use crate::state::AppState;

    /// Every address of ours starts like this, which is how a bookmark of an earlier run is recognised.
    const OURS: &str = "dav://tandem@127.0.0.1:";

    /// The phones whose place is there now, with the address it has.
    static PLACED: Mutex<Option<HashMap<String, String>>> = Mutex::new(None);
    /// When a place was last tried for a phone: one that failed is not tried at once again on every change of the devices.
    static TRIED: Mutex<Option<HashMap<String, Instant>>> = Mutex::new(None);
    static CLEANED: Mutex<bool> = Mutex::new(false);

    fn placed() -> HashMap<String, String> {
        PLACED.lock().unwrap().clone().unwrap_or_default()
    }

    fn wanted_file(app: &AppHandle) -> Option<PathBuf> {
        app.path().app_data_dir().ok().map(|d| d.join("sidebar.json"))
    }

    pub fn wanted(app: &AppHandle) -> Vec<String> {
        wanted_file(app)
            .and_then(|f| std::fs::read(f).ok())
            .and_then(|d| serde_json::from_slice(&d).ok())
            .unwrap_or_default()
    }

    fn set_wanted(app: &AppHandle, ids: &[String]) {
        if let Some(file) = wanted_file(app) {
            let _ = std::fs::create_dir_all(file.parent().unwrap_or(&file));
            let _ = std::fs::write(file, serde_json::to_vec(ids).unwrap_or_default());
        }
    }

    pub async fn set(app: &AppHandle, engine: &Arc<TandemEngine>, id: &str, on: bool) -> Result<(), String> {
        let mut ids = wanted(app);
        ids.retain(|i| i != id);
        if !on {
            set_wanted(app, &ids);
            take_away(engine, id);
            return Ok(());
        }
        let device = engine.devices().into_iter().find(|d| d.id == id).ok_or("no such device")?;
        place(engine, id, &device.name).await?;
        ids.push(id.to_string());
        set_wanted(app, &ids);
        Ok(())
    }

    /// Serves the files, mounts them and puts the bookmark there. Fails with the reason when GVfs is not there or refuses.
    async fn place(engine: &Arc<TandemEngine>, id: &str, name: &str) -> Result<(), String> {
        let share = engine.serve_files_as_drive(id.to_string(), name.to_string()).await.map_err(|e| e.to_string())?;
        // `http://127.0.0.1:<port>/<name>` as GVfs calls it, with the user that it is to ask the password for.
        let address = share.url.replacen("http://", "dav://tandem@", 1) + "/";
        let mounted = {
            let (address, password) = (address.clone(), share.password.clone());
            tauri::async_runtime::spawn_blocking(move || mount(&address, &password)).await.map_err(|e| e.to_string())?
        };
        if let Err(reason) = mounted {
            engine.stop_serving_files_as_drive(id.to_string());
            return Err(reason);
        }
        bookmark(&address, Some(name));
        PLACED.lock().unwrap().get_or_insert_with(HashMap::new).insert(id.to_string(), address);
        Ok(())
    }

    fn take_away(engine: &TandemEngine, id: &str) {
        let Some(address) = PLACED.lock().unwrap().get_or_insert_with(HashMap::new).remove(id) else { return };
        bookmark(&address, None);
        engine.stop_serving_files_as_drive(id.to_string());
        // A mount whose server is gone only gives errors, so it goes too; on a thread of its own, because GVfs may take
        // its time and this runs when the devices change.
        std::thread::spawn(move || {
            let _ = run_with_timeout(Command::new("gio").args(["mount", "-u", &address]), None);
        });
    }

    pub fn refresh(app: &AppHandle) {
        let Ok(engine) = app.state::<AppState>().engine() else { return };
        {
            let mut cleaned = CLEANED.lock().unwrap();
            if !*cleaned {
                // The bookmarks of an earlier run point at servers that stopped with it.
                remove_bookmarks(|uri| uri.starts_with(OURS));
                *cleaned = true;
            }
        }
        let wanted = wanted(app);
        let devices = engine.devices();
        let placed = placed();
        for id in placed.keys() {
            let online = devices.iter().any(|d| &d.id == id && d.online);
            if !online || !wanted.contains(id) {
                take_away(&engine, id);
            }
        }
        // A phone removed from the circle is not wanted any more either.
        let known: Vec<String> = wanted.iter().filter(|id| devices.iter().any(|d| &d.id == *id)).cloned().collect();
        if known.len() != wanted.len() {
            set_wanted(app, &known);
        }
        for device in devices.into_iter().filter(|d| d.online && known.contains(&d.id) && !placed.contains_key(&d.id)) {
            {
                let mut tried = TRIED.lock().unwrap();
                let tried = tried.get_or_insert_with(HashMap::new);
                if tried.get(&device.id).is_some_and(|at| at.elapsed() < Duration::from_secs(20)) {
                    continue;
                }
                tried.insert(device.id.clone(), Instant::now());
            }
            let engine = engine.clone();
            tauri::async_runtime::spawn(async move {
                if let Err(reason) = place(&engine, &device.id, &device.name).await {
                    log::warn!("the files of {} could not go in the sidebar: {reason}", device.name);
                }
            });
        }
    }

    pub fn stop_all(app: &AppHandle) {
        let Ok(engine) = app.state::<AppState>().engine() else { return };
        for id in placed().keys() {
            take_away(&engine, id);
        }
    }

    /// `gio mount` asks for the password on its input when it has no terminal, so it is given there.
    fn mount(address: &str, password: &str) -> Result<(), String> {
        match run_with_timeout(Command::new("gio").args(["mount", address]), Some(&format!("{password}\n"))) {
            Ok(true) => Ok(()),
            Ok(false) => {
                // Mounted already, from before: that is fine.
                let listed = Command::new("gio").args(["mount", "-l"]).output().map(|o| String::from_utf8_lossy(&o.stdout).into_owned());
                let port_and_name = address.trim_start_matches("dav://tandem@").trim_end_matches('/');
                if listed.is_ok_and(|l| l.contains(port_and_name)) { Ok(()) } else { Err("GVfs could not mount the files".into()) }
            }
            Err(_) => Err("this needs GVfs (the gio command), which the file managers of GNOME, Cinnamon, Xfce and MATE use".into()),
        }
    }

    /// Runs a command with an end: a mount that waits for a server that does not answer must not keep the app waiting.
    fn run_with_timeout(command: &mut Command, input: Option<&str>) -> std::io::Result<bool> {
        let mut child = command.stdin(Stdio::piped()).stdout(Stdio::null()).stderr(Stdio::null()).spawn()?;
        if let (Some(input), Some(mut stdin)) = (input, child.stdin.take()) {
            let _ = stdin.write_all(input.as_bytes());
        }
        let deadline = std::time::Instant::now() + Duration::from_secs(15);
        loop {
            if let Some(status) = child.try_wait()? {
                return Ok(status.success());
            }
            if std::time::Instant::now() > deadline {
                let _ = child.kill();
                return Ok(false);
            }
            std::thread::sleep(Duration::from_millis(100));
        }
    }

    fn bookmarks_file() -> Option<PathBuf> {
        let config = std::env::var_os("XDG_CONFIG_HOME")
            .map(PathBuf::from)
            .or_else(|| std::env::var_os("HOME").map(|h| PathBuf::from(h).join(".config")))?;
        Some(config.join("gtk-3.0").join("bookmarks"))
    }

    /// Puts the address in GTK's bookmarks under `name`, or takes it out with `None`. The other lines stay as they are.
    fn bookmark(address: &str, name: Option<&str>) {
        remove_bookmarks(|uri| uri == address);
        let (Some(name), Some(file)) = (name, bookmarks_file()) else { return };
        let mut text = std::fs::read_to_string(&file).unwrap_or_default();
        if !text.is_empty() && !text.ends_with('\n') {
            text.push('\n');
        }
        text.push_str(&format!("{address} {name}\n"));
        let _ = std::fs::create_dir_all(file.parent().unwrap_or(&file));
        let _ = std::fs::write(&file, text);
    }

    fn remove_bookmarks(matches: impl Fn(&str) -> bool) {
        let Some(file) = bookmarks_file() else { return };
        let Ok(text) = std::fs::read_to_string(&file) else { return };
        let kept: Vec<&str> = text.lines().filter(|line| !matches(line.split(' ').next().unwrap_or(""))).collect();
        if kept.len() != text.lines().count() {
            let _ = std::fs::write(&file, kept.iter().map(|l| format!("{l}\n")).collect::<String>());
        }
    }
}
