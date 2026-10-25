//! Deciding which players are worth showing.
//!
//! Each device tells the other what it can play, and every player of the other device that has something to say is shown, also one
//! that plays what a player here shows. A phone's Spotify was left out when the Mac's Spotify showed the same track, but Spotify on a
//! computer shows the track of the account wherever it plays, so the phone's music disappeared while it played on the phone (Mark,
//! 2026-10-25: he would rather see it). Both apps use this one function, so they agree.

use crate::proto::MediaPlayer;

/// The players of the other device that are worth showing next to the ones of this device.
pub fn visible(_local: &[MediaPlayer], remote: &[MediaPlayer]) -> Vec<MediaPlayer> {
    remote.iter().filter(|r| !r.title.trim().is_empty()).cloned().collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn player(app: &str, title: &str, artist: &str, duration: Option<u64>) -> MediaPlayer {
        MediaPlayer {
            id: app.to_string(),
            app: app.to_string(),
            title: title.to_string(),
            artist: artist.to_string(),
            album: String::new(),
            playing: true,
            position_ms: None,
            duration_ms: duration,
            can_prev: true,
            can_next: true,
            can_seek: false,
            art: 0,
        }
    }

    #[test]
    fn the_same_track_here_does_not_hide_the_phone() {
        let mac = player("Spotify", "Blinding Lights", "The Weeknd", Some(200_040));
        let phone = player("com.spotify.music", "Blinding Lights", "The Weeknd", Some(200_000));
        assert_eq!(visible(&[mac], &[phone.clone()]), vec![phone]);
    }

    #[test]
    fn nothing_to_show_is_left_out() {
        let blank = player("a", "  ", "", None);
        let song = player("b", "Song A", "Artist", None);
        assert_eq!(visible(&[], &[blank, song.clone()]), vec![song]);
    }
}
