//! When another device of the circle is nearly empty or full, so the device the person is at can say so. Only a change counts: a
//! battery that goes down past the low mark while not charging, or reaches full on the charger. Said once, and not again until the
//! battery has clearly left that state, so a level that wobbles around the mark does not say it over and over.

use crate::proto::Battery;

/// At or below this, not charging, the battery is nearly empty.
pub const LOW: u8 = 15;
/// It has to come back above this (or be charging) before nearly empty is said again.
const LOW_AGAIN: u8 = 20;
/// Full, on the charger.
pub const FULL: u8 = 100;
/// It has to drop below this before full is said again.
const FULL_AGAIN: u8 = 95;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum BatteryAlert {
    Low,
    Full,
}

/// The alert that the change from `before` to `after` gives, if any. `said` is what was said last for this device, and is kept up
/// to date here. Without a `before` (the first word since the device connected) nothing is said: the level may have been like that
/// for hours.
pub fn check(before: Option<&Battery>, after: &Battery, said: &mut Option<BatteryAlert>) -> Option<BatteryAlert> {
    match *said {
        Some(BatteryAlert::Low) if after.charging || after.level >= LOW_AGAIN => *said = None,
        Some(BatteryAlert::Full) if after.level < FULL_AGAIN => *said = None,
        _ => {}
    }
    let before = before?;
    if !after.charging && after.level <= LOW && (before.charging || before.level > LOW) && *said != Some(BatteryAlert::Low) {
        *said = Some(BatteryAlert::Low);
        return Some(BatteryAlert::Low);
    }
    if after.charging && after.level >= FULL && before.level < FULL && *said != Some(BatteryAlert::Full) {
        *said = Some(BatteryAlert::Full);
        return Some(BatteryAlert::Full);
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    fn battery(level: u8, charging: bool) -> Battery {
        Battery { level, charging, power_save: false }
    }

    /// Runs a series of levels through `check` and gives what was said at each step.
    fn run(steps: &[(u8, bool)]) -> Vec<Option<BatteryAlert>> {
        let mut said = None;
        let mut before: Option<Battery> = None;
        steps
            .iter()
            .map(|&(level, charging)| {
                let after = battery(level, charging);
                let alert = check(before.as_ref(), &after, &mut said);
                before = Some(after);
                alert
            })
            .collect()
    }

    #[test]
    fn going_down_past_the_mark_says_nearly_empty_once() {
        let said = run(&[(17, false), (16, false), (15, false), (14, false), (13, false)]);
        assert_eq!(said, vec![None, None, Some(BatteryAlert::Low), None, None]);
    }

    #[test]
    fn a_level_that_wobbles_around_the_mark_says_it_once() {
        let said = run(&[(16, false), (15, false), (16, false), (15, false), (16, false), (14, false)]);
        assert_eq!(said.iter().filter(|s| s.is_some()).count(), 1);
    }

    #[test]
    fn after_charging_it_is_said_again_the_next_time() {
        let said = run(&[(16, false), (15, false), (15, true), (40, true), (40, false), (16, false), (15, false)]);
        assert_eq!(said, vec![None, Some(BatteryAlert::Low), None, None, None, None, Some(BatteryAlert::Low)]);
    }

    #[test]
    fn full_on_the_charger_is_said_once_until_it_drops() {
        let said = run(&[(98, true), (99, true), (100, true), (100, true), (99, true), (100, true), (90, false), (99, true), (100, true)]);
        assert_eq!(said.iter().filter(|s| **s == Some(BatteryAlert::Full)).count(), 2);
        assert_eq!(said[2], Some(BatteryAlert::Full));
    }

    #[test]
    fn the_first_word_of_a_device_says_nothing() {
        assert_eq!(run(&[(5, false)]), vec![None]);
        assert_eq!(run(&[(100, true)]), vec![None]);
    }

    #[test]
    fn plugged_in_while_low_says_nothing() {
        assert_eq!(run(&[(16, true), (15, true), (14, true)]), vec![None, None, None]);
    }
}
