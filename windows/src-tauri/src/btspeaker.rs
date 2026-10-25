//! A phone asks this computer to be its Bluetooth speaker (`Msg::BtSpeaker`), when it cannot send its sound without the screen
//! recording question of Android. This computer connects to the phone's Audio Source from its own side (see
//! `tandem_winsys::btspeaker`) and answers with `Msg::BtSpeakerState`, the error in plain words when it did not work, so the phone
//! can fall back to sending the sound itself. The sound then plays through the system's own Bluetooth audio, not through `sound`.

use std::collections::HashMap;
use std::sync::{LazyLock, Mutex};
use std::time::Duration;

use tandem_winsys::btspeaker::Speaker;
use tauri::{AppHandle, Manager};

use crate::state::AppState;
use crate::{events, settings};

struct Playing {
    speaker: Speaker,
    /// Tells this start from a later one of the same phone, so the watch of an old one lets go.
    generation: u64,
}

static PLAYING: LazyLock<Mutex<HashMap<String, Playing>>> = LazyLock::new(|| Mutex::new(HashMap::new()));
static GENERATION: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(1);

/// Whether this computer can say `audio.bt_sink`.
pub fn available() -> bool {
    tandem_winsys::btspeaker::available()
}

/// Whether the sound of `from` comes in over Bluetooth now.
pub fn playing_from(from: &str) -> bool {
    PLAYING.lock().unwrap().contains_key(from)
}

fn reply(app: &AppHandle, to: &str, on: bool, error: String) {
    let Ok(engine) = app.state::<AppState>().engine() else { return };
    let to = to.to_string();
    tauri::async_runtime::spawn(async move {
        let _ = engine.send_bt_speaker_state(to, on, error).await;
    });
}

pub fn requested(app: &AppHandle, from: String, on: bool, name: String) {
    let app = app.clone();
    // Connecting takes seconds, and BlueZ and WinRT block while it does.
    std::thread::Builder::new()
        .name("tandem-bt-speaker-ask".into())
        .spawn(move || {
            let old = PLAYING.lock().unwrap().remove(&from);
            if let Some(old) = old {
                old.speaker.stop();
            }
            if !on {
                reply(&app, &from, false, String::new());
                return;
            }
            if let Err(error) = start(&app, &from, &name) {
                log::info!("no Bluetooth speaker for {from}: {error}");
                reply(&app, &from, false, error);
            }
        })
        .ok();
}

fn start(app: &AppHandle, from: &str, name: &str) -> Result<(), String> {
    // The same switch as for the sound that comes over the network, with the same exception for a screen this computer looks at.
    if !settings::get(app).phone_sound && !crate::screen_sound::asked(from) {
        return Err("this computer does not play the sound of other devices".into());
    }
    // Its own sound goes to that phone: the phone's sound coming back would be recorded and sent again, round and round.
    if crate::output::sending_to(from) {
        return Err("this computer sends its own sound to that phone".into());
    }
    let mut names = vec![name.to_string()];
    let known = events::device_name(app, from);
    if !known.is_empty() && !names.contains(&known) {
        names.push(known);
    }
    names.retain(|n| !n.trim().is_empty());
    let speaker = Speaker::connect(&names)?;
    log::info!("playing the sound of {} over Bluetooth", speaker.name());
    let generation = GENERATION.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
    PLAYING.lock().unwrap().insert(from.to_string(), Playing { speaker, generation });
    reply(app, from, true, String::new());
    watch(app.clone(), from.to_string(), generation);
    Ok(())
}

/// Tells the phone when the link ends by itself (it went out of range, its person chose another output there).
fn watch(app: AppHandle, from: String, generation: u64) {
    std::thread::Builder::new()
        .name("tandem-bt-speaker-watch".into())
        .spawn(move || loop {
            std::thread::sleep(Duration::from_secs(3));
            let mut playing = PLAYING.lock().unwrap();
            let Some(current) = playing.get(&from).filter(|p| p.generation == generation) else { return };
            if current.speaker.connected() {
                continue;
            }
            if let Some(ended) = playing.remove(&from) {
                drop(playing);
                ended.speaker.stop();
                reply(&app, &from, false, "the Bluetooth link ended".into());
            }
            return;
        })
        .ok();
}
