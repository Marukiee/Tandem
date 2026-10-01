//! Deciding which players are worth showing.
//!
//! Each device tells the other what it can play. Showing everything would put two Spotify
//! players on the Mac when the phone's Spotify is only remote controlling the Mac's: it has the
//! same track, and it is the same music. So a player of the other device that is playing what a
//! local player already plays is left out. Both apps use this one function, so they agree.

use crate::proto::MediaPlayer;

/// The players of the other device that are worth showing next to the ones of this device.
pub fn visible(local: &[MediaPlayer], remote: &[MediaPlayer]) -> Vec<MediaPlayer> {
    remote
        .iter()
        .filter(|r| !r.title.trim().is_empty())
        .filter(|r| !local.iter().any(|l| same_music(l, r)))
        .cloned()
        .collect()
}

/// Two players are the same music when they have the same track: the same title, an artist that
/// fits, and, if both know it, a length within a few seconds. The app does not matter, because a
/// phone that remote controls a Mac says the same thing in a different app name.
pub fn same_music(a: &MediaPlayer, b: &MediaPlayer) -> bool {
    let (title_a, title_b) = (clean_title(&a.title), clean_title(&b.title));
    if title_a.is_empty() || title_a != title_b {
        return false;
    }
    let (artist_a, artist_b) = (clean(&a.artist), clean(&b.artist));
    // One side often lists every artist and the other only the first.
    let artists_fit = artist_a.is_empty()
        || artist_b.is_empty()
        || artist_a.contains(&artist_b)
        || artist_b.contains(&artist_a);
    if !artists_fit {
        return false;
    }
    match (a.duration_ms, b.duration_ms) {
        (Some(x), Some(y)) if x > 0 && y > 0 => x.abs_diff(y) <= 3_000,
        _ => true,
    }
}

/// Lowercase letters and digits with single spaces, so punctuation and spacing never decide.
fn clean(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut space = true;
    for c in text.chars().flat_map(char::to_lowercase) {
        if c.is_alphanumeric() {
            out.push(c);
            space = false;
        } else if !space {
            out.push(' ');
            space = true;
        }
    }
    out.trim_end().to_string()
}

/// A title without what apps add to it: "(Remastered 2011)", "[Live]", " - Radio Edit".
fn clean_title(title: &str) -> String {
    let mut plain = String::with_capacity(title.len());
    let mut depth = 0u32;
    for c in title.chars() {
        match c {
            '(' | '[' => depth += 1,
            ')' | ']' => depth = depth.saturating_sub(1),
            _ if depth == 0 => plain.push(c),
            _ => {}
        }
    }
    const EXTRA: [&str; 11] = ["remaster", "version", "edit", "live", "mix", "deluxe", "mono", "stereo", "bonus", "radio", "single"];
    let lowered = plain.to_lowercase();
    if let Some(at) = lowered.find(" - ") {
        let tail = &lowered[at + 3..];
        if EXTRA.iter().any(|word| tail.contains(word)) {
            plain.truncate(at);
        }
    }
    clean(&plain)
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
    fn the_same_track_in_another_app_is_one_player() {
        let mac = player("Spotify", "Blinding Lights", "The Weeknd", Some(200_040));
        let phone = player("com.spotify.music", "Blinding Lights", "The Weeknd", Some(200_000));
        assert!(same_music(&mac, &phone));
        assert!(visible(&[mac], &[phone]).is_empty());
    }

    #[test]
    fn case_spacing_and_extras_do_not_matter() {
        let a = player("a", "Wonderwall - Remastered", "Oasis", None);
        let b = player("b", "WONDERWALL (Remastered 2014)", "oasis", None);
        let c = player("c", "Wonderwall", "Oasis", None);
        assert!(same_music(&a, &b) && same_music(&b, &c) && same_music(&a, &c));
    }

    #[test]
    fn a_longer_artist_list_still_fits() {
        let a = player("a", "Se\u{f1}orita", "Shawn Mendes, Camila Cabello", Some(190_000));
        let b = player("b", "Se\u{f1}orita", "Shawn Mendes", Some(190_500));
        assert!(same_music(&a, &b));
    }

    #[test]
    fn a_different_track_is_a_different_player() {
        let mac = player("Spotify", "Blinding Lights", "The Weeknd", Some(200_000));
        let phone = player("Spotify", "Starboy", "The Weeknd", Some(230_000));
        assert!(!same_music(&mac, &phone));
        assert_eq!(visible(&[mac], &[phone.clone()]), vec![phone]);
    }

    #[test]
    fn the_same_title_by_someone_else_is_not_the_same() {
        let a = player("a", "Hurt", "Nine Inch Nails", Some(370_000));
        let b = player("b", "Hurt", "Johnny Cash", Some(218_000));
        assert!(!same_music(&a, &b));
    }

    #[test]
    fn a_length_that_is_far_off_is_another_recording() {
        let studio = player("a", "Hotel California", "Eagles", Some(391_000));
        let live = player("b", "Hotel California", "Eagles", Some(443_000));
        assert!(!same_music(&studio, &live));
    }

    #[test]
    fn an_unknown_artist_or_length_is_given_the_benefit_of_the_doubt() {
        let a = player("a", "Africa", "Toto", Some(295_000));
        let b = player("b", "Africa", "", None);
        assert!(same_music(&a, &b));
    }

    #[test]
    fn nothing_to_show_is_left_out() {
        let blank = player("a", "  ", "", None);
        assert!(visible(&[], &[blank]).is_empty());
        assert!(!same_music(&player("a", "", "x", None), &player("b", "", "x", None)));
    }

    #[test]
    fn players_that_differ_stay_next_to_each_other() {
        let mac = player("Spotify", "Song A", "Artist", Some(100_000));
        let phone_a = player("YouTube Music", "Song A", "Artist", Some(100_000));
        let phone_b = player("Podcasts", "Episode 12", "Host", Some(1_800_000));
        let shown = visible(&[mac], &[phone_a, phone_b.clone()]);
        assert_eq!(shown, vec![phone_b]);
    }
}
