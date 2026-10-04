//! The device that shows the picture: reads every frame stream to the end the moment it appears, puts the frames in
//! order, and hands them to the app from a task of its own so that a slow app costs frames and never the network.

use std::collections::VecDeque;
use std::sync::atomic::AtomicU16;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use quinn::RecvStream;
use tokio::sync::{Notify, watch};
use tokio_util::sync::CancellationToken;
use tracing::debug;

use crate::engine::Inner;
use crate::ids::DeviceId;
use crate::live::assembler::{Assembler, Frame, Step};
use crate::live::wire::*;
use crate::live::{MediaFrame, MediaStats, Phase};
use crate::proto::{Msg, STREAM_MEDIA};

/// Frames queued towards the app before it counts as too slow.
pub const DELIVERY_QUEUE: usize = 24;
/// A frame that has waited this long for the app is stale. The queue is thrown away and a keyframe is asked for: a few
/// frames fewer on screen is better than a picture that runs behind.
const DELIVERY_STALE: Duration = Duration::from_millis(400);
/// A keyframe finding more than this many frames waiting, or one this old, takes the place of all of them.
const BEHIND_FRAMES: usize = 3;
const BEHIND_AGE: Duration = Duration::from_millis(100);
/// A frame whose stream stays unfinished this long is given up on.
const READ_TIMEOUT: Duration = Duration::from_secs(5);
/// How long a frame that overtakes the answer to the request waits for it.
const ACCEPT_WAIT: Duration = Duration::from_secs(2);
const REPORT_EVERY: Duration = Duration::from_secs(1);

struct Queued {
    frame: Frame,
    discontinuity: bool,
    at: Instant,
}

#[derive(Default)]
struct Counters {
    frames: u32,
    bytes: u64,
    lost: u32,
    dropped: u32,
}

struct Jitter {
    arrival: Option<Instant>,
    pts: u64,
    value_us: f64,
}

pub struct ViewerState {
    pub phase: Phase,
    pub suspended: Option<u64>,
    pub accept: Option<MediaAccept>,
    pub control: bool,
    asm: Assembler,
    /// Frames in order, waiting for the app.
    queue: VecDeque<Queued>,
    pub stats: MediaStats,
    since: Counters,
    jitter: Jitter,
    last_frame: Option<Instant>,
    last_report: Instant,
    /// Set when the session ends, so the delivery task can say why as its last act.
    pub end_reason: Option<MediaEnd>,
}

pub struct ViewerSession {
    pub id: u64,
    pub peer: DeviceId,
    pub kind: MediaKind,
    pub request: MediaRequest,
    pub peer_boot: u64,
    pub cancel: CancellationToken,
    pub st: Mutex<ViewerState>,
    /// The driver should look at its timers again.
    pub wake: Notify,
    /// A frame is waiting for the app.
    ready: Notify,
    activated: watch::Sender<bool>,
    pub pointer_counter: AtomicU16,
}

impl ViewerSession {
    pub fn new(id: u64, peer: DeviceId, request: MediaRequest, peer_boot: u64) -> Arc<ViewerSession> {
        let now = Instant::now();
        Arc::new(ViewerSession {
            id,
            peer,
            kind: request.kind,
            request,
            peer_boot,
            cancel: CancellationToken::new(),
            st: Mutex::new(ViewerState {
                phase: Phase::Pending,
                suspended: None,
                accept: None,
                control: false,
                asm: Assembler::new(now),
                queue: VecDeque::new(),
                stats: MediaStats::default(),
                since: Counters::default(),
                jitter: Jitter { arrival: None, pts: 0, value_us: 0.0 },
                last_frame: None,
                last_report: now,
                end_reason: None,
            }),
            wake: Notify::new(),
            ready: Notify::new(),
            activated: watch::channel(false).0,
            pointer_counter: AtomicU16::new(0),
        })
    }

    /// The host said yes. Starts the tasks that hand frames to the app and keep the timers.
    pub fn activate(self: &Arc<Self>, inner: &Arc<Inner>, accept: MediaAccept) {
        {
            let mut st = self.st.lock().unwrap();
            st.control = accept.control;
            st.accept = Some(accept);
            st.phase = Phase::Active;
            st.last_report = Instant::now();
            st.asm = Assembler::new(Instant::now());
        }
        self.activated.send_replace(true);
        tokio::spawn(run_delivery(inner.clone(), self.clone()));
        tokio::spawn(run_driver(inner.clone(), self.clone()));
    }

    /// Passes on what the assembler decided. True when a keyframe should be asked for.
    fn apply(&self, st: &mut ViewerState, step: Step, now: Instant) -> bool {
        st.stats.lost += step.lost as u64;
        st.stats.discarded += step.discarded as u64;
        st.stats.late += step.late as u64;
        st.since.lost += step.lost;
        let mut broke = false;
        for (frame, discontinuity) in step.deliver {
            if broke {
                st.stats.app_dropped += 1;
                st.since.dropped += 1;
                continue;
            }
            let mut discontinuity = discontinuity;
            let behind = st.queue.len() > BEHIND_FRAMES || st.queue.front().is_some_and(|q| now.duration_since(q.at) > BEHIND_AGE);
            if frame.keyframe() && behind {
                // Everything before a keyframe is old news, and the app is behind with it. A frame or two in the queue
                // is the normal state of a busy decoder and is left alone.
                let old = st.queue.len() as u64;
                st.queue.clear();
                st.stats.app_dropped += old;
                st.since.dropped += old as u32;
                discontinuity = true;
            } else if st.queue.front().is_some_and(|q| now.duration_since(q.at) > DELIVERY_STALE) || st.queue.len() >= DELIVERY_QUEUE {
                // The app cannot keep up, and what follows would refer to what is dropped here.
                let old = st.queue.len() as u64 + 1;
                st.queue.clear();
                st.stats.app_dropped += old;
                st.since.dropped += old as u32;
                broke = true;
                continue;
            }
            st.queue.push_back(Queued { frame, discontinuity, at: now });
            st.stats.frames_out += 1;
        }
        if broke {
            let more = st.asm.app_overflow(now);
            st.stats.discarded += more.discarded as u64;
        }
        if !st.queue.is_empty() {
            self.ready.notify_one();
        }
        let ask = st.asm.key_due(now);
        if ask {
            st.asm.key_asked(now);
            st.stats.keyframes_requested += 1;
        }
        ask
    }

    /// The session is over. Returns where it was, or `None` when it had ended already.
    pub fn end(&self, reason: MediaEnd) -> Option<Phase> {
        let mut st = self.st.lock().unwrap();
        if st.phase == Phase::Ended {
            return None;
        }
        let was = st.phase;
        st.phase = Phase::Ended;
        st.end_reason = Some(reason);
        st.queue.clear();
        drop(st);
        self.cancel.cancel();
        Some(was)
    }

    pub fn connection_changed(&self) -> bool {
        let now = Instant::now();
        let mut st = self.st.lock().unwrap();
        st.suspended = None;
        st.last_report = now;
        if st.phase != Phase::Active {
            return false;
        }
        st.asm.reset_for_resume(now);
        let ask = st.asm.key_due(now);
        if ask {
            st.asm.key_asked(now);
            st.stats.keyframes_requested += 1;
        }
        drop(st);
        self.wake.notify_one();
        ask
    }

    pub fn stats(&self, rtt: Option<Duration>) -> MediaStats {
        let st = self.st.lock().unwrap();
        let mut stats = st.stats.clone();
        stats.jitter_us = st.jitter.value_us as u32;
        stats.rtt_ms = rtt.map(|r| r.as_millis() as u32);
        stats.last_frame_age_ms = st.last_frame.map(|t| t.elapsed().as_millis() as u64);
        stats.inflight_frames = st.asm.pending() as u32;
        stats
    }
}

fn gap_wait(inner: &Inner, peer: &DeviceId) -> Duration {
    let base = inner.live.tuning.read().unwrap().gap_wait;
    base + inner.session_of(peer).map(|s| s.conn.rtt()).unwrap_or_default()
}

impl Inner {
    /// One frame stream of the other device. Reads it to the end and gives the result to the assembler.
    pub(crate) async fn serve_media_stream(self: Arc<Self>, peer: DeviceId, mut recv: RecvStream) {
        let mut head = [0u8; 1 + FRAME_HEADER_LEN];
        match tokio::time::timeout(READ_TIMEOUT, recv.read_exact(&mut head)).await {
            Ok(Ok(())) => {}
            // Nothing was learnt from this stream, so there is nothing to lose track of.
            _ => return,
        }
        if head[0] != STREAM_MEDIA {
            let _ = recv.stop(STOP_BAD.into());
            return;
        }
        let header = match FrameHeader::decode(head[1..].try_into().unwrap()) {
            Ok(header) => header,
            Err(_) => {
                let _ = recv.stop(STOP_BAD.into());
                return;
            }
        };
        let Some(viewer) = self.live_viewer_session(header.session, &peer) else {
            let _ = recv.stop(STOP_UNKNOWN.into());
            self.live_tell_unknown(peer, header.session);
            return;
        };

        // The first frames can overtake the answer to the request, which travels on another stream.
        let mut active = viewer.activated.subscribe();
        if !*active.borrow() {
            let waited = tokio::time::timeout(ACCEPT_WAIT, active.wait_for(|up| *up)).await;
            if !matches!(waited, Ok(Ok(_))) {
                let _ = recv.stop(STOP_UNWANTED.into());
                return;
            }
        }

        let key = header.keyframe();
        {
            let mut st = viewer.st.lock().unwrap();
            if st.phase != Phase::Active || !st.asm.wants(header.seq, key) {
                st.stats.discarded += 1;
                drop(st);
                let _ = recv.stop(STOP_UNWANTED.into());
                return;
            }
            st.asm.begin(header.seq, key);
        }

        // The whole payload, in one allocation: the length was checked against the limit with the header.
        let want = header.len as usize;
        let mut data: Vec<u8> = Vec::with_capacity(want);
        let started = Instant::now();
        let finished = loop {
            let left = READ_TIMEOUT.saturating_sub(started.elapsed());
            match tokio::time::timeout(left, recv.read_chunk(usize::MAX, true)).await {
                Ok(Ok(Some(chunk))) => {
                    if data.len() + chunk.bytes.len() > want {
                        let _ = recv.stop(STOP_BAD.into());
                        break false;
                    }
                    data.extend_from_slice(&chunk.bytes);
                }
                Ok(Ok(None)) => break data.len() == want,
                _ => break false,
            }
        };

        let now = Instant::now();
        let (ask, wake) = {
            let mut st = viewer.st.lock().unwrap();
            if st.phase != Phase::Active {
                return;
            }
            let step = if finished {
                st.stats.frames_in += 1;
                st.stats.bytes += want as u64;
                st.since.frames += 1;
                st.since.bytes += want as u64;
                st.last_frame = Some(now);
                // The spread of arrival times against the spread of the picture times, as RFC 3550 does.
                if let Some(previous) = st.jitter.arrival {
                    let arrived = now.duration_since(previous).as_micros() as f64;
                    let shown = header.pts_us.wrapping_sub(st.jitter.pts) as i64 as f64;
                    let d = (arrived - shown).abs();
                    st.jitter.value_us += (d - st.jitter.value_us) / 16.0;
                }
                st.jitter.arrival = Some(now);
                st.jitter.pts = header.pts_us;
                st.asm.complete(now, Frame { seq: header.seq, flags: header.flags, pts_us: header.pts_us, data })
            } else {
                st.asm.abort(now, header.seq)
            };
            let ask = viewer.apply(&mut st, step, now);
            (ask, true)
        };
        if wake {
            viewer.wake.notify_one();
        }
        if ask {
            self.live_send(peer, Msg::MediaKeyframe { session: viewer.id });
        }
    }
}

/// Hands the frames to the app, one at a time and in order, and tells it how the session ended.
async fn run_delivery(inner: Arc<Inner>, viewer: Arc<ViewerSession>) {
    loop {
        let next = viewer.st.lock().unwrap().queue.pop_front();
        let Some(item) = next else {
            tokio::select! {
                _ = viewer.cancel.cancelled() => break,
                _ = viewer.ready.notified() => continue,
            }
        };
        if viewer.cancel.is_cancelled() {
            break;
        }
        let app = inner.live.viewer.read().unwrap().clone();
        if let Some(app) = app {
            let frame = MediaFrame {
                pts_us: item.frame.pts_us,
                keyframe: item.frame.keyframe(),
                discontinuity: item.discontinuity,
                data: item.frame.data,
            };
            crate::live::guarded("on_frame", || app.on_frame(viewer.id, frame));
        }
    }
    // The last thing the app hears of this session, so it never sees a frame after it.
    let reason = viewer.st.lock().unwrap().end_reason.unwrap_or(MediaEnd::Error);
    let app = inner.live.viewer.read().unwrap().clone();
    if let Some(app) = app {
        crate::live::guarded("on_ended", || app.on_ended(viewer.id, reason));
    }
}

/// The timers of a session: the wait for a missing frame, asking again for a keyframe, and the report every second.
async fn run_driver(inner: Arc<Inner>, viewer: Arc<ViewerSession>) {
    loop {
        let wait = gap_wait(&inner, &viewer.peer);
        let wake_at = {
            let st = viewer.st.lock().unwrap();
            let report = st.last_report + REPORT_EVERY;
            [st.asm.gap_deadline(wait), st.asm.key_deadline(), Some(report)].into_iter().flatten().min().unwrap_or(report)
        };
        tokio::select! {
            _ = viewer.cancel.cancelled() => return,
            _ = viewer.wake.notified() => {}
            _ = tokio::time::sleep_until(wake_at.into()) => {}
        }
        let now = Instant::now();
        let (ask, report) = {
            let mut st = viewer.st.lock().unwrap();
            if st.phase != Phase::Active {
                return;
            }
            let step = st.asm.tick(now, wait);
            let ask = viewer.apply(&mut st, step, now);
            let report = if st.suspended.is_none() && now.duration_since(st.last_report) >= REPORT_EVERY {
                let interval_ms = now.duration_since(st.last_report).as_millis() as u32;
                st.last_report = now;
                let counters = std::mem::take(&mut st.since);
                Some(MediaReport {
                    session: viewer.id,
                    interval_ms,
                    frames: counters.frames,
                    bytes: counters.bytes,
                    lost: counters.lost,
                    dropped: counters.dropped,
                    jitter_us: st.jitter.value_us as u32,
                })
            } else {
                None
            };
            (ask, report)
        };
        if ask {
            inner.live_send(viewer.peer, Msg::MediaKeyframe { session: viewer.id });
        }
        if let Some(report) = report {
            debug!(session = viewer.id, frames = report.frames, lost = report.lost, "report");
            inner.live_send(viewer.peer, Msg::MediaReport(report));
        }
    }
}
