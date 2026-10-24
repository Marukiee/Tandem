//! The sound that belongs to a screen that is looked at. The viewer asks for it (`SoundRequest`) when the person wants it here, or
//! when headphones are in and the person says yes; the computer whose screen it is records what it plays and sends it as it sends its
//! sound to a device picked as its speaker (see `output`), and stops when the screen session ends.

use std::collections::HashSet;
#[cfg(target_os = "linux")]
use std::process::{Command, Stdio};
use std::sync::{LazyLock, Mutex};

use tauri::{AppHandle, Manager};

use crate::state::AppState;
use crate::{events, i18n, settings};

/// The devices whose sound this computer asked for, because it looks at their screen.
static ASKED: LazyLock<Mutex<HashSet<String>>> = LazyLock::new(|| Mutex::new(HashSet::new()));

/// Whether this computer asked `from` for its sound: then it plays, also when the sound of other devices is switched off.
pub fn asked(from: &str) -> bool {
    ASKED.lock().unwrap().contains(from)
}

fn ask_for(app: &AppHandle, peer: &str, on: bool) {
    {
        let mut asked = ASKED.lock().unwrap();
        if on { asked.insert(peer.to_string()) } else { asked.remove(peer) };
    }
    let Ok(engine) = app.state::<AppState>().engine() else { return };
    let peer = peer.to_string();
    tauri::async_runtime::spawn(async move {
        let _ = engine.request_sound(peer, on).await;
    });
}

/// This computer started to look at the screen of `peer`: the sound comes here when that is the choice, or when headphones are in and
/// the person says so.
pub fn view_started(app: &AppHandle, peer: String) {
    let chosen = settings::get(app);
    if chosen.screen_sound == "here" {
        ask_for(app, &peer, true);
        return;
    }
    if !chosen.screen_sound_ask || !headphones() {
        return;
    }
    let app = app.clone();
    std::thread::Builder::new()
        .name("tandem-sound-question".into())
        .spawn(move || {
            let name = events::device_name(&app, &peer);
            let question = crate::ask::Question {
                id: 0,
                title: i18n::t1(&app, "screen_sound_title", &name),
                body: i18n::t(&app, "screen_sound_body"),
                allow: i18n::t(&app, "screen_sound_here"),
                always: i18n::t(&app, "screen_sound_always"),
                deny: i18n::t(&app, "screen_sound_there"),
                control: false,
            };
            match crate::ask::ask(&app, question) {
                crate::ask::Answer::Allow => ask_for(&app, &peer, true),
                crate::ask::Answer::Always => {
                    crate::settings::update(&app, |s| s.screen_sound = "here".into());
                    ask_for(&app, &peer, true);
                }
                crate::ask::Answer::Deny => {}
            }
        })
        .ok();
}

pub fn view_ended(app: &AppHandle, peer: &str) {
    if asked(peer) {
        ask_for(app, peer, false);
        crate::sound::stop(peer);
    }
}

/// Whether what this computer plays goes to headphones now: a Bluetooth device, or the jack or a headset port of the sound card.
pub fn headphones() -> bool {
    #[cfg(target_os = "linux")]
    {
        let run = |args: &[&str]| -> Option<String> {
            let mut command = Command::new("pactl");
            command.args(args).stdin(Stdio::null()).stderr(Stdio::null());
            tandem_winsys::system_env(&mut command);
            command.output().ok().filter(|o| o.status.success()).map(|o| String::from_utf8_lossy(&o.stdout).into_owned())
        };
        let Some(sink) = run(&["get-default-sink"]).map(|s| s.trim().to_string()) else {
            // Without pactl (Fedora leaves it out), PipeWire's own tool still says what the default output is: a Bluetooth one, or a
            // headset or the jack by its name.
            let mut command = Command::new("wpctl");
            command.args(["inspect", "@DEFAULT_AUDIO_SINK@"]).stdin(Stdio::null()).stderr(Stdio::null());
            tandem_winsys::system_env(&mut command);
            let out = command.output().ok().filter(|o| o.status.success()).map(|o| String::from_utf8_lossy(&o.stdout).to_lowercase());
            return out.is_some_and(|o| o.contains("bluez") || o.contains("headphone") || o.contains("headset"));
        };
        if sink.contains("bluez") {
            return true;
        }
        // The port in use of that sink: "headphones" or "headset" when something is in the jack.
        let Some(list) = run(&["list", "sinks"]) else { return false };
        let mut in_sink = false;
        for line in list.lines() {
            let line = line.trim();
            if let Some(name) = line.strip_prefix("Name:") {
                in_sink = name.trim() == sink;
            } else if in_sink {
                if let Some(port) = line.strip_prefix("Active Port:") {
                    let port = port.to_lowercase();
                    return port.contains("headphone") || port.contains("headset");
                }
            }
        }
        false
    }
    #[cfg(not(target_os = "linux"))]
    {
        false
    }
}

// ---- Sending the sound of this computer ------------------------------------------------------------------------------------------

/// A viewer asks for the sound of this computer, or no longer. It goes the way the sound goes to a device picked as the speaker (see
/// `output`), in place of that.
pub fn requested(app: &AppHandle, from: String, on: bool) {
    if !on {
        crate::output::stop_for(app, &from, Some(crate::output::Why::Screen));
        return;
    }
    if let Err(error) = crate::output::start(app, from, crate::output::Why::Screen) {
        log::info!("the sound of this computer does not go to the viewer: {error}");
    }
}

/// The screen session with `peer` ended: its sound stops too.
pub fn host_ended(app: &AppHandle, peer: &str) {
    crate::output::stop_for(app, peer, Some(crate::output::Why::Screen));
}
