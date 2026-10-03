//! What the phone plays, in the media controls of Windows: the overlay by the volume, the lock screen, and the media
//! keys of the keyboard. The system is told what plays and which buttons make sense; its buttons come back as commands
//! for the phone.

use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};

use base64::Engine as _;
use tandem_core::ffi::{TandemMediaAction, TandemMediaPlayer};
use tandem_winsys::{Button, Media, Now, Request};
use tauri::{AppHandle, Manager};

use crate::settings;
use crate::state::{AppState, Data};

/// How long a paused player stays in the media controls. After that the keys are free for whatever plays on this PC.
const PAUSE_GRACE: Duration = Duration::from_secs(300);

static MEDIA: OnceLock<Media> = OnceLock::new();

/// The player the controls show, which is the one their buttons act on.
static ACTIVE: Mutex<Option<Active>> = Mutex::new(None);

/// The moment a pause runs out that a timer is already waiting for, so a flood of updates starts one timer, not many.
static EXPIRY: Mutex<Option<Instant>> = Mutex::new(None);

#[derive(Clone)]
struct Active {
    device: String,
    player: String,
    can_seek: bool,
    /// When it last played, which is now while it does.
    last_playing: Instant,
}

#[cfg(windows)]
pub fn start(app: &AppHandle) {
    let Some(window) = app.get_webview_window("main") else { return };
    let Ok(hwnd) = window.hwnd() else {
        log::warn!("the main window has no handle for the media controls");
        return;
    };
    let handle = app.clone();
    let _ = MEDIA.set(Media::start(hwnd.0 as isize, move |request| respond(&handle, request)));
}

#[cfg(not(windows))]
pub fn start(app: &AppHandle) {
    let handle = app.clone();
    let _ = MEDIA.set(Media::start(0, move |request| respond(&handle, request)));
}

/// Looks again at what the phones play and shows it, or takes it away.
pub fn refresh(app: &AppHandle) {
    let Some(media) = MEDIA.get() else { return };
    let enabled = settings::get(app).system_media;
    let state = app.state::<AppState>();
    let shown = {
        let data = state.data.lock().unwrap();
        let mut active = ACTIVE.lock().unwrap();
        let chosen = if enabled { choose(&data, active.as_ref()) } else { None };
        *active = chosen.as_ref().map(|(device, player, last_playing)| Active {
            device: device.clone(),
            player: player.id.clone(),
            can_seek: player.can_seek,
            last_playing: *last_playing,
        });
        chosen.map(|(_, player, last_playing)| (cover(&data, player.art), player, last_playing))
    };
    match shown {
        Some((cover, player, last_playing)) => {
            if !player.playing {
                expire_at(app, last_playing + PAUSE_GRACE);
            }
            media.show(Now {
                title: player.title,
                artist: player.artist,
                album: player.album,
                playing: player.playing,
                position: player.position_ms.map(Duration::from_millis),
                duration: player.duration_ms.map(Duration::from_millis),
                can_prev: player.can_prev,
                can_next: player.can_next,
                art: player.art,
                cover,
            });
        }
        None => media.hide(),
    }
}

/// The player to show: one that plays (the one already shown if it still does), else the one that played last, for a
/// while, so a pause does not take the controls away.
fn choose(data: &Data, previous: Option<&Active>) -> Option<(String, TandemMediaPlayer, Instant)> {
    let all = || data.players.iter().flat_map(|(device, list)| list.iter().map(move |player| (device, player)));
    let playing: Vec<_> = all().filter(|(_, player)| player.playing).collect();
    if !playing.is_empty() {
        let pick = playing
            .iter()
            .find(|(device, player)| previous.is_some_and(|a| a.device == **device && a.player == player.id))
            .or(playing.first())?;
        return Some((pick.0.clone(), pick.1.clone(), Instant::now()));
    }
    let previous = previous?;
    if previous.last_playing.elapsed() > PAUSE_GRACE {
        return None;
    }
    let (device, player) = all().find(|(device, player)| **device == previous.device && player.id == previous.player)?;
    Some((device.clone(), player.clone(), previous.last_playing))
}

fn cover(data: &Data, key: u64) -> Option<Vec<u8>> {
    if key == 0 {
        return None;
    }
    let uri = data.art.get(&key)?;
    let encoded = uri.strip_prefix("data:image/jpeg;base64,")?;
    base64::engine::general_purpose::STANDARD.decode(encoded).ok()
}

/// Looks again when the pause has run out, since nothing else tells that a minute has passed.
fn expire_at(app: &AppHandle, deadline: Instant) {
    let mut waiting = EXPIRY.lock().unwrap();
    if *waiting == Some(deadline) {
        return;
    }
    *waiting = Some(deadline);
    let app = app.clone();
    std::thread::spawn(move || {
        std::thread::sleep(deadline.saturating_duration_since(Instant::now()) + Duration::from_secs(1));
        refresh(&app);
    });
}

/// A button of the system, or the bar dragged: it goes to the phone that plays.
fn respond(app: &AppHandle, request: Request) {
    let Some(active) = ACTIVE.lock().unwrap().clone() else { return };
    let (action, position) = match request {
        Request::Button(Button::Play) => (TandemMediaAction::Play, None),
        Request::Button(Button::Pause | Button::Stop) => (TandemMediaAction::Pause, None),
        Request::Button(Button::Next) => (TandemMediaAction::Next, None),
        Request::Button(Button::Previous) => (TandemMediaAction::Previous, None),
        Request::Seek(to) if active.can_seek => (TandemMediaAction::Seek, Some(to.as_millis() as u64)),
        Request::Seek(_) => return,
    };
    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        let engine = app.state::<AppState>().engine();
        if let Ok(engine) = engine {
            let _ = engine.send_media_command(active.device, active.player, action, position).await;
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    fn player(id: &str, playing: bool) -> TandemMediaPlayer {
        TandemMediaPlayer {
            id: id.into(),
            app: "App".into(),
            title: "Title".into(),
            artist: "Artist".into(),
            album: String::new(),
            playing,
            position_ms: None,
            duration_ms: None,
            can_prev: true,
            can_next: true,
            can_seek: true,
            art: 0,
        }
    }

    fn data(players: &[(&str, Vec<TandemMediaPlayer>)]) -> Data {
        let mut data = Data::default();
        for (device, list) in players {
            data.players.insert(device.to_string(), list.clone());
        }
        data
    }

    fn shown(device: &str, id: &str, ago: Duration) -> Active {
        let last_playing = Instant::now().checked_sub(ago).expect("the machine has been up for a few minutes");
        Active { device: device.into(), player: id.into(), can_seek: true, last_playing }
    }

    #[test]
    fn what_plays_is_shown() {
        let data = data(&[("phone", vec![player("a", false), player("b", true)])]);
        let (device, chosen, _) = choose(&data, None).unwrap();
        assert_eq!((device.as_str(), chosen.id.as_str()), ("phone", "b"));
    }

    #[test]
    fn the_player_that_was_shown_stays_while_another_plays_too() {
        let data = data(&[("phone", vec![player("a", true), player("b", true)])]);
        let before = shown("phone", "b", Duration::ZERO);
        assert_eq!(choose(&data, Some(&before)).unwrap().1.id, "b");
    }

    #[test]
    fn a_pause_keeps_the_player_for_a_while() {
        let data = data(&[("phone", vec![player("a", false)])]);
        let before = shown("phone", "a", Duration::from_secs(60));
        let (_, chosen, last_playing) = choose(&data, Some(&before)).unwrap();
        assert!(!chosen.playing);
        assert_eq!(last_playing, before.last_playing);
    }

    #[test]
    fn a_long_pause_gives_the_keys_back() {
        let data = data(&[("phone", vec![player("a", false)])]);
        let before = shown("phone", "a", PAUSE_GRACE + Duration::from_secs(5));
        assert!(choose(&data, Some(&before)).is_none());
    }

    #[test]
    fn nothing_is_shown_when_nothing_plays_and_nothing_was() {
        let data = data(&[("phone", vec![player("a", false)])]);
        assert!(choose(&data, None).is_none());
    }

    #[test]
    fn a_player_that_is_gone_is_not_kept() {
        let data = data(&[("phone", vec![player("b", false)])]);
        let before = shown("phone", "a", Duration::from_secs(10));
        assert!(choose(&data, Some(&before)).is_none());
    }
}
