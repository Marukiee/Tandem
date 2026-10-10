//! The target bitrate of a session, moved by what the link shows.
//!
//! Down quickly when anything suggests a queue is building, up slowly and only when the link is really in use. A
//! pure state machine over explicit times, so it can be tested without a network.

use std::time::{Duration, Instant};

/// What happened in one interval.
#[derive(Clone, Copy, Debug, Default)]
pub struct RateSample {
    /// Frames the host dropped or cancelled.
    pub dropped: u32,
    /// Frames the viewer says never arrived.
    pub lost: u32,
    /// Frames the viewer's app could not take.
    pub app_dropped: u32,
    /// Frames that took far too long to be acknowledged.
    pub slow_acks: u32,
    /// What actually arrived, in bits per second.
    pub delivered_bps: u32,
}

impl RateSample {
    pub fn trouble(&self) -> bool {
        self.dropped > 0 || self.lost > 0 || self.app_dropped > 0 || self.slow_acks > 0
    }
}

const CUT: f64 = 0.8;
/// A cut is not repeated before the queue it was meant to drain had time to drain.
const SETTLE: Duration = Duration::from_millis(1500);
const HOLD_BASE: Duration = Duration::from_secs(3);
const QUIET_NEEDED: u32 = 2;
const RAISE: f64 = 0.15;
const MIN_RAISE: u32 = 50_000;
/// The link must carry at least this much of the target for what arrived to say what the link can do: a picture that hardly changes
/// delivers little whatever the link is, and a cut based on that would take the target to nothing, and keep it there.
const IN_USE: f64 = 0.6;
/// Where the target does not go below, as a part of the start: a picture that is held down to a tenth is a picture nobody can read.
const FLOOR_PART: u32 = 5;
/// Moves smaller than this are not worth telling the encoder about, except downwards.
const HINT_STEP: f64 = 0.05;
pub const FLOOR_BPS: u32 = 100_000;

#[derive(Debug)]
pub struct RateController {
    max: u32,
    min: u32,
    current: u32,
    last_hint: u32,
    quiet: u32,
    consecutive_cuts: u32,
    hold_until: Option<Instant>,
    last_cut: Option<Instant>,
}

impl RateController {
    /// `start` is also the ceiling: the host never goes above what it accepted with.
    pub fn new(start: u32) -> RateController {
        let max = start.max(FLOOR_BPS);
        RateController {
            max,
            min: (max / FLOOR_PART).max(FLOOR_BPS).min(max),
            current: max,
            last_hint: max,
            quiet: 0,
            consecutive_cuts: 0,
            hold_until: None,
            last_cut: None,
        }
    }

    pub fn target(&self) -> u32 {
        self.current
    }

    pub fn ceiling(&self) -> u32 {
        self.max
    }

    /// Takes one interval. Returns the bitrate to tell the app when it moved enough to matter.
    pub fn update(&mut self, now: Instant, sample: &RateSample) -> Option<u32> {
        if sample.trouble() {
            self.quiet = 0;
            let settled = self.last_cut.is_none_or(|t| now.duration_since(t) >= SETTLE);
            if settled {
                let mut next = (self.current as f64 * CUT) as u32;
                // What arrived only says what the link carries when the link was busy.
                if sample.delivered_bps as f64 >= self.current as f64 * IN_USE {
                    next = next.min((sample.delivered_bps as f64 * 0.9) as u32);
                }
                self.current = next.clamp(self.min, self.current.max(self.min));
                self.consecutive_cuts = (self.consecutive_cuts + 1).min(3);
                let hold = HOLD_BASE * (1 << (self.consecutive_cuts - 1));
                self.hold_until = Some(now + hold);
                self.last_cut = Some(now);
            }
        } else {
            self.quiet += 1;
            let held = self.hold_until.is_some_and(|t| now < t);
            let in_use = sample.delivered_bps as f64 >= self.current as f64 * IN_USE;
            // A busy link that stays quiet climbs at once; an idle picture says nothing about the link, so it climbs back more slowly, which
            // costs nothing until the picture moves again.
            let needed = if in_use { QUIET_NEEDED } else { QUIET_NEEDED * 3 };
            if !held && self.quiet >= needed && self.current < self.max {
                let step = ((self.current as f64 * RAISE) as u32).max(MIN_RAISE);
                self.current = self.current.saturating_add(step).min(self.max);
                self.quiet = 0;
                self.consecutive_cuts = 0;
            }
        }
        self.hint()
    }

    fn hint(&mut self) -> Option<u32> {
        let moved_down = self.current < self.last_hint;
        let moved_up = self.current as f64 >= self.last_hint as f64 * (1.0 + HINT_STEP);
        if moved_down || moved_up {
            self.last_hint = self.current;
            Some(self.current)
        } else {
            None
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const MBIT: u32 = 1_000_000;

    fn clean(bps: u32) -> RateSample {
        RateSample { delivered_bps: bps, ..Default::default() }
    }

    fn bad(bps: u32) -> RateSample {
        RateSample { dropped: 2, delivered_bps: bps, ..Default::default() }
    }

    #[test]
    fn it_starts_at_the_ceiling_and_stays_when_all_is_well() {
        let mut rate = RateController::new(8 * MBIT);
        let t = Instant::now();
        for second in 0..30 {
            assert_eq!(rate.update(t + Duration::from_secs(second), &clean(7 * MBIT)), None);
        }
        assert_eq!(rate.target(), 8 * MBIT);
    }

    #[test]
    fn trouble_cuts_quickly_and_to_what_arrived() {
        let mut rate = RateController::new(8 * MBIT);
        let t = Instant::now();
        // 80% of 8 is 6.4, but only 5 arrived, which is most of what was asked for, so the link was busy: 90% of that.
        let hint = rate.update(t, &bad(5 * MBIT)).unwrap();
        assert_eq!(hint, 4_500_000);
        assert_eq!(rate.target(), 4_500_000);
    }

    #[test]
    fn a_quiet_picture_does_not_pull_the_target_down_to_what_it_sent() {
        let mut rate = RateController::new(8 * MBIT);
        // A drop while almost nothing is being sent: the target goes down by the cut and not to the nothing that arrived.
        rate.update(Instant::now(), &bad(50_000));
        assert_eq!(rate.target(), 6_400_000);
    }

    #[test]
    fn a_cut_is_not_repeated_while_the_queue_drains() {
        let mut rate = RateController::new(8 * MBIT);
        let t = Instant::now();
        rate.update(t, &bad(0));
        let after_first = rate.target();
        assert_eq!(after_first, 6_400_000);
        assert_eq!(rate.update(t + Duration::from_millis(500), &bad(0)), None);
        assert_eq!(rate.target(), after_first);
        // Later it is a new problem.
        assert!(rate.update(t + Duration::from_secs(2), &bad(0)).is_some());
        assert!(rate.target() < after_first);
    }

    #[test]
    fn it_never_goes_below_a_fifth_of_the_start() {
        let mut rate = RateController::new(8 * MBIT);
        let t = Instant::now();
        for step in 0..60 {
            rate.update(t + Duration::from_secs(2 * step), &bad(1));
        }
        assert_eq!(rate.target(), 1_600_000);
    }

    #[test]
    fn it_comes_back_slowly_and_only_after_a_hold() {
        let mut rate = RateController::new(8 * MBIT);
        let t = Instant::now();
        rate.update(t, &bad(0));
        let cut = rate.target();
        // Quiet seconds inside the hold change nothing.
        for second in 1..3 {
            assert_eq!(rate.update(t + Duration::from_secs(second), &clean(cut)), None);
        }
        assert_eq!(rate.target(), cut);
        // The hold ends at three seconds. Two quiet intervals have gone by then, so the first step comes at once.
        let mut moved = Vec::new();
        for second in 3..40 {
            if let Some(hint) = rate.update(t + Duration::from_secs(second), &clean(rate.target())) {
                moved.push((second, hint));
            }
        }
        let (first_at, first) = moved[0];
        assert!(first_at >= 3, "raised after {first_at} seconds");
        assert!(first > cut && (first as f64) < cut as f64 * 1.20, "a step of about 15%, got {cut} to {first}");
        // Steps are spaced by at least two quiet intervals.
        for pair in moved.windows(2) {
            assert!(pair[1].0 - pair[0].0 >= 2);
        }
        assert!(rate.target() <= 8 * MBIT);
    }

    #[test]
    fn a_quiet_picture_climbs_back_slowly_and_not_at_all_inside_the_hold() {
        let mut rate = RateController::new(8 * MBIT);
        let t = Instant::now();
        rate.update(t, &bad(0));
        let cut = rate.target();
        let mut steps = 0;
        for second in 1..13 {
            // A still screen sends almost nothing, which says nothing about what the link can do: it climbs, but only every sixth quiet second.
            if rate.update(t + Duration::from_secs(second), &clean(100_000)).is_some() {
                steps += 1;
            }
        }
        assert!(rate.target() > cut, "it should have started to climb");
        assert!(steps <= 2, "{steps} steps in twelve quiet seconds");
        for second in 13..400 {
            rate.update(t + Duration::from_secs(second), &clean(100_000));
        }
        assert_eq!(rate.target(), 8 * MBIT, "the whole way back in the end");
    }

    #[test]
    fn repeated_trouble_makes_the_hold_longer() {
        let mut rate = RateController::new(8 * MBIT);
        let t = Instant::now();
        rate.update(t, &bad(0));
        rate.update(t + Duration::from_secs(2), &bad(0));
        let low = rate.target();
        // Two cuts in a row: six seconds of hold, counted from the last one, so until second eight.
        for second in 3..8 {
            rate.update(t + Duration::from_secs(second), &clean(low));
        }
        assert_eq!(rate.target(), low);
        let mut raised = false;
        for second in 8..20 {
            raised |= rate.update(t + Duration::from_secs(second), &clean(low)).is_some();
        }
        assert!(raised);
    }

    #[test]
    fn small_upward_moves_are_not_reported() {
        let mut rate = RateController::new(100 * MBIT);
        let t = Instant::now();
        rate.update(t, &bad(0));
        // 75 Mbit now. A raise of 8% is reported; the point is that the report follows the target.
        let mut last = rate.target();
        for second in 5..200 {
            if let Some(hint) = rate.update(t + Duration::from_secs(second), &clean(rate.target())) {
                assert!(hint as f64 >= last as f64 * 1.05 || hint < last);
                last = hint;
            }
        }
    }

    #[test]
    fn a_very_low_start_still_has_a_floor() {
        let mut rate = RateController::new(50_000);
        assert_eq!(rate.ceiling(), FLOOR_BPS);
        rate.update(Instant::now(), &bad(0));
        assert_eq!(rate.target(), FLOOR_BPS);
    }
}
