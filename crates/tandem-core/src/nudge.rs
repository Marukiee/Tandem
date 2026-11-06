//! A nudge: a program on this computer says something short, and it ends up on the wrist of whoever owns the phone. A render that is done,
//! a download, a script that waits. It travels as an ordinary notification with an app id of its own, so the phone can tell it apart and
//! hand it to the watch, or show it itself when there is none. On purpose not a `tandem://` link: any website could open that.

use std::path::Path;
use std::sync::Mutex;
use std::time::{Duration, Instant};

use crate::Engine;
use crate::ids::{Platform, now_ms};
use crate::local;
use crate::proto::{Msg, NotificationMsg};

pub const APP_ID: &str = "tandem.nudge";
const MAX_CHARS: usize = 200;
const MIN_GAP: Duration = Duration::from_secs(10);
const CHANNEL: &str = "tandem";

/// When the last one went out. A loop in a script must not make a wrist buzz all night.
static LAST: Mutex<Option<Instant>> = Mutex::new(None);

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum NudgeError {
    Empty,
    TooSoon,
    NoPhone,
}

impl std::fmt::Display for NudgeError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            NudgeError::Empty => "there is no text to send",
            NudgeError::TooSoon => "the last one went out less than 10 seconds ago",
            NudgeError::NoPhone => "no phone is connected",
        })
    }
}

/// One line of plain text, cut off at the limit.
fn clean(text: &str) -> String {
    let flat: String = text.chars().map(|c| if c.is_control() { ' ' } else { c }).collect();
    flat.split_whitespace().collect::<Vec<_>>().join(" ").chars().take(MAX_CHARS).collect()
}

fn too_soon(last: Option<Instant>, now: Instant) -> bool {
    last.is_some_and(|last| now.saturating_duration_since(last) < MIN_GAP)
}

fn notification(name: String, text: String, ts: u64) -> NotificationMsg {
    NotificationMsg {
        key: format!("{APP_ID}.{ts}"),
        app_id: APP_ID.to_string(),
        app_name: name.clone(),
        title: name,
        text,
        sub_text: None,
        ts,
        ongoing: false,
        silent: false,
        buttons: Vec::new(),
        otp: None,
        progress: None,
    }
}

impl Engine {
    /// Sends it to the phones that can be reached now, and says how many that were.
    pub async fn nudge(&self, text: &str) -> Result<usize, NudgeError> {
        let text = clean(text);
        if text.is_empty() {
            return Err(NudgeError::Empty);
        }
        let now = Instant::now();
        if too_soon(*LAST.lock().unwrap(), now) {
            return Err(NudgeError::TooSoon);
        }
        let phones: Vec<_> =
            self.devices().into_iter().filter(|d| d.platform == Platform::Android && (d.online || d.ble)).map(|d| d.id).collect();
        let message = notification(self.name(), text, now_ms());
        let reached = self.send_msg(&phones, Msg::Notification(message)).await.len();
        if reached == 0 {
            return Err(NudgeError::NoPhone);
        }
        *LAST.lock().unwrap() = Some(now);
        Ok(reached)
    }
}

/// Listens for `notify` from this machine, for as long as the engine runs. For an app; `tandemd` has its own commands.
pub async fn serve(engine: Engine, dir: &Path) -> std::io::Result<()> {
    local::serve(dir, CHANNEL, move |line| {
        let engine = engine.clone();
        async move {
            let Some(text) = line.strip_prefix("notify\t") else { return "error\nunknown command\n".to_string() };
            match engine.nudge(text).await {
                Ok(_) => "ok\n".to_string(),
                Err(e) => format!("error\n{e}\n"),
            }
        }
    })
    .await
}

/// The command itself: asks the Tandem that runs on this machine. `Err` says what went wrong, in words for a person.
pub fn ask(dir: &Path, text: &str) -> Result<(), String> {
    let runtime = tokio::runtime::Builder::new_current_thread().enable_all().build().map_err(|e| e.to_string())?;
    let line = format!("notify\t{}", clean(text));
    match runtime.block_on(local::ask(dir, CHANNEL, &line)) {
        Ok(Some(reply)) => match reply.split_once('\n') {
            Some(("ok", _)) => Ok(()),
            Some((_, why)) => Err(why.trim().to_string()),
            None => Err(reply),
        },
        Ok(None) => Err("Tandem is not running".to_string()),
        Err(e) => Err(e.to_string()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn text_becomes_one_line_within_the_limit() {
        assert_eq!(clean("  Render\n\tklaar  "), "Render klaar");
        assert_eq!(clean(&"é".repeat(500)).chars().count(), MAX_CHARS);
        assert_eq!(clean(" \n "), "");
    }

    #[test]
    fn a_second_one_waits_ten_seconds() {
        let now = Instant::now();
        assert!(!too_soon(None, now));
        assert!(too_soon(Some(now), now + Duration::from_secs(9)));
        assert!(!too_soon(Some(now), now + Duration::from_secs(10)));
    }
}
