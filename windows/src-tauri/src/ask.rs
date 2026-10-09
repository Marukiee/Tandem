//! The question whether a device may look at this screen, and perhaps use the mouse and keyboard. It is a card in the corner (a small
//! window of Tandem, the same kind as the card of Quick Share) with three answers. Where a window cannot be put in front (Wayland) the
//! same question is also a notification with the buttons, which is seen. The first answer counts, and the other one goes away.

use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{mpsc, Mutex};
use std::time::Duration;

use serde::Serialize;
use tauri::{AppHandle, Emitter, LogicalPosition, LogicalSize, Manager};

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Answer {
    Allow,
    Always,
    Deny,
}

/// What the card says. The words are made here, in the language of the app, so the page has nothing to translate.
#[derive(Clone, Serialize)]
pub struct Question {
    pub id: u64,
    pub title: String,
    pub body: String,
    pub allow: String,
    pub always: String,
    pub deny: String,
    /// The mouse and keyboard are asked for too.
    pub control: bool,
}

static PENDING: Mutex<Vec<(Question, mpsc::Sender<Answer>)>> = Mutex::new(Vec::new());
static NEXT: AtomicU64 = AtomicU64::new(1);

const WIDTH: f64 = 392.0;
/// The height of one card with the longest text there is, plus the room around it.
const HEIGHT: f64 = 212.0;
const GIVE_UP: Duration = Duration::from_secs(120);

/// Asks and waits for the answer; nobody answering is a no. Call it from a thread that may wait.
pub fn ask(app: &AppHandle, mut question: Question) -> Answer {
    let id = NEXT.fetch_add(1, Ordering::Relaxed);
    question.id = id;
    let (tx, rx) = mpsc::channel();
    #[cfg(target_os = "linux")]
    let notification = {
        let tx = tx.clone();
        let buttons = [("allow", question.allow.as_str()), ("always", question.always.as_str()), ("deny", question.deny.as_str())];
        tandem_winsys::notify::ask(&question.title, &question.body, &buttons, move |key| {
            let _ = tx.send(match key {
                "allow" => Answer::Allow,
                "always" => Answer::Always,
                _ => Answer::Deny,
            });
        })
    };
    PENDING.lock().unwrap().push((question, tx));
    refresh(app);
    let answer = rx.recv_timeout(GIVE_UP).unwrap_or(Answer::Deny);
    #[cfg(target_os = "linux")]
    if let Some(number) = notification {
        tandem_winsys::notify::close(number);
    }
    PENDING.lock().unwrap().retain(|(q, _)| q.id != id);
    refresh(app);
    answer
}

fn questions() -> Vec<Question> {
    PENDING.lock().unwrap().iter().map(|(q, _)| q.clone()).collect()
}

#[tauri::command]
pub fn ask_state() -> Vec<Question> {
    questions()
}

#[tauri::command]
pub fn ask_answer(id: u64, answer: String) {
    let answer = match answer.as_str() {
        "allow" => Answer::Allow,
        "always" => Answer::Always,
        _ => Answer::Deny,
    };
    if let Some((_, tx)) = PENDING.lock().unwrap().iter().find(|(q, _)| q.id == id) {
        let _ = tx.send(answer);
    }
}

fn refresh(app: &AppHandle) {
    let list = questions();
    let _ = app.emit("host_asks", &list);
    let count = list.len();
    let handle = app.clone();
    let _ = app.run_on_main_thread(move || card(&handle, count));
}

/// The card is a small window of its own in the bottom right corner, above the card of Quick Share when that is there too.
fn card(app: &AppHandle, count: usize) {
    let existing = app.get_webview_window("ask-card");
    if count == 0 {
        if let Some(window) = existing {
            let _ = window.close();
        }
        return;
    }
    let height = 24.0 + count as f64 * HEIGHT;
    match existing {
        Some(window) => {
            let _ = window.set_size(LogicalSize::new(WIDTH, height));
            place(app, &window, height);
        }
        None => {
            let built = tauri::WebviewWindowBuilder::new(app, "ask-card", tauri::WebviewUrl::App("ask.html".into()))
                .title("Tandem")
                .inner_size(WIDTH, height)
                .decorations(false)
                .resizable(false)
                .always_on_top(true)
                .skip_taskbar(true)
                // Where a window cannot be put in a corner or kept on top (Wayland), it has to be given the focus to be seen at all.
                .focused(cfg!(target_os = "linux"))
                .build();
            if let Ok(window) = built {
                place(app, &window, height);
            }
        }
    }
}

fn place(app: &AppHandle, window: &tauri::WebviewWindow, height: f64) {
    let Ok(Some(monitor)) = app.primary_monitor() else { return };
    let scale = monitor.scale_factor();
    let size = monitor.size();
    let (width_points, height_points) = (size.width as f64 / scale, size.height as f64 / scale);
    let below = app
        .get_webview_window("qs-card")
        .and_then(|w| w.outer_size().ok().map(|s| s.height as f64 / scale))
        .unwrap_or(0.0);
    let _ = window.set_position(LogicalPosition::new(width_points - WIDTH - 16.0, height_points - height - 64.0 - below));
}
