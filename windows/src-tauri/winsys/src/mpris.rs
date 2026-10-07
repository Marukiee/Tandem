//! The media controls of a Linux desktop, which are a service on the session bus called MPRIS. GNOME, KDE, the lock screen and the
//! media keys of the keyboard all find players through it. The music of a phone shows up as a player named Tandem, with the title,
//! the artist and the cover, and the buttons go back to the phone.

use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;

use zbus::blocking::Connection;
use zbus::blocking::connection::Builder;
use zbus::zvariant::{ObjectPath, OwnedValue, Value};
use zbus::interface;

use crate::{Button, Now, Request};

type Callback = Arc<dyn Fn(Request) + Send + Sync>;

const PATH: &str = "/org/mpris/MediaPlayer2";
const NAME: &str = "org.mpris.MediaPlayer2.tandem";

struct Root;

#[interface(name = "org.mpris.MediaPlayer2")]
impl Root {
    fn raise(&self) {}
    fn quit(&self) {}

    #[zbus(property)]
    fn can_quit(&self) -> bool {
        false
    }
    #[zbus(property)]
    fn can_raise(&self) -> bool {
        false
    }
    #[zbus(property)]
    fn has_track_list(&self) -> bool {
        false
    }
    #[zbus(property)]
    fn identity(&self) -> &str {
        "Tandem"
    }
    #[zbus(property)]
    fn desktop_entry(&self) -> &str {
        "Tandem"
    }
    #[zbus(property)]
    fn supported_uri_schemes(&self) -> Vec<String> {
        Vec::new()
    }
    #[zbus(property)]
    fn supported_mime_types(&self) -> Vec<String> {
        Vec::new()
    }
}

struct Player {
    now: Now,
    visible: bool,
    cover_url: Option<String>,
    on_request: Callback,
}

impl Player {
    fn playing(&self) -> bool {
        self.visible && self.now.playing
    }
}

#[interface(name = "org.mpris.MediaPlayer2.Player")]
impl Player {
    fn next(&self) {
        (self.on_request)(Request::Button(Button::Next));
    }
    fn previous(&self) {
        (self.on_request)(Request::Button(Button::Previous));
    }
    fn play(&self) {
        (self.on_request)(Request::Button(Button::Play));
    }
    fn pause(&self) {
        (self.on_request)(Request::Button(Button::Pause));
    }
    fn stop(&self) {
        (self.on_request)(Request::Button(Button::Stop));
    }
    fn play_pause(&self) {
        let button = if self.playing() { Button::Pause } else { Button::Play };
        (self.on_request)(Request::Button(button));
    }
    /// Relative to where it is now, in microseconds.
    fn seek(&self, offset: i64) {
        let now = self.now.position.map(|p| p.as_micros() as i64).unwrap_or(0);
        (self.on_request)(Request::Seek(Duration::from_micros((now + offset).max(0) as u64)));
    }
    fn set_position(&self, _track: ObjectPath<'_>, position: i64) {
        (self.on_request)(Request::Seek(Duration::from_micros(position.max(0) as u64)));
    }
    fn open_uri(&self, _uri: &str) {}

    #[zbus(property)]
    fn playback_status(&self) -> &str {
        if !self.visible {
            "Stopped"
        } else if self.now.playing {
            "Playing"
        } else {
            "Paused"
        }
    }
    #[zbus(property)]
    fn metadata(&self) -> HashMap<String, OwnedValue> {
        let mut map: HashMap<String, OwnedValue> = HashMap::new();
        let mut put = |key: &str, value: Value<'_>| {
            if let Ok(value) = OwnedValue::try_from(value) {
                map.insert(key.to_string(), value);
            }
        };
        if let Ok(track) = ObjectPath::try_from("/nl/markmaaktmedia/tandem/track") {
            put("mpris:trackid", Value::from(track));
        }
        if self.visible {
            put("xesam:title", Value::from(self.now.title.clone()));
            if !self.now.artist.is_empty() {
                put("xesam:artist", Value::from(vec![self.now.artist.clone()]));
            }
            if !self.now.album.is_empty() {
                put("xesam:album", Value::from(self.now.album.clone()));
            }
            if let Some(length) = self.now.duration {
                put("mpris:length", Value::from(length.as_micros() as i64));
            }
            if let Some(url) = &self.cover_url {
                put("mpris:artUrl", Value::from(url.clone()));
            }
        }
        map
    }
    #[zbus(property)]
    fn position(&self) -> i64 {
        self.now.position.map(|p| p.as_micros() as i64).unwrap_or(0)
    }
    #[zbus(property)]
    fn rate(&self) -> f64 {
        1.0
    }
    #[zbus(property)]
    fn minimum_rate(&self) -> f64 {
        1.0
    }
    #[zbus(property)]
    fn maximum_rate(&self) -> f64 {
        1.0
    }
    #[zbus(property)]
    fn volume(&self) -> f64 {
        1.0
    }
    #[zbus(property)]
    fn can_go_next(&self) -> bool {
        self.visible && self.now.can_next
    }
    #[zbus(property)]
    fn can_go_previous(&self) -> bool {
        self.visible && self.now.can_prev
    }
    #[zbus(property)]
    fn can_play(&self) -> bool {
        self.visible
    }
    #[zbus(property)]
    fn can_pause(&self) -> bool {
        self.visible
    }
    #[zbus(property)]
    fn can_seek(&self) -> bool {
        self.visible && self.now.duration.is_some()
    }
    #[zbus(property)]
    fn can_control(&self) -> bool {
        true
    }
}

/// The player on the bus. When there is no session bus (a machine without a desktop), nothing happens and the app goes on.
pub struct Media {
    connection: Option<Connection>,
}

impl Media {
    pub fn start(on_request: impl Fn(Request) + Send + Sync + 'static) -> Media {
        let player = Player { now: Now::default(), visible: false, cover_url: None, on_request: Arc::new(on_request) };
        let built = Builder::session()
            .and_then(|b| b.name(NAME))
            .and_then(|b| b.serve_at(PATH, Root))
            .and_then(|b| b.serve_at(PATH, player))
            .and_then(|b| b.build());
        match built {
            Ok(connection) => Media { connection: Some(connection) },
            Err(error) => {
                log::warn!("the media controls of the desktop are not available: {error}");
                Media { connection: None }
            }
        }
    }

    pub fn show(&self, now: Now) {
        self.update(Some(now));
    }

    pub fn hide(&self) {
        self.update(None);
    }

    fn update(&self, now: Option<Now>) {
        let Some(connection) = &self.connection else { return };
        let Ok(player) = connection.object_server().interface::<_, Player>(PATH) else { return };
        {
            let mut guard = player.get_mut();
            match now {
                Some(now) => {
                    // A cover is a file the desktop opens by its address: a new one is written when the picture changes.
                    if now.art != guard.now.art || guard.cover_url.is_none() {
                        guard.cover_url = now.cover.as_deref().and_then(|jpeg| write_cover(now.art, jpeg));
                    }
                    guard.now = now;
                    guard.visible = true;
                }
                None => guard.visible = false,
            }
        }
        let emitter = player.signal_emitter();
        let guard = player.get();
        zbus::block_on(async {
            let _ = guard.playback_status_changed(emitter).await;
            let _ = guard.metadata_changed(emitter).await;
            let _ = guard.can_go_next_changed(emitter).await;
            let _ = guard.can_go_previous_changed(emitter).await;
            let _ = guard.can_play_changed(emitter).await;
            let _ = guard.can_pause_changed(emitter).await;
            let _ = guard.can_seek_changed(emitter).await;
        });
    }
}

fn write_cover(art: u64, jpeg: &[u8]) -> Option<String> {
    let path = std::env::temp_dir().join(format!("tandem-cover-{art}.jpg"));
    std::fs::write(&path, jpeg).ok()?;
    Some(format!("file://{}", path.to_string_lossy()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;

    /// Needs a session bus: `dbus-run-session -- cargo test`. Without one it says so and passes, so a machine without a desktop is not
    /// a failure.
    #[test]
    fn a_phone_shows_up_as_a_player_and_its_buttons_come_back() {
        if std::env::var_os("DBUS_SESSION_BUS_ADDRESS").is_none() {
            eprintln!("no session bus, so the player is not tried");
            return;
        }
        let seen: Arc<Mutex<Vec<Request>>> = Arc::new(Mutex::new(Vec::new()));
        let sink = seen.clone();
        let media = Media::start(move |request| sink.lock().unwrap().push(request));
        assert!(media.connection.is_some(), "the service could not be put on the bus");
        media.show(Now {
            title: "A song".into(),
            artist: "Somebody".into(),
            playing: true,
            duration: Some(Duration::from_secs(200)),
            can_next: true,
            ..Now::default()
        });

        // Another program on the same bus asks for what the desktop would ask.
        let client = Connection::session().unwrap();
        let proxy = zbus::blocking::Proxy::new(&client, NAME, PATH, "org.mpris.MediaPlayer2.Player").unwrap();
        let status: String = proxy.get_property("PlaybackStatus").unwrap();
        assert_eq!(status, "Playing");
        let metadata: HashMap<String, OwnedValue> = proxy.get_property("Metadata").unwrap();
        let title: String = metadata["xesam:title"].try_clone().unwrap().try_into().unwrap();
        assert_eq!(title, "A song");
        proxy.call_method("Next", &()).unwrap();
        proxy.call_method("PlayPause", &()).unwrap();
        let requests = seen.lock().unwrap().clone();
        assert_eq!(requests, vec![Request::Button(Button::Next), Request::Button(Button::Pause)]);

        media.hide();
        let status: String = proxy.get_property("PlaybackStatus").unwrap();
        assert_eq!(status, "Stopped");
    }
}
