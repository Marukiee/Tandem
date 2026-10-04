//! Puts the frames of one session in order and decides what a loss means.
//!
//! The frames come over streams of their own, so they finish in any order. Here they are held until the one before
//! them has come, handed on in sequence, and when one is lost for good everything after it is useless until the next
//! keyframe: a P frame whose reference is missing shows damage that stays on screen. A freeze is better than that.
//!
//! No clocks of its own and no network: the caller says what time it is, which keeps every rule testable.

use std::collections::HashSet;
use std::time::{Duration, Instant};

use crate::live::wire::{FLAG_KEYFRAME, seq_before};

/// Frames that wait for an earlier one before the wait is given up.
pub const MAX_PENDING: usize = 32;
const MAX_IN_PROGRESS: usize = 256;
/// How long to wait for a keyframe before asking again.
pub const KEY_RETRY: Duration = Duration::from_millis(500);
/// How long after the start without a keyframe until one is asked for.
pub const KEY_START_WAIT: Duration = Duration::from_secs(2);

#[derive(Debug)]
pub struct Frame {
    pub seq: u32,
    pub flags: u8,
    pub pts_us: u64,
    pub data: Vec<u8>,
}

impl Frame {
    pub fn keyframe(&self) -> bool {
        self.flags & FLAG_KEYFRAME != 0
    }
}

/// What one event led to.
#[derive(Debug, Default)]
pub struct Step {
    /// In the order to hand them on, with whether the decoder should start afresh at that frame.
    pub deliver: Vec<(Frame, bool)>,
    /// Frames that never arrived.
    pub lost: u32,
    /// Frames that did arrive but are no use now.
    pub discarded: u32,
    /// Frames that came after they were skipped.
    pub late: u32,
    /// The chain broke because of this event.
    pub broke: bool,
}

pub struct Assembler {
    /// The next sequence number to hand on. Everything before it is done with.
    next: u32,
    started: bool,
    /// The chain is broken: nothing but a keyframe is any use.
    waiting_key: bool,
    /// The next frame to go out starts afresh.
    discont: bool,
    /// Whole frames that wait for an earlier one.
    pending: Vec<Frame>,
    in_progress: HashSet<u32>,
    keys_in_progress: HashSet<u32>,
    gap_since: Option<Instant>,
    highest: u32,
    seen_any: bool,
    key_request_at: Option<Instant>,
}

impl Assembler {
    pub fn new(now: Instant) -> Assembler {
        Assembler {
            next: 0,
            started: false,
            waiting_key: true,
            discont: true,
            pending: Vec::new(),
            in_progress: HashSet::new(),
            keys_in_progress: HashSet::new(),
            gap_since: None,
            highest: 0,
            seen_any: false,
            key_request_at: Some(now + KEY_START_WAIT),
        }
    }

    pub fn waiting_for_keyframe(&self) -> bool {
        self.waiting_key
    }

    pub fn pending(&self) -> usize {
        self.pending.len()
    }

    fn see(&mut self, seq: u32) {
        if !self.seen_any || seq_before(self.highest, seq) {
            self.highest = seq;
            self.seen_any = true;
        }
    }

    fn is_old(&self, seq: u32) -> bool {
        self.started && seq_before(seq, self.next)
    }

    /// Asked when only the header of a frame is known. False means the frame is of no use and its stream can be
    /// stopped, which saves the host the bandwidth.
    pub fn wants(&self, seq: u32, keyframe: bool) -> bool {
        if self.is_old(seq) {
            return false;
        }
        !self.waiting_key || keyframe
    }

    /// A frame has started to arrive.
    pub fn begin(&mut self, seq: u32, keyframe: bool) {
        if self.in_progress.len() >= MAX_IN_PROGRESS {
            self.in_progress.clear();
            self.keys_in_progress.clear();
        }
        self.in_progress.insert(seq);
        if keyframe {
            self.keys_in_progress.insert(seq);
        }
        self.see(seq);
    }

    /// A frame has arrived whole.
    pub fn complete(&mut self, now: Instant, frame: Frame) -> Step {
        let mut step = Step::default();
        let seq = frame.seq;
        self.in_progress.remove(&seq);
        self.keys_in_progress.remove(&seq);
        self.see(seq);

        if self.is_old(seq) {
            step.late = 1;
            return step;
        }
        if self.waiting_key {
            if frame.keyframe() {
                self.accept_key(now, frame, &mut step);
            } else {
                step.discarded = 1;
            }
            return step;
        }
        if seq == self.next {
            self.emit(frame, false, &mut step);
            self.drain(&mut step);
        } else if frame.keyframe() {
            // A keyframe needs nothing before it, so it does not wait for what is missing.
            self.accept_key(now, frame, &mut step);
        } else if self.pending.len() >= MAX_PENDING || self.pending.iter().any(|p| p.seq == seq) {
            step.discarded = 1;
            self.break_chain(now, &mut step);
        } else {
            self.pending.push(frame);
            self.gap_since.get_or_insert(now);
        }
        if self.pending.is_empty() {
            self.gap_since = None;
        }
        step
    }

    /// A frame will not arrive: its stream was reset, or broke, or took too long.
    pub fn abort(&mut self, now: Instant, seq: u32) -> Step {
        let mut step = Step::default();
        self.in_progress.remove(&seq);
        let was_key = self.keys_in_progress.remove(&seq);
        if self.is_old(seq) {
            return step;
        }
        if self.waiting_key {
            if was_key {
                self.key_request_at = Some(now);
            }
            return step;
        }
        self.break_chain(now, &mut step);
        step
    }

    /// Time passed. Gives up on a frame that was waited for long enough.
    pub fn tick(&mut self, now: Instant, gap_wait: Duration) -> Step {
        let mut step = Step::default();
        if let Some(since) = self.gap_since {
            if now.duration_since(since) >= gap_wait && !self.in_progress.contains(&self.next) {
                self.break_chain(now, &mut step);
            }
        }
        step
    }

    /// When `tick` has something to do about a gap.
    pub fn gap_deadline(&self, gap_wait: Duration) -> Option<Instant> {
        let since = self.gap_since?;
        if self.in_progress.contains(&self.next) { None } else { Some(since + gap_wait) }
    }

    /// The app could not take a frame, so what follows it cannot be used. Counted as the app's loss, not the network's.
    pub fn app_overflow(&mut self, now: Instant) -> Step {
        let mut step = Step::default();
        if !self.waiting_key {
            self.break_chain(now, &mut step);
            step.lost = 0;
        }
        step
    }

    /// The connection was replaced or came back: what was on its way is gone.
    pub fn reset_for_resume(&mut self, now: Instant) {
        self.waiting_key = true;
        self.discont = true;
        self.pending.clear();
        self.in_progress.clear();
        self.keys_in_progress.clear();
        self.gap_since = None;
        self.key_request_at = Some(now);
    }

    /// Whether a keyframe should be asked for now.
    pub fn key_due(&self, now: Instant) -> bool {
        self.waiting_key && self.key_request_at.is_some_and(|t| now >= t)
    }

    pub fn key_deadline(&self) -> Option<Instant> {
        if self.waiting_key { self.key_request_at } else { None }
    }

    /// A request for a keyframe went out; the next one is not due for a while.
    pub fn key_asked(&mut self, now: Instant) {
        self.key_request_at = Some(now + KEY_RETRY);
    }

    fn emit(&mut self, frame: Frame, discontinuity: bool, step: &mut Step) {
        self.next = frame.seq.wrapping_add(1);
        step.deliver.push((frame, discontinuity));
    }

    fn accept_key(&mut self, now: Instant, frame: Frame, step: &mut Step) {
        let was_waiting = self.waiting_key;
        let skipped = if self.started { frame.seq.wrapping_sub(self.next) } else { 0 };
        let (older, newer): (Vec<Frame>, Vec<Frame>) =
            std::mem::take(&mut self.pending).into_iter().partition(|p| seq_before(p.seq, frame.seq));
        self.pending = newer;
        step.discarded += older.len() as u32;
        if !was_waiting {
            // The chain was whole, so the frames in between are lost except the ones that did arrive.
            step.lost += skipped.saturating_sub(older.len() as u32);
        }
        let discontinuity = self.discont || was_waiting || skipped > 0;
        self.discont = false;
        self.waiting_key = false;
        self.started = true;
        self.key_request_at = None;
        self.in_progress.retain(|s| !seq_before(*s, frame.seq));
        self.emit(frame, discontinuity, step);
        self.drain(step);
        if self.pending.is_empty() {
            self.gap_since = None;
        } else {
            self.gap_since = Some(now);
        }
    }

    /// Hands on what was waiting, as far as it follows without a hole.
    fn drain(&mut self, step: &mut Step) {
        while let Some(at) = self.pending.iter().position(|p| p.seq == self.next) {
            let frame = self.pending.swap_remove(at);
            self.emit(frame, false, step);
        }
    }

    fn break_chain(&mut self, now: Instant, step: &mut Step) {
        let span = if self.seen_any && !seq_before(self.highest, self.next) { self.highest.wrapping_sub(self.next).wrapping_add(1) } else { 1 };
        let missing = span.saturating_sub(self.pending.len() as u32).max(1);
        step.lost += missing;
        step.discarded += self.pending.len() as u32;
        step.broke = true;
        self.pending.clear();
        self.in_progress.clear();
        self.gap_since = None;
        self.waiting_key = true;
        self.discont = true;
        // A keyframe that is already on its way makes asking pointless for a moment.
        self.key_request_at = Some(if self.keys_in_progress.is_empty() { now } else { now + KEY_RETRY });
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn frame(seq: u32, key: bool) -> Frame {
        Frame { seq, flags: if key { FLAG_KEYFRAME } else { 0 }, pts_us: seq as u64 * 1000, data: vec![seq as u8; 4] }
    }

    fn seqs(step: &Step) -> Vec<u32> {
        step.deliver.iter().map(|(f, _)| f.seq).collect()
    }

    const WAIT: Duration = Duration::from_millis(150);

    #[test]
    fn frames_in_order_go_straight_through() {
        let t = Instant::now();
        let mut a = Assembler::new(t);
        let first = a.complete(t, frame(0, true));
        assert_eq!(seqs(&first), vec![0]);
        assert!(first.deliver[0].1, "the first frame starts afresh");
        for n in 1..10 {
            let step = a.complete(t, frame(n, false));
            assert_eq!(seqs(&step), vec![n]);
            assert!(!step.deliver[0].1);
        }
        assert!(!a.waiting_for_keyframe());
    }

    #[test]
    fn nothing_but_a_keyframe_starts_a_session() {
        let t = Instant::now();
        let mut a = Assembler::new(t);
        assert!(!a.wants(0, false));
        assert!(a.wants(0, true));
        let step = a.complete(t, frame(0, false));
        assert!(step.deliver.is_empty());
        assert_eq!(step.discarded, 1);
    }

    #[test]
    fn a_frame_that_finishes_early_waits_for_the_one_before() {
        let t = Instant::now();
        let mut a = Assembler::new(t);
        a.complete(t, frame(0, true));
        a.begin(1, false);
        a.begin(2, false);
        // 2 finishes first.
        let step = a.complete(t, frame(2, false));
        assert!(step.deliver.is_empty());
        assert_eq!(a.pending(), 1);
        // While 1 is on its way there is no deadline: it is not lost, it is coming.
        assert_eq!(a.gap_deadline(WAIT), None);
        let step = a.complete(t, frame(1, false));
        assert_eq!(seqs(&step), vec![1, 2]);
        assert_eq!(a.pending(), 0);
    }

    #[test]
    fn a_frame_that_never_starts_is_lost_after_the_wait() {
        let t = Instant::now();
        let mut a = Assembler::new(t);
        a.complete(t, frame(0, true));
        a.complete(t, frame(2, false));
        a.complete(t, frame(3, false));
        assert_eq!(a.gap_deadline(WAIT), Some(t + WAIT));
        assert!(!a.tick(t + Duration::from_millis(100), WAIT).broke);
        let step = a.tick(t + WAIT, WAIT);
        assert!(step.broke);
        assert_eq!(step.lost, 1);
        assert_eq!(step.discarded, 2);
        assert!(a.waiting_for_keyframe());
        // From now on P frames are of no use, and the keyframe is asked for.
        assert!(!a.wants(4, false));
        assert!(a.key_due(t + WAIT));
    }

    #[test]
    fn a_frame_the_host_reset_breaks_the_chain_at_once() {
        let t = Instant::now();
        let mut a = Assembler::new(t);
        a.complete(t, frame(0, true));
        a.begin(1, false);
        a.begin(2, false);
        a.complete(t, frame(2, false));
        let step = a.abort(t, 1);
        assert!(step.broke);
        assert!(a.waiting_for_keyframe());
        assert!(a.key_due(t));
    }

    #[test]
    fn a_keyframe_jumps_over_the_gap_and_marks_it() {
        let t = Instant::now();
        let mut a = Assembler::new(t);
        a.complete(t, frame(0, true));
        a.complete(t, frame(1, false));
        // 2 to 5 never come, 4 does but is older than the keyframe.
        a.complete(t, frame(4, false));
        let step = a.complete(t, frame(6, true));
        assert_eq!(seqs(&step), vec![6]);
        assert!(step.deliver[0].1, "frames were skipped, so the decoder starts afresh");
        assert_eq!(step.lost, 3);
        assert_eq!(step.discarded, 1);
        assert!(!a.waiting_for_keyframe());
        assert_eq!(seqs(&a.complete(t, frame(7, false))), vec![7]);
    }

    #[test]
    fn a_keyframe_heals_a_broken_chain() {
        let t = Instant::now();
        let mut a = Assembler::new(t);
        a.complete(t, frame(0, true));
        a.complete(t, frame(2, false));
        a.tick(t + WAIT, WAIT);
        assert!(a.waiting_for_keyframe());
        // Late frames of the old chain are not wanted, however they arrive.
        assert!(a.complete(t, frame(1, false)).deliver.is_empty());
        let step = a.complete(t, frame(9, true));
        assert_eq!(seqs(&step), vec![9]);
        assert!(step.deliver[0].1);
        assert_eq!(step.lost, 0, "the loss was counted when the chain broke");
        assert!(!a.waiting_for_keyframe());
    }

    #[test]
    fn frames_that_come_after_they_were_skipped_are_dropped() {
        let t = Instant::now();
        let mut a = Assembler::new(t);
        a.complete(t, frame(5, true));
        a.complete(t, frame(6, false));
        assert!(!a.wants(3, true));
        let step = a.complete(t, frame(5, false));
        assert_eq!(step.late, 1);
        assert!(step.deliver.is_empty());
    }

    #[test]
    fn it_works_around_the_end_of_the_numbers() {
        let t = Instant::now();
        let mut a = Assembler::new(t);
        a.complete(t, frame(u32::MAX - 1, true));
        a.complete(t, frame(0, false));
        let step = a.complete(t, frame(u32::MAX, false));
        assert_eq!(seqs(&step), vec![u32::MAX, 0]);
        assert_eq!(seqs(&a.complete(t, frame(1, false))), vec![1]);
    }

    #[test]
    fn too_many_waiting_frames_give_up_the_wait() {
        let t = Instant::now();
        let mut a = Assembler::new(t);
        a.complete(t, frame(0, true));
        let mut broke = false;
        for n in 2..(2 + MAX_PENDING as u32 + 2) {
            broke |= a.complete(t, frame(n, false)).broke;
        }
        assert!(broke);
        assert!(a.waiting_for_keyframe());
        assert_eq!(a.pending(), 0);
    }

    #[test]
    fn a_new_connection_wipes_what_was_on_its_way() {
        let t = Instant::now();
        let mut a = Assembler::new(t);
        a.complete(t, frame(0, true));
        a.complete(t, frame(2, false));
        a.reset_for_resume(t);
        assert!(a.waiting_for_keyframe());
        assert_eq!(a.pending(), 0);
        assert!(a.key_due(t));
        a.key_asked(t);
        assert!(!a.key_due(t + Duration::from_millis(100)));
        assert!(a.key_due(t + KEY_RETRY));
        let step = a.complete(t, frame(10, true));
        assert_eq!(seqs(&step), vec![10]);
        assert!(step.deliver[0].1);
    }

    #[test]
    fn the_first_keyframe_is_asked_for_only_when_it_is_late() {
        let t = Instant::now();
        let a = Assembler::new(t);
        assert!(!a.key_due(t + Duration::from_secs(1)));
        assert!(a.key_due(t + KEY_START_WAIT));
    }

    #[test]
    fn a_keyframe_on_its_way_holds_back_the_request() {
        let t = Instant::now();
        let mut a = Assembler::new(t);
        a.complete(t, frame(0, true));
        a.begin(5, true);
        a.begin(3, false);
        let step = a.abort(t, 3);
        assert!(step.broke);
        assert!(!a.key_due(t), "keyframe 5 is already coming");
        assert!(a.key_due(t + KEY_RETRY));
        // If it was the keyframe that failed, ask at once.
        let mut b = Assembler::new(t);
        b.complete(t, frame(0, true));
        b.reset_for_resume(t);
        b.begin(4, true);
        b.abort(t, 4);
        assert!(b.key_due(t));
    }
}
