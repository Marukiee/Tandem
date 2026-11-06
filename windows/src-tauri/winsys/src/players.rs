//! What plays on this computer, and pressing its buttons, so a phone or a watch can show and use it. Linux asks every player on the session
//! bus (MPRIS), Windows the media sessions of its volume flyout. Tandem's own player is left out: that one shows what the phone plays.

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Player {
    pub id: String,
    pub app: String,
    pub title: String,
    pub artist: String,
    pub album: String,
    pub playing: bool,
    pub position_ms: Option<u64>,
    pub duration_ms: Option<u64>,
    pub can_prev: bool,
    pub can_next: bool,
    pub can_seek: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Command {
    Play,
    Pause,
    Toggle,
    Next,
    Previous,
    Seek(u64),
}

/// Every player that has something to show.
pub fn list() -> Vec<Player> {
    imp::list()
}

/// Presses a button of the player with this id. An id that is no player here does nothing.
pub fn press(id: &str, command: Command) {
    imp::press(id, command)
}

#[cfg(target_os = "linux")]
mod imp {
    use std::collections::HashMap;
    use std::sync::Mutex;

    use zbus::blocking::{Connection, Proxy};
    use zbus::zvariant::{Array, ObjectPath, OwnedValue};

    use super::{Command, Player};
    use crate::pausemedia::imp::{OWN, PATH, PLAYER, PREFIX, players};

    const ROOT: &str = "org.mpris.MediaPlayer2";

    type Metadata = HashMap<String, OwnedValue>;

    fn text(meta: &Metadata, key: &str) -> String {
        meta.get(key).and_then(|v| v.downcast_ref::<&str>().ok().map(str::to_string)).unwrap_or_default()
    }

    fn artists(meta: &Metadata) -> String {
        let Some(value) = meta.get("xesam:artist") else { return String::new() };
        let Ok(list) = value.downcast_ref::<&Array>() else { return text(meta, "xesam:artist") };
        list.iter().filter_map(|v| v.downcast_ref::<&str>().ok()).collect::<Vec<_>>().join(", ")
    }

    /// The length in milliseconds. It is a number of microseconds, in whichever integer type the player chose.
    fn length_ms(meta: &Metadata) -> Option<u64> {
        let value = meta.get("mpris:length")?;
        let micros = value.downcast_ref::<i64>().ok().and_then(|v| u64::try_from(v).ok()).or_else(|| value.downcast_ref::<u64>().ok())?;
        (micros > 0).then_some(micros / 1000)
    }

    /// All properties of an interface in one call. A `Proxy` would subscribe to their changes first, every time this is looked at.
    fn all(bus: &Connection, name: &str, interface: &str) -> Option<HashMap<String, OwnedValue>> {
        let reply = bus.call_method(Some(name), PATH, Some("org.freedesktop.DBus.Properties"), "GetAll", &(interface,)).ok()?;
        reply.body().deserialize().ok()
    }

    fn flag(properties: &HashMap<String, OwnedValue>, key: &str) -> bool {
        properties.get(key).and_then(|v| v.downcast_ref::<bool>().ok()).unwrap_or(false)
    }

    fn player(bus: &Connection, name: &str) -> Option<Player> {
        let properties = all(bus, name, PLAYER)?;
        let meta: Metadata = properties.get("Metadata")?.try_clone().ok()?.try_into().ok()?;
        let title = text(&meta, "xesam:title");
        if title.trim().is_empty() {
            return None;
        }
        let app = all(bus, name, ROOT)
            .map(|root| text(&root, "Identity"))
            .filter(|identity| !identity.is_empty())
            .unwrap_or_else(|| name.trim_start_matches(PREFIX).to_string());
        Some(Player {
            id: name.to_string(),
            app,
            title,
            artist: artists(&meta),
            album: text(&meta, "xesam:album"),
            playing: text(&properties, "PlaybackStatus") == "Playing",
            position_ms: properties.get("Position").and_then(|p| p.downcast_ref::<i64>().ok()).and_then(|p| u64::try_from(p / 1000).ok()),
            duration_ms: length_ms(&meta),
            can_prev: flag(&properties, "CanGoPrevious"),
            can_next: flag(&properties, "CanGoNext"),
            can_seek: flag(&properties, "CanSeek"),
        })
    }

    /// The one connection to the session bus, kept: a new one for every look would mean a handshake and a thread every few seconds.
    fn bus() -> Option<Connection> {
        static BUS: Mutex<Option<Connection>> = Mutex::new(None);
        let mut bus = BUS.lock().unwrap();
        if bus.is_none() {
            *bus = Connection::session().ok();
        }
        bus.clone()
    }

    pub fn list() -> Vec<Player> {
        let Some(bus) = bus() else { return Vec::new() };
        players(&bus).iter().filter_map(|name| player(&bus, name)).collect()
    }

    pub fn press(id: &str, command: Command) {
        // The id comes from another device: only ever a media player on this bus.
        if !id.starts_with(PREFIX) || id == OWN {
            return;
        }
        let Some(bus) = bus() else { return };
        let Ok(proxy) = Proxy::new(&bus, id, PATH, PLAYER) else { return };
        let _: zbus::Result<()> = match command {
            Command::Play => proxy.call("Play", &()),
            Command::Pause => proxy.call("Pause", &()),
            Command::Toggle => proxy.call("PlayPause", &()),
            Command::Next => proxy.call("Next", &()),
            Command::Previous => proxy.call("Previous", &()),
            Command::Seek(ms) => {
                let Ok(meta) = proxy.get_property::<Metadata>("Metadata") else { return };
                let Some(track) = meta.get("mpris:trackid").and_then(|v| v.downcast_ref::<ObjectPath>().ok()) else { return };
                proxy.call("SetPosition", &(track, (ms as i64).saturating_mul(1000)))
            }
        };
    }
}

#[cfg(windows)]
mod imp {
    use windows::Media::Control::{
        GlobalSystemMediaTransportControlsSession as Session, GlobalSystemMediaTransportControlsSessionPlaybackStatus as Status,
    };

    use super::{Command, Player};
    use crate::pausemedia::imp::sessions;

    /// "Spotify.exe" and "SpotifyAB.SpotifyMusic_zpdnekdrzrea0!Spotify" both become "Spotify".
    pub(super) fn app_name(id: &str) -> String {
        let last = id.rsplit('!').next().unwrap_or(id);
        let last = last.strip_suffix(".exe").or_else(|| last.strip_suffix(".EXE")).unwrap_or(last);
        last.rsplit('.').next().unwrap_or(last).to_string()
    }

    fn id_of(session: &Session) -> Option<String> {
        session.SourceAppUserModelId().ok().map(|id| id.to_string())
    }

    /// Tandem's own session shows what the phone plays.
    fn is_own(id: &str) -> bool {
        id.to_lowercase().contains("tandem")
    }

    /// A span of 100 nanoseconds.
    fn ms(span: windows::Foundation::TimeSpan) -> u64 {
        u64::try_from(span.Duration / 10_000).unwrap_or(0)
    }

    fn player(session: &Session) -> Option<Player> {
        let id = id_of(session)?;
        if is_own(&id) {
            return None;
        }
        let media = session.TryGetMediaPropertiesAsync().ok()?.join().ok()?;
        let title = media.Title().ok()?.to_string();
        if title.trim().is_empty() {
            return None;
        }
        let info = session.GetPlaybackInfo().ok()?;
        let controls = info.Controls().ok();
        let can = |get: fn(&windows::Media::Control::GlobalSystemMediaTransportControlsSessionPlaybackControls) -> windows::core::Result<bool>| {
            controls.as_ref().and_then(|c| get(c).ok()).unwrap_or(false)
        };
        let timeline = session.GetTimelineProperties().ok();
        let duration = timeline.as_ref().and_then(|t| t.EndTime().ok()).map(ms).filter(|d| *d > 0);
        Some(Player {
            app: app_name(&id),
            id,
            title,
            artist: media.Artist().map(|a| a.to_string()).unwrap_or_default(),
            album: media.AlbumTitle().map(|a| a.to_string()).unwrap_or_default(),
            playing: info.PlaybackStatus().ok() == Some(Status::Playing),
            position_ms: timeline.as_ref().and_then(|t| t.Position().ok()).map(ms),
            duration_ms: duration,
            can_prev: can(|c| c.IsPreviousEnabled()),
            can_next: can(|c| c.IsNextEnabled()),
            can_seek: can(|c| c.IsPlaybackPositionEnabled()),
        })
    }

    pub fn list() -> Vec<Player> {
        sessions().iter().filter_map(player).collect()
    }

    pub fn press(id: &str, command: Command) {
        let Some(session) = sessions().into_iter().find(|s| id_of(s).is_some_and(|name| name == id && !is_own(&name))) else { return };
        let _ = match command {
            Command::Play => session.TryPlayAsync(),
            Command::Pause => session.TryPauseAsync(),
            Command::Toggle => session.TryTogglePlayPauseAsync(),
            Command::Next => session.TrySkipNextAsync(),
            Command::Previous => session.TrySkipPreviousAsync(),
            Command::Seek(ms) => session.TryChangePlaybackPositionAsync((ms as i64).saturating_mul(10_000)),
        }
        .and_then(|op| op.join());
    }
}

#[cfg(not(any(target_os = "linux", windows)))]
mod imp {
    use super::{Command, Player};

    pub fn list() -> Vec<Player> {
        Vec::new()
    }

    pub fn press(_: &str, _: Command) {}
}

#[cfg(all(test, windows))]
mod tests {
    #[test]
    fn the_name_of_an_app_comes_out_of_its_id() {
        assert_eq!(super::imp::app_name("Spotify.exe"), "Spotify");
        assert_eq!(super::imp::app_name("SpotifyAB.SpotifyMusic_zpdnekdrzrea0!Spotify"), "Spotify");
    }
}
