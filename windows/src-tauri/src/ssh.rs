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
use tauri::{AppHandle, Emitter, Manager, State, WebviewUrl, WebviewWindowBuilder};

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
        for ip in ips.into_iter().take(4) {
            let Ok(addr) = format!("{ip}:22").parse::<SocketAddr>().or_else(|_| format!("[{ip}]:22").parse::<SocketAddr>()) else { continue };
            if TcpStream::connect_timeout(&addr, Duration::from_millis(1500)).is_ok() {
                return Some(ip);
            }
        }
        None
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
    WebviewWindowBuilder::new(&app, &label, WebviewUrl::App(url.into()))
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
    let child = pair.slave.spawn_command(command).map_err(|e| format!("ssh could not start: {e}"))?;
    drop(pair.slave);
    let mut reader = pair.master.try_clone_reader().map_err(|e| e.to_string())?;
    let writer = pair.master.take_writer().map_err(|e| e.to_string())?;

    let id = NEXT.fetch_add(1, Ordering::Relaxed);
    LOGINS.lock().unwrap().insert(id, Login { master: pair.master, writer, child });

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
        assert_eq!(urlencode("Mac van Mark"), "Mac%20van%20Mark");
        assert_eq!(urlencode("fe80::1"), "fe80%3A%3A1");
    }

    #[test]
    fn a_login_runs_in_a_terminal_and_its_output_comes_back() {
        let pair = native_pty_system().openpty(PtySize { rows: 24, cols: 80, pixel_width: 0, pixel_height: 0 }).unwrap();
        let mut command = CommandBuilder::new("echo");
        command.arg("hello from the pty");
        let mut child = pair.slave.spawn_command(command).unwrap();
        drop(pair.slave);
        let mut reader = pair.master.try_clone_reader().unwrap();
        let mut text = String::new();
        let mut buffer = [0u8; 256];
        while let Ok(n) = reader.read(&mut buffer) {
            if n == 0 {
                break;
            }
            text.push_str(&String::from_utf8_lossy(&buffer[..n]));
            if text.contains("hello from the pty") {
                break;
            }
        }
        let _ = child.wait();
        assert!(text.contains("hello from the pty"), "{text:?}");
    }
}
