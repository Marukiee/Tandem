//! The device that has the picture: takes encoded frames from its app and gets them to the viewer without ever making
//! the app wait and without letting a slow link build a queue.
//!
//! Every decision about a frame is made in `HostSession::push`, synchronously, on the thread of the app. What it
//! queues is written to the network by a task of its own, one stream per frame, and a watcher per frame cancels the
//! stream when it takes too long.

use std::collections::VecDeque;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use bytes::Bytes;
use tokio::sync::mpsc;
use tokio_util::sync::CancellationToken;
use tracing::debug;

use crate::engine::Inner;
use crate::ids::DeviceId;
use crate::live::rate::{RateController, RateSample};
use crate::live::wire::*;
use crate::live::{MediaPush, MediaStats, Phase};
use crate::proto::STREAM_MEDIA;

/// Frames in flight at once, however small. Streams are not free, and a viewer written before this had 32 of them.
pub const MAX_INFLIGHT: usize = 24;
/// A keyframe is asked for at most this often.
const KEY_ASK_GAP: Duration = Duration::from_millis(500);
/// A keyframe request from the viewer that comes this soon after a keyframe left is about that keyframe.
const KEY_ECHO_MIN: Duration = Duration::from_millis(250);
const OPEN_TIMEOUT: Duration = Duration::from_millis(250);
const SEND_QUEUE: usize = MAX_INFLIGHT + 8;

/// How long a frame is worth sending at all. Past that the stream is reset.
pub fn frame_ttl(keyframe: bool, rtt: Duration) -> Duration {
    if keyframe { (rtt * 8).max(Duration::from_secs(2)) } else { (rtt * 4).max(Duration::from_millis(400)) }
}

/// The oldest frame in flight may be this old before new frames are turned away.
pub fn stall_limit(rtt: Duration) -> Duration {
    (rtt * 3).max(Duration::from_millis(250))
}

/// A frame that takes longer than this to be acknowledged says the link is queueing.
pub fn slow_ack_limit(rtt: Duration) -> Duration {
    Duration::from_millis(250) + rtt * 2
}

/// How many bytes may be in flight: enough to keep the link busy for a round trip or so, not enough for a queue.
pub fn byte_budget(bps: u32, rtt: Duration) -> usize {
    let secs = (rtt.as_secs_f64() * 3.0).clamp(0.3, 1.0);
    (((bps as f64) / 8.0 * secs) as usize).max(384 * 1024)
}

pub struct InFlight {
    seq: u32,
    len: usize,
    since: Instant,
    cancel: CancellationToken,
}

/// A frame on its way to the writer.
pub struct OutFrame {
    head: Bytes,
    prefix: Option<Bytes>,
    data: Bytes,
    seq: u32,
    key: bool,
    admitted: Instant,
    cancel: CancellationToken,
}

#[derive(Default)]
pub struct Window {
    pub dropped: u32,
    pub slow_acks: u32,
    pub bytes: u64,
}

#[derive(Default)]
pub struct ReportAcc {
    pub lost: u32,
    pub app_dropped: u32,
    pub bytes: u64,
    pub ms: u64,
    pub jitter_us: u32,
}

pub struct HostState {
    pub phase: Phase,
    /// Set while the connection to the viewer is gone. The number tells one outage from the next.
    pub suspended: Option<u64>,
    pub accept: Option<MediaAccept>,
    pub control: bool,
    next_seq: u32,
    /// The stream has a hole: nothing but a keyframe may go out.
    broken: bool,
    discont: bool,
    key_asked: Option<Instant>,
    last_key_sent: Option<Instant>,
    config: Option<Bytes>,
    inflight: VecDeque<InFlight>,
    inflight_bytes: usize,
    tx: Option<mpsc::Sender<OutFrame>>,
    pub stats: MediaStats,
    rate: Option<RateController>,
    window: Window,
    reports: ReportAcc,
    pub last_report: Instant,
    /// For tests: the next frames are counted as sent but never leave, as if the network ate them.
    pub lose: u32,
    pub last_pointer: Option<u16>,
    last_ack_latency: Option<Duration>,
}

pub struct HostSession {
    pub id: u64,
    pub peer: DeviceId,
    pub kind: MediaKind,
    pub request: MediaRequest,
    pub peer_boot: u64,
    /// The policy allows the other device to control this one at all.
    pub control_allowed: bool,
    pub cancel: CancellationToken,
    pub st: Mutex<HostState>,
}

impl HostState {
    fn ask_keyframe(&mut self, now: Instant) -> bool {
        if self.key_asked.is_none_or(|t| now.duration_since(t) >= KEY_ASK_GAP) {
            self.key_asked = Some(now);
            true
        } else {
            false
        }
    }

    /// Something that was meant for the viewer did not leave: whatever follows would refer to it.
    fn break_stream(&mut self) {
        self.broken = true;
        self.discont = true;
    }

    fn release(&mut self, seq: u32) -> Option<InFlight> {
        let at = self.inflight.iter().position(|f| f.seq == seq)?;
        let flight = self.inflight.remove(at)?;
        self.inflight_bytes = self.inflight_bytes.saturating_sub(flight.len);
        Some(flight)
    }
}

impl HostSession {
    pub fn new(id: u64, peer: DeviceId, request: MediaRequest, peer_boot: u64, control_allowed: bool) -> Arc<HostSession> {
        Arc::new(HostSession {
            id,
            peer,
            kind: request.kind,
            request,
            peer_boot,
            control_allowed,
            cancel: CancellationToken::new(),
            st: Mutex::new(HostState {
                phase: Phase::Pending,
                suspended: None,
                accept: None,
                control: false,
                next_seq: 0,
                broken: true,
                discont: true,
                key_asked: None,
                last_key_sent: None,
                config: None,
                inflight: VecDeque::new(),
                inflight_bytes: 0,
                tx: None,
                stats: MediaStats::default(),
                rate: None,
                window: Window::default(),
                reports: ReportAcc::default(),
                last_report: Instant::now(),
                lose: 0,
                last_pointer: None,
                last_ack_latency: None,
            }),
        })
    }

    /// The app said yes. Starts the writer and the monitor.
    pub fn activate(self: &Arc<Self>, inner: &Arc<Inner>, accept: MediaAccept) {
        let (tx, rx) = mpsc::channel(SEND_QUEUE);
        {
            let mut st = self.st.lock().unwrap();
            st.control = accept.control;
            st.rate = Some(RateController::new(accept.bitrate));
            st.stats.target_bitrate = st.rate.as_ref().map(|r| r.target()).unwrap_or(0);
            st.accept = Some(accept);
            st.tx = Some(tx);
            st.last_report = Instant::now();
            st.phase = Phase::Active;
        }
        tokio::spawn(run_writer(inner.clone(), self.clone(), rx));
        tokio::spawn(run_monitor(inner.clone(), self.clone()));
    }

    /// Decides at once what happens to a frame. Returns the outcome and whether the app should be asked for a
    /// keyframe (the caller does that, outside the lock).
    pub fn push(&self, rtt: Duration, data: Bytes, pts_us: u64, keyframe: bool) -> (MediaPush, bool) {
        let now = Instant::now();
        let mut st = self.st.lock().unwrap();
        if st.phase != Phase::Active {
            return (MediaPush::NoSession, false);
        }
        if st.suspended.is_some() {
            st.stats.dropped_offline += 1;
            return (MediaPush::Dropped, false);
        }
        let codec = st.accept.as_ref().map(|a| a.codec).unwrap_or(MediaCodec::H264);
        if data.is_empty() || data.len() > MAX_FRAME_BYTES || !starts_with_start_code(&data) {
            st.stats.dropped_invalid += 1;
            return (MediaPush::Invalid, false);
        }
        // Every keyframe must be decodable on its own: put the parameter sets in front when the encoder left them out.
        let mut prefix = None;
        if keyframe {
            if has_parameter_sets(codec, &data) {
                st.config = Some(Bytes::from(parameter_sets(codec, &data)));
            } else if let Some(config) = st.config.clone() {
                prefix = Some(config);
            } else {
                st.stats.dropped_invalid += 1;
                return (MediaPush::Invalid, false);
            }
        }
        let seq = st.next_seq;
        st.next_seq = seq.wrapping_add(1);
        st.stats.frames_in += 1;

        if !keyframe && st.broken {
            st.stats.dropped_waiting += 1;
            let ask = st.ask_keyframe(now);
            return (MediaPush::WaitingForKeyframe, ask);
        }

        let total = data.len() + prefix.as_ref().map_or(0, |p| p.len());
        let target = st.rate.as_ref().map(|r| r.target()).unwrap_or(8_000_000);
        let jammed = match st.inflight.front() {
            None => false,
            Some(oldest) => {
                now.duration_since(oldest.since) > stall_limit(rtt)
                    || st.inflight_bytes + total > byte_budget(target, rtt)
                    || st.inflight.len() >= MAX_INFLIGHT
            }
        };
        // A keyframe the stream is waiting for goes out whatever the queue looks like: it makes everything in the
        // queue worthless, and it is taken out below.
        let rescue = keyframe && st.broken;
        if jammed && !rescue {
            st.stats.dropped_busy += 1;
            st.window.dropped += 1;
            st.break_stream();
            let ask = st.ask_keyframe(now);
            return (MediaPush::Dropped, ask);
        }

        let mut flags = 0;
        if keyframe {
            flags |= FLAG_KEYFRAME | FLAG_CONFIG;
        }
        if st.discont && keyframe {
            flags |= FLAG_DISCONTINUITY;
        }
        if keyframe {
            if st.broken {
                let old: Vec<InFlight> = st.inflight.drain(..).collect();
                st.stats.superseded += old.len() as u64;
                for flight in old {
                    flight.cancel.cancel();
                }
                st.inflight_bytes = 0;
            }
            st.broken = false;
            st.discont = false;
            st.last_key_sent = Some(now);
        }
        st.stats.frames_out += 1;
        st.stats.bytes += total as u64;
        st.window.bytes += total as u64;

        if st.lose > 0 {
            st.lose -= 1;
            return (MediaPush::Sent, false);
        }

        let header = FrameHeader { flags, session: self.id, seq, pts_us, len: total as u32 }.encode();
        let mut head = Vec::with_capacity(1 + FRAME_HEADER_LEN);
        head.push(STREAM_MEDIA);
        head.extend_from_slice(&header);
        let cancel = self.cancel.child_token();
        st.inflight.push_back(InFlight { seq, len: total, since: now, cancel: cancel.clone() });
        st.inflight_bytes += total;
        let frame = OutFrame { head: Bytes::from(head), prefix, data, seq, key: keyframe, admitted: now, cancel };
        let queued = st.tx.as_ref().map(|tx| tx.try_send(frame).is_ok()).unwrap_or(false);
        if queued {
            return (MediaPush::Sent, false);
        }
        st.release(seq);
        st.stats.dropped_busy += 1;
        st.window.dropped += 1;
        st.break_stream();
        let ask = st.ask_keyframe(now);
        (MediaPush::Dropped, ask)
    }

    /// The viewer wants a keyframe. True when the app should be asked.
    pub fn keyframe_requested(&self, rtt: Duration) -> bool {
        let now = Instant::now();
        let mut st = self.st.lock().unwrap();
        if st.phase != Phase::Active {
            return false;
        }
        // A request that was sent before the last keyframe arrived says nothing new.
        if st.last_key_sent.is_some_and(|t| now.duration_since(t) < KEY_ECHO_MIN.max(rtt * 2)) && !st.broken {
            return false;
        }
        st.stats.keyframes_requested += 1;
        st.break_stream();
        st.ask_keyframe(now)
    }

    /// The session is over. Returns where it was, or `None` when it had ended already.
    pub fn end(&self) -> Option<Phase> {
        let mut st = self.st.lock().unwrap();
        if st.phase == Phase::Ended {
            return None;
        }
        let was = st.phase;
        st.phase = Phase::Ended;
        st.tx = None;
        st.inflight.clear();
        st.inflight_bytes = 0;
        drop(st);
        // The streams still on their way are reset by their watchers.
        self.cancel.cancel();
        Some(was)
    }

    pub fn set_config(&self, config: Bytes) {
        self.st.lock().unwrap().config = Some(config);
    }

    pub fn note_report(&self, report: &MediaReport) {
        let mut st = self.st.lock().unwrap();
        st.last_report = Instant::now();
        st.reports.lost = st.reports.lost.saturating_add(report.lost);
        st.reports.app_dropped = st.reports.app_dropped.saturating_add(report.dropped);
        st.reports.bytes = st.reports.bytes.saturating_add(report.bytes);
        st.reports.ms = st.reports.ms.saturating_add(report.interval_ms as u64);
        st.reports.jitter_us = st.reports.jitter_us.max(report.jitter_us);
    }

    /// The connection is back, or was replaced: what was in flight on the old one is gone.
    pub fn connection_changed(&self) -> bool {
        let now = Instant::now();
        let mut st = self.st.lock().unwrap();
        st.suspended = None;
        st.last_report = now;
        if st.phase != Phase::Active {
            return false;
        }
        st.break_stream();
        st.ask_keyframe(now)
    }

    fn acked(&self, seq: u32, latency: Duration, rtt: Duration) {
        let mut st = self.st.lock().unwrap();
        if st.release(seq).is_some() {
            st.last_ack_latency = Some(latency);
            if latency > slow_ack_limit(rtt) {
                st.window.slow_acks += 1;
            }
        }
    }

    /// The frame was given up on or never got out. Returns whether the app should be asked for a keyframe.
    pub fn gave_up(&self, seq: u32) -> bool {
        let now = Instant::now();
        let mut st = self.st.lock().unwrap();
        if st.release(seq).is_none() {
            // Taken out earlier, because a keyframe made it worthless.
            return false;
        }
        st.stats.dropped_late += 1;
        st.window.dropped += 1;
        st.break_stream();
        st.ask_keyframe(now)
    }

    /// The viewer stopped the stream: it did not want the frame.
    fn declined(&self, seq: u32) {
        self.st.lock().unwrap().release(seq);
    }

    pub fn stats(&self, rtt: Option<Duration>) -> MediaStats {
        let st = self.st.lock().unwrap();
        let mut stats = st.stats.clone();
        stats.target_bitrate = st.rate.as_ref().map(|r| r.target()).unwrap_or(0);
        stats.inflight_frames = st.inflight.len() as u32;
        stats.inflight_bytes = st.inflight_bytes as u64;
        stats.jitter_us = st.reports.jitter_us;
        stats.rtt_ms = rtt.map(|r| r.as_millis() as u32);
        stats
    }
}

/// Writes the frames to the network, in order, one stream each.
async fn run_writer(inner: Arc<Inner>, host: Arc<HostSession>, mut rx: mpsc::Receiver<OutFrame>) {
    loop {
        let frame = tokio::select! {
            _ = host.cancel.cancelled() => return,
            frame = rx.recv() => match frame { Some(frame) => frame, None => return },
        };
        if frame.cancel.is_cancelled() {
            continue;
        }
        let Some(session) = inner.session_of(&host.peer) else {
            inner.live_gave_up(&host, frame.seq);
            continue;
        };
        let conn = session.conn.clone();
        let rtt = conn.rtt();
        let ttl = frame_ttl(frame.key, rtt);
        let deadline = frame.admitted + ttl;

        let mut stream = match tokio::time::timeout(OPEN_TIMEOUT, conn.open_uni()).await {
            Ok(Ok(stream)) => stream,
            _ => {
                debug!(session = host.id, "could not open a stream for a frame");
                inner.live_gave_up(&host, frame.seq);
                continue;
            }
        };
        // Above file transfers, below the control stream. A keyframe goes before the frames that follow it.
        let _ = stream.set_priority(if frame.key { 2 } else { 1 });

        let mut chunks: Vec<Bytes> = Vec::with_capacity(3);
        chunks.push(frame.head.clone());
        if let Some(prefix) = &frame.prefix {
            chunks.push(prefix.clone());
        }
        chunks.push(frame.data.clone());
        let write = tokio::time::timeout_at(deadline.into(), stream.write_all_chunks(&mut chunks)).await;
        match write {
            Ok(Ok(())) => {}
            Ok(Err(_)) => {
                inner.live_gave_up(&host, frame.seq);
                continue;
            }
            Err(_) => {
                let _ = stream.reset(RESET_LATE.into());
                inner.live_gave_up(&host, frame.seq);
                continue;
            }
        }
        let _ = stream.finish();

        let stopped = stream.stopped();
        let inner = inner.clone();
        let host = host.clone();
        let sent_at = Instant::now();
        tokio::spawn(async move {
            let seq = frame.seq;
            tokio::select! {
                result = stopped => match result {
                    Ok(None) => host.acked(seq, sent_at.elapsed(), rtt),
                    Ok(Some(_)) => host.declined(seq),
                    Err(_) => inner.live_gave_up(&host, seq),
                },
                _ = tokio::time::sleep_until(deadline.into()) => {
                    let _ = stream.reset(RESET_LATE.into());
                    inner.live_gave_up(&host, seq);
                }
                _ = frame.cancel.cancelled() => {
                    let _ = stream.reset(if host.cancel.is_cancelled() { RESET_ENDED } else { RESET_SUPERSEDED }.into());
                }
            }
        });
    }
}

/// Once a second: moves the target bitrate, asks again for a keyframe that has not come, and notices a viewer that
/// has gone quiet.
async fn run_monitor(inner: Arc<Inner>, host: Arc<HostSession>) {
    let mut tick = tokio::time::interval(Duration::from_secs(1));
    tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    tick.tick().await;
    let mut last = Instant::now();
    loop {
        tokio::select! {
            _ = host.cancel.cancelled() => return,
            _ = tick.tick() => {}
        }
        let now = Instant::now();
        let silent_for = inner.live.tuning.read().unwrap().report_timeout;
        let (hint, ask, silent) = {
            let mut st = host.st.lock().unwrap();
            let dt = now.duration_since(last).max(Duration::from_millis(100));
            last = now;
            if st.suspended.is_some() {
                // The window is not about the link while there is none.
                st.window = Window::default();
                st.reports = ReportAcc::default();
                (None, false, false)
            } else {
                let sent_bps = (st.window.bytes as f64 * 8.0 / dt.as_secs_f64()) as u32;
                let delivered = if st.reports.ms >= 500 {
                    (st.reports.bytes as f64 * 8000.0 / st.reports.ms as f64) as u32
                } else {
                    sent_bps
                };
                let sample = RateSample {
                    dropped: st.window.dropped,
                    lost: st.reports.lost,
                    app_dropped: st.reports.app_dropped,
                    slow_acks: st.window.slow_acks,
                    delivered_bps: delivered,
                };
                st.window = Window::default();
                st.reports = ReportAcc::default();
                let hint = st.rate.as_mut().and_then(|rate| rate.update(now, &sample));
                if let Some(rate) = &st.rate {
                    st.stats.target_bitrate = rate.target();
                }
                // A keyframe that was asked for and has not come: the app may have missed it.
                let ask = st.broken && st.key_asked.is_some() && st.ask_keyframe(now);
                let silent = now.duration_since(st.last_report) > silent_for;
                (hint, ask, silent)
            }
        };
        if let Some(bps) = hint {
            inner.live_bitrate_hint(&host, bps);
        }
        if ask {
            inner.live_ask_keyframe(&host);
        }
        if silent {
            debug!(session = host.id, "the viewer stopped reporting");
            inner.live_finish_host(&host, MediaEnd::PeerGone, true);
            return;
        }
    }
}
