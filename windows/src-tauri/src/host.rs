//! This computer as a screen that other devices can look at and, when it is allowed, use: the part of the app that works like a
//! remote desktop. A device asks for the screen; the person here is asked (or has said "always" for that device); then the screen is
//! photographed a few times a second, made into H.264 and sent, and the mouse and keyboard of the viewer are played back here
//! through the same code that plays a phone's trackpad. The viewers are the Mac, the phone, Windows and Linux.
//!
//! The picture comes from the screen grabber of the system layer (GDI on Windows, the X server on Linux, the portal and GStreamer on
//! Wayland) and is encoded in software (OpenH264), so it needs no graphics card and works the same everywhere.

use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use openh264::encoder::{BitRate, Complexity, Encoder, EncoderConfig, FrameRate, FrameType, RateControlMode, UsageType};
use openh264::formats::YUVSource;
use openh264::OpenH264API;
use serde_json::json;
use tandem_core::ffi::{
    TandemMediaAccept, TandemMediaCodec, TandemMediaEnd, TandemMediaHost, TandemMediaInput, TandemMediaKind, TandemMediaPermission,
    TandemMediaPush, TandemMediaRequest,
};
use tandem_winsys::Grabber;
use tauri::{AppHandle, Emitter, Manager};
use tauri_plugin_dialog::{DialogExt, MessageDialogButtons, MessageDialogResult};

use crate::state::AppState;
use crate::{events, i18n, input};

/// The pictures a second that are tried for first, when the viewer does not say. The loop then goes as high as the computer manages (see
/// `capture_loop`), up to what the viewer asked for.
const FPS: u32 = 30;

/// The most that is ever asked for: more than a viewer can show and more than software can encode.
const MAX_FPS: u32 = 60;

/// A starting bitrate for a picture of this size at this rate: enough for text to stay sharp when little moves, without asking more of
/// a network than it has.
fn starting_bitrate(width: u32, height: u32, fps: u32) -> u32 {
    let wanted = 0.09 * f64::from(width) * f64::from(height) * f64::from(fps.max(1));
    (wanted as u32).clamp(3_000_000, 14_000_000)
}

const MAX_BITRATE: u32 = 20_000_000;

/// Whether this system can show its screen at all (Windows, and Linux under X11).
pub fn available() -> bool {
    // Asked once: on Linux it opens a connection to the X server, and the state is asked for each time a window loads.
    static AVAILABLE: std::sync::OnceLock<bool> = std::sync::OnceLock::new();
    *AVAILABLE.get_or_init(Grabber::available)
}

/// Whether the viewers may also use the mouse and keyboard here.
pub fn control_available() -> bool {
    crate::commands::input_blocked().is_none()
}

struct Sharing {
    stop: Arc<AtomicBool>,
    keyframe: Arc<AtomicBool>,
    bitrate: Arc<AtomicU32>,
    peer: String,
    control: bool,
}

type Sessions = Arc<Mutex<HashMap<u64, Sharing>>>;

/// The list of who is looking now, for the banner.
fn announce(app: &AppHandle, sessions: &Mutex<HashMap<u64, Sharing>>) {
    let list: Vec<_> = sessions
        .lock()
        .unwrap()
        .iter()
        .map(|(session, s)| json!({ "session": session.to_string(), "peer": s.peer, "name": events::device_name(app, &s.peer), "control": s.control }))
        .collect();
    let _ = app.emit("hosting", list);
}

pub struct Host {
    pub app: AppHandle,
    sessions: Sessions,
}

impl Host {
    pub fn new(app: AppHandle) -> Host {
        Host { app, sessions: Arc::new(Mutex::new(HashMap::new())) }
    }

    fn engine(&self) -> Option<Arc<tandem_core::ffi::TandemEngine>> {
        self.app.state::<AppState>().engine().ok()
    }

    /// Tells the windows who is looking at this screen, so they can say so and offer to stop it.
    fn announce(&self) {
        announce(&self.app, &self.sessions);
    }

    fn start(&self, from: String, request: &TandemMediaRequest) {
        let Some(engine) = self.engine() else { return };
        let session = request.session;
        let control = request.control && control_available();
        // A viewer that is a computer shows its own pointer over the picture: the pointer of this one in the picture would be a second one,
        // a little behind. A phone has no pointer over the picture, so it keeps this one.
        let viewer_is_computer = engine.devices().iter().any(|d| {
            d.id == from && matches!(d.platform, tandem_core::ffi::TandemPlatform::MacOs | tandem_core::ffi::TandemPlatform::Windows | tandem_core::ffi::TandemPlatform::Linux)
        });
        let wanted_fps = if request.max_fps == 0 { FPS } else { request.max_fps.clamp(10, MAX_FPS) };
        // On Wayland the desktop asks the person here (once, and then it remembers), so this waits for the answer. The pictures come as the
        // planes the encoder wants, at the size that is sent and at most as often as is wanted: all that work is done in one place that is
        // good at it, and nothing is thrown away after it was made.
        let Some(grabber) = Grabber::with_options(tandem_winsys::GrabOptions {
            max: (request.max_width, request.max_height),
            cursor: !(control && viewer_is_computer),
            fps: wanted_fps,
            i420: true,
        }) else {
            let _ = engine.media_deny(session, TandemMediaEnd::Unavailable);
            return;
        };
        let (source_w, source_h) = grabber.size();
        if source_w < 64 || source_h < 64 {
            let _ = engine.media_deny(session, TandemMediaEnd::Unavailable);
            return;
        }
        let (width, height) = target_size(source_w, source_h, request.max_width, request.max_height);
        let fps = wanted_fps;
        let bitrate = if request.max_bitrate > 0 { request.max_bitrate.min(MAX_BITRATE) } else { starting_bitrate(width, height, fps) };
        let answer = TandemMediaAccept { codec: TandemMediaCodec::H264, width, height, fps, bitrate, control };
        if engine.media_accept(session, answer).is_err() {
            return;
        }
        let sharing = Sharing {
            stop: Arc::new(AtomicBool::new(false)),
            keyframe: Arc::new(AtomicBool::new(true)),
            bitrate: Arc::new(AtomicU32::new(bitrate)),
            peer: from.clone(),
            control,
        };
        let (stop, keyframe, rate) = (sharing.stop.clone(), sharing.keyframe.clone(), sharing.bitrate.clone());
        self.sessions.lock().unwrap().insert(session, sharing);
        self.announce();
        events::toast(&self.app, &i18n::t(&self.app, "host_started_title"), &i18n::t1(&self.app, "host_started_body", &events::device_name(&self.app, &from)));
        let sessions = self.sessions.clone();
        let app = self.app.clone();
        std::thread::Builder::new()
            .name("tandem-screen".into())
            .spawn(move || {
                capture_loop(engine, session, grabber, (width, height), fps, stop, keyframe, rate);
                sessions.lock().unwrap().remove(&session);
                // What is left, not an empty list: another viewer may still be looking.
                announce(&app, &sessions);
            })
            .ok();
    }
}

/// The question as a notification with three buttons (Linux). `Some((allowed, remembered))` once it was answered, or turned down for lack of
/// an answer; `None` when the desktop has no notifications to ask it with.
#[cfg(target_os = "linux")]
fn ask_by_notification(title: &str, body: &str, allow: &str, always: &str, deny: &str) -> Option<(bool, bool)> {
    let (tx, rx) = std::sync::mpsc::channel::<String>();
    let buttons = [("allow", allow), ("always", always), ("deny", deny)];
    let number = tandem_winsys::notify::ask(title, body, &buttons, move |key| {
        let _ = tx.send(key.to_string());
    })?;
    let key = rx.recv_timeout(std::time::Duration::from_secs(120)).ok();
    tandem_winsys::notify::close(number);
    Some(match key.as_deref() {
        Some("allow") => (true, false),
        Some("always") => (true, true),
        _ => (false, false),
    })
}

#[cfg(not(target_os = "linux"))]
fn ask_by_notification(_title: &str, _body: &str, _allow: &str, _always: &str, _deny: &str) -> Option<(bool, bool)> {
    None
}

impl TandemMediaHost for Host {
    fn on_request(&self, from: String, request: TandemMediaRequest, pre_approved: bool) {
        let Some(engine) = self.engine() else { return };
        if request.kind != TandemMediaKind::Screen || !request.codecs.contains(&TandemMediaCodec::H264) {
            let _ = engine.media_deny(request.session, TandemMediaEnd::Unsupported);
            return;
        }
        // The person turned showing the screen off: every device is turned away, whatever it was allowed.
        if !crate::settings::get(&self.app).screen_host {
            let _ = engine.media_deny(request.session, TandemMediaEnd::Policy);
            return;
        }
        if !available() {
            let _ = engine.media_deny(request.session, TandemMediaEnd::Unavailable);
            return;
        }
        if pre_approved {
            self.start(from, &request);
            return;
        }
        // The question waits for an answer, so it is asked off the thread of the engine.
        let (app, sessions) = (self.app.clone(), self.sessions.clone());
        let this = Host { app, sessions };
        std::thread::spawn(move || {
            let name = events::device_name(&this.app, &from);
            let wants_control = request.control && control_available();
            let body = if wants_control { i18n::t(&this.app, "host_ask_control") } else { i18n::t(&this.app, "host_ask_view") };
            let (allow, always, deny) = (i18n::t(&this.app, "host_allow"), i18n::t(&this.app, "host_always"), i18n::t(&this.app, "host_deny"));
            let title = i18n::t1(&this.app, "host_ask_title", &name);
            // Where a window cannot be put in front (Wayland) the question is a notification with the buttons, which is seen; the window
            // of the question is for a desktop that has no notifications to offer.
            let (allowed, remembered) = match ask_by_notification(&title, &body, &allow, &always, &deny) {
                Some(decision) => decision,
                None => {
                    let answer = this
                        .app
                        .dialog()
                        .message(body)
                        .title(title)
                        .buttons(MessageDialogButtons::YesNoCancelCustom(allow.clone(), always.clone(), deny))
                        .blocking_show_with_result();
                    let remembered = match &answer {
                        MessageDialogResult::Custom(label) => *label == always,
                        MessageDialogResult::No => true,
                        _ => false,
                    };
                    let allowed = remembered
                        || match &answer {
                            MessageDialogResult::Custom(label) => *label == allow,
                            MessageDialogResult::Yes | MessageDialogResult::Ok => true,
                            _ => false,
                        };
                    (allowed, remembered)
                }
            };
            // "Always": this device asks no more, for the screen and for the mouse and keyboard.
            if remembered {
                if let Some(engine) = this.engine() {
                    if let Ok(mut policy) = engine.media_policy(from.clone()) {
                        policy.screen = TandemMediaPermission::Always;
                        if wants_control {
                            policy.control = TandemMediaPermission::Always;
                        }
                        let _ = engine.set_media_policy(from.clone(), policy);
                    }
                }
            }
            if allowed {
                this.start(from, &request);
            } else if let Some(engine) = this.engine() {
                let _ = engine.media_deny(request.session, TandemMediaEnd::Declined);
            }
        });
    }

    fn on_keyframe(&self, session: u64) {
        if let Some(s) = self.sessions.lock().unwrap().get(&session) {
            s.keyframe.store(true, Ordering::Relaxed);
        }
    }

    fn on_bitrate(&self, session: u64, bits_per_second: u32) {
        if let Some(s) = self.sessions.lock().unwrap().get(&session) {
            s.bitrate.store(bits_per_second.clamp(300_000, MAX_BITRATE), Ordering::Relaxed);
        }
    }

    fn on_input(&self, session: u64, event: TandemMediaInput) {
        // Only what the person allowed gets through: the core drops input when control was refused, and this is the second lock.
        let allowed = self.sessions.lock().unwrap().get(&session).is_some_and(|s| s.control);
        if allowed {
            input::send_media(event);
        }
    }

    fn on_stop(&self, session: u64, _reason: TandemMediaEnd) {
        if let Some(s) = self.sessions.lock().unwrap().remove(&session) {
            s.stop.store(true, Ordering::Relaxed);
            input::release_all();
        }
        self.announce();
    }
}

/// Stops everything that is shown, for the button in the window.
#[tauri::command]
pub fn host_stop(app: AppHandle) {
    stop_all(&app);
}

pub fn stop_all(app: &AppHandle) {
    if let Ok(engine) = app.state::<AppState>().engine() {
        for session in engine.media_sessions() {
            if session.role == tandem_core::ffi::TandemMediaRole::Host {
                let _ = engine.media_stop(session.session);
            }
        }
    }
}

/// The size of the picture that is sent: the screen, made smaller to fit in what the viewer asked for, and even in both directions,
/// which H.264 wants.
pub fn target_size(source_w: u32, source_h: u32, max_w: u32, max_h: u32) -> (u32, u32) {
    let max_w = if max_w == 0 { source_w } else { max_w };
    let max_h = if max_h == 0 { source_h } else { max_h };
    let scale = (f64::from(max_w) / f64::from(source_w)).min(f64::from(max_h) / f64::from(source_h)).min(1.0);
    let even = |n: f64| ((n as u32).max(16)) & !1;
    (even(f64::from(source_w) * scale), even(f64::from(source_h) * scale))
}

/// Pixels as B, G, R, unused, at one size, turned into the planes of I420 at another, by taking the nearest pixel. The weights are the
/// usual ones for video of this kind (BT.601, limited range). The rows are shared out over a few threads: this is the part of the work
/// that a loop does badly, and for a screen of two megapixels it is the difference between thirty pictures a second and fifteen.
pub fn bgra_to_i420(bgra: &[u8], source_w: usize, source_h: usize, width: usize, height: usize, out: &mut Vec<u8>) {
    out.clear();
    out.resize(width * height * 3 / 2, 0);
    let (luma, chroma) = out.split_at_mut(width * height);
    let (u_plane, v_plane) = chroma.split_at_mut(width * height / 4);
    // Where each column and each row of the picture comes from, worked out once instead of for every pixel.
    let columns: Vec<usize> = (0..width).map(|x| (x * source_w / width).min(source_w - 1) * 4).collect();
    let rows: Vec<usize> = (0..height).map(|y| (y * source_h / height).min(source_h - 1) * source_w * 4).collect();
    let threads = std::thread::available_parallelism().map(|n| n.get()).unwrap_or(2).clamp(1, 4);
    // Whole pairs of rows go to a thread, because a row of chroma is for two rows of luma.
    let pairs_per_thread = (height / 2).div_ceil(threads).max(1);
    std::thread::scope(|scope| {
        let (mut luma_rest, mut u_rest, mut v_rest) = (luma, u_plane, v_plane);
        let mut first = 0usize;
        while first < height {
            let count = (pairs_per_thread * 2).min(height - first);
            let (luma_part, luma_next) = luma_rest.split_at_mut(count * width);
            let (u_part, u_next) = u_rest.split_at_mut(count / 2 * (width / 2));
            let (v_part, v_next) = v_rest.split_at_mut(count / 2 * (width / 2));
            luma_rest = luma_next;
            u_rest = u_next;
            v_rest = v_next;
            let (columns, rows) = (&columns, &rows);
            scope.spawn(move || {
                for (n, line) in luma_part.chunks_exact_mut(width).enumerate() {
                    let row = rows[first + n];
                    for (x, out_pixel) in line.iter_mut().enumerate() {
                        let at = row + columns[x];
                        let (b, g, r) = (i32::from(bgra[at]), i32::from(bgra[at + 1]), i32::from(bgra[at + 2]));
                        *out_pixel = (((66 * r + 129 * g + 25 * b + 128) >> 8) + 16) as u8;
                    }
                }
                for y in 0..count / 2 {
                    let row = rows[first + y * 2];
                    for x in 0..width / 2 {
                        // The colour of a block of two by two is taken at its top left pixel, which is as good as the average for a screen.
                        let at = row + columns[x * 2];
                        let (b, g, r) = (i32::from(bgra[at]), i32::from(bgra[at + 1]), i32::from(bgra[at + 2]));
                        u_part[y * (width / 2) + x] = (((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128).clamp(0, 255) as u8;
                        v_part[y * (width / 2) + x] = (((112 * r - 94 * g - 18 * b + 128) >> 8) + 128).clamp(0, 255) as u8;
                    }
                }
            });
            first += count;
        }
    });
}

/// The planes of an I420 picture that is already made (one block of bytes: luma, then U, then V), for the encoder, without a copy.
struct Planes<'a> {
    width: usize,
    height: usize,
    data: &'a [u8],
}

impl YUVSource for Planes<'_> {
    fn dimensions(&self) -> (usize, usize) {
        (self.width, self.height)
    }

    fn strides(&self) -> (usize, usize, usize) {
        (self.width, self.width / 2, self.width / 2)
    }

    fn y(&self) -> &[u8] {
        &self.data[..self.width * self.height]
    }

    fn u(&self) -> &[u8] {
        let luma = self.width * self.height;
        &self.data[luma..luma + luma / 4]
    }

    fn v(&self) -> &[u8] {
        let luma = self.width * self.height;
        &self.data[luma + luma / 4..luma + luma / 2]
    }
}

fn make_encoder(bitrate: u32, fps: u32, width: u32, height: u32) -> Option<Encoder> {
    let config = EncoderConfig::new()
        .bitrate(BitRate::from_bps(bitrate))
        .max_frame_rate(FrameRate::from_hz(fps as f32))
        .usage_type(UsageType::ScreenContentRealTime)
        .rate_control_mode(RateControlMode::Bitrate)
        // A picture that is held back to save bits is a hitch that the person sees; the rate is held by the bitrate itself.
        .skip_frames(false)
        // Slices of the picture are encoded side by side, and the faster setting is what a large screen needs to keep up.
        .num_threads(if u64::from(width) * u64::from(height) > 1_000_000 { 4 } else { 2 })
        .complexity(if u64::from(width) * u64::from(height) > 1_500_000 { Complexity::Low } else { Complexity::Medium });
    Encoder::with_api_config(OpenH264API::from_source(), config).ok()
}

/// A measurement of how many pictures of this screen this computer can take, convert and encode, for choosing what to improve. Started
/// with `TANDEM_BENCH_HOST=1` in the environment; the result is in the log. It wants something that moves on the screen to measure with.
pub fn bench() {
    if std::env::var_os("TANDEM_BENCH_HOST").is_none() {
        return;
    }
    std::thread::Builder::new()
        .name("tandem-bench".into())
        .spawn(|| {
            std::thread::sleep(Duration::from_secs(10));
            for i420 in [true, false] {
                let Some(grabber) = Grabber::with_options(tandem_winsys::GrabOptions { max: (1920, 1200), cursor: false, fps: 60, i420 }) else {
                    log::warn!("bench host: the screen could not be taken");
                    continue;
                };
                let (source_w, source_h) = grabber.size();
                let (w, h) = target_size(source_w, source_h, 1920, 1200);
                let Some(mut encoder) = make_encoder(starting_bitrate(w, h, 30), 30, w, h) else { continue };
                let mut planes = Vec::new();
                let mut last: Option<Arc<Vec<u8>>> = None;
                let (mut delivered, mut work_ms, mut bytes, mut failed) = (0u32, 0.0f64, 0usize, 0u32);
                let started = Instant::now();
                while started.elapsed() < Duration::from_secs(10) {
                    if let Some((sw, sh, shot)) = grabber.grab_shared() {
                        if !last.as_ref().is_some_and(|l| Arc::ptr_eq(l, &shot)) {
                            let work = Instant::now();
                            let data: &[u8] = if grabber.is_i420() {
                                &shot
                            } else {
                                bgra_to_i420(&shot, sw as usize, sh as usize, w as usize, h as usize, &mut planes);
                                &planes
                            };
                            match encoder.encode(&Planes { width: w as usize, height: h as usize, data }) {
                                Ok(stream) => bytes += stream.to_vec().len(),
                                Err(error) => {
                                    failed += 1;
                                    if failed == 1 {
                                        log::warn!("bench host: the first picture was not encoded ({error}), {} bytes of data for {w}x{h}", data.len());
                                    }
                                }
                            }
                            work_ms += work.elapsed().as_secs_f64() * 1000.0;
                            delivered += 1;
                            last = Some(shot);
                        }
                    }
                    std::thread::sleep(Duration::from_millis(2));
                }
                log::info!(
                    "bench host: {} {w}x{h}: {delivered} new pictures in 10 s ({:.1} a second), {:.1} ms of work for each, {:.1} Mbit a second, {failed} not encoded",
                    if i420 { "I420 from GStreamer" } else { "BGRx converted here" },
                    f64::from(delivered) / 10.0,
                    if delivered > 0 { work_ms / f64::from(delivered) } else { 0.0 },
                    bytes as f64 * 8.0 / 10.0 / 1_000_000.0
                );
            }
        })
        .ok();
}

/// How the work of one picture is going, to choose how many a second there can be: when the work takes most of the time between two
/// pictures the rate goes down, so that what is shown is the latest and not a queue of old ones, and it goes up again when there is room.
struct Pace {
    target: u32,
    fps: u32,
    average_ms: f64,
}

impl Pace {
    fn new(target: u32) -> Pace {
        Pace { target, fps: target.min(30).max(10), average_ms: 0.0 }
    }

    /// One picture took `ms`. Gives the rate to go on with.
    fn took(&mut self, ms: f64) -> u32 {
        self.average_ms = if self.average_ms == 0.0 { ms } else { self.average_ms * 0.85 + ms * 0.15 };
        let interval = 1000.0 / f64::from(self.fps);
        if self.average_ms > interval * 0.85 && self.fps > 10 {
            self.fps = ((1000.0 / self.average_ms) * 0.9) as u32;
            self.fps = self.fps.clamp(10, self.target);
        } else if self.average_ms < interval * 0.5 && self.fps < self.target {
            self.fps = (self.fps + 5).min(self.target);
        }
        self.fps
    }
}

fn capture_loop(
    engine: Arc<tandem_core::ffi::TandemEngine>,
    session: u64,
    grabber: Grabber,
    (width, height): (u32, u32),
    fps: u32,
    stop: Arc<AtomicBool>,
    keyframe: Arc<AtomicBool>,
    bitrate: Arc<AtomicU32>,
) {
    let mut rate = bitrate.load(Ordering::Relaxed);
    let Some(mut encoder) = make_encoder(rate, fps, width, height) else {
        log::warn!("the screen cannot be encoded: the encoder did not start");
        let _ = engine.media_stop(session);
        return;
    };
    let started = Instant::now();
    let mut planes: Vec<u8> = Vec::new();
    let mut pace = Pace::new(fps);
    let mut current_fps = pace.fps;
    let mut last: Option<Arc<Vec<u8>>> = None;
    let mut last_push = Instant::now();
    let mut failures = 0;
    let mut next = Instant::now();
    while !stop.load(Ordering::Relaxed) {
        // The viewer says the link carries less or more: the encoder is made again at that rate, which also starts with a keyframe.
        let wanted = bitrate.load(Ordering::Relaxed);
        if wanted.abs_diff(rate) * 5 > rate {
            if let Some(fresh) = make_encoder(wanted, current_fps, width, height) {
                encoder = fresh;
                rate = wanted;
            }
        }
        match grabber.grab_shared() {
            Some((source_w, source_h, shot)) => {
                failures = 0;
                let same = last.as_ref().is_some_and(|l| Arc::ptr_eq(l, &shot));
                let asked = keyframe.swap(false, Ordering::Relaxed);
                // A screen that does not change sends nothing, except now and then: a viewer takes silence for a broken link.
                if !same || asked || last_push.elapsed() > Duration::from_millis(500) {
                    let work = Instant::now();
                    if asked {
                        encoder.force_intra_frame();
                    }
                    let data: &[u8] = if grabber.is_i420() {
                        &shot
                    } else {
                        bgra_to_i420(&shot, source_w as usize, source_h as usize, width as usize, height as usize, &mut planes);
                        &planes
                    };
                    let yuv = Planes { width: width as usize, height: height as usize, data };
                    if let Ok(stream) = encoder.encode(&yuv) {
                        let kind = stream.frame_type();
                        if !matches!(kind, FrameType::Skip | FrameType::Invalid) {
                            let key = matches!(kind, FrameType::IDR | FrameType::I);
                            let pts = started.elapsed().as_micros() as u64;
                            match engine.media_push_frame(session, stream.to_vec(), pts, key) {
                                TandemMediaPush::NoSession => return,
                                TandemMediaPush::Dropped | TandemMediaPush::WaitingForKeyframe => keyframe.store(true, Ordering::Relaxed),
                                _ => {}
                            }
                            last_push = Instant::now();
                        }
                    }
                    last = Some(shot);
                    current_fps = pace.took(work.elapsed().as_secs_f64() * 1000.0);
                }
            }
            None => {
                // A locked screen cannot be photographed; try again, and give up when it stays that way.
                failures += 1;
                if failures > 200 {
                    let _ = engine.media_stop(session);
                    return;
                }
            }
        }
        next += Duration::from_micros(1_000_000 / u64::from(current_fps.max(1)));
        let now = Instant::now();
        if next > now {
            std::thread::sleep(next - now);
        } else {
            // Behind: do not try to catch up with a burst, start counting again from now.
            next = now;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[cfg(feature = "screen-host")]
    use openh264::formats::YUVBuffer;

    #[test]
    fn the_picture_is_made_smaller_to_fit_and_stays_even() {
        assert_eq!(target_size(1920, 1080, 1920, 1080), (1920, 1080));
        assert_eq!(target_size(2560, 1440, 1920, 1080), (1920, 1080));
        assert_eq!(target_size(3840, 2160, 1280, 720), (1280, 720));
        // What is asked for is not larger than the screen.
        assert_eq!(target_size(1366, 768, 3000, 3000), (1366, 768));
        let (w, h) = target_size(1367, 769, 1000, 1000);
        assert!(w % 2 == 0 && h % 2 == 0);
        assert_eq!(target_size(800, 600, 0, 0), (800, 600));
    }

    #[test]
    fn the_rate_goes_down_when_the_work_is_too_much_and_up_when_there_is_room() {
        let mut pace = Pace::new(60);
        // A picture that takes 40 ms cannot be had thirty times a second: it comes down to what can be kept up.
        for _ in 0..60 {
            pace.took(40.0);
        }
        assert!(pace.fps <= 25 && pace.fps >= 10, "{}", pace.fps);
        // Work that is quick again lets it climb back, towards what was asked for.
        for _ in 0..200 {
            pace.took(4.0);
        }
        assert!(pace.fps >= 55, "{}", pace.fps);
    }

    #[test]
    fn a_bigger_picture_at_a_higher_rate_starts_with_more_bits() {
        assert!(starting_bitrate(1920, 1080, 30) > starting_bitrate(1280, 720, 30));
        assert!(starting_bitrate(1920, 1080, 60) >= starting_bitrate(1920, 1080, 30));
        assert!((3_000_000..=14_000_000).contains(&starting_bitrate(7680, 4320, 60)));
        assert_eq!(starting_bitrate(320, 200, 10), 3_000_000);
    }

    #[test]
    fn the_converted_picture_is_the_same_whatever_the_number_of_threads() {
        // A picture that is not a multiple of the number of threads in height, with some structure in it.
        let (w, h) = (640usize, 358usize);
        let bgra: Vec<u8> = (0..w * h).flat_map(|i| [(i % 251) as u8, ((i / w) % 253) as u8, ((i * 7) % 256) as u8, 0]).collect();
        let mut out = Vec::new();
        bgra_to_i420(&bgra, w, h, w, h, &mut out);
        // The first pixel by hand.
        let (b, g, r) = (i32::from(bgra[0]), i32::from(bgra[1]), i32::from(bgra[2]));
        assert_eq!(out[0], (((66 * r + 129 * g + 25 * b + 128) >> 8) + 16) as u8);
        // The last chroma sample of the last row, which is the one the last thread makes.
        let at = ((h - 2) * w + (w - 2)) * 4;
        let (b, g, r) = (i32::from(bgra[at]), i32::from(bgra[at + 1]), i32::from(bgra[at + 2]));
        let u_last = out[w * h + (h / 2 - 1) * (w / 2) + (w / 2 - 1)];
        assert_eq!(u_last, (((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128).clamp(0, 255) as u8);
        assert_eq!(out.len(), w * h * 3 / 2);
    }

    #[test]
    fn colours_come_out_with_the_usual_weights() {
        // Red, as B, G, R, unused.
        let red: Vec<u8> = std::iter::repeat([0u8, 0, 255, 0]).take(16).flatten().collect();
        let mut out = Vec::new();
        bgra_to_i420(&red, 4, 4, 4, 4, &mut out);
        assert!(out[0].abs_diff(82) <= 2, "luma {}", out[0]);
        assert!(out[16].abs_diff(90) <= 2, "blue difference {}", out[16]);
        assert!(out[20].abs_diff(240) <= 2, "red difference {}", out[20]);
        // White and black.
        let white: Vec<u8> = std::iter::repeat([255u8; 4]).take(4).flatten().collect();
        bgra_to_i420(&white, 2, 2, 2, 2, &mut out);
        assert_eq!(out[0], 235);
        assert_eq!(out[4], 128);
    }

    #[test]
    fn scaling_takes_the_nearest_pixel() {
        // Left half blue, right half green, shown at half the size.
        let mut picture = Vec::new();
        for _ in 0..4 {
            picture.extend_from_slice(&[255u8, 0, 0, 0, 255, 0, 0, 0, 0, 255, 0, 0, 0, 255, 0, 0]);
        }
        let mut out = Vec::new();
        bgra_to_i420(&picture, 4, 4, 2, 2, &mut out);
        assert_ne!(out[0], out[1], "the two halves differ after scaling");
    }

    /// What the host sends can be decoded by a viewer: the same encoder, the other way, with the decoder of the Linux viewer.
    #[cfg(feature = "native-video")]
    #[test]
    fn what_the_host_sends_is_a_picture_to_a_viewer() {
        let (w, h) = (640usize, 360usize);
        let mut encoder = make_encoder(2_000_000, 15, 640, 360).unwrap();
        let mut decoder = crate::video::Decoder::new().unwrap();
        let mut planes = Vec::new();
        let mut pictures = 0;
        for n in 0..6usize {
            let bgra: Vec<u8> = (0..w * h).flat_map(|i| [((i + n * 7) % 256) as u8, 200, ((i / w) % 256) as u8, 0]).collect();
            bgra_to_i420(&bgra, w, h, w, h, &mut planes);
            let stream = encoder.encode(&YUVBuffer::from_vec(planes.clone(), w, h)).unwrap();
            if !matches!(stream.frame_type(), FrameType::Skip | FrameType::Invalid) && decoder.picture(&stream.to_vec()).is_some() {
                pictures += 1;
            }
        }
        assert!(pictures >= 4, "pictures that came out: {pictures}");
    }

    /// The pictures of the screens that are really used: the encoder takes them, at the sizes it is made for, with the planes of video that
    /// GStreamer makes (and no copy in between).
    #[test]
    fn a_large_screen_in_planes_is_encoded() {
        for (w, h) in [(1920usize, 1200usize), (1280, 800), (2560, 1440)] {
            let mut data = vec![0u8; w * h * 3 / 2];
            for (i, b) in data.iter_mut().enumerate() {
                *b = ((i * 7 + i / w) % 251) as u8;
            }
            let mut encoder = make_encoder(starting_bitrate(w as u32, h as u32, 30), 30, w as u32, h as u32).expect("the encoder starts");
            for n in 0..4 {
                data[n * 3] = data[n * 3].wrapping_add(40);
                let stream = encoder.encode(&Planes { width: w, height: h, data: &data }).unwrap_or_else(|e| panic!("{w}x{h} picture {n}: {e}"));
                assert!(!stream.to_vec().is_empty(), "{w}x{h} picture {n} came out empty ({:?})", stream.frame_type());
            }
        }
    }

    /// A screen of blocks goes in and a stream that starts with a keyframe full of parameter sets comes out, which is what the core wants
    /// from a host.
    #[test]
    fn the_encoder_makes_a_stream_the_core_accepts() {
        let (w, h) = (320usize, 240usize);
        let bgra: Vec<u8> = (0..w * h).flat_map(|i| [(i % 256) as u8, ((i / w) % 256) as u8, 120, 0]).collect();
        let mut planes = Vec::new();
        bgra_to_i420(&bgra, w, h, w, h, &mut planes);
        let mut encoder = make_encoder(1_000_000, 15, 320, 240).unwrap();
        let first = encoder.encode(&YUVBuffer::from_vec(planes.clone(), w, h)).unwrap();
        assert!(matches!(first.frame_type(), FrameType::IDR | FrameType::I));
        let data = first.to_vec();
        assert_eq!(&data[..4], &[0, 0, 0, 1]);
        // SPS (7) and PPS (8) are in the first keyframe.
        let kinds: Vec<u8> = data.windows(5).filter(|w| w[..4] == [0, 0, 0, 1]).map(|w| w[4] & 0x1F).collect();
        assert!(kinds.contains(&7) && kinds.contains(&8), "{kinds:?}");
    }
}
