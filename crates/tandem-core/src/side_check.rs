//! When a computer checks by itself, with the quiet sweeps of `side.rs`, on which side of it a device stands. Each time it matters: an
//! extra screen starts, a device was moved, a device the shared mouse goes to connects again or the computer wakes, and the pointer goes
//! over to a device that was not checked for a while; and while an extra screen runs, every few minutes. Never on every crossing of the
//! pointer, and less and less often for a device that keeps not hearing it (its microphone is off, Tandem is in the background there,
//! the sound of the computer is down), so it is not asked again and again for nothing.
//!
//! Time is the wall clock in milliseconds, so a computer that slept for the night counts the night (a clock that only counts while it
//! is awake would say the last check was a moment ago). A clock that went back counts as a long time.

use std::collections::HashMap;

/// What happened that makes a check worth it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Reason {
    /// An extra screen for the device started.
    ScreenStarted,
    /// The device says it was picked up and put down again.
    Moved,
    /// The device connected (again).
    Connected,
    /// This computer woke from sleep.
    Woke,
    /// The pointer went over to the device.
    PointerOver,
    /// Now and then while its extra screen runs.
    InUse,
}

/// Two reasons that come together (a device that connects and gets its screen) give one check.
pub const TOGETHER_MS: u64 = 20_000;
/// After a connect or a wake: a device that drops off the network for a moment and comes back is not checked twice.
pub const AGAIN_MS: u64 = 2 * 60_000;
/// While it is in use: every couple of minutes. A check costs next to no power; what it does cost is the microphone light on the device
/// that listens, which the owner said does not bother him.
pub const IN_USE_MS: u64 = 2 * 60_000;
/// The longest wait after checks that did not hear anything.
pub const AT_MOST_MS: u64 = 40 * 60_000;

#[derive(Clone, Copy, Debug)]
struct Entry {
    at_ms: u64,
    /// Checks in a row that found no side.
    misses: u32,
}

#[derive(Default, Debug)]
pub struct Schedule {
    devices: HashMap<String, Entry>,
}

impl Schedule {
    /// Whether to check `device` now for `reason`; when it says yes, the check counts as started, so a second reason a moment later does
    /// not start another one.
    pub fn start(&mut self, device: &str, reason: Reason, now_ms: u64) -> bool {
        if !self.due(device, reason, now_ms) {
            return false;
        }
        let misses = self.devices.get(device).map_or(0, |e| e.misses);
        self.devices.insert(device.to_string(), Entry { at_ms: now_ms, misses });
        true
    }

    fn due(&self, device: &str, reason: Reason, now_ms: u64) -> bool {
        let Some(entry) = self.devices.get(device) else { return true };
        let Some(since) = now_ms.checked_sub(entry.at_ms) else { return true };
        let wait = match reason {
            // The person just did something with that device: it is worth a check even when the last ones heard nothing.
            Reason::ScreenStarted | Reason::Moved => TOGETHER_MS,
            Reason::Connected | Reason::Woke => AGAIN_MS.max(backoff(entry.misses)),
            Reason::PointerOver | Reason::InUse => IN_USE_MS.max(backoff(entry.misses)),
        };
        since >= wait
    }

    /// How the check that `start` let go ended: `found` when it gave a clear side.
    pub fn done(&mut self, device: &str, found: bool, now_ms: u64) {
        let entry = self.devices.entry(device.to_string()).or_insert(Entry { at_ms: now_ms, misses: 0 });
        entry.at_ms = entry.at_ms.max(now_ms);
        entry.misses = if found { 0 } else { entry.misses.saturating_add(1) };
    }
}

/// Twice as long after each miss, from the time while in use, up to `AT_MOST_MS`.
fn backoff(misses: u32) -> u64 {
    if misses == 0 {
        return 0;
    }
    IN_USE_MS.saturating_mul(1 << (misses - 1).min(8)).min(AT_MOST_MS)
}

#[cfg(test)]
mod tests {
    use super::*;

    const MIN: u64 = 60_000;
    const T0: u64 = 1_800_000_000_000;

    #[test]
    fn a_device_never_checked_is_checked_for_any_reason() {
        for reason in [Reason::ScreenStarted, Reason::Moved, Reason::Connected, Reason::Woke, Reason::PointerOver, Reason::InUse] {
            assert!(Schedule::default().start("phone", reason, T0), "{reason:?}");
        }
    }

    #[test]
    fn two_reasons_at_once_give_one_check() {
        let mut s = Schedule::default();
        assert!(s.start("phone", Reason::Connected, T0));
        assert!(!s.start("phone", Reason::ScreenStarted, T0 + 3_000));
        // Still running (no `done` yet) counts too.
        assert!(!s.start("phone", Reason::PointerOver, T0 + 5_000));
        // Another device is on its own.
        assert!(s.start("laptop", Reason::PointerOver, T0 + 5_000));
    }

    #[test]
    fn the_pointer_going_over_checks_only_after_a_while() {
        let mut s = Schedule::default();
        assert!(s.start("laptop", Reason::PointerOver, T0));
        s.done("laptop", true, T0 + 2_000);
        for at in [10_000, MIN, IN_USE_MS] {
            assert!(!s.start("laptop", Reason::PointerOver, T0 + at), "{at}");
        }
        assert!(s.start("laptop", Reason::PointerOver, T0 + 2_000 + IN_USE_MS));
    }

    #[test]
    fn an_extra_screen_that_starts_or_a_move_checks_again_soon() {
        let mut s = Schedule::default();
        assert!(s.start("phone", Reason::InUse, T0));
        s.done("phone", true, T0 + 1_500);
        assert!(!s.start("phone", Reason::ScreenStarted, T0 + 10_000));
        assert!(s.start("phone", Reason::ScreenStarted, T0 + 1_500 + TOGETHER_MS));
        s.done("phone", true, T0 + 30_000);
        assert!(s.start("phone", Reason::Moved, T0 + 30_000 + TOGETHER_MS));
    }

    #[test]
    fn a_connect_or_a_wake_checks_after_two_minutes() {
        let mut s = Schedule::default();
        assert!(s.start("laptop", Reason::Connected, T0));
        s.done("laptop", true, T0);
        assert!(!s.start("laptop", Reason::Connected, T0 + MIN));
        assert!(!s.start("laptop", Reason::Woke, T0 + MIN));
        assert!(s.start("laptop", Reason::Woke, T0 + AGAIN_MS));
    }

    #[test]
    fn a_night_asleep_counts_and_a_clock_that_went_back_counts_as_long_ago() {
        let mut s = Schedule::default();
        assert!(s.start("laptop", Reason::InUse, T0));
        s.done("laptop", true, T0);
        assert!(s.start("laptop", Reason::Woke, T0 + 8 * 60 * MIN));
        s.done("laptop", true, T0 + 8 * 60 * MIN);
        assert!(s.start("laptop", Reason::PointerOver, T0 - MIN));
    }

    #[test]
    fn a_device_that_keeps_not_hearing_is_asked_less_and_less_often() {
        let mut s = Schedule::default();
        let mut now = T0;
        let mut waits = Vec::new();
        assert!(s.start("phone", Reason::InUse, now));
        for _ in 0..7 {
            s.done("phone", false, now);
            let from = now;
            while !s.start("phone", Reason::InUse, now) {
                now += MIN;
            }
            waits.push((now - from) / MIN);
        }
        assert_eq!(waits, vec![2, 4, 8, 16, 32, 40, 40]);
        // A connect after misses waits as long as the pointer does.
        s.done("phone", false, now);
        assert!(!s.start("phone", Reason::Connected, now + AGAIN_MS));
        // But the person starting a screen is checked anyway, and a side found brings it back to normal.
        assert!(s.start("phone", Reason::ScreenStarted, now + TOGETHER_MS));
        s.done("phone", true, now + TOGETHER_MS);
        assert!(s.start("phone", Reason::InUse, now + TOGETHER_MS + IN_USE_MS));
    }
}
