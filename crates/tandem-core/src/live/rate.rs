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

const CUT: f64 = 0.75;
/// A cut is not repeated before the queue it was meant to drain had time to drain.
const SETTLE: Duration = Duration::from_millis(1500);
const HOLD_BASE: Duration = Duration::from_secs(4);
const QUIET_NEEDED: u32 = 3;
const RAISE: f64 = 0.08;
const MIN_RAISE: u32 = 50_000;
/// The link must carry at least this much of the target, otherwise it is the picture that is quiet, not the link.
const IN_USE: f64 = 0.6;
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
            min: (max / 10).max(FLOOR_BPS).min(max),
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
                if sample.delivered_bps > 0 {
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
            if !held && self.quiet >= QUIET_NEEDED && in_use && self.current < self.max {
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
        // 75% of 8 is 6, but only 4 arrived: 90% of that.
        let hint = rate.update(t, &bad(4 * MBIT)).unwrap();
        assert_eq!(hint, 3_600_000);
        assert_eq!(rate.target(), 3_600_000);
    }

    #[test]
    fn a_cut_is_not_repeated_while_the_queue_drains() {
        let mut rate = RateController::new(8 * MBIT);
        let t = Instant::now();
        rate.update(t, &bad(0));
        let after_first = rate.target();
        assert_eq!(after_first, 6 * MBIT);
        assert_eq!(rate.update(t + Duration::from_millis(500), &bad(0)), None);
        assert_eq!(rate.target(), after_first);
        // Later it is a new problem.
        assert!(rate.update(t + Duration::from_secs(2), &bad(0)).is_some());
        assert!(rate.target() < after_first);
    }

    #[test]
    fn it_never_goes_below_a_tenth_of_the_start() {
        let mut rate = RateController::new(8 * MBIT);
        let t = Instant::now();
        for step in 0..60 {
            rate.update(t + Duration::from_secs(2 * step), &bad(1));
        }
        assert_eq!(rate.target(), 800_000);
    }

    #[test]
    fn it_comes_back_slowly_and_only_after_a_hold() {
        let mut rate = RateController::new(8 * MBIT);
        let t = Instant::now();
        rate.update(t, &bad(0));
        let cut = rate.target();
        // Quiet seconds inside the hold change nothing.
        for second in 1..4 {
            assert_eq!(rate.update(t + Duration::from_secs(second), &clean(cut)), None);
        }
        assert_eq!(rate.target(), cut);
        // The hold ends at four seconds. Three quiet intervals have gone by then, so the first small step comes at once.
        let mut moved = Vec::new();
        for second in 4..40 {
            if let Some(hint) = rate.update(t + Duration::from_secs(second), &clean(rate.target())) {
                moved.push((second, hint));
            }
        }
        let (first_at, first) = moved[0];
        assert!(first_at >= 4, "raised after {first_at} seconds");
        assert!(first > cut && (first as f64) < cut as f64 * 1.12, "a step of about 8%, got {cut} to {first}");
        // Steps are spaced by at least three quiet intervals.
        for pair in moved.windows(2) {
            assert!(pair[1].0 - pair[0].0 >= 3);
        }
        assert!(rate.target() <= 8 * MBIT);
    }

    #[test]
    fn it_does_not_climb_while_the_picture_is_quiet() {
        let mut rate = RateController::new(8 * MBIT);
        let t = Instant::now();
        rate.update(t, &bad(0));
        let cut = rate.target();
        for second in 5..60 {
            // A still screen sends almost nothing, which says nothing about what the link can do.
            rate.update(t + Duration::from_secs(second), &clean(100_000));
        }
        assert_eq!(rate.target(), cut);
    }

    #[test]
    fn repeated_trouble_makes_the_hold_longer() {
        let mut rate = RateController::new(8 * MBIT);
        let t = Instant::now();
        rate.update(t, &bad(0));
        rate.update(t + Duration::from_secs(2), &bad(0));
        let low = rate.target();
        // Two cuts in a row: eight seconds of hold, counted from the last one, so until second ten.
        for second in 3..10 {
            rate.update(t + Duration::from_secs(second), &clean(low));
        }
        assert_eq!(rate.target(), low);
        let mut raised = false;
        for second in 10..20 {
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
