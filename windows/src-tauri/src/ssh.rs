//! Logging in to another computer of the circle from inside Tandem. The login is the ssh that comes with the system, run in a
//! pseudo terminal; its output goes to a terminal in a window of its own (xterm.js, `ui/ssh.html`) and what is typed there goes
//! back in. Keys, the agent and the known hosts are the ones the person already has. The button for it is grey until the computer
//! answers on the ssh port.

use std::collections::HashMap;
use std::io::{Read, Write};
use std::net::{SocketAddr, TcpStream};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{LazyLock, Mutex};
use std::time::Duration;

use portable_pty::{CommandBuilder, MasterPty, PtySize, native_pty_system};
use serde_json::{Value, json};
use tauri::ipc::{Channel, InvokeResponseBody};
use tauri::{AppHandle, Emitter, Manager, State, WebviewUrl};

use crate::state::AppState;

type Reply<T> = Result<T, String>;

struct Login {
    master: Box<dyn MasterPty + Send>,
    writer: Box<dyn Write + Send>,
    child: Box<dyn portable_pty::Child + Send + Sync>,
}

static LOGINS: LazyLock<Mutex<HashMap<u64, Login>>> = LazyLock::new(|| Mutex::new(HashMap::new()));
static NEXT: AtomicU64 = AtomicU64::new(1);

/// The address of this device that answers on the ssh port, when one does. Only computers are asked: a phone has no ssh to offer.
#[tauri::command]
pub async fn ssh_probe(state: State<'_, AppState>, id: String) -> Reply<Option<String>> {
    let engine = state.engine()?;
    let ips = engine.device_ips(id);
    tokio_probe(ips).await
}

async fn tokio_probe(ips: Vec<String>) -> Reply<Option<String>> {
    tauri::async_runtime::spawn_blocking(move || {
        // All at once: one that does not answer costs a second and a half, and that is not paid once for each.
        let found: Vec<Option<String>> = std::thread::scope(|scope| {
            let handles: Vec<_> = ips
                .into_iter()
                .take(4)
                .map(|ip| {
                    scope.spawn(move || {
                        let addr = format!("{ip}:22").parse::<SocketAddr>().or_else(|_| format!("[{ip}]:22").parse::<SocketAddr>()).ok()?;
                        TcpStream::connect_timeout(&addr, Duration::from_millis(1500)).ok().map(|_| ip)
                    })
                })
                .collect();
            handles.into_iter().map(|h| h.join().ok().flatten()).collect()
        });
        // The first of the likeliest addresses that answered.
        found.into_iter().flatten().next()
    })
    .await
    .map_err(|e| e.to_string())
}

/// The window in which the login happens. It asks who to log in as, and then calls `ssh_start`.
#[tauri::command]
pub fn ssh_open(app: AppHandle, id: String, name: String, address: String) -> Reply<()> {
    let label = format!("ssh-{}", NEXT.fetch_add(1, Ordering::Relaxed));
    let url = format!(
        "ssh.html?id={}&name={}&address={}",
        urlencode(&id),
        urlencode(&name),
        urlencode(&address)
    );
    crate::tray::new_window(&app, &label, WebviewUrl::App(url.into()))
        .title(name)
        .inner_size(860.0, 560.0)
        .min_inner_size(360.0, 240.0)
        // Linux draws no frame of its own: the page does (see ui/js/chrome.js).
        .decorations(cfg!(windows))
        .build()
        .map(|_| ())
        .map_err(|e| e.to_string())
}

fn urlencode(text: &str) -> String {
    text.bytes()
        .map(|b| match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => (b as char).to_string(),
            _ => format!("%{b:02X}"),
        })
        .collect()
}

/// A user name that is safe to hand to ssh: no spaces, no leading dash, nothing the shell or ssh would read as an option.
fn clean_user(user: &str) -> Option<String> {
    let user = user.trim();
    let ok = !user.is_empty()
        && user.len() <= 64
        && !user.starts_with('-')
        && user.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '_' | '-' | '.' | '@' | '\\'));
    ok.then(|| user.to_string())
}

/// Starts the login and sends what comes back to the window. Returns the number of the login.
#[tauri::command]
pub fn ssh_start(
    app: AppHandle,
    label: String,
    user: String,
    address: String,
    cols: u16,
    rows: u16,
    on_output: Channel<InvokeResponseBody>,
) -> Reply<u64> {
    let user = clean_user(&user).ok_or("that is not a name that can log in")?;
    if address.parse::<std::net::IpAddr>().is_err() {
        return Err("that is not an address".into());
    }
    let pair = native_pty_system()
        .openpty(PtySize { rows: rows.max(2), cols: cols.max(10), pixel_width: 0, pixel_height: 0 })
        .map_err(|e| e.to_string())?;
    let mut command = CommandBuilder::new("ssh");
    command.args(["-o", "StrictHostKeyChecking=accept-new", "-o", "ServerAliveInterval=20", &format!("{user}@{address}")]);
    command.env("TERM", "xterm-256color");
    // The libraries of an AppImage must not leak into the ssh of the system.
    for (key, value) in tandem_winsys::system_env_edits() {
        match value {
            Some(rest) => command.env(key, rest),
            None => command.env_remove(key),
        }
    }
    let child = pair.slave.spawn_command(command).map_err(|e| format!("ssh could not start: {e}"))?;
    drop(pair.slave);
    let mut reader = pair.master.try_clone_reader().map_err(|e| e.to_string())?;
    let writer = pair.master.take_writer().map_err(|e| e.to_string())?;

    let id = NEXT.fetch_add(1, Ordering::Relaxed);
    LOGINS.lock().unwrap().insert(id, Login { master: pair.master, writer, child });
    #[cfg(windows)]
    close_console_at_the_end(id);

    std::thread::Builder::new()
        .name("tandem-ssh".into())
        .spawn(move || {
            let mut buffer = [0u8; 8192];
            loop {
                match reader.read(&mut buffer) {
                    Ok(0) | Err(_) => break,
                    Ok(n) => {
                        if on_output.send(InvokeResponseBody::Raw(buffer[..n].to_vec())).is_err() {
                            break;
                        }
                    }
                }
            }
            if let Some(mut login) = LOGINS.lock().unwrap().remove(&id) {
                let _ = login.child.wait();
            }
            let _ = app.emit_to(label, "ssh-ended", json!({ "login": id }));
        })
        .map_err(|e| e.to_string())?;
    Ok(id)
}

/// On Windows what is read from a terminal stays open for as long as its console does, also after the program in it ended, so the window
/// would never hear that the login is over. The console is closed when ssh is gone, and only then the reader gets to the end.
#[cfg(windows)]
fn close_console_at_the_end(id: u64) {
    let _ = std::thread::Builder::new().name("tandem-ssh-end".into()).spawn(move || {
        loop {
            std::thread::sleep(std::time::Duration::from_millis(300));
            let mut logins = LOGINS.lock().unwrap();
            let Some(login) = logins.get_mut(&id) else { return };
            if !matches!(login.child.try_wait(), Ok(None)) {
                let ended = logins.remove(&id);
                // Closing a console can wait for the reader, which may want this lock when it is done.
                drop(logins);
                drop(ended);
                return;
            }
        }
    });
}

#[tauri::command]
pub fn ssh_write(login: u64, data: String) {
    if let Some(l) = LOGINS.lock().unwrap().get_mut(&login) {
        let _ = l.writer.write_all(data.as_bytes());
        let _ = l.writer.flush();
    }
}

#[tauri::command]
pub fn ssh_resize(login: u64, cols: u16, rows: u16) {
    if let Some(l) = LOGINS.lock().unwrap().get(&login) {
        let _ = l.master.resize(PtySize { rows: rows.max(2), cols: cols.max(10), pixel_width: 0, pixel_height: 0 });
    }
}

/// The window closed: the login goes with it.
#[tauri::command]
pub fn ssh_close(login: u64) {
    if let Some(mut l) = LOGINS.lock().unwrap().remove(&login) {
        let _ = l.child.kill();
        // Collected, so the ended ssh does not stay behind as a process that nobody asked after.
        let _ = l.child.wait();
    }
}

/// What a window may know about its own login, for the page that is shown before it starts.
#[tauri::command]
pub fn ssh_info(app: AppHandle, label: String) -> Value {
    json!({ "label": label, "platform": if cfg!(windows) { "windows" } else { "linux" }, "open": app.get_webview_window(&label).is_some() })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_plain_names_may_log_in() {
        assert_eq!(clean_user("mark").as_deref(), Some("mark"));
        assert_eq!(clean_user(" mark.dev ").as_deref(), Some("mark.dev"));
        assert_eq!(clean_user("-oProxyCommand=x"), None);
        assert_eq!(clean_user("a b"), None);
        assert_eq!(clean_user(""), None);
        assert_eq!(clean_user("mark;rm"), None);
    }

    #[test]
    fn the_address_in_the_url_is_escaped() {
        assert_eq!(urlencode("Mac van Piet"), "Mac%20van%20Piet");
        assert_eq!(urlencode("fe80::1"), "fe80%3A%3A1");
    }

    #[test]
    fn a_login_runs_in_a_terminal_and_its_output_comes_back() {
        let pair = native_pty_system().openpty(PtySize { rows: 24, cols: 80, pixel_width: 0, pixel_height: 0 }).unwrap();
        #[cfg(windows)]
        let command = {
            let mut command = CommandBuilder::new("cmd");
            command.args(["/C", "echo hello from the pty"]);
            command
        };
        #[cfg(not(windows))]
        let command = {
            let mut command = CommandBuilder::new("echo");
            command.arg("hello from the pty");
            command
        };
        let child = pair.slave.spawn_command(command).unwrap();
        drop(pair.slave);
        let mut reader = pair.master.try_clone_reader().unwrap();
        let mut writer = pair.master.take_writer().unwrap();
        let (tx, rx) = std::sync::mpsc::channel();
        std::thread::spawn(move || {
            let mut buffer = [0u8; 256];
            while let Ok(n) = reader.read(&mut buffer) {
                if n == 0 || tx.send(String::from_utf8_lossy(&buffer[..n]).into_owned()).is_err() {
                    break;
                }
            }
        });
        // The ids of this test keep clear of the ones `NEXT` gives out.
        let id = u64::MAX;
        #[cfg(windows)]
        {
            LOGINS.lock().unwrap().insert(id, Login { master: pair.master, writer: Box::new(std::io::sink()), child });
            close_console_at_the_end(id);
        }
        #[cfg(not(windows))]
        let mut child = child;

        let mut text = String::new();
        let mut asked = false;
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(30);
        // The channel closing is the end of the output: that is what tells the window the login is over.
        loop {
            let left = deadline.saturating_duration_since(std::time::Instant::now());
            match rx.recv_timeout(left) {
                Ok(part) => {
                    text.push_str(&part);
                    // A console of Windows first asks where its cursor is and waits for the answer; in the window xterm.js gives it.
                    if !asked && text.contains("\x1b[6n") {
                        asked = true;
                        let _ = writer.write_all(b"\x1b[1;1R");
                        let _ = writer.flush();
                    }
                }
                Err(std::sync::mpsc::RecvTimeoutError::Disconnected) => break,
                // On a Mac and Linux the end only comes once the other side of the terminal is closed too, which is what the output is enough for here.
                Err(std::sync::mpsc::RecvTimeoutError::Timeout) => panic!("the login did not end, output so far: {text:?}"),
            }
            #[cfg(not(windows))]
            if text.contains("hello from the pty") {
                break;
            }
        }
        #[cfg(not(windows))]
        let _ = child.wait();
        #[cfg(windows)]
        assert!(!LOGINS.lock().unwrap().contains_key(&id));
        let _ = id;
        assert!(text.contains("hello from the pty"), "{text:?}");
    }
}
