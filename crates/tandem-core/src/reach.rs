//! What is a real change of network and what only looks like one, and what a device is known to hold already.
//!
//! A phone says "the network changed" for a screen that went on, a signal that moved or a VPN that reconnected. Acting on each of those
//! (trying every device again, asking every connection whether it still works, starting the waits over) kept a radio awake all day on
//! mobile data (R-V15). So the engine looks at the addresses of this device itself and only a different set, that stays the same for a
//! moment, counts.

use std::collections::HashSet;
use std::net::IpAddr;
use std::time::{Duration, Instant, SystemTime};

use serde_json::{Value, json};

use crate::net::{Route, route_of};

/// The set of addresses of this device, and the one it may be changing to.
#[derive(Default)]
pub(crate) struct AddressWatch {
    known: Vec<IpAddr>,
    /// A different set seen once: it counts when the next look finds the same one.
    candidate: Option<Vec<IpAddr>>,
    looked: bool,
}

#[derive(Debug, PartialEq, Eq)]
pub(crate) enum Look {
    /// The first look: nothing to compare with.
    First,
    Same,
    /// Different from what was known, but not seen twice yet (an address that comes and goes).
    Waiting,
    Changed(Moved),
}

/// How many addresses came and went, by kind of route. Never the addresses themselves: they do not go in the log.
#[derive(Debug, Default, PartialEq, Eq)]
pub(crate) struct Moved {
    added: [u32; 3],
    removed: [u32; 3],
}

impl Moved {
    pub(crate) fn json(&self) -> Value {
        let by = |n: &[u32; 3]| json!({ "lan": n[0], "tailnet": n[1], "other": n[2] });
        json!({ "added": by(&self.added), "removed": by(&self.removed) })
    }
}

fn index(route: Route) -> usize {
    match route {
        Route::Lan => 0,
        Route::Tailnet => 1,
        Route::Other => 2,
    }
}

impl AddressWatch {
    /// `now` is sorted. `at_once`: take a different set as a change without waiting for a second look (the app says it changed, or it
    /// said so a moment ago and the addresses may be late).
    pub(crate) fn look(&mut self, now: Vec<IpAddr>, at_once: bool) -> Look {
        if !self.looked {
            self.looked = true;
            self.known = now;
            return Look::First;
        }
        if now == self.known {
            self.candidate = None;
            return Look::Same;
        }
        if !at_once && self.candidate.as_ref() != Some(&now) {
            self.candidate = Some(now);
            return Look::Waiting;
        }
        let mut moved = Moved::default();
        for ip in now.iter().filter(|ip| !self.known.contains(ip)) {
            moved.added[index(route_of(*ip))] += 1;
        }
        for ip in self.known.iter().filter(|ip| !now.contains(ip)) {
            moved.removed[index(route_of(*ip))] += 1;
        }
        self.known = now;
        self.candidate = None;
        Look::Changed(moved)
    }
}

/// Monotonic clocks stop while a computer sleeps and the wall clock does not: the difference is how long it slept.
pub(crate) struct Clocks {
    mono: Instant,
    wall: SystemTime,
}

/// A look that is this much further on the wall clock than on the monotonic one means the device slept in between.
const SLEPT_AFTER: Duration = Duration::from_secs(20);

impl Clocks {
    pub(crate) fn new() -> Self {
        Clocks { mono: Instant::now(), wall: SystemTime::now() }
    }

    /// Whether the device slept since the last call. A clock that was set back is not a sleep.
    pub(crate) fn slept(&mut self) -> bool {
        let (mono, wall) = (Instant::now(), SystemTime::now());
        let ran = mono.duration_since(self.mono);
        let passed = wall.duration_since(self.wall).unwrap_or_default();
        *self = Clocks { mono, wall };
        passed > ran + SLEPT_AFTER
    }
}

/// The covers (`Msg::MediaArt`) another device is known to have, so that a new connection to the same running app does not send them
/// again: a cover is some 12 KB and two or three go over at every reconnect.
///
/// A cover only counts once its connection lived a while after it was sent (a send on a connection that dies a moment later may never
/// have arrived), and only for the same run of the other app: its memory is gone when it started again (`Hello::boot_id`).
#[derive(Default)]
pub(crate) struct ArtHeld {
    boot: u64,
    held: HashSet<u64>,
    /// Sent on the connection that is up now, and when.
    pending: Vec<(u64, Instant)>,
}

/// How long a connection must have lived after a cover was sent on it for the cover to count as delivered.
const ART_CONFIRM_AFTER: Duration = Duration::from_secs(10);
/// Remembered covers per device: the others are sent again when they come back, which is what the other side's own cache costs too.
const ART_HELD_MAX: usize = 48;

impl ArtHeld {
    /// A connection to the device came up. A boot id of 0 is a device of an older version: nothing is assumed about it.
    pub(crate) fn connected(&mut self, boot: u64) {
        if boot == 0 || boot != self.boot {
            self.held.clear();
        }
        self.boot = boot;
        self.pending.clear();
    }

    pub(crate) fn holds(&self, key: u64) -> bool {
        self.boot != 0 && (self.held.contains(&key) || self.pending.iter().any(|(k, _)| *k == key))
    }

    pub(crate) fn sent(&mut self, key: u64, at: Instant) {
        if self.boot != 0 && !self.holds(key) {
            self.pending.push((key, at));
        }
    }

    /// The connection ended: what was sent early enough on it is held.
    pub(crate) fn disconnected(&mut self, now: Instant) {
        for (key, at) in std::mem::take(&mut self.pending) {
            if now.duration_since(at) >= ART_CONFIRM_AFTER {
                if self.held.len() >= ART_HELD_MAX {
                    self.held.clear();
                }
                self.held.insert(key);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ips(list: &[&str]) -> Vec<IpAddr> {
        let mut out: Vec<IpAddr> = list.iter().map(|s| s.parse().unwrap()).collect();
        out.sort();
        out
    }

    #[test]
    fn an_address_that_comes_and_goes_is_not_a_change() {
        let mut watch = AddressWatch::default();
        assert_eq!(watch.look(ips(&["192.168.1.5"]), false), Look::First);
        assert_eq!(watch.look(ips(&["192.168.1.5"]), false), Look::Same);
        // It flaps, tick after tick: never the same set twice in a row.
        assert_eq!(watch.look(ips(&["192.168.1.5", "10.0.0.2"]), false), Look::Waiting);
        assert_eq!(watch.look(ips(&["192.168.1.5"]), false), Look::Same);
        assert_eq!(watch.look(ips(&["192.168.1.5", "10.0.0.3"]), false), Look::Waiting);
        assert_eq!(watch.look(ips(&["192.168.1.5", "10.0.0.4"]), false), Look::Waiting);
        assert_eq!(watch.look(ips(&["192.168.1.5"]), false), Look::Same);
    }

    #[test]
    fn a_new_set_that_stays_is_a_change_counted_by_kind_of_route() {
        let mut watch = AddressWatch::default();
        watch.look(ips(&["192.168.1.5"]), false);
        assert_eq!(watch.look(ips(&["10.1.1.9", "100.64.0.7"]), false), Look::Waiting);
        let Look::Changed(moved) = watch.look(ips(&["10.1.1.9", "100.64.0.7"]), false) else { panic!("a set that stayed is a change") };
        assert_eq!(moved.json(), json!({ "added": { "lan": 1, "tailnet": 1, "other": 0 }, "removed": { "lan": 1, "tailnet": 0, "other": 0 } }));
        assert_eq!(watch.look(ips(&["10.1.1.9", "100.64.0.7"]), false), Look::Same);
    }

    #[test]
    fn the_app_saying_so_is_a_change_at_once() {
        let mut watch = AddressWatch::default();
        watch.look(ips(&["192.168.1.5"]), false);
        assert!(matches!(watch.look(ips(&["10.1.1.9"]), true), Look::Changed(_)));
        // The same set said again by the app is nothing.
        assert_eq!(watch.look(ips(&["10.1.1.9"]), true), Look::Same);
    }

    #[test]
    fn a_network_that_goes_away_and_comes_back_is_seen_even_if_the_first_look_found_none() {
        let mut watch = AddressWatch::default();
        assert_eq!(watch.look(Vec::new(), false), Look::First);
        assert_eq!(watch.look(ips(&["192.168.1.5"]), false), Look::Waiting);
        assert!(matches!(watch.look(ips(&["192.168.1.5"]), false), Look::Changed(_)));
    }

    #[test]
    fn a_cover_counts_as_delivered_only_after_its_connection_lived_on_and_for_the_same_run() {
        let t0 = Instant::now();
        let mut held = ArtHeld::default();
        held.connected(7);
        assert!(!held.holds(1));
        held.sent(1, t0);
        // Within the same connection it is held at once (it is not sent twice).
        assert!(held.holds(1));
        held.sent(2, t0 + Duration::from_secs(20));
        held.disconnected(t0 + Duration::from_secs(21));
        // The first lived 21 s after it was sent; the second only a second.
        held.connected(7);
        assert!(held.holds(1));
        assert!(!held.holds(2));
        // The app on the other side started again: it has forgotten everything.
        held.connected(8);
        assert!(!held.holds(1));
        // A device that says nothing about its run (an older version) is never assumed to hold anything.
        held.connected(0);
        held.sent(3, t0);
        assert!(!held.holds(3));
    }

    #[test]
    fn a_sleep_is_the_wall_clock_running_ahead_of_the_monotonic_one() {
        let mut clocks = Clocks::new();
        assert!(!clocks.slept());
        // The monotonic clock stood still for 40 s: the wall clock was ahead.
        clocks.wall -= Duration::from_secs(40);
        assert!(clocks.slept());
        assert!(!clocks.slept());
        // The clock was set back: not a sleep.
        clocks.wall += Duration::from_secs(3600);
        assert!(!clocks.slept());
    }
}
