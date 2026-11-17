//! A lasting log of what goes over the connections, to find out afterwards what keeps a phone awake: one line per 5 minutes with, per
//! device, the messages sent and received by kind and what QUIC itself sent besides (acknowledgements, pings), plus event lines for
//! connections, dial attempts, changes of network and of the screen and the data. One file per day in `traffic/`, JSON lines.
//!
//! Only kinds, counts, sizes, device ids and names, and the kind of a route: never what a message says, and never an address.
//!
//! It must cost nothing in battery, so it has no timer of its own: a line of counts is closed and written when the device is awake anyway
//! (the next message sent or received, an event), with the real start and end of what it covers. A stretch in which the device slept is one
//! line when it wakes, not many empty ones. Counting costs a short lock; the disk is touched at most once a minute, off the path of
//! sending, and without a sync.

use std::collections::HashMap;
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::sync::atomic::{AtomicU64, Ordering};

use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

use crate::ids::DeviceId;
use crate::proto::Msg;
use crate::sent_tally::Kind;

/// The length of one line of counts.
pub(crate) const BUCKET_MS: u64 = 300_000;
/// How long the files are kept, and how much they may take together.
pub(crate) const KEEP_DAYS: u64 = 14;
pub(crate) const MAX_BYTES: u64 = 30_000_000;
/// Event lines waiting for the next write: more than this and the oldest are dropped, so a disk that stalls cannot grow memory.
const MAX_PENDING: usize = 4000;
const DAY_MS: u64 = 86_400_000;
/// The disk is written at most this often.
const WRITE_EVERY_MS: u64 = 60_000;

/// What the app of this device knows and the core does not: put in every line of counts.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct TrafficContext {
    pub screen_on: Option<bool>,
    /// "wifi", "mobile", "ethernet", "none" or what the app says.
    pub network: Option<String>,
    pub battery: Option<u8>,
    pub charging: Option<bool>,
    pub quiet: bool,
    pub metered: bool,
}

/// What QUIC counted on one connection, the parts that are not messages.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) struct Quic {
    pub tx_datagrams: u64,
    pub tx_bytes: u64,
    pub rx_datagrams: u64,
    pub rx_bytes: u64,
    pub tx_acks: u64,
    pub tx_pings: u64,
    pub tx_streams: u64,
    pub tx_datagram_frames: u64,
    pub rx_acks: u64,
    pub rx_pings: u64,
    pub rx_streams: u64,
    pub rx_datagram_frames: u64,
    pub lost: u64,
}

impl Quic {
    pub(crate) fn of(stats: &quinn::ConnectionStats) -> Quic {
        Quic {
            tx_datagrams: stats.udp_tx.datagrams,
            tx_bytes: stats.udp_tx.bytes,
            rx_datagrams: stats.udp_rx.datagrams,
            rx_bytes: stats.udp_rx.bytes,
            tx_acks: stats.frame_tx.acks,
            tx_pings: stats.frame_tx.ping,
            tx_streams: stats.frame_tx.stream,
            tx_datagram_frames: stats.frame_tx.datagram,
            rx_acks: stats.frame_rx.acks,
            rx_pings: stats.frame_rx.ping,
            rx_streams: stats.frame_rx.stream,
            rx_datagram_frames: stats.frame_rx.datagram,
            lost: stats.path.lost_packets,
        }
    }

    fn since(self, before: Quic) -> Quic {
        let d = |a: u64, b: u64| a.saturating_sub(b);
        Quic {
            tx_datagrams: d(self.tx_datagrams, before.tx_datagrams),
            tx_bytes: d(self.tx_bytes, before.tx_bytes),
            rx_datagrams: d(self.rx_datagrams, before.rx_datagrams),
            rx_bytes: d(self.rx_bytes, before.rx_bytes),
            tx_acks: d(self.tx_acks, before.tx_acks),
            tx_pings: d(self.tx_pings, before.tx_pings),
            tx_streams: d(self.tx_streams, before.tx_streams),
            tx_datagram_frames: d(self.tx_datagram_frames, before.tx_datagram_frames),
            rx_acks: d(self.rx_acks, before.rx_acks),
            rx_pings: d(self.rx_pings, before.rx_pings),
            rx_streams: d(self.rx_streams, before.rx_streams),
            rx_datagram_frames: d(self.rx_datagram_frames, before.rx_datagram_frames),
            lost: d(self.lost, before.lost),
        }
    }

    fn add(&mut self, other: Quic) {
        self.tx_datagrams += other.tx_datagrams;
        self.tx_bytes += other.tx_bytes;
        self.rx_datagrams += other.rx_datagrams;
        self.rx_bytes += other.rx_bytes;
        self.tx_acks += other.tx_acks;
        self.tx_pings += other.tx_pings;
        self.tx_streams += other.tx_streams;
        self.tx_datagram_frames += other.tx_datagram_frames;
        self.rx_acks += other.rx_acks;
        self.rx_pings += other.rx_pings;
        self.rx_streams += other.rx_streams;
        self.rx_datagram_frames += other.rx_datagram_frames;
        self.lost += other.lost;
    }

    fn is_empty(&self) -> bool {
        *self == Quic::default()
    }

    fn json(&self) -> Value {
        json!({
            "udp_tx": [self.tx_datagrams, self.tx_bytes],
            "udp_rx": [self.rx_datagrams, self.rx_bytes],
            "tx": { "ack": self.tx_acks, "ping": self.tx_pings, "stream": self.tx_streams, "datagram": self.tx_datagram_frames },
            "rx": { "ack": self.rx_acks, "ping": self.rx_pings, "stream": self.rx_streams, "datagram": self.rx_datagram_frames },
            "lost": self.lost,
        })
    }
}

/// A connection that is up now, as the engine sees it when a minute is due.
pub(crate) struct LiveSample {
    pub peer: DeviceId,
    pub name: String,
    pub serial: u64,
    pub route: &'static str,
    pub rtt_ms: u32,
    pub quic: Quic,
}

#[derive(Default)]
struct PeerCount {
    sent: HashMap<Kind, (u64, u64)>,
    recv: HashMap<Kind, (u64, u64)>,
    quic: Quic,
    name: String,
    route: Option<&'static str>,
    rtt_ms: Option<u32>,
    connected: bool,
}

struct State {
    bucket_start: u64,
    /// The last message or event that was counted, so a line that covers a sleep says when the activity stopped.
    last_activity: u64,
    last_write: u64,
    peers: HashMap<DeviceId, PeerCount>,
    baseline: HashMap<DeviceId, (u64, Quic)>,
    pending: Vec<String>,
    context: TrafficContext,
}

pub(crate) struct TrafficLog {
    pub dir: PathBuf,
    state: Mutex<State>,
    /// When something is due to be written: checked on every message without a lock, see `is_due`.
    next_due: AtomicU64,
    /// A second place the same lines are copied to (the folder of the app on Android that `adb pull` reaches).
    mirror: Mutex<Option<PathBuf>>,
}

impl TrafficLog {
    pub(crate) fn new(data_dir: &Path, now_ms: u64) -> TrafficLog {
        TrafficLog {
            dir: data_dir.join("traffic"),
            next_due: AtomicU64::new(now_ms + BUCKET_MS),
            state: Mutex::new(State {
                bucket_start: now_ms,
                last_activity: now_ms,
                last_write: now_ms,
                peers: HashMap::new(),
                baseline: HashMap::new(),
                pending: Vec::new(),
                context: TrafficContext::default(),
            }),
            mirror: Mutex::new(None),
        }
    }

    pub(crate) fn set_mirror(&self, dir: Option<PathBuf>) {
        *self.mirror.lock().unwrap() = dir;
    }

    pub(crate) fn mirror(&self) -> Option<PathBuf> {
        self.mirror.lock().unwrap().clone()
    }

    /// Whether a line is due at `now_ms`: a comparison, no lock.
    pub(crate) fn is_due(&self, now_ms: u64) -> bool {
        now_ms >= self.next_due.load(Ordering::Relaxed)
    }

    fn reschedule(&self, state: &State) {
        let bucket_end = state.bucket_start + BUCKET_MS;
        let due = if state.pending.is_empty() { bucket_end } else { bucket_end.min(state.last_write + WRITE_EVERY_MS) };
        self.next_due.store(due, Ordering::Relaxed);
    }

    pub(crate) fn sent(&self, peer: DeviceId, msg: &Msg, bytes: usize, now_ms: u64) {
        let kind = Kind::of(msg);
        let mut state = self.state.lock().unwrap();
        state.last_activity = now_ms;
        let entry = state.peers.entry(peer).or_default().sent.entry(kind).or_default();
        entry.0 += 1;
        entry.1 += bytes as u64;
    }

    pub(crate) fn received(&self, peer: DeviceId, msg: &Msg, bytes: usize, now_ms: u64) {
        let kind = Kind::of(msg);
        let mut state = self.state.lock().unwrap();
        state.last_activity = now_ms;
        let entry = state.peers.entry(peer).or_default().recv.entry(kind).or_default();
        entry.0 += 1;
        entry.1 += bytes as u64;
    }

    /// A line about something that happened, with its time. `fields` are added to it.
    pub(crate) fn event(&self, now_ms: u64, name: &str, fields: Value) {
        let mut line = json!({ "t": now_ms, "k": "event", "e": name });
        if let (Some(line), Value::Object(extra)) = (line.as_object_mut(), fields) {
            line.extend(extra);
        }
        let mut state = self.state.lock().unwrap();
        state.last_activity = now_ms;
        state.pending.push(line.to_string());
        if state.pending.len() > MAX_PENDING {
            let drop = state.pending.len() - MAX_PENDING;
            state.pending.drain(..drop);
        }
        self.reschedule(&state);
    }

    /// A connection came up: counting its QUIC numbers starts from here.
    pub(crate) fn connected(&self, peer: DeviceId, serial: u64, quic: Quic) {
        self.state.lock().unwrap().baseline.insert(peer, (serial, quic));
    }

    /// A connection went: what it counted since the last line is kept for the next one.
    pub(crate) fn disconnected(&self, peer: DeviceId, serial: u64, quic: Quic) {
        let mut state = self.state.lock().unwrap();
        if let Some((was, before)) = state.baseline.get(&peer).copied()
            && was == serial
        {
            state.baseline.remove(&peer);
            let count = state.peers.entry(peer).or_default();
            count.quic.add(quic.since(before));
            count.connected = false;
        }
    }

    /// Changes the context and writes a line when something in it differs.
    pub(crate) fn context_changed(&self, now_ms: u64, change: impl FnOnce(&mut TrafficContext)) {
        let (before, after) = {
            let mut state = self.state.lock().unwrap();
            let before = state.context.clone();
            change(&mut state.context);
            (before, state.context.clone())
        };
        if before != after {
            self.event(now_ms, "state", serde_json::to_value(&after).unwrap_or(Value::Null));
        }
    }

    /// The lines that are due at `now_ms`: the events waiting (at most once a minute), and a line of counts when 5 minutes have passed
    /// since the last one. That line covers from its start to `now_ms`, however long the device slept in between.
    pub(crate) fn take_due(&self, now_ms: u64, live: &[LiveSample]) -> Vec<String> {
        let mut state = self.state.lock().unwrap();
        let mut lines = Vec::new();
        if now_ms >= state.last_write + WRITE_EVERY_MS || now_ms >= state.bucket_start + BUCKET_MS {
            lines = std::mem::take(&mut state.pending);
            state.last_write = now_ms;
        }
        if now_ms < state.bucket_start + BUCKET_MS {
            self.reschedule(&state);
            return lines;
        }
        // What the live connections counted since the last line.
        for sample in live {
            let before = match state.baseline.get(&sample.peer) {
                Some((serial, before)) if *serial == sample.serial => *before,
                _ => Quic::default(),
            };
            state.baseline.insert(sample.peer, (sample.serial, sample.quic));
            let count = state.peers.entry(sample.peer).or_default();
            count.quic.add(sample.quic.since(before));
            count.name = sample.name.clone();
            count.route = Some(sample.route);
            count.rtt_ms = Some(sample.rtt_ms);
            count.connected = true;
        }
        let span = now_ms - state.bucket_start;
        let mut peers = Vec::new();
        let mut ids: Vec<DeviceId> = state.peers.keys().copied().collect();
        ids.sort_by_key(|id| id.to_string());
        for id in ids {
            let count = &state.peers[&id];
            if count.sent.is_empty() && count.recv.is_empty() && count.quic.is_empty() {
                continue;
            }
            peers.push(json!({
                "id": id.to_string(),
                "name": count.name,
                "route": count.route,
                "rtt_ms": count.rtt_ms,
                "connected": count.connected,
                "sent": kinds_json(&count.sent),
                "recv": kinds_json(&count.recv),
                "quic": count.quic.json(),
            }));
        }
        if !peers.is_empty() {
            let line = json!({
                "t": state.bucket_start,
                "end": now_ms,
                "last": state.last_activity.min(now_ms),
                "k": "bucket",
                "span_s": span / 1000,
                "ctx": state.context,
                "peers": peers,
            });
            lines.push(line.to_string());
        }
        state.peers.clear();
        state.bucket_start = now_ms;
        self.reschedule(&state);
        lines
    }

    /// Whether the line of counts is due, and not only some events: then the app is asked what it knows (battery, screen, network).
    pub(crate) fn bucket_due(&self, now_ms: u64) -> bool {
        now_ms >= self.state.lock().unwrap().bucket_start + BUCKET_MS
    }

    /// What the app said about the device, for the line that is closed now. No event line: the changes that matter have their own.
    pub(crate) fn set_app_context(&self, screen_on: Option<bool>, network: Option<String>, battery: Option<u8>, charging: Option<bool>) {
        let mut state = self.state.lock().unwrap();
        state.context.screen_on = screen_on;
        state.context.network = network;
        state.context.battery = battery;
        state.context.charging = charging;
    }

    /// Appends lines to the file of the day of `now_ms`. The folder is cleaned (old days, the size) when the file of a new day is opened,
    /// and not by anything of its own.
    pub(crate) fn write(&self, now_ms: u64, lines: &[String]) {
        if lines.is_empty() {
            return;
        }
        let mirror = self.mirror();
        for dir in std::iter::once(self.dir.as_path()).chain(mirror.as_deref()) {
            if let Ok(true) = append_lines(dir, now_ms, lines) {
                let _ = rotate(dir, now_ms, KEEP_DAYS, MAX_BYTES);
            }
        }
    }
}

fn kinds_json(map: &HashMap<Kind, (u64, u64)>) -> Value {
    let mut out = serde_json::Map::new();
    let mut kinds: Vec<(&Kind, &(u64, u64))> = map.iter().collect();
    kinds.sort_by(|a, b| a.0.as_str().cmp(b.0.as_str()));
    for (kind, (count, bytes)) in kinds {
        out.insert(kind.as_str().to_string(), json!([count, bytes]));
    }
    Value::Object(out)
}

/// `2026-11-17` for a time in Unix milliseconds, in UTC.
pub(crate) fn day_name(ms: u64) -> String {
    let (y, m, d) = civil_from_days((ms / DAY_MS) as i64);
    format!("{y:04}-{m:02}-{d:02}")
}

/// The day number (since 1970) of a file name `YYYY-MM-DD.jsonl`.
fn day_of_name(name: &str) -> Option<i64> {
    let stem = name.strip_suffix(".jsonl")?;
    let mut parts = stem.split('-');
    let (y, m, d): (i64, i64, i64) = (parts.next()?.parse().ok()?, parts.next()?.parse().ok()?, parts.next()?.parse().ok()?);
    if parts.next().is_some() || !(1..=12).contains(&m) || !(1..=31).contains(&d) {
        return None;
    }
    Some(days_from_civil(y, m, d))
}

// The days of the proleptic Gregorian calendar, after Howard Hinnant's algorithms.
fn civil_from_days(z: i64) -> (i64, i64, i64) {
    let z = z + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    (if m <= 2 { y + 1 } else { y }, m, d)
}

fn days_from_civil(y: i64, m: i64, d: i64) -> i64 {
    let y = if m <= 2 { y - 1 } else { y };
    let era = y.div_euclid(400);
    let yoe = y.rem_euclid(400);
    let doy = (153 * (if m > 2 { m - 3 } else { m + 9 }) + 2) / 5 + d - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146_097 + doe - 719_468
}

/// Returns whether this opened the file of the day: the first line of a day.
pub(crate) fn append_lines(dir: &Path, now_ms: u64, lines: &[String]) -> std::io::Result<bool> {
    fs::create_dir_all(dir)?;
    let path = dir.join(format!("{}.jsonl", day_name(now_ms)));
    let created = !path.exists();
    let mut file = fs::OpenOptions::new().create(true).append(true).open(path)?;
    let mut text = String::new();
    for line in lines {
        text.push_str(line);
        text.push('\n');
    }
    // Buffered by the system, not synced: losing the last minute in a crash is fine.
    file.write_all(text.as_bytes())?;
    Ok(created)
}

/// Removes the files older than `keep_days`, and then the oldest ones until what is left is at most `max_bytes`. The file of today stays.
pub(crate) fn rotate(dir: &Path, now_ms: u64, keep_days: u64, max_bytes: u64) -> std::io::Result<()> {
    let today = (now_ms / DAY_MS) as i64;
    let mut files: Vec<(i64, PathBuf, u64)> = Vec::new();
    for entry in fs::read_dir(dir)? {
        let entry = entry?;
        let name = entry.file_name().to_string_lossy().to_string();
        if let Some(day) = day_of_name(&name) {
            files.push((day, entry.path(), entry.metadata().map(|m| m.len()).unwrap_or(0)));
        }
    }
    files.sort();
    files.retain(|(day, path, _)| {
        if today - day >= keep_days as i64 {
            let _ = fs::remove_file(path);
            false
        } else {
            true
        }
    });
    let mut total: u64 = files.iter().map(|f| f.2).sum();
    for (day, path, size) in &files {
        if total <= max_bytes || *day == today {
            break;
        }
        if fs::remove_file(path).is_ok() {
            total -= size;
        }
    }
    Ok(())
}


// ---- What the engine gives it -----------------------------------------------------------------------------------------------------

/// What the app of the device knows, when it is asked as a line of counts is closed.
pub type ContextSource = std::sync::Arc<dyn Fn() -> TrafficContext + Send + Sync>;

fn route_name(ip: std::net::IpAddr) -> &'static str {
    match crate::net::route_of(ip) {
        crate::net::Route::Lan => "lan",
        crate::net::Route::Tailnet => "tailnet",
        crate::net::Route::Other => "other",
    }
}

impl crate::engine::Inner {
    pub(crate) fn traffic_sent(&self, peer: DeviceId, msg: &Msg, bytes: usize) {
        let now = crate::ids::now_ms();
        self.traffic.sent(peer, msg, bytes, now);
        self.traffic_flush(now);
    }

    pub(crate) fn traffic_received(&self, peer: DeviceId, msg: &Msg, bytes: usize) {
        let now = crate::ids::now_ms();
        self.traffic.received(peer, msg, bytes, now);
        self.traffic_flush(now);
    }

    pub(crate) fn traffic_event(&self, name: &str, fields: Value) {
        let now = crate::ids::now_ms();
        self.traffic.event(now, name, fields);
        self.traffic_flush(now);
    }

    pub(crate) fn traffic_context_changed(&self, change: impl FnOnce(&mut TrafficContext)) {
        let now = crate::ids::now_ms();
        self.traffic.context_changed(now, change);
    }

    /// A connection came up (or went): what it is, for the event line.
    pub(crate) fn traffic_session_up(&self, session: &crate::session::Session, name: &str) {
        self.traffic.connected(session.peer, session.serial, Quic::of(&session.conn.stats()));
        self.traffic_event(
            "connected",
            json!({ "peer": session.peer.to_string(), "name": name, "route": route_name(session.remote.ip()), "dialer": session.dialer }),
        );
    }

    pub(crate) fn traffic_session_down(&self, session: &crate::session::Session, name: &str) {
        self.traffic.disconnected(session.peer, session.serial, Quic::of(&session.conn.stats()));
        let reason = match session.conn.close_reason() {
            Some(quinn::ConnectionError::TimedOut) => "timed out",
            Some(quinn::ConnectionError::ApplicationClosed(_)) => "closed by the other device",
            Some(quinn::ConnectionError::ConnectionClosed(_)) => "closed by the transport",
            Some(quinn::ConnectionError::Reset) => "reset",
            Some(quinn::ConnectionError::LocallyClosed) => "closed here",
            Some(_) => "other",
            None => "unknown",
        };
        self.traffic_event("disconnected", json!({ "peer": session.peer.to_string(), "name": name, "route": route_name(session.remote.ip()), "reason": reason }));
    }

    /// Writes what is due. Called only from work the engine does anyway, never from a timer of its own: when nothing happens nothing is
    /// written, and a sleep is one line when the device wakes. The disk is touched off the path of the caller.
    fn traffic_flush(&self, now: u64) {
        if !self.traffic.is_due(now) {
            return;
        }
        let bucket = self.traffic.bucket_due(now);
        let mut live = Vec::new();
        if bucket {
            let source = self.traffic_context_source.lock().unwrap().clone();
            if let Some(source) = source {
                let c = source();
                self.traffic.set_app_context(c.screen_on, c.network, c.battery, c.charging);
            }
            for (id, peer) in self.peers.lock().unwrap().iter() {
                if let Some(session) = &peer.session {
                    live.push(LiveSample {
                        peer: *id,
                        name: peer.hello.as_ref().map(|h| h.name.clone()).unwrap_or_default(),
                        serial: session.serial,
                        route: route_name(session.remote.ip()),
                        rtt_ms: session.conn.rtt().as_millis() as u32,
                        quic: Quic::of(&session.conn.stats()),
                    });
                }
            }
        }
        let lines = self.traffic.take_due(now, &live);
        if lines.is_empty() {
            return;
        }
        let log = self.traffic.clone();
        match tokio::runtime::Handle::try_current() {
            Ok(handle) => {
                handle.spawn_blocking(move || log.write(now, &lines));
            }
            Err(_) => log.write(now, &lines),
        }
    }
}

// ---- Reading it back, for a page in an app --------------------------------------------------------------------------------------

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct KindTotal {
    pub kind: String,
    pub sent_count: u64,
    pub sent_bytes: u64,
    pub recv_count: u64,
    pub recv_bytes: u64,
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct DeviceTotal {
    pub id: String,
    pub name: String,
    pub sent_count: u64,
    pub sent_bytes: u64,
    pub recv_count: u64,
    pub recv_bytes: u64,
    /// UDP datagrams QUIC sent and received, acknowledgements and pings included.
    pub packets_tx: u64,
    pub packets_rx: u64,
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct HourTotal {
    pub hour_start_ms: u64,
    pub sent_count: u64,
    pub sent_bytes: u64,
    pub recv_count: u64,
    pub recv_bytes: u64,
    pub packets: u64,
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct TrafficSummary {
    pub by_kind: Vec<KindTotal>,
    pub by_device: Vec<DeviceTotal>,
    /// The last 24 hours, the oldest first, one entry per hour.
    pub by_hour: Vec<HourTotal>,
}

/// The totals of the lines of counts since `since_ms` in the files of `dir`, and per hour for the 24 hours before `now_ms`.
pub(crate) fn summarize(dir: &Path, since_ms: u64, now_ms: u64) -> TrafficSummary {
    let mut kinds: HashMap<String, KindTotal> = HashMap::new();
    let mut devices: HashMap<String, DeviceTotal> = HashMap::new();
    let hour_ms = 3_600_000;
    let first_hour = now_ms / hour_ms * hour_ms - 23 * hour_ms;
    let mut hours: Vec<HourTotal> = (0..24).map(|i| HourTotal { hour_start_ms: first_hour + i * hour_ms, ..Default::default() }).collect();
    let mut names: Vec<String> = fs::read_dir(dir).into_iter().flatten().flatten().map(|e| e.file_name().to_string_lossy().to_string()).collect();
    names.sort();
    let earliest_day = (since_ms.min(first_hour) / DAY_MS) as i64;
    for name in names {
        let Some(day) = day_of_name(&name) else { continue };
        if day < earliest_day {
            continue;
        }
        let Ok(text) = fs::read_to_string(dir.join(&name)) else { continue };
        for line in text.lines() {
            let Ok(value) = serde_json::from_str::<Value>(line) else { continue };
            if value["k"] != "bucket" {
                continue;
            }
            let t = value["t"].as_u64().unwrap_or(0);
            let in_total = t >= since_ms;
            let hour = (t >= first_hour && t < first_hour + 24 * hour_ms).then(|| ((t - first_hour) / hour_ms) as usize);
            if !in_total && hour.is_none() {
                continue;
            }
            for peer in value["peers"].as_array().into_iter().flatten() {
                let id = peer["id"].as_str().unwrap_or("").to_string();
                let device = devices.entry(id.clone()).or_insert_with(|| DeviceTotal { id, ..Default::default() });
                if let Some(name) = peer["name"].as_str().filter(|n| !n.is_empty()) {
                    device.name = name.to_string();
                }
                let (mut sc, mut sb, mut rc, mut rb) = (0, 0, 0, 0);
                for (field, count, bytes, is_kind_total) in [("sent", &mut sc, &mut sb, true), ("recv", &mut rc, &mut rb, false)] {
                    for (kind, pair) in peer[field].as_object().into_iter().flatten() {
                        let (c, b) = (pair[0].as_u64().unwrap_or(0), pair[1].as_u64().unwrap_or(0));
                        *count += c;
                        *bytes += b;
                        if in_total {
                            let entry = kinds.entry(kind.clone()).or_insert_with(|| KindTotal { kind: kind.clone(), ..Default::default() });
                            if is_kind_total {
                                entry.sent_count += c;
                                entry.sent_bytes += b;
                            } else {
                                entry.recv_count += c;
                                entry.recv_bytes += b;
                            }
                        }
                    }
                }
                let tx = peer["quic"]["udp_tx"][0].as_u64().unwrap_or(0);
                let rx = peer["quic"]["udp_rx"][0].as_u64().unwrap_or(0);
                if in_total {
                    device.sent_count += sc;
                    device.sent_bytes += sb;
                    device.recv_count += rc;
                    device.recv_bytes += rb;
                    device.packets_tx += tx;
                    device.packets_rx += rx;
                }
                if let Some(i) = hour {
                    hours[i].sent_count += sc;
                    hours[i].sent_bytes += sb;
                    hours[i].recv_count += rc;
                    hours[i].recv_bytes += rb;
                    hours[i].packets += tx + rx;
                }
            }
        }
    }
    let mut by_kind: Vec<KindTotal> = kinds.into_values().collect();
    by_kind.sort_by(|a, b| (b.sent_bytes + b.recv_bytes).cmp(&(a.sent_bytes + a.recv_bytes)).then(a.kind.cmp(&b.kind)));
    let mut by_device: Vec<DeviceTotal> = devices.into_values().filter(|d| d.sent_count + d.recv_count + d.packets_tx + d.packets_rx > 0).collect();
    by_device.sort_by(|a, b| (b.packets_tx + b.packets_rx).cmp(&(a.packets_tx + a.packets_rx)).then(a.id.cmp(&b.id)));
    TrafficSummary { by_kind, by_device, by_hour: hours }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::proto::Status;

    fn peer(n: u8) -> DeviceId {
        DeviceId::from_public_key(&[n; 32])
    }

    fn tmp(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("tandem-traffic-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        dir
    }

    const T0: u64 = 1_800_000_000_000; // a Tuesday in 2027, a multiple of 5 minutes

    #[test]
    fn days_are_named_by_the_calendar_in_utc() {
        assert_eq!(day_name(0), "1970-01-01");
        assert_eq!(day_name(951_782_400_000), "2000-02-29");
        assert_eq!(day_name(T0), "2027-01-15");
        assert_eq!(day_of_name("2027-01-15.jsonl"), Some(T0 as i64 / DAY_MS as i64));
        assert_eq!(day_of_name("notes.txt"), None);
    }

    #[test]
    fn a_line_of_counts_comes_after_5_minutes_with_kinds_sizes_and_the_quic_numbers_and_nothing_else() {
        let log = TrafficLog::new(&tmp("bucket"), T0);
        let id = peer(1);
        log.sent(id, &Msg::Ping { nonce: 1 }, 20, T0 + 1);
        log.sent(id, &Msg::Ping { nonce: 2 }, 20, T0 + 2);
        log.sent(id, &Msg::Status(Status::default()), 600, T0 + 3);
        log.received(id, &Msg::Pong { nonce: 1 }, 18, T0 + 4);
        let quic = |datagrams| Quic { tx_datagrams: datagrams, rx_datagrams: datagrams / 2, ..Quic::default() };
        log.connected(id, 7, quic(10));
        let live = |datagrams| vec![LiveSample { peer: id, name: "Mac".into(), serial: 7, route: "tailnet", rtt_ms: 40, quic: quic(datagrams) }];
        // Not yet.
        assert!(!log.is_due(T0 + 60_000 - 1));
        assert!(log.take_due(T0 + 60_000, &live(30)).is_empty());
        assert!(!log.is_due(T0 + BUCKET_MS - 1));
        assert!(log.is_due(T0 + BUCKET_MS));
        let lines = log.take_due(T0 + BUCKET_MS + 1_000, &live(50));
        assert_eq!(lines.len(), 1);
        let line: Value = serde_json::from_str(&lines[0]).unwrap();
        assert_eq!(line["k"], "bucket");
        assert_eq!((line["t"].as_u64(), line["end"].as_u64(), line["last"].as_u64()), (Some(T0), Some(T0 + BUCKET_MS + 1_000), Some(T0 + 4)));
        let p = &line["peers"][0];
        assert_eq!(p["name"], "Mac");
        assert_eq!(p["route"], "tailnet");
        assert_eq!(p["sent"]["Ping"], json!([2, 40]));
        assert_eq!(p["sent"]["Status"], json!([1, 600]));
        assert_eq!(p["recv"]["Pong"], json!([1, 18]));
        // Since the connection came up at 10: 40 datagrams out and 20 in.
        assert_eq!(p["quic"]["udp_tx"][0], 40);
        assert_eq!(p["quic"]["udp_rx"][0], 20);
        // Nothing but kinds, counts and sizes: no addresses in it.
        assert!(!lines[0].contains("192.") && !lines[0].contains("addr"));
        // The next bucket starts again from nothing, and a quiet one writes no line.
        assert!(log.take_due(T0 + 2 * BUCKET_MS + 1_000, &live(50)).is_empty());
    }

    #[test]
    fn a_sleep_is_one_line_with_its_real_start_and_end_and_not_many_empty_ones() {
        let log = TrafficLog::new(&tmp("sleep"), T0);
        let id = peer(5);
        log.sent(id, &Msg::Ping { nonce: 1 }, 20, T0 + 10_000);
        // Three hours without anything, then the next message: it is time, and the line says what it covers.
        let wake = T0 + 3 * 3_600_000;
        assert!(log.is_due(wake));
        log.sent(id, &Msg::Ping { nonce: 2 }, 20, wake);
        let lines = log.take_due(wake, &[]);
        assert_eq!(lines.len(), 1);
        let line: Value = serde_json::from_str(&lines[0]).unwrap();
        assert_eq!((line["t"].as_u64(), line["end"].as_u64(), line["span_s"].as_u64()), (Some(T0), Some(wake), Some(10_800)));
        assert_eq!(line["last"].as_u64(), Some(wake));
        // The next one starts where this one ended.
        assert!(!log.is_due(wake + BUCKET_MS - 1));
        assert!(log.is_due(wake + BUCKET_MS));
    }

    #[test]
    fn events_wait_for_activity_and_are_written_at_most_once_a_minute() {
        let log = TrafficLog::new(&tmp("lazy"), T0);
        log.event(T0 + 5_000, "connected", json!({}));
        // Not before a minute has passed since the last write, and then only when something asks.
        assert!(!log.is_due(T0 + 59_999));
        assert!(log.take_due(T0 + 30_000, &[]).is_empty());
        assert!(log.is_due(T0 + 60_000));
        assert_eq!(log.take_due(T0 + 61_000, &[]).len(), 1);
        log.event(T0 + 62_000, "disconnected", json!({}));
        assert!(!log.is_due(T0 + 100_000));
        assert!(log.is_due(T0 + 121_000));
    }

    #[test]
    fn events_come_out_with_their_time_and_the_state_changes_once() {
        let log = TrafficLog::new(&tmp("events"), T0);
        log.event(T0 + 5, "connected", json!({ "peer": "abc", "route": "lan" }));
        log.context_changed(T0 + 6, |c| c.quiet = true);
        log.context_changed(T0 + 7, |c| c.quiet = true);
        let lines = log.take_due(T0 + WRITE_EVERY_MS, &[]);
        assert_eq!(lines.len(), 2, "{lines:?}");
        let first: Value = serde_json::from_str(&lines[0]).unwrap();
        assert_eq!((first["e"].as_str(), first["t"].as_u64()), (Some("connected"), Some(T0 + 5)));
        let second: Value = serde_json::from_str(&lines[1]).unwrap();
        assert_eq!((second["e"].as_str(), second["quiet"].as_bool()), (Some("state"), Some(true)));
    }

    #[test]
    fn a_connection_that_ended_keeps_what_it_counted_for_the_next_line() {
        let log = TrafficLog::new(&tmp("ended"), T0);
        let id = peer(2);
        log.connected(id, 1, Quic::default());
        log.disconnected(id, 1, Quic { tx_datagrams: 12, ..Quic::default() });
        let lines = log.take_due(T0 + BUCKET_MS, &[]);
        let line: Value = serde_json::from_str(&lines[0]).unwrap();
        assert_eq!(line["peers"][0]["quic"]["udp_tx"][0], 12);
        assert_eq!(line["peers"][0]["connected"], false);
    }

    #[test]
    fn the_files_are_one_per_day_kept_14_days_and_cut_to_the_size_from_the_oldest() {
        let dir = tmp("rotate");
        for day in 0..20u64 {
            append_lines(&dir, T0 - day * DAY_MS, &["x".repeat(1000)]).unwrap();
        }
        assert_eq!(fs::read_dir(&dir).unwrap().count(), 20);
        rotate(&dir, T0, 14, u64::MAX).unwrap();
        assert_eq!(fs::read_dir(&dir).unwrap().count(), 14);
        // Room for three and a half files: the oldest go first, today stays.
        rotate(&dir, T0, 14, 3_500).unwrap();
        let mut left: Vec<String> = fs::read_dir(&dir).unwrap().map(|e| e.unwrap().file_name().to_string_lossy().to_string()).collect();
        left.sort();
        assert_eq!(left.len(), 3, "{left:?}");
        assert_eq!(left.last().unwrap(), &format!("{}.jsonl", day_name(T0)));
        // Even too small for today's own file, today stays.
        rotate(&dir, T0, 14, 10).unwrap();
        assert_eq!(fs::read_dir(&dir).unwrap().count(), 1);
    }

    #[test]
    fn the_folder_is_cleaned_when_the_file_of_a_new_day_is_opened_and_not_otherwise() {
        let log = TrafficLog::new(&tmp("newday"), T0);
        let dir = log.dir.clone();
        for day in 1..=20u64 {
            append_lines(&dir, T0 - day * DAY_MS, &["old".into()]).unwrap();
        }
        // A line on a day whose file exists does not clean.
        append_lines(&dir, T0, &["first".into()]).unwrap();
        let before = fs::read_dir(&dir).unwrap().count();
        log.write(T0 + 1_000, &["second".into()]);
        assert_eq!(fs::read_dir(&dir).unwrap().count(), before);
        // The first line of the next day does.
        log.write(T0 + DAY_MS, &["new day".into()]);
        assert!(fs::read_dir(&dir).unwrap().count() <= 15);
    }

    #[test]
    fn counting_a_message_never_waits_for_the_disk() {
        // The counts are in memory behind a short lock, and the writing happens apart from them: a folder that cannot be written to
        // changes nothing for the counting, and the lines are only dropped.
        let log = TrafficLog::new(Path::new("/proc/this/cannot/be/written"), T0);
        let started = std::time::Instant::now();
        for n in 0..10_000u64 {
            log.sent(peer(3), &Msg::Ping { nonce: n }, 20, T0);
        }
        assert!(started.elapsed() < std::time::Duration::from_millis(500));
        log.write(T0, &["a line".into()]);
    }

    #[test]
    fn the_summary_adds_up_kinds_devices_and_hours() {
        let dir = tmp("summary");
        let log = TrafficLog::new(&dir, T0);
        let id = peer(4);
        log.sent(id, &Msg::Ping { nonce: 1 }, 20, T0 + 1);
        log.received(id, &Msg::Pong { nonce: 1 }, 18, T0 + 2);
        let lines = log.take_due(T0 + BUCKET_MS, &[LiveSample { peer: id, name: "Mac".into(), serial: 1, route: "lan", rtt_ms: 5, quic: Quic { tx_datagrams: 6, rx_datagrams: 4, ..Quic::default() } }]);
        append_lines(&dir, T0, &lines).unwrap();
        let summary = summarize(&dir, T0 - 1, T0 + 2 * BUCKET_MS);
        assert_eq!(summary.by_kind.iter().find(|k| k.kind == "Ping").map(|k| (k.sent_count, k.sent_bytes)), Some((1, 20)));
        assert_eq!(summary.by_device.len(), 1);
        assert_eq!((summary.by_device[0].name.as_str(), summary.by_device[0].packets_tx, summary.by_device[0].packets_rx), ("Mac", 6, 4));
        assert_eq!(summary.by_hour.len(), 24);
        assert_eq!(summary.by_hour.iter().map(|h| h.packets).sum::<u64>(), 10);
        // Later than the line: nothing in the totals.
        assert!(summarize(&dir, T0 + DAY_MS, T0 + 2 * DAY_MS).by_device.is_empty());
    }
}
