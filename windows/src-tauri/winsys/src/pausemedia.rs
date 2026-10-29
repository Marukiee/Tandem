//! Pausing what plays on this computer, and playing it again: for a call on the phone. Linux asks every player on the session bus
//! (MPRIS, which browsers, Spotify and the music apps all are), Windows the media sessions it shows in its own volume flyout. Only
//! what is playing is paused, and the players paused are given back, so only those are started again.

/// Pauses every player that is playing now. The names of the ones paused, for `play`.
pub fn pause_playing() -> Vec<String> {
    imp::pause_playing()
}

/// Plays again those of `names` that are still paused: one the person started or closed meanwhile is left alone.
pub fn play(names: &[String]) {
    imp::play(names)
}

#[cfg(target_os = "linux")]
mod imp {
    use zbus::blocking::{Connection, Proxy};

    const PREFIX: &str = "org.mpris.MediaPlayer2.";
    /// Tandem's own player, which shows what the phone plays: the phone pauses that by itself in a call.
    const OWN: &str = "org.mpris.MediaPlayer2.tandem";
    const PATH: &str = "/org/mpris/MediaPlayer2";
    const PLAYER: &str = "org.mpris.MediaPlayer2.Player";

    fn players(bus: &Connection) -> Vec<String> {
        let Ok(dbus) = Proxy::new(bus, "org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus") else { return Vec::new() };
        let names: Vec<String> = dbus.call("ListNames", &()).unwrap_or_default();
        names.into_iter().filter(|n| n.starts_with(PREFIX) && n != OWN).collect()
    }

    fn status(bus: &Connection, name: &str) -> Option<String> {
        Proxy::new(bus, name, PATH, PLAYER).ok()?.get_property::<String>("PlaybackStatus").ok()
    }

    fn call(bus: &Connection, name: &str, method: &str) {
        if let Ok(player) = Proxy::new(bus, name, PATH, PLAYER) {
            let _: Result<(), _> = player.call(method, &());
        }
    }

    pub fn pause_playing() -> Vec<String> {
        let Ok(bus) = Connection::session() else { return Vec::new() };
        players(&bus)
            .into_iter()
            .filter(|name| status(&bus, name).as_deref() == Some("Playing"))
            .inspect(|name| call(&bus, name, "Pause"))
            .collect()
    }

    pub fn play(names: &[String]) {
        let Ok(bus) = Connection::session() else { return };
        for name in names {
            if status(&bus, name).as_deref() == Some("Paused") {
                call(&bus, name, "Play");
            }
        }
    }
}

#[cfg(windows)]
mod imp {
    use windows::Media::Control::{
        GlobalSystemMediaTransportControlsSessionManager as Manager, GlobalSystemMediaTransportControlsSessionPlaybackStatus as Status,
    };

    fn sessions() -> Vec<windows::Media::Control::GlobalSystemMediaTransportControlsSession> {
        let Ok(manager) = Manager::RequestAsync().and_then(|op| op.get()) else { return Vec::new() };
        manager.GetSessions().map(|list| list.into_iter().collect()).unwrap_or_default()
    }

    fn status(session: &windows::Media::Control::GlobalSystemMediaTransportControlsSession) -> Option<Status> {
        session.GetPlaybackInfo().ok()?.PlaybackStatus().ok()
    }

    pub fn pause_playing() -> Vec<String> {
        sessions()
            .into_iter()
            .filter(|s| status(s) == Some(Status::Playing))
            .filter_map(|s| {
                let name = s.SourceAppUserModelId().ok()?.to_string();
                s.TryPauseAsync().ok()?.get().ok()?;
                Some(name)
            })
            .collect()
    }

    pub fn play(names: &[String]) {
        for session in sessions() {
            let Some(name) = session.SourceAppUserModelId().ok().map(|n| n.to_string()) else { continue };
            if names.contains(&name) && status(&session) == Some(Status::Paused) {
                let _ = session.TryPlayAsync().and_then(|op| op.get());
            }
        }
    }
}

#[cfg(not(any(target_os = "linux", windows)))]
mod imp {
    pub fn pause_playing() -> Vec<String> {
        Vec::new()
    }

    pub fn play(_: &[String]) {}
}
