//! What plays on this computer, told to the phones: a phone or the watch on it shows it and can press its buttons. The system says nothing
//! when a player changes on every desktop, so it is looked at every few seconds, and only while a phone is connected.

use std::collections::HashMap;
use std::sync::Mutex;
use std::time::Duration;

use tandem_core::ffi::{TandemMediaAction, TandemMediaPlayer, TandemPlatform};
use tandem_winsys::players::{Command, Player, list, press};
use tauri::{AppHandle, Manager};

use crate::settings;
use crate::state::AppState;

const EVERY: Duration = Duration::from_secs(3);

/// What each phone was last told, so it hears only a change, and all of it again after it connected.
static TOLD: Mutex<Option<HashMap<String, String>>> = Mutex::new(None);

/// What makes a list different. Not the position, which is different every time and which the phone counts on by itself.
fn fingerprint(players: &[Player]) -> String {
    let parts: Vec<_> = players
        .iter()
        .map(|p| format!("{}\u{1f}{}\u{1f}{}\u{1f}{}\u{1f}{}\u{1f}{:?}", p.id, p.title, p.artist, p.album, p.playing, p.duration_ms))
        .collect();
    parts.join("\u{1e}")
}

fn for_phone(player: Player) -> TandemMediaPlayer {
    TandemMediaPlayer {
        id: player.id,
        app: player.app,
        title: player.title,
        artist: player.artist,
        album: player.album,
        playing: player.playing,
        position_ms: player.position_ms,
        duration_ms: player.duration_ms,
        can_prev: player.can_prev,
        can_next: player.can_next,
        can_seek: player.can_seek,
        art: 0,
    }
}

/// The phones that need to hear `current` now, given what they were told. A phone that was told nothing has nothing to unlearn from an empty list.
fn who_hears(told: &HashMap<String, String>, phones: &[String], current: &str) -> Vec<String> {
    phones.iter().filter(|id| told.get(*id).map_or(!current.is_empty(), |said| said != current)).cloned().collect()
}

pub fn start(app: &AppHandle) {
    let app = app.clone();
    std::thread::spawn(move || {
        loop {
            std::thread::sleep(EVERY);
            look(&app);
        }
    });
}

fn look(app: &AppHandle) {
    let Ok(engine) = app.state::<AppState>().engine() else { return };
    let phones: Vec<String> = engine.devices().into_iter().filter(|d| d.online && d.platform == TandemPlatform::Android).map(|d| d.id).collect();
    let mut guard = TOLD.lock().unwrap();
    let told = guard.get_or_insert_with(HashMap::new);
    // One that left starts over when it comes back.
    told.retain(|id, _| phones.contains(id));
    if phones.is_empty() {
        return;
    }
    let players = if settings::get(app).share_media { list() } else { Vec::new() };
    let current = fingerprint(&players);
    let targets = who_hears(told, &phones, &current);
    if targets.is_empty() {
        return;
    }
    for id in &targets {
        told.insert(id.clone(), current.clone());
    }
    drop(guard);
    let list = players.into_iter().map(for_phone).collect();
    let _ = tauri::async_runtime::block_on(engine.send_media_players(targets, list));
}

/// A button pressed on a phone or a watch. The volume is that of the computer, whichever player was meant.
pub fn command(app: &AppHandle, player: String, action: Option<TandemMediaAction>, position_ms: Option<u64>) {
    if !settings::get(app).share_media {
        return;
    }
    let Some(action) = action else { return };
    // Asking the system can take a moment: not on the thread that handles the events.
    std::thread::spawn(move || {
        let pressed = match action {
            TandemMediaAction::VolumeUp => return tandem_winsys::find::step_output(true),
            TandemMediaAction::VolumeDown => return tandem_winsys::find::step_output(false),
            TandemMediaAction::Play => Command::Play,
            TandemMediaAction::Pause => Command::Pause,
            TandemMediaAction::Toggle => Command::Toggle,
            TandemMediaAction::Next => Command::Next,
            TandemMediaAction::Previous => Command::Previous,
            TandemMediaAction::Seek => match position_ms {
                Some(ms) => Command::Seek(ms),
                None => return,
            },
        };
        press(&player, pressed);
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    fn player(title: &str, playing: bool, position: u64) -> Player {
        Player {
            id: "a".into(),
            app: "App".into(),
            title: title.into(),
            artist: String::new(),
            album: String::new(),
            playing,
            position_ms: Some(position),
            duration_ms: Some(1000),
            can_prev: true,
            can_next: true,
            can_seek: true,
        }
    }

    #[test]
    fn only_a_real_change_is_told() {
        assert_eq!(fingerprint(&[player("x", true, 1)]), fingerprint(&[player("x", true, 900)]));
        assert_ne!(fingerprint(&[player("x", true, 1)]), fingerprint(&[player("x", false, 1)]));
        assert_ne!(fingerprint(&[player("x", true, 1)]), fingerprint(&[player("y", true, 1)]));
    }

    #[test]
    fn a_phone_hears_a_change_and_nothing_else() {
        let phones = vec!["p".to_string()];
        let mut told = HashMap::new();
        assert!(who_hears(&told, &phones, "").is_empty());
        assert_eq!(who_hears(&told, &phones, "song"), phones);
        told.insert("p".to_string(), "song".to_string());
        assert!(who_hears(&told, &phones, "song").is_empty());
        // The music stopped: the phone has to hear that it is gone.
        assert_eq!(who_hears(&told, &phones, ""), phones);
    }
}
