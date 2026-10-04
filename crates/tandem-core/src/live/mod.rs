//! Live video between two devices: a screen or a camera, with the control of a screen on top.
//!
//! One side is the host and has the picture. Its app encodes and pushes access units into the core
//! ([`Engine::media_push_frame`]), the core gets them to the other side, the viewer, one QUIC stream per frame, and
//! gives them in order to the viewer's app. The core never decodes, never encodes and never captures.
//!
//! What matters here is what happens when the network is not perfect: see docs/SCREEN.md for the rules, and
//! `host.rs`, `viewer.rs`, `assembler.rs` and `rate.rs` for where they live.

pub mod assembler;
mod host;
pub mod policy;
pub mod rate;
mod viewer;
pub mod wire;

use std::collections::HashMap;
use std::panic::AssertUnwindSafe;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, RwLock};
use std::time::{Duration, Instant};

use bytes::Bytes;

use crate::engine::{Engine, Inner, rand_u64};
use crate::error::{Error, Result};
use crate::events::Event;
use crate::ids::DeviceId;
use crate::proto::Msg;
use crate::store::Store;

pub use policy::{MediaPermission, MediaPolicy};
pub use wire::*;

use host::HostSession;
use viewer::ViewerSession;

/// Sessions one device holds at most, in both roles together.
pub const MAX_SESSIONS: usize = 8;

/// Where a session is in its life. `Ended` is only ever seen by tasks that were already running when it ended.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Phase {
    /// A request is out or in, and nobody has said yes yet.
    Pending,
    Active,
    Ended,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum MediaRole {
    Host,
    Viewer,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum MediaState {
    /// Viewer: the request is out.
    Requesting,
    /// Host: the app is being asked.
    Pending,
    Active,
    /// The connection is gone and may come back within the grace period.
    Suspended,
}

/// What the viewer asks for. Zeros mean no preference.
#[derive(Clone, Debug)]
pub struct MediaWant {
    pub kind: MediaKind,
    pub codecs: Vec<MediaCodec>,
    pub max_width: u32,
    pub max_height: u32,
    pub max_fps: u32,
    pub max_bitrate: u32,
    pub control: bool,
    pub facing: MediaFacing,
}

impl MediaWant {
    pub fn new(kind: MediaKind) -> MediaWant {
        MediaWant {
            kind,
            codecs: vec![MediaCodec::H264],
            max_width: 0,
            max_height: 0,
            max_fps: 0,
            max_bitrate: 0,
            control: false,
            facing: MediaFacing::Any,
        }
    }
}

/// What the host app answers with, once it has the consent and knows what its encoder can do.
#[derive(Clone, Debug)]
pub struct MediaAnswer {
    pub codec: MediaCodec,
    pub width: u32,
    pub height: u32,
    pub fps: u32,
    /// The starting target in bits per second, and the most the core will ever ask the encoder for.
    pub bitrate: u32,
    /// Whether the viewer may send input. Clamped by the request and by the policy.
    pub control: bool,
}

/// A frame as the viewer's app gets it.
#[derive(Debug)]
pub struct MediaFrame {
    pub pts_us: u64,
    pub keyframe: bool,
    /// The decoder should start afresh here: frames before it were dropped, or this is the first one.
    pub discontinuity: bool,
    /// One access unit in Annex B. A keyframe starts with its parameter sets.
    pub data: Vec<u8>,
}

/// What became of a frame the host app pushed.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum MediaPush {
    /// Queued for the network. Not a promise that it arrives.
    Sent,
    /// Dropped: the link is behind, or the viewer is away. The stream is marked broken and a keyframe is asked for.
    Dropped,
    /// Not a keyframe, and the stream has a hole: nothing is sent until a keyframe comes.
    WaitingForKeyframe,
    /// Refused: empty, too big, not Annex B, or a keyframe without any parameter sets.
    Invalid,
    /// No such session, or it has ended.
    NoSession,
}

/// Counters of one session, for a debug overlay and for tests. What does not apply to the role stays zero.
#[derive(Clone, Debug, Default)]
pub struct MediaStats {
    /// Host: frames the app pushed. Viewer: frames that arrived whole.
    pub frames_in: u64,
    /// Host: frames queued for the network. Viewer: frames handed to the app.
    pub frames_out: u64,
    /// Host: bytes queued. Viewer: bytes received.
    pub bytes: u64,
    pub dropped_busy: u64,
    pub dropped_late: u64,
    pub dropped_waiting: u64,
    pub dropped_invalid: u64,
    pub dropped_offline: u64,
    /// Frames cancelled because a keyframe made them worthless.
    pub superseded: u64,
    /// Viewer: frames that never arrived.
    pub lost: u64,
    /// Viewer: frames that arrived but were of no use.
    pub discarded: u64,
    /// Viewer: frames that came after they were skipped.
    pub late: u64,
    /// Viewer: frames the app was too slow for.
    pub app_dropped: u64,
    /// Viewer: requests sent. Host: requests that were passed on to the app.
    pub keyframes_requested: u64,
    /// Host: input that arrived without control being granted.
    pub input_refused: u64,
    pub target_bitrate: u32,
    pub inflight_frames: u32,
    pub inflight_bytes: u64,
    pub jitter_us: u32,
    pub last_frame_age_ms: Option<u64>,
    pub rtt_ms: Option<u32>,
}

#[derive(Clone, Debug)]
pub struct MediaSessionInfo {
    pub session: u64,
    pub peer: DeviceId,
    pub kind: MediaKind,
    pub role: MediaRole,
    pub state: MediaState,
    pub accept: Option<MediaAccept>,
    pub control: bool,
}

/// How long things wait. The defaults are what the apps get; tests shorten them.
#[derive(Clone, Copy, Debug)]
pub struct MediaTuning {
    /// How long a session lives on without a connection.
    pub grace: Duration,
    /// A viewer that sends no report for this long is gone.
    pub report_timeout: Duration,
    /// How long the host app has to answer a request.
    pub consent_timeout: Duration,
    /// How long the viewer waits for the answer.
    pub request_timeout: Duration,
    /// How long a frame that is missing is waited for, on top of the round trip, before it counts as lost.
    pub gap_wait: Duration,
}

impl Default for MediaTuning {
    fn default() -> Self {
        MediaTuning {
            grace: Duration::from_secs(10),
            report_timeout: Duration::from_secs(15),
            consent_timeout: Duration::from_secs(60),
            request_timeout: Duration::from_secs(75),
            gap_wait: Duration::from_millis(150),
        }
    }
}

/// The app that has the picture. Every method is called on a thread of the core and must only note what happened and
/// return. `on_stop` can overlap in time with another callback; after it, nothing more is wanted for the session.
pub trait MediaHost: Send + Sync {
    /// Another device asks for the screen or the camera. `pre_approved` means the person said "always" for this device
    /// and kind: do not ask again, but do start the capture and call `media_accept`.
    fn on_request(&self, from: DeviceId, request: MediaRequest, pre_approved: bool);
    /// The viewer needs a keyframe now: force one out of the encoder.
    fn on_keyframe(&self, session: u64);
    /// The link carries less (or more) than before: move the encoder to this many bits per second.
    fn on_bitrate(&self, session: u64, bits_per_second: u32);
    /// Input from the viewer. Only comes when control was granted.
    fn on_input(&self, session: u64, input: MediaInput);
    /// The session is over, for whatever reason, including a stop by this very app.
    fn on_stop(&self, session: u64, reason: MediaEnd);
}

/// The app that shows the picture. `on_frame` is called one frame at a time, in order, from a task of the session's own;
/// `on_ended` is always the last call for a session.
pub trait MediaViewer: Send + Sync {
    fn on_accepted(&self, session: u64, accept: MediaAccept);
    fn on_update(&self, session: u64, update: MediaUpdate);
    fn on_frame(&self, session: u64, frame: MediaFrame);
    fn on_ended(&self, session: u64, reason: MediaEnd);
}

/// An app that panics must not take the session with it.
pub(crate) fn guarded(what: &str, call: impl FnOnce()) {
    if std::panic::catch_unwind(AssertUnwindSafe(call)).is_err() {
        tracing::error!("the app panicked in {what}");
    }
}

#[derive(Clone)]
pub(crate) enum Entry {
    Host(Arc<HostSession>),
    Viewer(Arc<ViewerSession>),
}

impl Entry {
    fn id(&self) -> u64 {
        match self {
            Entry::Host(h) => h.id,
            Entry::Viewer(v) => v.id,
        }
    }

    fn peer(&self) -> DeviceId {
        match self {
            Entry::Host(h) => h.peer,
            Entry::Viewer(v) => v.peer,
        }
    }

    fn kind(&self) -> MediaKind {
        match self {
            Entry::Host(h) => h.kind,
            Entry::Viewer(v) => v.kind,
        }
    }

    fn role(&self) -> MediaRole {
        match self {
            Entry::Host(_) => MediaRole::Host,
            Entry::Viewer(_) => MediaRole::Viewer,
        }
    }

    fn cancel(&self) -> tokio_util::sync::CancellationToken {
        match self {
            Entry::Host(h) => h.cancel.clone(),
            Entry::Viewer(v) => v.cancel.clone(),
        }
    }

    fn peer_boot(&self) -> u64 {
        match self {
            Entry::Host(h) => h.peer_boot,
            Entry::Viewer(v) => v.peer_boot,
        }
    }

    fn suspended(&self) -> Option<u64> {
        match self {
            Entry::Host(h) => h.st.lock().unwrap().suspended,
            Entry::Viewer(v) => v.st.lock().unwrap().suspended,
        }
    }

    fn set_suspended(&self, epoch: Option<u64>) {
        match self {
            Entry::Host(h) => h.st.lock().unwrap().suspended = epoch,
            Entry::Viewer(v) => v.st.lock().unwrap().suspended = epoch,
        }
    }

    fn info(&self) -> MediaSessionInfo {
        match self {
            Entry::Host(h) => {
                let st = h.st.lock().unwrap();
                let state = match (st.phase, st.suspended) {
                    (Phase::Pending, _) => MediaState::Pending,
                    (_, Some(_)) => MediaState::Suspended,
                    _ => MediaState::Active,
                };
                MediaSessionInfo { session: h.id, peer: h.peer, kind: h.kind, role: MediaRole::Host, state, accept: st.accept.clone(), control: st.control }
            }
            Entry::Viewer(v) => {
                let st = v.st.lock().unwrap();
                let state = match (st.phase, st.suspended) {
                    (Phase::Pending, _) => MediaState::Requesting,
                    (_, Some(_)) => MediaState::Suspended,
                    _ => MediaState::Active,
                };
                MediaSessionInfo { session: v.id, peer: v.peer, kind: v.kind, role: MediaRole::Viewer, state, accept: st.accept.clone(), control: st.control }
            }
        }
    }
}

/// Everything live video keeps in the engine.
pub(crate) struct Live {
    sessions: Mutex<HashMap<u64, Entry>>,
    pub(crate) host: RwLock<Option<Arc<dyn MediaHost>>>,
    pub(crate) viewer: RwLock<Option<Arc<dyn MediaViewer>>>,
    pub(crate) service: policy::MediaService,
    pub(crate) tuning: RwLock<MediaTuning>,
    told_unknown: Mutex<HashMap<(DeviceId, u64), Instant>>,
    epoch: AtomicU64,
    /// For the calls from threads of the app, which are not inside the runtime.
    handle: tokio::runtime::Handle,
}

impl Live {
    pub(crate) fn new(store: Store) -> Live {
        Live {
            sessions: Mutex::new(HashMap::new()),
            host: RwLock::new(None),
            viewer: RwLock::new(None),
            service: policy::MediaService::new(store),
            tuning: RwLock::new(MediaTuning::default()),
            told_unknown: Mutex::new(HashMap::new()),
            epoch: AtomicU64::new(1),
            handle: tokio::runtime::Handle::current(),
        }
    }
}

// ---- What the app calls -----------------------------------------------------------------

impl Engine {
    /// Where requests for this device's screen or camera, keyframe requests, bitrate hints and input go.
    pub fn set_media_host(&self, host: Arc<dyn MediaHost>) {
        *self.inner.live.host.write().unwrap() = Some(host);
    }

    /// Where the frames of sessions this device asked for go.
    pub fn set_media_viewer(&self, viewer: Arc<dyn MediaViewer>) {
        *self.inner.live.viewer.write().unwrap() = Some(viewer);
    }

    /// Asks `peer` for its screen or camera. Returns the session id at once; the answer arrives through
    /// `MediaViewer::on_accepted` or `on_ended`.
    pub fn media_request(&self, peer: DeviceId, want: MediaWant) -> Result<u64> {
        let _rt = self.inner.live.handle.enter();
        self.inner.live_request(peer, want)
    }

    /// Tells `peer` that this device is ready to show its screen or camera and would like it to look. The peer's app
    /// decides and answers with a `media_request`; nothing starts by itself. Does nothing when `peer` is not connected.
    pub fn media_offer(&self, peer: DeviceId, kind: MediaKind, facing: MediaFacing) -> Result<()> {
        if kind == MediaKind::Other {
            return Err(Error::invalid("that kind of video does not exist"));
        }
        if !self.inner.peers.lock().unwrap().contains_key(&peer) {
            return Err(Error::NotTrusted);
        }
        if self.inner.session_of(&peer).is_none() {
            return Err(Error::NotConnected);
        }
        self.inner.live_send(peer, Msg::MediaOffer { kind, facing });
        Ok(())
    }

    /// The host app agrees. Frames may be pushed from now on; the first must be a keyframe.
    pub fn media_accept(&self, session: u64, answer: MediaAnswer) -> Result<()> {
        let _rt = self.inner.live.handle.enter();
        self.inner.live_accept(session, answer)
    }

    pub fn media_deny(&self, session: u64, reason: MediaEnd) -> Result<()> {
        let _rt = self.inner.live.handle.enter();
        let host = self.inner.live_host_session_any(session)?;
        if host.st.lock().unwrap().phase != Phase::Pending {
            return Err(Error::invalid("that session is not waiting for an answer"));
        }
        self.inner.live_send(host.peer, Msg::MediaDeny { session, reason, message: String::new() });
        self.inner.live_finish_host(&host, reason, false);
        Ok(())
    }

    /// Ends a session from either side. The other side is told.
    pub fn media_stop(&self, session: u64) -> Result<()> {
        let _rt = self.inner.live.handle.enter();
        let entry = self.inner.live.sessions.lock().unwrap().get(&session).cloned().ok_or_else(|| Error::invalid("no such session"))?;
        self.inner.live_finish(entry, MediaEnd::Ended, true);
        Ok(())
    }

    /// The host app has an encoded frame. Never waits for the network; see [`MediaPush`] for what became of it.
    pub fn media_push_frame(&self, session: u64, data: impl Into<Bytes>, pts_us: u64, keyframe: bool) -> MediaPush {
        let _rt = self.inner.live.handle.enter();
        let Some(host) = self.inner.live_host_session_any(session).ok() else { return MediaPush::NoSession };
        let rtt = self.inner.session_of(&host.peer).map(|s| s.conn.rtt()).unwrap_or(Duration::from_millis(50));
        let (outcome, ask) = host.push(rtt, data.into(), pts_us, keyframe);
        if ask {
            self.inner.live_ask_keyframe(&host);
        }
        outcome
    }

    /// Parameter sets (SPS and PPS, and VPS for HEVC) the encoder gave apart from its frames. Kept, and put in front of
    /// every keyframe that comes without them.
    pub fn media_push_config(&self, session: u64, data: impl Into<Bytes>) -> Result<()> {
        let host = self.inner.live_host_session_any(session)?;
        let data: Bytes = data.into();
        let codec = host.st.lock().unwrap().accept.as_ref().map(|a| a.codec).unwrap_or(MediaCodec::H264);
        if !starts_with_start_code(&data) || !has_parameter_sets(codec, &data) {
            return Err(Error::invalid("that is not a set of parameter sets in Annex B"));
        }
        host.set_config(Bytes::from(parameter_sets(codec, &data)));
        Ok(())
    }

    /// The picture changed shape, or control is granted or taken away. Tell the viewer before the first frame that is
    /// different; that frame must be a keyframe.
    pub fn media_update(&self, session: u64, update: MediaUpdate) -> Result<()> {
        let _rt = self.inner.live.handle.enter();
        let host = self.inner.live_host_session_any(session)?;
        let mut update = update;
        if let Some(grant) = update.control {
            let granted = grant && host.control_allowed && host.request.control && host.kind == MediaKind::Screen;
            host.st.lock().unwrap().control = granted;
            update.control = Some(granted);
        }
        if host.st.lock().unwrap().phase != Phase::Active {
            return Err(Error::invalid("that session is not running"));
        }
        self.inner.live_send(host.peer, Msg::MediaUpdate { session, update });
        Ok(())
    }

    /// The viewer sends input to the host. Fails when control was not granted.
    pub fn media_send_input(&self, session: u64, input: MediaInput) -> Result<()> {
        let _rt = self.inner.live.handle.enter();
        let viewer = self.inner.live_viewer_session_any(session)?;
        let (active, control, suspended) = {
            let st = viewer.st.lock().unwrap();
            (st.phase == Phase::Active, st.control, st.suspended.is_some())
        };
        if !active {
            return Err(Error::invalid("that session is not running"));
        }
        if !control {
            return Err(Error::invalid("control was not granted"));
        }
        if suspended {
            return Err(Error::NotConnected);
        }
        let Some(input) = input.sanitized() else { return Ok(()) };
        // A position that is late is worth nothing, so it is not sent over a stream that would deliver it late.
        if let MediaInput::PointerAbs { x, y } = &input {
            if let Some(conn) = self.inner.session_of(&viewer.peer) {
                let counter = viewer.pointer_counter.fetch_add(1, Ordering::Relaxed);
                if conn.conn.send_datagram(pointer_datagram(session, counter, *x, *y)).is_ok() {
                    return Ok(());
                }
            }
        }
        self.inner.live_send(viewer.peer, Msg::MediaInput { session, input });
        Ok(())
    }

    /// The viewer's decoder choked: ask the host for a keyframe.
    pub fn media_request_keyframe(&self, session: u64) -> Result<()> {
        let _rt = self.inner.live.handle.enter();
        let viewer = self.inner.live_viewer_session_any(session)?;
        {
            let mut st = viewer.st.lock().unwrap();
            if st.phase != Phase::Active {
                return Err(Error::invalid("that session is not running"));
            }
            st.stats.keyframes_requested += 1;
        }
        self.inner.live_send(viewer.peer, Msg::MediaKeyframe { session });
        Ok(())
    }

    pub fn media_sessions(&self) -> Vec<MediaSessionInfo> {
        let entries: Vec<Entry> = self.inner.live.sessions.lock().unwrap().values().cloned().collect();
        let mut out: Vec<MediaSessionInfo> = entries.iter().map(Entry::info).collect();
        out.sort_by_key(|i| i.session);
        out
    }

    pub fn media_stats(&self, session: u64) -> Option<MediaStats> {
        let entry = self.inner.live.sessions.lock().unwrap().get(&session).cloned()?;
        let rtt = self.inner.session_of(&entry.peer()).map(|s| s.conn.rtt());
        Some(match entry {
            Entry::Host(h) => h.stats(rtt),
            Entry::Viewer(v) => v.stats(rtt),
        })
    }

    // What a person decided about other devices looking at this one.

    pub fn media_policy(&self, id: &DeviceId) -> MediaPolicy {
        self.inner.live.service.policy(id)
    }

    pub fn has_own_media_policy(&self, id: &DeviceId) -> bool {
        self.inner.live.service.has_own_policy(id)
    }

    pub fn media_default_policy(&self) -> MediaPolicy {
        self.inner.live.service.default_policy()
    }

    pub fn set_media_policy(&self, id: &DeviceId, policy: MediaPolicy) -> Result<()> {
        self.inner.live.service.set_policy(id, policy)
    }

    pub fn clear_media_policy(&self, id: &DeviceId) -> Result<()> {
        self.inner.live.service.clear_policy(id)
    }

    pub fn set_media_default_policy(&self, policy: MediaPolicy) -> Result<()> {
        self.inner.live.service.set_default_policy(policy)
    }

    // For tests and for diagnosing a link.

    #[doc(hidden)]
    pub fn set_media_tuning(&self, tuning: MediaTuning) {
        *self.inner.live.tuning.write().unwrap() = tuning;
    }

    /// The next `count` frames of a host session count as sent but never leave, as if the network had eaten them.
    #[doc(hidden)]
    pub fn media_debug_lose(&self, session: u64, count: u32) -> Result<()> {
        self.inner.live_host_session_any(session)?.st.lock().unwrap().lose = count;
        Ok(())
    }

    /// Closes the connection to `peer` the way a lost network would; the engines dial each other again.
    #[doc(hidden)]
    pub fn media_debug_drop_connection(&self, peer: &DeviceId) {
        if let Some(session) = self.inner.session_of(peer) {
            session.conn.close(4u32.into(), b"test");
        }
    }
}

// ---- Inside the engine ------------------------------------------------------------------

impl Inner {
    /// Queues a control message without ever waiting: these are called from threads that must not block.
    pub(crate) fn live_send(&self, peer: DeviceId, msg: Msg) {
        let Some(session) = self.session_of(&peer) else { return };
        match session.tx.try_send(msg) {
            Ok(()) => {}
            Err(tokio::sync::mpsc::error::TrySendError::Full(msg)) => {
                let tx = session.tx.clone();
                tokio::spawn(async move {
                    let _ = tx.send(msg).await;
                });
            }
            Err(tokio::sync::mpsc::error::TrySendError::Closed(_)) => {}
        }
    }

    fn live_host_cb(&self) -> Option<Arc<dyn MediaHost>> {
        self.live.host.read().unwrap().clone()
    }

    fn live_viewer_cb(&self) -> Option<Arc<dyn MediaViewer>> {
        self.live.viewer.read().unwrap().clone()
    }

    fn live_entry(&self, session: u64, peer: &DeviceId) -> Option<Entry> {
        self.live.sessions.lock().unwrap().get(&session).filter(|e| e.peer() == *peer).cloned()
    }

    pub(crate) fn live_host_session(&self, session: u64, peer: &DeviceId) -> Option<Arc<HostSession>> {
        match self.live_entry(session, peer)? {
            Entry::Host(h) => Some(h),
            Entry::Viewer(_) => None,
        }
    }

    pub(crate) fn live_viewer_session(&self, session: u64, peer: &DeviceId) -> Option<Arc<ViewerSession>> {
        match self.live_entry(session, peer)? {
            Entry::Viewer(v) => Some(v),
            Entry::Host(_) => None,
        }
    }

    fn live_host_session_any(&self, session: u64) -> Result<Arc<HostSession>> {
        match self.live.sessions.lock().unwrap().get(&session) {
            Some(Entry::Host(h)) => Ok(h.clone()),
            _ => Err(Error::invalid("no such session to host")),
        }
    }

    fn live_viewer_session_any(&self, session: u64) -> Result<Arc<ViewerSession>> {
        match self.live.sessions.lock().unwrap().get(&session) {
            Some(Entry::Viewer(v)) => Ok(v.clone()),
            _ => Err(Error::invalid("no such session to view")),
        }
    }

    fn peer_boot(&self, peer: &DeviceId) -> u64 {
        self.peers.lock().unwrap().get(peer).and_then(|p| p.hello.as_ref().map(|h| h.boot_id)).unwrap_or(0)
    }

    /// A message about a session this device has never heard of. Said once in a while, never to a `MediaStop`.
    pub(crate) fn live_tell_unknown(&self, peer: DeviceId, session: u64) {
        {
            let mut told = self.live.told_unknown.lock().unwrap();
            let now = Instant::now();
            if told.len() > 64 {
                told.retain(|_, at| now.duration_since(*at) < Duration::from_secs(2));
            }
            if told.get(&(peer, session)).is_some_and(|at| now.duration_since(*at) < Duration::from_secs(2)) {
                return;
            }
            told.insert((peer, session), now);
        }
        self.live_send(peer, Msg::MediaStop { session, reason: MediaEnd::UnknownSession });
    }

    // ---- Viewer: asking ----

    fn live_request(self: &Arc<Self>, peer: DeviceId, want: MediaWant) -> Result<u64> {
        if want.kind == MediaKind::Other {
            return Err(Error::invalid("that kind of video does not exist"));
        }
        if !self.peers.lock().unwrap().contains_key(&peer) {
            return Err(Error::NotTrusted);
        }
        if self.session_of(&peer).is_none() {
            return Err(Error::NotConnected);
        }
        let boot = self.peer_boot(&peer);
        let (id, viewer) = {
            let mut sessions = self.live.sessions.lock().unwrap();
            if sessions.len() >= MAX_SESSIONS {
                return Err(Error::invalid("too many live sessions at once"));
            }
            if sessions.values().any(|e| e.peer() == peer && e.kind() == want.kind && e.role() == MediaRole::Viewer) {
                return Err(Error::invalid("there is a session of that kind with that device already, stop it first"));
            }
            let mut id = rand_u64();
            while id == 0 || sessions.contains_key(&id) {
                id = rand_u64();
            }
            let request = MediaRequest {
                session: id,
                kind: want.kind,
                codecs: if want.codecs.is_empty() { vec![MediaCodec::H264] } else { want.codecs },
                max_width: want.max_width,
                max_height: want.max_height,
                max_fps: want.max_fps,
                max_bitrate: want.max_bitrate,
                control: want.control && want.kind == MediaKind::Screen,
                facing: want.facing,
            };
            let viewer = ViewerSession::new(id, peer, request, boot);
            sessions.insert(id, Entry::Viewer(viewer.clone()));
            (id, viewer)
        };
        self.live_send(peer, Msg::MediaRequest(viewer.request.clone()));

        let this = self.clone();
        let waiting = viewer.clone();
        let limit = self.live.tuning.read().unwrap().request_timeout;
        tokio::spawn(async move {
            tokio::select! {
                _ = waiting.cancel.cancelled() => {}
                _ = tokio::time::sleep(limit) => {
                    if waiting.st.lock().unwrap().phase == Phase::Pending {
                        this.live_finish_viewer(&waiting, MediaEnd::Timeout, true);
                    }
                }
            }
        });
        Ok(id)
    }

    // ---- Host: being asked ----

    fn live_deny(&self, peer: DeviceId, session: u64, reason: MediaEnd) {
        self.live_send(peer, Msg::MediaDeny { session, reason, message: String::new() });
    }

    fn on_media_request(self: &Arc<Self>, peer: DeviceId, mut request: MediaRequest) {
        let session = request.session;
        // A request that is sent again (the connection dropped in between) is not a second session.
        if let Some(entry) = self.live.sessions.lock().unwrap().get(&session).cloned() {
            match entry {
                Entry::Host(host) if host.peer == peer => {
                    let accept = {
                        let st = host.st.lock().unwrap();
                        if st.phase == Phase::Active { st.accept.clone() } else { None }
                    };
                    if let Some(accept) = accept {
                        self.live_send(peer, Msg::MediaAccept(accept));
                    }
                }
                _ => self.live_deny(peer, session, MediaEnd::Busy),
            }
            return;
        }
        if session == 0 || request.kind == MediaKind::Other {
            self.live_deny(peer, session, MediaEnd::Unsupported);
            return;
        }
        request.codecs.retain(|c| *c != MediaCodec::Other);
        if request.codecs.is_empty() {
            request.codecs.push(MediaCodec::H264);
        }
        request.max_width = request.max_width.min(16384);
        request.max_height = request.max_height.min(16384);
        request.max_fps = request.max_fps.min(240);
        request.control = request.control && request.kind == MediaKind::Screen;

        let policy = self.live.service.policy(&peer);
        let permission = policy.for_kind(request.kind);
        if permission == MediaPermission::Never {
            self.live_deny(peer, session, MediaEnd::Policy);
            return;
        }
        let Some(app) = self.live_host_cb() else {
            self.live_deny(peer, session, MediaEnd::Unsupported);
            return;
        };

        let control_allowed = policy.control != MediaPermission::Never;
        let pre_approved = permission == MediaPermission::Always && (!request.control || policy.control == MediaPermission::Always);
        let host = HostSession::new(session, peer, request.clone(), self.peer_boot(&peer), control_allowed);

        // One session per kind per device: a viewer that crashed must not lock itself out of the next try.
        let replaced = {
            let mut sessions = self.live.sessions.lock().unwrap();
            let old = sessions
                .values()
                .find(|e| e.peer() == peer && e.kind() == request.kind && e.role() == MediaRole::Host)
                .cloned();
            if old.is_none() && sessions.len() >= MAX_SESSIONS {
                drop(sessions);
                self.live_deny(peer, session, MediaEnd::Busy);
                return;
            }
            if let Some(old) = &old {
                sessions.remove(&old.id());
            }
            sessions.insert(session, Entry::Host(host.clone()));
            old
        };
        if let Some(old) = replaced {
            self.live_finish(old, MediaEnd::Replaced, true);
        }

        let this = self.clone();
        let waiting = host.clone();
        let limit = self.live.tuning.read().unwrap().consent_timeout;
        tokio::spawn(async move {
            tokio::select! {
                _ = waiting.cancel.cancelled() => {}
                _ = tokio::time::sleep(limit) => {
                    if waiting.st.lock().unwrap().phase == Phase::Pending {
                        this.live_deny(waiting.peer, waiting.id, MediaEnd::Timeout);
                        this.live_finish_host(&waiting, MediaEnd::Timeout, false);
                    }
                }
            }
        });

        self.emit(Event::MediaRequested { from: peer, request: request.clone(), pre_approved });
        tokio::spawn(async move {
            guarded("on_request", || app.on_request(peer, request, pre_approved));
        });
    }

    fn live_accept(self: &Arc<Self>, session: u64, answer: MediaAnswer) -> Result<()> {
        let host = self.live_host_session_any(session)?;
        if host.st.lock().unwrap().phase != Phase::Pending {
            return Err(Error::invalid("that session is not waiting for an answer"));
        }
        let request = &host.request;
        if answer.codec == MediaCodec::Other || !request.codecs.contains(&answer.codec) {
            return Err(Error::invalid("the viewer did not ask for that codec"));
        }
        if !(1..=16384).contains(&answer.width) || !(1..=16384).contains(&answer.height) || !(1..=240).contains(&answer.fps) {
            return Err(Error::invalid("that size or frame rate is not possible"));
        }
        let ceiling = if request.max_bitrate > 0 { request.max_bitrate } else { 200_000_000 };
        let accept = MediaAccept {
            session,
            kind: host.kind,
            codec: answer.codec,
            width: answer.width,
            height: answer.height,
            fps: answer.fps,
            bitrate: answer.bitrate.clamp(rate::FLOOR_BPS, ceiling.max(rate::FLOOR_BPS)),
            control: answer.control && request.control && host.control_allowed && host.kind == MediaKind::Screen,
        };
        host.activate(self, accept.clone());
        self.live_send(host.peer, Msg::MediaAccept(accept));
        self.emit(Event::MediaStarted { peer: host.peer, session, kind: host.kind, role: MediaRole::Host });
        Ok(())
    }

    // ---- Messages ----

    pub(crate) fn handle_live_msg(self: &Arc<Self>, peer: DeviceId, msg: Msg) {
        match msg {
            Msg::MediaRequest(request) => self.on_media_request(peer, request),
            Msg::MediaAccept(accept) => self.on_media_accept(peer, accept),
            Msg::MediaDeny { session, reason, .. } => {
                if let Some(viewer) = self.live_viewer_session(session, &peer) {
                    self.live_finish_viewer(&viewer, reason, false);
                }
            }
            Msg::MediaUpdate { session, update } => {
                let Some(viewer) = self.live_viewer_session(session, &peer) else {
                    return self.live_tell_unknown(peer, session);
                };
                let mut update = update;
                {
                    let mut st = viewer.st.lock().unwrap();
                    if st.phase != Phase::Active {
                        return;
                    }
                    if let Some(grant) = update.control {
                        st.control = grant && viewer.request.control;
                        update.control = Some(st.control);
                    }
                }
                if let Some(app) = self.live_viewer_cb() {
                    guarded("on_update", || app.on_update(session, update));
                }
            }
            Msg::MediaStop { session, reason } => {
                if let Some(entry) = self.live_entry(session, &peer) {
                    self.live_finish(entry, reason, false);
                }
            }
            Msg::MediaKeyframe { session } => {
                let Some(host) = self.live_host_session(session, &peer) else {
                    return self.live_tell_unknown(peer, session);
                };
                let rtt = self.session_of(&peer).map(|s| s.conn.rtt()).unwrap_or_default();
                if host.keyframe_requested(rtt) {
                    self.live_ask_keyframe(&host);
                }
            }
            Msg::MediaReport(report) => {
                let Some(host) = self.live_host_session(report.session, &peer) else {
                    return self.live_tell_unknown(peer, report.session);
                };
                host.note_report(&report);
            }
            Msg::MediaInput { session, input } => {
                let Some(host) = self.live_host_session(session, &peer) else {
                    return self.live_tell_unknown(peer, session);
                };
                self.live_input(&host, input);
            }
            Msg::MediaOffer { kind, facing } => {
                // Only the two kinds this version knows are worth showing a person.
                if kind != MediaKind::Other {
                    self.emit(Event::MediaOffered { from: peer, kind, facing });
                }
            }
            _ => {}
        }
    }

    fn on_media_accept(self: &Arc<Self>, peer: DeviceId, accept: MediaAccept) {
        let session = accept.session;
        let Some(viewer) = self.live_viewer_session(session, &peer) else {
            return self.live_tell_unknown(peer, session);
        };
        if viewer.st.lock().unwrap().phase != Phase::Pending {
            // An answer that is sent again after the connection dropped.
            return;
        }
        let request = &viewer.request;
        let sane = accept.codec != MediaCodec::Other
            && request.codecs.contains(&accept.codec)
            && (1..=16384).contains(&accept.width)
            && (1..=16384).contains(&accept.height)
            && (1..=240).contains(&accept.fps);
        if !sane {
            self.live_finish_viewer(&viewer, MediaEnd::Error, true);
            return;
        }
        let mut accept = accept;
        accept.control = accept.control && request.control && viewer.kind == MediaKind::Screen;
        viewer.activate(self, accept.clone());
        self.emit(Event::MediaStarted { peer, session, kind: viewer.kind, role: MediaRole::Viewer });
        if let Some(app) = self.live_viewer_cb() {
            guarded("on_accepted", || app.on_accepted(session, accept));
        }
    }

    /// Input from the viewer, by stream or by datagram. Only a granted control lets any of it through.
    fn live_input(&self, host: &Arc<HostSession>, input: MediaInput) {
        let Some(input) = input.sanitized() else { return };
        {
            let mut st = host.st.lock().unwrap();
            if st.phase != Phase::Active || !st.control {
                st.stats.input_refused += 1;
                return;
            }
        }
        if let Some(app) = self.live_host_cb() {
            guarded("on_input", || app.on_input(host.id, input));
        }
    }

    pub(crate) fn live_pointer(&self, peer: DeviceId, session: u64, counter: u16, x: f32, y: f32) {
        let Some(host) = self.live_host_session(session, &peer) else { return };
        {
            let mut st = host.st.lock().unwrap();
            // Datagrams can swap places. A position older than the last one is dropped.
            if let Some(last) = st.last_pointer {
                if counter == last || (counter.wrapping_sub(last) as i16) < 0 {
                    return;
                }
            }
            st.last_pointer = Some(counter);
        }
        self.live_input(&host, MediaInput::PointerAbs { x, y });
    }

    // ---- Host: callbacks to the app ----

    pub(crate) fn live_ask_keyframe(&self, host: &Arc<HostSession>) {
        if let Some(app) = self.live_host_cb() {
            let id = host.id;
            tokio::spawn(async move { guarded("on_keyframe", || app.on_keyframe(id)) });
        }
    }

    pub(crate) fn live_bitrate_hint(&self, host: &Arc<HostSession>, bits_per_second: u32) {
        self.emit(Event::MediaBitrate { peer: host.peer, session: host.id, bits_per_second });
        if let Some(app) = self.live_host_cb() {
            let id = host.id;
            tokio::spawn(async move { guarded("on_bitrate", || app.on_bitrate(id, bits_per_second)) });
        }
    }

    /// A frame of the host did not make it. Asks the app for a keyframe when that is called for.
    pub(crate) fn live_gave_up(&self, host: &Arc<HostSession>, seq: u32) {
        if host.gave_up(seq) {
            self.live_ask_keyframe(host);
        }
    }

    // ---- Ending ----

    pub(crate) fn live_finish(self: &Arc<Self>, entry: Entry, reason: MediaEnd, notify_peer: bool) {
        match entry {
            Entry::Host(host) => self.live_finish_host(&host, reason, notify_peer),
            Entry::Viewer(viewer) => self.live_finish_viewer(&viewer, reason, notify_peer),
        }
    }

    fn live_forget(&self, id: u64, same: impl Fn(&Entry) -> bool) {
        let mut sessions = self.live.sessions.lock().unwrap();
        if sessions.get(&id).is_some_and(same) {
            sessions.remove(&id);
        }
    }

    pub(crate) fn live_finish_host(self: &Arc<Self>, host: &Arc<HostSession>, reason: MediaEnd, notify_peer: bool) {
        if host.end().is_none() {
            return;
        }
        let host_ptr = Arc::as_ptr(host);
        self.live_forget(host.id, |e| matches!(e, Entry::Host(h) if Arc::as_ptr(h) == host_ptr));
        if notify_peer {
            self.live_send(host.peer, Msg::MediaStop { session: host.id, reason });
        }
        self.emit(Event::MediaEnded { peer: host.peer, session: host.id, kind: host.kind, role: MediaRole::Host, reason });
        if let Some(app) = self.live_host_cb() {
            let id = host.id;
            tokio::spawn(async move { guarded("on_stop", || app.on_stop(id, reason)) });
        }
    }

    pub(crate) fn live_finish_viewer(self: &Arc<Self>, viewer: &Arc<ViewerSession>, reason: MediaEnd, notify_peer: bool) {
        let Some(was) = viewer.end(reason) else { return };
        let viewer_ptr = Arc::as_ptr(viewer);
        self.live_forget(viewer.id, |e| matches!(e, Entry::Viewer(v) if Arc::as_ptr(v) == viewer_ptr));
        if notify_peer {
            self.live_send(viewer.peer, Msg::MediaStop { session: viewer.id, reason });
        }
        self.emit(Event::MediaEnded { peer: viewer.peer, session: viewer.id, kind: viewer.kind, role: MediaRole::Viewer, reason });
        // A session that ran says so from the task that hands over frames, so nothing can follow it.
        if was == Phase::Pending {
            if let Some(app) = self.live_viewer_cb() {
                let id = viewer.id;
                tokio::spawn(async move { guarded("on_ended", || app.on_ended(id, reason)) });
            }
        }
    }

    // ---- The connection ----

    /// A connection to `peer` is up, the first or a replacement of the one that was.
    pub(crate) fn live_peer_up(self: &Arc<Self>, peer: DeviceId, boot_id: u64) {
        let entries: Vec<Entry> = self.live.sessions.lock().unwrap().values().filter(|e| e.peer() == peer).cloned().collect();
        for entry in entries {
            if entry.peer_boot() != 0 && boot_id != 0 && entry.peer_boot() != boot_id {
                // The other device was restarted: nothing it knew is left.
                self.live_finish(entry, MediaEnd::PeerGone, false);
                continue;
            }
            entry.set_suspended(None);
            match &entry {
                Entry::Host(host) => {
                    if host.connection_changed() {
                        self.live_ask_keyframe(host);
                    }
                }
                Entry::Viewer(viewer) => {
                    let pending = viewer.st.lock().unwrap().phase == Phase::Pending;
                    if pending {
                        self.live_send(peer, Msg::MediaRequest(viewer.request.clone()));
                    } else if viewer.connection_changed() {
                        self.live_send(peer, Msg::MediaKeyframe { session: viewer.id });
                    }
                }
            }
        }
    }

    /// The connection to `peer` is gone. Sessions wait for it for the grace period.
    pub(crate) fn live_peer_down(self: &Arc<Self>, peer: DeviceId) {
        let entries: Vec<Entry> = self.live.sessions.lock().unwrap().values().filter(|e| e.peer() == peer).cloned().collect();
        for entry in entries {
            let epoch = self.live.epoch.fetch_add(1, Ordering::Relaxed);
            entry.set_suspended(Some(epoch));
            let this = self.clone();
            let grace = self.live.tuning.read().unwrap().grace;
            tokio::spawn(async move {
                let cancel = entry.cancel();
                tokio::select! {
                    _ = cancel.cancelled() => {}
                    _ = tokio::time::sleep(grace) => {
                        // Still the same outage, not a later one after a reconnect in between.
                        if entry.suspended() == Some(epoch) {
                            this.live_finish(entry, MediaEnd::PeerGone, false);
                        }
                    }
                }
            });
        }
    }
}
