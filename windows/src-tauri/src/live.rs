//! The screen or the camera of a phone, in a window of its own. The core delivers the pictures as H.264 access units in
//! Annex B. They go on, untouched, to the window, where the browser engine of the window decodes them (WebCodecs) and draws
//! them. Where that engine cannot (the web view of Linux), the app decodes them itself (`video.rs`, the feature `native-video`)
//! and the window gets JPEG pictures instead. This file keeps the sessions, the window that belongs to each, and the way the
//! frames get there.

use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, LazyLock, Mutex, OnceLock};
use std::time::{Duration, Instant};

use serde_json::{json, Value};
use tandem_core::ffi::{
    TandemMediaAccept, TandemMediaCodec, TandemMediaEnd, TandemMediaFacing, TandemMediaKind, TandemMediaUpdate,
    TandemMediaViewer, TandemMediaWant,
};
use tauri::ipc::{Channel, InvokeResponseBody};
use tauri::{AppHandle, Emitter, Manager, State, WebviewUrl, WebviewWindowBuilder};

use crate::state::AppState;

type Reply<T> = Result<T, String>;

/// How many frames wait for a window that is still loading. The newest keyframe and what follows it is all that matters.
const BACKLOG: usize = 90;

struct Session {
    label: String,
    name: String,
    /// The device on the other side, so a page can say that its second screen is the one that is shown.
    peer: String,
    kind: &'static str,
    channel: Option<Channel<InvokeResponseBody>>,
    backlog: Vec<Vec<u8>>,
    accepted: Option<Value>,
    rotation: u16,
    /// What is shown is a computer (a remote desktop), not a phone.
    computer: bool,
    /// That computer is a Mac: Control stands for Command there, which is where the shortcuts of a Mac live.
    mac: bool,
    /// The decoder of this app, when the window cannot decode (the feature `native-video`).
    #[cfg(feature = "native-video")]
    decode: Option<std::sync::mpsc::SyncSender<Frame>>,
    /// Set when a frame was dropped because the decoder was behind: it waits for the next full picture.
    #[cfg_attr(not(feature = "native-video"), allow(dead_code))]
    lost: Arc<AtomicBool>,
    /// A second screen (see `display_start`): no window of this app, the pictures go to a program that shows them.
    display: bool,
    /// A second screen shown in a window of this app that fills the screen (Windows), not one made to fit its picture.
    extended: bool,
    player: Option<Player>,
    /// The Wayland output a second screen fills: the one whose size was asked for.
    output: Option<String>,
}

/// A program of its own that decodes and shows the pictures of a second screen: the window of this app cannot do that smoothly enough for
/// sixty pictures a second at the size of a screen (it makes a JPEG of each), and GStreamer decodes and draws straight to the screen.
struct Player {
    feed: std::sync::mpsc::SyncSender<Vec<u8>>,
    child: Arc<Mutex<Option<std::process::Child>>>,
    /// Nothing was sent yet, or a picture was dropped: only a keyframe may go next, or the decoder shows damage until the next one.
    waiting: bool,
    asked: Option<Instant>,
    counts: Arc<FeedCounts>,
}

/// What became of the pictures of a second screen, for the line in the log every few seconds.
#[derive(Default)]
struct FeedCounts {
    dropped: AtomicU64,
    skipped: AtomicU64,
    asked: AtomicU64,
}

impl Player {
    /// One picture from the core. True when a keyframe should be asked for.
    fn take(&mut self, data: Vec<u8>, keyframe: bool) -> bool {
        if keyframe {
            self.waiting = false;
        }
        if self.waiting {
            self.counts.skipped.fetch_add(1, Ordering::Relaxed);
            return self.ask();
        }
        if self.feed.try_send(data).is_err() {
            // GStreamer is behind. What follows refers to this picture, so nothing goes until a full one.
            self.counts.dropped.fetch_add(1, Ordering::Relaxed);
            self.waiting = true;
            return self.ask();
        }
        false
    }

    fn ask(&mut self) -> bool {
        let now = Instant::now();
        if self.asked.is_some_and(|at| now.duration_since(at) < Duration::from_millis(500)) {
            return false;
        }
        self.asked = Some(now);
        self.counts.asked.fetch_add(1, Ordering::Relaxed);
        true
    }
}

impl Drop for Player {
    fn drop(&mut self) {
        if let Some(mut child) = self.child.lock().unwrap().take() {
            let _ = child.kill();
            let _ = child.wait();
        }
    }
}

#[cfg(feature = "native-video")]
struct Frame {
    pts_us: u64,
    keyframe: bool,
    discontinuity: bool,
    data: Vec<u8>,
}

static SESSIONS: LazyLock<Mutex<HashMap<u64, Session>>> = LazyLock::new(|| Mutex::new(HashMap::new()));

/// One frame as the window gets it: a byte of flags (1 keyframe, 2 decoder to be flushed first), the time in microseconds
/// as eight bytes big endian, and the access unit.
fn pack(pts_us: u64, keyframe: bool, discontinuity: bool, data: Vec<u8>) -> Vec<u8> {
    let mut out = Vec::with_capacity(9 + data.len());
    out.push(u8::from(keyframe) | (u8::from(discontinuity) << 1));
    out.extend_from_slice(&pts_us.to_be_bytes());
    out.extend_from_slice(&data);
    out
}

/// A decoded picture as the window gets it: the flag 4, the time, and the JPEG.
#[cfg(feature = "native-video")]
fn pack_jpeg(pts_us: u64, jpeg: Vec<u8>) -> Vec<u8> {
    let mut out = Vec::with_capacity(9 + jpeg.len());
    out.push(4);
    out.extend_from_slice(&pts_us.to_be_bytes());
    out.extend_from_slice(&jpeg);
    out
}

/// Gives a packet to the window, or keeps it for the window that is still loading.
fn deliver(session: u64, packet: Vec<u8>, keyframe: bool) {
    let mut sessions = SESSIONS.lock().unwrap();
    let Some(s) = sessions.get_mut(&session) else { return };
    if let Some(channel) = &s.channel {
        if channel.send(InvokeResponseBody::Raw(packet)).is_err() {
            // The window is gone: nothing more to send it.
            s.channel = None;
        }
        return;
    }
    if keyframe {
        s.backlog.clear();
    }
    if s.backlog.len() < BACKLOG {
        s.backlog.push(packet);
    }
}

/// The thread that decodes the pictures of one session. A frame that cannot be decoded in time is dropped and the next
/// full picture is waited for, so the window never falls behind what the phone is showing.
#[cfg(feature = "native-video")]
fn spawn_decoder(app: &AppHandle, session: u64, lost: Arc<AtomicBool>) -> Option<std::sync::mpsc::SyncSender<Frame>> {
    use std::time::{Duration, Instant};
    let (tx, rx) = std::sync::mpsc::sync_channel::<Frame>(8);
    let app = app.clone();
    std::thread::Builder::new()
        .name("tandem-video".into())
        .spawn(move || {
            let Some(mut decoder) = crate::video::Decoder::new() else { return };
            let mut waiting = true;
            let mut asked = Instant::now() - Duration::from_secs(10);
            while let Ok(frame) = rx.recv() {
                if frame.discontinuity {
                    if let Some(fresh) = crate::video::Decoder::new() {
                        decoder = fresh;
                    }
                    waiting = true;
                }
                if lost.swap(false, Ordering::Relaxed) {
                    waiting = true;
                }
                if frame.keyframe {
                    waiting = false;
                }
                if waiting {
                    // Nothing to build on: ask for a full picture, not more than once in a while.
                    if asked.elapsed() > Duration::from_millis(700) {
                        asked = Instant::now();
                        if let Ok(engine) = app.state::<AppState>().engine() {
                            let _ = engine.media_request_keyframe(session);
                        }
                    }
                    continue;
                }
                if let Some(jpeg) = decoder.picture(&frame.data) {
                    // Only the newest picture is worth keeping for a window that is not there yet.
                    deliver(session, pack_jpeg(frame.pts_us, jpeg), true);
                }
            }
        })
        .ok()?;
    Some(tx)
}

pub struct Viewer {
    pub app: AppHandle,
}

impl Viewer {
    fn tell(&self, session: u64, event: &str, payload: Value) {
        let label = SESSIONS.lock().unwrap().get(&session).map(|s| s.label.clone());
        if let Some(label) = label {
            let _ = self.app.emit_to(label, event, payload);
        }
    }
}

impl TandemMediaViewer for Viewer {
    fn on_accepted(&self, session: u64, accept: TandemMediaAccept) {
        let state = json!({ "width": accept.width, "height": accept.height, "fps": accept.fps, "control": accept.control });
        let shows_itself = {
            let mut sessions = SESSIONS.lock().unwrap();
            let Some(s) = sessions.get_mut(&session) else { return };
            s.accepted = Some(state.clone());
            s.display
        };
        if shows_itself {
            // The program that shows the pictures starts now that there is something to show.
            let output = SESSIONS.lock().unwrap().get(&session).and_then(|s| s.output.clone());
            let player = start_player(&self.app, session, output.as_deref());
            let name = {
                let mut sessions = SESSIONS.lock().unwrap();
                match sessions.get_mut(&session) {
                    Some(s) => {
                        s.player = player;
                        s.name.clone()
                    }
                    None => return,
                }
            };
            announce_displays(&self.app);
            crate::events::say(&self.app, &crate::i18n::t1(&self.app, "display_on", &name));
            return;
        }
        self.tell(session, "live-accepted", state);
    }

    fn on_update(&self, session: u64, update: TandemMediaUpdate) {
        if let Some(rotation) = update.rotation {
            if let Some(s) = SESSIONS.lock().unwrap().get_mut(&session) {
                s.rotation = rotation;
            }
        }
        self.tell(session, "live-update", json!({ "width": update.width, "height": update.height, "rotation": update.rotation, "control": update.control }));
    }

    fn on_frame(&self, session: u64, pts_us: u64, keyframe: bool, discontinuity: bool, data: Vec<u8>) {
        {
            let mut sessions = SESSIONS.lock().unwrap();
            if let Some(s) = sessions.get_mut(&session).filter(|s| s.display) {
                // A second screen: straight to the program that shows it. When that is behind, the picture is dropped and a full one is
                // asked for, so what is shown never lags behind more than a few pictures.
                let ask = s.player.as_mut().is_some_and(|player| player.take(data, keyframe));
                drop(sessions);
                if ask {
                    if let Ok(engine) = self.app.state::<AppState>().engine() {
                        let _ = engine.media_request_keyframe(session);
                    }
                }
                let _ = (pts_us, discontinuity);
                return;
            }
        }
        #[cfg(feature = "native-video")]
        {
            let sessions = SESSIONS.lock().unwrap();
            if let Some(Some(decode)) = sessions.get(&session).map(|s| s.decode.as_ref()) {
                if decode.try_send(Frame { pts_us, keyframe, discontinuity, data }).is_err() {
                    // The decoder is behind: this picture is lost, and what follows it builds on it.
                    if let Some(s) = sessions.get(&session) {
                        s.lost.store(true, Ordering::Relaxed);
                    }
                }
                return;
            }
        }
        deliver(session, pack(pts_us, keyframe, discontinuity, data), keyframe);
    }

    fn on_ended(&self, session: u64, reason: TandemMediaEnd) {
        // The window goes with the session, whatever the reason: a grey window that says nothing useful is in the way. What went wrong,
        // when something did, is said in the main window and in a notification, with what there is to do about it.
        let Some(ended) = SESSIONS.lock().unwrap().remove(&session) else { return };
        if ended.display {
            announce_displays(&self.app);
        }
        let key = match reason {
            TandemMediaEnd::Declined => Some("live_end_declined"),
            TandemMediaEnd::Policy => Some("live_end_policy"),
            TandemMediaEnd::Unavailable => Some("live_end_unavailable"),
            TandemMediaEnd::Unsupported => Some("live_end_unsupported"),
            TandemMediaEnd::Timeout => Some("live_end_timeout"),
            TandemMediaEnd::Busy => Some("live_end_busy"),
            TandemMediaEnd::PeerGone => Some("live_end_lost"),
            _ => None,
        };
        if let Some(key) = key {
            let text = crate::i18n::t1(&self.app, key, &ended.name);
            crate::events::say(&self.app, &text);
            crate::events::toast(&self.app, &ended.name, &text);
        }
        if let Some(window) = self.app.get_webview_window(&ended.label) {
            let _ = window.destroy();
        }
    }
}

/// Asks a phone for its screen or its camera and opens the window that shows it.
#[tauri::command]
pub async fn live_start(app: AppHandle, id: String, kind: String, name: String, computer: Option<bool>, mac: Option<bool>) -> Reply<String> {
    let session = start(&app, id, kind == "camera", TandemMediaFacing::Any, name, computer.unwrap_or(false), false)?;
    if computer.unwrap_or(false) {
        if let Some(s) = SESSIONS.lock().unwrap().get_mut(&session) {
            s.computer = true;
            s.mac = mac.unwrap_or(false);
        }
    }
    Ok(session.to_string())
}

/// The request and the window. Also what a phone that starts the sharing itself ends up in.
pub fn start(app: &AppHandle, id: String, camera: bool, facing: TandemMediaFacing, name: String, computer: bool, extend: bool) -> Reply<u64> {
    let engine = app.state::<AppState>().engine()?;
    // A second screen is as many pixels as the screen it is shown on, lying as it is; anything else is what a window can usefully show.
    let ((extend_w, extend_h), (logical_w, logical_h), _) = if extend { screen_shape(app) } else { ((0, 0), (0, 0), None) };
    let want = TandemMediaWant {
        kind: if camera { TandemMediaKind::Camera } else { TandemMediaKind::Screen },
        codecs: vec![TandemMediaCodec::H264],
        // The whole screen as it is, up to what a big monitor has: scaled down the text of a screen turns soft.
        max_width: if extend { extend_w } else if camera { 1920 } else { 3840 },
        max_height: if extend { extend_h } else if camera { 1080 } else { 2160 },
        max_fps: if camera { 30 } else { 60 },
        max_bitrate: 0,
        // The picture of a phone can be clicked on from here, when the phone allows it (its accessibility service, see live.js).
        control: !camera,
        facing: if camera { facing } else { TandemMediaFacing::Any },
        extend,
        logical_width: logical_w,
        logical_height: logical_h,
    };
    let peer = id.clone();
    let session = engine.media_request(id, want).map_err(|e| e.to_string())?;
    let label = format!("live-{session}");
    let lost = Arc::new(AtomicBool::new(false));
    SESSIONS.lock().unwrap().insert(
        session,
        Session {
            label: label.clone(),
            name: name.clone(),
            peer,
            kind: if camera { "camera" } else { "screen" },
            channel: None,
            backlog: Vec::new(),
            accepted: None,
            rotation: 0,
            computer: false,
            mac: false,
            #[cfg(feature = "native-video")]
            decode: spawn_decoder(app, session, lost.clone()),
            lost,
            display: false,
            extended: extend,
            player: None,
            output: None,
        },
    );
    // The shape before the first picture says it (see `live_fit`): wide for a camera or the screen of a computer, tall for a phone.
    let (width, height) = if camera || computer { (860.0, 560.0) } else { (440.0, 820.0) };
    let built = WebviewWindowBuilder::new(app, &label, WebviewUrl::App(format!("live.html?session={session}").into()))
        .title(name)
        .inner_size(width, height)
        .min_inner_size(240.0, 240.0)
        // A second screen fills the screen it is on; Linux draws no frame of its own: the page does, over the picture (see ui/js/chrome.js).
        .fullscreen(extend)
        .decorations(cfg!(windows) && !extend)
        .build();
    if let Err(e) = built {
        SESSIONS.lock().unwrap().remove(&session);
        let _ = engine.media_stop(session);
        return Err(e.to_string());
    }
    Ok(session)
}

/// What `gst-inspect-1.0` says about an element (its properties among it), or nothing when GStreamer does not have it.
pub(crate) fn gst_inspect(element: &str) -> Option<String> {
    let mut command = std::process::Command::new("gst-inspect-1.0");
    tandem_winsys::system_env(&mut command);
    let out = command.arg(element).stderr(std::process::Stdio::null()).output().ok()?;
    out.status.success().then(|| String::from_utf8_lossy(&out.stdout).into_owned())
}

/// The elements of the pipeline that shows a second screen, found out once: every `gst-inspect-1.0` costs a moment, and the first
/// picture should not wait for them.
struct Gst {
    decoder: Option<String>,
    convert: String,
    sink: String,
    /// The sink can be told which output to fill.
    choose_output: bool,
}

fn gst() -> &'static Gst {
    static GST: OnceLock<Gst> = OnceLock::new();
    GST.get_or_init(|| {
        let wayland = std::env::var("XDG_SESSION_TYPE").is_ok_and(|s| s.eq_ignore_ascii_case("wayland")) || std::env::var_os("WAYLAND_DISPLAY").is_some();
        let threads = std::thread::available_parallelism().map(|n| n.get()).unwrap_or(2).clamp(1, 4);
        // To try another decoder on a real computer without a new build: `TANDEM_DISPLAY_DECODER=vah264dec`, say.
        let chosen = std::env::var("TANDEM_DISPLAY_DECODER").ok().filter(|d| !d.trim().is_empty());
        // The decoder of the processor and not the one of the graphics card: the second one would be woken for it on a laptop that has
        // two. Threads of its own per picture (what it does when the input is not live, and a pipe is not) hold a picture back for each
        // thread: three pictures late while things move, and the last ones of a movement only shown when the next pictures come, which on
        // a still screen is up to a second later. Threads per slice cost no delay.
        // Which one is best differs per computer: measured there (see `decoder_probe`), where the app can make a stream to try.
        #[cfg(all(target_os = "linux", feature = "screen-host"))]
        let measured = crate::decoder_probe::choice;
        #[cfg(not(all(target_os = "linux", feature = "screen-host")))]
        let measured = || None::<String>;
        let decoder = chosen.or_else(measured).or_else(|| match gst_inspect("avdec_h264") {
            Some(about) if about.contains("thread-type") => Some(format!("avdec_h264 thread-type=slice max-threads={threads}")),
            Some(_) => Some("avdec_h264 max-threads=1".into()),
            None => gst_inspect("openh264dec").map(|_| "openh264dec".into()),
        });
        // Turning the pictures of the decoder into what the screen takes is a pass over every pixel, which one thread does not do sixty
        // times a second at the size of a laptop screen.
        let convert = match gst_inspect("videoconvert") {
            Some(about) if about.contains("n-threads") => format!("videoconvert n-threads={threads}"),
            _ => "videoconvert".into(),
        };
        let wayland_sink = if wayland { gst_inspect("waylandsink") } else { None };
        let choose_output = wayland_sink.as_ref().is_some_and(|about| about.contains("fullscreen-output"));
        let sink = if wayland_sink.is_some() {
            "waylandsink fullscreen=true sync=false"
        } else if gst_inspect("glimagesink").is_some() {
            "glimagesink sync=false"
        } else {
            "autovideosink sync=false"
        };
        Gst { decoder, convert, sink: sink.into(), choose_output }
    })
}

/// What is written after every picture: an access unit delimiter, an SEI that holds nothing but a name (user data, which decoders skip),
/// and a start code. The parser of GStreamer only knows that a picture is complete when it has read two whole units of the next one, so
/// every picture waited for the next: on the laptop (GStreamer 1.28) 17 ms while things move, and the last picture of a movement on a
/// still screen until the next one came, half a second in the test. With this the parser hands a picture on in 0.1 ms. The next picture
/// goes without its own start code, so all that is written stays one valid stream: delimiter and SEI are the start of the next picture.
pub(crate) const AU_END: [u8; 34] = [
    0, 0, 0, 1, 0x09, 0xF0, //
    0, 0, 0, 1, 0x06, 0x05, 0x10, b'T', b'a', b'n', b'd', b'e', b'm', b'.', b's', b'c', b'r', b'e', b'e', b'n', b'.', b'v', b'1', 0x80, //
    0, 0, 0, 1,
];

/// The part of an access unit that is written to GStreamer; `AU_END` goes after it.
pub(crate) fn framed(data: &[u8], first: bool) -> &[u8] {
    let code = |d: &[u8]| if d.starts_with(&[0, 0, 0, 1]) { 4 } else if d.starts_with(&[0, 0, 1]) { 3 } else { 0 };
    let mut rest = data;
    // A delimiter of its own would make two in one picture.
    let at = code(rest);
    if at > 0 && rest.get(at).is_some_and(|nal| nal & 0x1F == 9) {
        rest = match rest[at..].windows(3).position(|w| w == [0, 0, 1]).map(|p| at + p) {
            Some(p) if rest[p - 1] == 0 => &rest[p - 1..],
            Some(p) => &rest[p..],
            None => &[],
        };
    }
    if !first {
        rest = &rest[code(rest)..];
    }
    rest
}

/// Starts the program that shows the pictures of a second screen, filling this computer's screen, and the thread that feeds it.
fn start_player(app: &AppHandle, session: u64, output: Option<&str>) -> Option<Player> {
    use std::io::{BufRead, Write};
    use std::process::{Command, Stdio};
    let gst = gst();
    let Some(decoder) = &gst.decoder else {
        log::warn!("display: no H.264 decoder for GStreamer");
        return None;
    };
    // No queue in front of the parser: one that lets go of bytes when it is full cuts pictures in half, and the decoder shows damage until
    // the next keyframe. Pictures are dropped whole, before the pipe (`Player::take`). The queue behind the decoder may drop decoded
    // pictures, and lets the decoder go on with the next one while the last is turned into colours and shown.
    // The screen whose size was asked for, when there are more than one.
    let fill = match output {
        Some(name) if gst.choose_output && !name.is_empty() && !name.contains(char::is_whitespace) => format!(" fullscreen-output={name}"),
        _ => String::new(),
    };
    let pipeline = format!(
        "-q fdsrc fd=0 blocksize=262144 ! h264parse ! {decoder} ! queue max-size-buffers=2 max-size-bytes=0 max-size-time=0 leaky=downstream ! {} ! {}{fill}",
        gst.convert, gst.sink
    );
    log::info!("display {session}: gst-launch-1.0 {pipeline}");
    let mut command = Command::new("gst-launch-1.0");
    tandem_winsys::system_env(&mut command);
    for part in pipeline.split_whitespace() {
        command.arg(part);
    }
    let mut child = command.stdin(Stdio::piped()).stdout(Stdio::null()).stderr(Stdio::piped()).spawn().ok()?;
    let mut stdin = child.stdin.take()?;
    // What GStreamer says when it cannot do it goes in the log: without it a second screen that stays black gives nothing to go on.
    if let Some(errors) = child.stderr.take() {
        let _ = std::thread::Builder::new().name("tandem-display-log".into()).spawn(move || {
            for line in std::io::BufReader::new(errors).lines().map_while(Result::ok).take(40) {
                log::warn!("display {session}: gstreamer: {line}");
            }
        });
    }
    // A few pictures at most: more is a delay that only grows while GStreamer is behind, and pictures are better dropped then.
    let (feed, rx) = std::sync::mpsc::sync_channel::<Vec<u8>>(4);
    let counts = Arc::new(FeedCounts::default());
    let seen = counts.clone();
    let app = app.clone();
    let started = Instant::now();
    std::thread::Builder::new()
        .name("tandem-display-feed".into())
        .spawn(move || {
            let mut first = true;
            let (mut pictures, mut bytes, mut longest, mut since) = (0u64, 0u64, Duration::ZERO, Instant::now());
            while let Ok(data) = rx.recv() {
                let picture = framed(&data, first);
                let at = Instant::now();
                if stdin.write_all(picture).and_then(|_| stdin.write_all(&AU_END)).is_err() {
                    break;
                }
                if first {
                    log::info!("display {session}: first picture written {} ms after GStreamer was started", started.elapsed().as_millis());
                }
                first = false;
                // A write that waits is GStreamer that has not taken what came before: its decoder or the screen is behind.
                longest = longest.max(at.elapsed());
                pictures += 1;
                bytes += (picture.len() + AU_END.len()) as u64;
                let span = since.elapsed();
                if span >= Duration::from_secs(10) {
                    log::info!(
                        "display {session}: {:.1} pictures a second, {:.1} Mbit/s, longest write {} ms; since the start {} dropped while GStreamer was behind, {} skipped until a keyframe, {} keyframes asked",
                        pictures as f64 / span.as_secs_f64(),
                        bytes as f64 * 8.0 / span.as_secs_f64() / 1e6,
                        longest.as_millis(),
                        seen.dropped.load(Ordering::Relaxed),
                        seen.skipped.load(Ordering::Relaxed),
                        seen.asked.load(Ordering::Relaxed),
                    );
                    (pictures, bytes, longest, since) = (0, 0, Duration::ZERO, Instant::now());
                }
            }
            // The program is gone (its window was closed) or the session is: either way the second screen is over.
            end_display(&app, session);
        })
        .ok()?;
    Some(Player { feed, child: Arc::new(Mutex::new(Some(child))), waiting: true, asked: None, counts })
}

/// The link of a session that is looked at got poor, or is good again: the window of it says so over the picture; a second screen has no window
/// of this app (Linux), so it is said in the main window.
pub fn quality(app: &AppHandle, session: u64, poor: bool) {
    let (label, name, windowed) = match SESSIONS.lock().unwrap().get(&session) {
        Some(s) => (s.label.clone(), s.name.clone(), !s.display),
        None => return,
    };
    if windowed {
        let _ = app.emit_to(label, "live-quality", json!({ "poor": poor }));
    } else if poor {
        crate::events::say(app, &crate::i18n::t1(app, "link_poor", &name));
    }
}

/// Tells the main window which second screens are being shown.
fn announce_displays(app: &AppHandle) {
    let list: Vec<Value> = SESSIONS
        .lock()
        .unwrap()
        .iter()
        .filter(|(_, s)| (s.display && s.player.is_some()) || s.extended)
        .map(|(id, s)| json!({ "session": id.to_string(), "name": s.name, "peer": s.peer }))
        .collect();
    let _ = app.emit("display", list);
}

/// Ends a second screen: the program that shows it goes, and the other computer is told.
fn end_display(app: &AppHandle, session: u64) {
    let removed = SESSIONS.lock().unwrap().remove(&session);
    if removed.is_none() {
        return;
    }
    drop(removed);
    // On Windows a second screen is a window of this app, which goes with it.
    if let Some(window) = app.get_webview_window(&format!("live-{session}")) {
        let _ = window.destroy();
    }
    if let Ok(engine) = app.state::<AppState>().engine() {
        let _ = engine.media_stop(session);
    }
    announce_displays(app);
}

/// The pixels of the screen the main window is on.
/// The screen the main window is on: its pixels and its logical size (what windows are laid out in), lying as a landscape screen does, as
/// a second screen is asked for. Windows says the pixels and the real scale. The toolkit of Linux says the logical size times a whole
/// scale, which for a screen at 125% or 150% is far more than its pixels (3072 by 1728 for 1920 by 1080 at 125%), so the pixels and the
/// real scale come from the desktop when it says them.
fn screen_shape(app: &AppHandle) -> ((u32, u32), (u32, u32), Option<String>) {
    let monitor = app
        .get_webview_window("main")
        .and_then(|w| w.current_monitor().ok().flatten())
        .or_else(|| app.primary_monitor().ok().flatten());
    let Some(monitor) = monitor else { return ((1920, 1080), (1920, 1080), None) };
    let scale = if monitor.scale_factor().is_finite() && monitor.scale_factor() >= 0.5 { monitor.scale_factor() } else { 1.0 };
    let (size, position) = (monitor.size(), monitor.position());
    let logical = ((f64::from(size.width) / scale).round() as u32, (f64::from(size.height) / scale).round() as u32);
    let mut shape = ((size.width, size.height), logical);
    let mut output = None;
    if cfg!(target_os = "linux") {
        let at = ((f64::from(position.x) / scale).round() as i32, (f64::from(position.y) / scale).round() as i32);
        if let Some(found) = tandem_winsys::pick_monitor(&tandem_winsys::monitors(), at, (position.x, position.y), logical) {
            shape = (found.pixels, found.logical());
            output = Some(found.connector);
        }
    }
    log::info!(
        "second screen: {} is {}x{} pixels, {}x{} logical (the toolkit says {}x{} at scale {scale})",
        output.as_deref().unwrap_or("this screen"),
        shape.0.0,
        shape.0.1,
        shape.1.0,
        shape.1.1,
        size.width,
        size.height
    );
    let (pixels, logical) = lying(shape);
    (pixels, logical, output)
}

fn lying(((pw, ph), (lw, lh)): ((u32, u32), (u32, u32))) -> ((u32, u32), (u32, u32)) {
    if pw < ph { ((ph, pw), (lh, lw)) } else { ((pw, ph), (lw, lh)) }
}

/// Asks a computer for a screen of its own, as big as this one, and shows it over this one when it is made. On Windows that is a window of
/// this app that fills the screen (its webview decodes video in hardware); elsewhere a GStreamer window does.
#[tauri::command]
pub async fn display_start(app: AppHandle, id: String, name: String, mac: Option<bool>) -> Reply<String> {
    display_open(&app, id, name, mac.unwrap_or(false))
}

/// What `display_start` does, also for a computer whose person asked this one to be a second screen (`TandemEvent::MediaOffered`).
pub fn display_open(app: &AppHandle, id: String, name: String, mac: bool) -> Reply<String> {
    let app = app.clone();
    if cfg!(windows) {
        let session = start(&app, id, false, TandemMediaFacing::Any, name, true, true)?;
        if let Some(s) = SESSIONS.lock().unwrap().get_mut(&session) {
            s.computer = true;
            s.mac = mac;
        }
        return Ok(session.to_string());
    }
    let engine = app.state::<AppState>().engine()?;
    // What the player needs is looked up while the other computer makes its screen, not after.
    let _ = std::thread::Builder::new().name("tandem-display-probe".into()).spawn(|| {
        gst();
    });
    let ((width, height), (logical_width, logical_height), output) = screen_shape(&app);
    let want = TandemMediaWant {
        kind: TandemMediaKind::Screen,
        codecs: vec![TandemMediaCodec::H264],
        max_width: width,
        max_height: height,
        max_fps: 60,
        max_bitrate: 0,
        // The mouse of this computer is not over the picture (it is a window of another program), so there is nothing to control from here:
        // what moves on the screen is moved with the mouse of the other computer.
        control: false,
        facing: TandemMediaFacing::Any,
        extend: true,
        logical_width,
        logical_height,
    };
    let peer = id.clone();
    let session = engine.media_request(id, want).map_err(|e| e.to_string())?;
    SESSIONS.lock().unwrap().insert(
        session,
        Session {
            label: format!("display-{session}"),
            name,
            peer,
            kind: "screen",
            channel: None,
            backlog: Vec::new(),
            accepted: None,
            rotation: 0,
            computer: true,
            mac: false,
            #[cfg(feature = "native-video")]
            decode: None,
            lost: Arc::new(AtomicBool::new(false)),
            display: true,
            extended: false,
            player: None,
            output,
        },
    );
    Ok(session.to_string())
}

/// Asks another device to be a second screen of this computer: this one makes the screen, that one is told to show it.
#[tauri::command]
pub fn display_offer(app: AppHandle, id: String) -> Reply<()> {
    #[cfg(feature = "screen-host")]
    {
        crate::host::offer_extend(&app, &id)
    }
    #[cfg(not(feature = "screen-host"))]
    {
        let _ = (app, id);
        Err("this computer cannot make a screen".to_string())
    }
}

/// Stops the screen that this computer made for one device (see `display_offer`).
#[tauri::command]
pub fn display_offer_stop(app: AppHandle, id: String) {
    #[cfg(feature = "screen-host")]
    crate::host::stop_peer(&app, &id);
    #[cfg(not(feature = "screen-host"))]
    let _ = (app, id);
}

/// Ends every second screen that is shown here.
#[tauri::command]
pub fn display_stop(app: AppHandle) {
    let shown: Vec<u64> = SESSIONS.lock().unwrap().iter().filter(|(_, s)| s.display || s.extended).map(|(id, _)| *id).collect();
    for session in shown {
        end_display(&app, session);
    }
}

/// The second screens that are shown here now.
#[tauri::command]
pub fn display_state() -> Vec<Value> {
    SESSIONS
        .lock()
        .unwrap()
        .iter()
        .filter(|(_, s)| (s.display && s.player.is_some()) || s.extended)
        .map(|(id, s)| json!({ "session": id.to_string(), "name": s.name, "peer": s.peer }))
        .collect()
}

/// The number of a session as the windows say it. It is a random 64 bit number, and a number in a web page keeps 53 bits of it, so
/// the windows hold it as text: a session whose number was rounded on the way is a session that "is over".
fn session_of(text: &str) -> Reply<u64> {
    text.trim().parse::<u64>().map_err(|_| "that is not a session".to_string())
}

/// The first picture is there: the window takes the shape of what it shows (a computer is wide, a phone is tall), as big as is
/// comfortable on this screen, so the picture does not sit in the middle of a black window.
#[tauri::command]
pub fn live_fit(app: AppHandle, session: String, width: f64, height: f64) {
    let Ok(session) = session_of(&session) else { return };
    if width < 2.0 || height < 2.0 {
        return;
    }
    // A second screen fills the screen as it is.
    if SESSIONS.lock().unwrap().get(&session).is_some_and(|s| s.extended) {
        return;
    }
    let Some(window) = app.get_webview_window(&format!("live-{session}")) else { return };
    let (max_w, max_h) = window
        .current_monitor()
        .ok()
        .flatten()
        .map(|m| (m.size().width as f64 / m.scale_factor() * 0.85, m.size().height as f64 / m.scale_factor() * 0.85))
        .unwrap_or((1200.0, 800.0));
    let aspect = width / height;
    let (mut w, mut h) = if aspect >= 1.0 { (1000.0, 1000.0 / aspect) } else { (440.0, 440.0 / aspect) };
    if h > max_h {
        h = max_h;
        w = h * aspect;
    }
    if w > max_w {
        w = max_w;
        h = w / aspect;
    }
    let _ = window.set_size(tauri::LogicalSize::new(w.round().max(240.0), h.round().max(240.0)));
}

/// The window is ready for frames. What it missed while it loaded is sent first, from the newest keyframe on.
#[tauri::command]
pub fn live_attach(session: String, on_frame: Channel<InvokeResponseBody>) -> Reply<Value> {
    let session = session_of(&session)?;
    let mut sessions = SESSIONS.lock().unwrap();
    let s = sessions.get_mut(&session).ok_or("this session is over")?;
    for packet in std::mem::take(&mut s.backlog) {
        let _ = on_frame.send(InvokeResponseBody::Raw(packet));
    }
    s.channel = Some(on_frame);
    Ok(json!({
        "name": s.name, "kind": s.kind, "accepted": s.accepted, "rotation": s.rotation,
        "native": cfg!(feature = "native-video"), "platform": if cfg!(windows) { "windows" } else { "linux" }, "computer": s.computer, "mac": s.mac,
    }))
}

/// A click, a key or a scroll on the picture, for the phone. The window sends them as small objects: `pointer` (x and y as
/// fractions of the picture), `button`, `scroll`, `key` (a USB HID usage, with the modifiers held: shift 1, control 2, alt 4, meta 8) and `text`.
#[tauri::command]
pub fn live_input(state: State<'_, AppState>, session: String, input: Value) -> Reply<()> {
    use tandem_core::ffi::TandemMediaInput as Input;
    let session = session_of(&session)?;
    let number = |name: &str| input[name].as_f64().unwrap_or(0.0);
    let event = match input["t"].as_str().unwrap_or_default() {
        "pointer" => Input::PointerAbs { x: number("x").clamp(0.0, 1.0) as f32, y: number("y").clamp(0.0, 1.0) as f32 },
        "button" => Input::Button {
            button: number("button").clamp(0.0, 4.0) as u8,
            down: input["down"].as_bool().unwrap_or(false),
            clicks: number("clicks").clamp(1.0, 3.0) as u8,
        },
        "scroll" => Input::Scroll { dx: number("dx").clamp(-2000.0, 2000.0) as i16, dy: number("dy").clamp(-2000.0, 2000.0) as i16 },
        "key" => Input::Key {
            code: number("code") as u32,
            down: input["down"].as_bool().unwrap_or(true),
            mods: number("mods").clamp(0.0, 15.0) as u16,
            text: String::new(),
        },
        "text" => Input::Text { text: input["text"].as_str().unwrap_or_default().chars().take(200).collect() },
        _ => return Err("not an input".into()),
    };
    state.engine()?.media_send_input(session, event).map_err(|e| e.to_string())
}

/// The decoder lost the thread of the picture.
#[tauri::command]
pub fn live_keyframe(state: State<'_, AppState>, session: String) {
    let Ok(session) = session_of(&session) else { return };
    if let Ok(engine) = state.engine() {
        let _ = engine.media_request_keyframe(session);
    }
}

/// The window is closing or the person pressed stop.
#[tauri::command]
pub fn live_stop(app: AppHandle, state: State<'_, AppState>, session: String) {
    let Ok(session) = session_of(&session) else { return };
    SESSIONS.lock().unwrap().remove(&session);
    announce_displays(&app);
    // The window goes with the session, wherever the stop came from, also when the session was gone already.
    if let Some(window) = app.get_webview_window(&format!("live-{session}")) {
        let _ = window.destroy();
    }
    if let Ok(engine) = state.engine() {
        let _ = engine.media_stop(session);
    }
}

/// Keeps the window above the others, or lets it go.
#[tauri::command]
pub fn live_pin(app: AppHandle, session: String, on: bool) {
    let Ok(session) = session_of(&session) else { return };
    if let Some(window) = app.get_webview_window(&format!("live-{session}")) {
        let _ = window.set_always_on_top(on);
    }
}

/// What this computer lets another device do: show the screen and use the mouse and keyboard, each as `ask`, `always` or `never`.
#[tauri::command]
pub fn media_policy(state: State<'_, AppState>, id: String) -> Reply<Value> {
    let policy = state.engine()?.media_policy(id).map_err(|e| e.to_string())?;
    Ok(json!({ "screen": permission_text(policy.screen), "control": permission_text(policy.control) }))
}

#[tauri::command]
pub fn media_policy_set(state: State<'_, AppState>, id: String, screen: Option<String>, control: Option<String>) -> Reply<Value> {
    let engine = state.engine()?;
    let mut policy = engine.media_policy(id.clone()).map_err(|e| e.to_string())?;
    if let Some(text) = screen {
        policy.screen = permission_of(&text);
    }
    if let Some(text) = control {
        policy.control = permission_of(&text);
    }
    engine.set_media_policy(id, policy.clone()).map_err(|e| e.to_string())?;
    Ok(json!({ "screen": permission_text(policy.screen), "control": permission_text(policy.control) }))
}

fn permission_text(p: tandem_core::ffi::TandemMediaPermission) -> &'static str {
    use tandem_core::ffi::TandemMediaPermission as P;
    match p {
        P::Ask => "ask",
        P::Always => "always",
        P::Never => "never",
    }
}

fn permission_of(text: &str) -> tandem_core::ffi::TandemMediaPermission {
    use tandem_core::ffi::TandemMediaPermission as P;
    match text {
        "always" => P::Always,
        "never" => P::Never,
        _ => P::Ask,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_session_number_survives_the_trip_as_text() {
        // A number this big loses its last digits in a web page, which is how a session came to be "over" the moment it started.
        let big = u64::MAX - 12;
        assert_eq!(session_of(&big.to_string()), Ok(big));
        assert_eq!(session_of(" 42 "), Ok(42));
        assert!(session_of("not a number").is_err());
        assert!(session_of("").is_err());
    }

    /// The NAL units of an Annex B stream, by type and payload.
    fn nals(stream: &[u8]) -> Vec<(u8, Vec<u8>)> {
        let mut starts = Vec::new();
        let mut i = 0;
        while i + 3 <= stream.len() {
            if stream[i..i + 3] == [0, 0, 1] {
                starts.push(i + 3);
                i += 3;
            } else {
                i += 1;
            }
        }
        starts
            .iter()
            .enumerate()
            .filter_map(|(n, &start)| {
                let mut end = starts.get(n + 1).map_or(stream.len(), |next| next - 3);
                while end > start && stream[end - 1] == 0 {
                    end -= 1;
                }
                (end > start).then(|| (stream[start] & 0x1F, stream[start..end].to_vec()))
            })
            .collect()
    }

    #[test]
    fn what_goes_to_gstreamer_is_one_stream_with_a_delimiter_after_every_picture() {
        let key = [&[0, 0, 0, 1, 0x67, 0x64, 0x00, 0x2A][..], &[0, 0, 0, 1, 0x68, 0xEE, 0x3C], &[0, 0, 0, 1, 0x65, 0x88, 0x84, 0x21]].concat();
        let delta = vec![0, 0, 0, 1, 0x41, 0x9A, 0x22];
        // A picture with a delimiter of its own and short start codes, as another encoder may write it.
        let other = [&[0, 0, 1, 0x09, 0x10][..], &[0, 0, 1, 0x41, 0x9A, 0x33]].concat();
        let mut stream = Vec::new();
        for (n, unit) in [&key, &delta, &other].into_iter().enumerate() {
            stream.extend_from_slice(framed(unit, n == 0));
            stream.extend_from_slice(&AU_END);
        }
        let types: Vec<u8> = nals(&stream).iter().map(|(t, _)| *t).collect();
        assert_eq!(types, vec![7, 8, 5, 9, 6, 1, 9, 6, 1, 9, 6]);
        let payloads = nals(&stream);
        assert_eq!(payloads[2].1, vec![0x65, 0x88, 0x84, 0x21]);
        assert_eq!(payloads[5].1, vec![0x41, 0x9A, 0x22]);
        assert_eq!(payloads[8].1, vec![0x41, 0x9A, 0x33]);
        // The SEI is user data (type 5) of 16 bytes and ends in its stop bit: a valid message that a decoder passes over.
        assert_eq!(&payloads[4].1[..3], &[0x06, 0x05, 0x10]);
        assert_eq!(payloads[4].1.len(), 3 + 16 + 1);
        assert_eq!(payloads[4].1.last(), Some(&0x80));
        // What is written last ends in a start code, which the next picture continues.
        assert!(stream.ends_with(&[0x80, 0, 0, 0, 1]));
        assert!(stream.starts_with(&[0, 0, 0, 1, 0x67]));
    }

    #[test]
    fn a_screen_on_its_side_is_asked_for_lying() {
        assert_eq!(lying(((1800, 2880), (1200, 1920))), ((2880, 1800), (1920, 1200)));
        assert_eq!(lying(((2880, 1800), (1920, 1200))), ((2880, 1800), (1920, 1200)));
    }
}
