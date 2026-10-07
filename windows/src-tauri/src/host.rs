//! This computer as a screen that other devices can look at and, when it is allowed, use: the part of the app that works like a
//! remote desktop. A device asks for the screen; the person here is asked (or has said "always" for that device); then the screen is
//! photographed a few times a second, made into H.264 and sent, and the mouse and keyboard of the viewer are played back here
//! through the same code that plays a phone's trackpad. The viewers are the Mac, the phone, Windows and Linux.
//!
//! The picture comes from the screen grabber of the system layer (GDI on Windows, the X server on Linux) and is encoded in
//! software (OpenH264), so it needs no graphics card and works the same everywhere. Under Wayland there is no way to grab the screen
//! without the portal of the desktop, so a Wayland session does not offer its screen.

use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use openh264::encoder::{BitRate, Encoder, EncoderConfig, FrameRate, FrameType, RateControlMode, UsageType};
use openh264::formats::YUVBuffer;
use openh264::OpenH264API;
use serde_json::json;
use tandem_core::ffi::{
    TandemMediaAccept, TandemMediaCodec, TandemMediaEnd, TandemMediaHost, TandemMediaInput, TandemMediaKind, TandemMediaPush,
    TandemMediaRequest,
};
use tandem_winsys::Grabber;
use tauri::{AppHandle, Emitter, Manager};
use tauri_plugin_dialog::{DialogExt, MessageDialogButtons};

use crate::state::AppState;
use crate::{events, i18n, input};

/// The pictures a second: a screen that is looked at over a network does not need more, and software encoding is not free.
const FPS: u32 = 15;

/// Fewer for a large screen, where taking the picture and encoding it cost more than the time between two pictures.
fn fps_for(width: u32, height: u32) -> u32 {
    match u64::from(width) * u64::from(height) {
        0..=2_200_000 => FPS,
        2_200_001..=4_500_000 => 10,
        _ => 6,
    }
}
const DEFAULT_BITRATE: u32 = 4_000_000;
const MAX_BITRATE: u32 = 8_000_000;

/// Whether this system can show its screen at all (Windows, and Linux under X11).
pub fn available() -> bool {
    // Asked once: on Linux it opens a connection to the X server, and the state is asked for each time a window loads.
    static AVAILABLE: std::sync::OnceLock<bool> = std::sync::OnceLock::new();
    *AVAILABLE.get_or_init(|| Grabber::new().is_some())
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
        let Some(grabber) = Grabber::new() else {
            let _ = engine.media_deny(session, TandemMediaEnd::Unavailable);
            return;
        };
        let (source_w, source_h) = grabber.size();
        if source_w < 64 || source_h < 64 {
            let _ = engine.media_deny(session, TandemMediaEnd::Unavailable);
            return;
        }
        let (width, height) = target_size(source_w, source_h, request.max_width, request.max_height);
        let control = request.control && control_available();
        let bitrate = if request.max_bitrate > 0 { request.max_bitrate.min(MAX_BITRATE) } else { DEFAULT_BITRATE };
        let fps = fps_for(width, height);
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

impl TandemMediaHost for Host {
    fn on_request(&self, from: String, request: TandemMediaRequest, pre_approved: bool) {
        let Some(engine) = self.engine() else { return };
        if request.kind != TandemMediaKind::Screen || !request.codecs.contains(&TandemMediaCodec::H264) {
            let _ = engine.media_deny(request.session, TandemMediaEnd::Unsupported);
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
            let allowed = this
                .app
                .dialog()
                .message(body)
                .title(i18n::t1(&this.app, "host_ask_title", &name))
                .buttons(MessageDialogButtons::OkCancelCustom(i18n::t(&this.app, "host_allow"), i18n::t(&this.app, "host_deny")))
                .blocking_show();
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
/// usual ones for video of this kind (BT.601, limited range).
pub fn bgra_to_i420(bgra: &[u8], source_w: usize, source_h: usize, width: usize, height: usize, out: &mut Vec<u8>) {
    out.clear();
    out.resize(width * height * 3 / 2, 0);
    let (luma, chroma) = out.split_at_mut(width * height);
    let (u_plane, v_plane) = chroma.split_at_mut(width * height / 4);
    // Where each column and each row of the picture comes from, worked out once instead of for every pixel.
    let columns: Vec<usize> = (0..width).map(|x| (x * source_w / width).min(source_w - 1) * 4).collect();
    let rows: Vec<usize> = (0..height).map(|y| (y * source_h / height).min(source_h - 1) * source_w * 4).collect();
    for y in 0..height {
        let row = rows[y];
        let line = &mut luma[y * width..(y + 1) * width];
        for (x, out_pixel) in line.iter_mut().enumerate() {
            let at = row + columns[x];
            let (b, g, r) = (i32::from(bgra[at]), i32::from(bgra[at + 1]), i32::from(bgra[at + 2]));
            *out_pixel = (((66 * r + 129 * g + 25 * b + 128) >> 8) + 16) as u8;
        }
    }
    for y in 0..height / 2 {
        let row = rows[y * 2];
        for x in 0..width / 2 {
            // The colour of a block of two by two is taken at its top left pixel, which is as good as the average for a screen.
            let at = row + columns[x * 2];
            let (b, g, r) = (i32::from(bgra[at]), i32::from(bgra[at + 1]), i32::from(bgra[at + 2]));
            u_plane[y * (width / 2) + x] = (((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128).clamp(0, 255) as u8;
            v_plane[y * (width / 2) + x] = (((112 * r - 94 * g - 18 * b + 128) >> 8) + 128).clamp(0, 255) as u8;
        }
    }
}

fn make_encoder(bitrate: u32, fps: u32) -> Option<Encoder> {
    let config = EncoderConfig::new()
        .bitrate(BitRate::from_bps(bitrate))
        .max_frame_rate(FrameRate::from_hz(fps as f32))
        .usage_type(UsageType::ScreenContentRealTime)
        .rate_control_mode(RateControlMode::Bitrate);
    Encoder::with_api_config(OpenH264API::from_source(), config).ok()
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
    let Some(mut encoder) = make_encoder(rate, fps) else {
        log::warn!("the screen cannot be encoded: the encoder did not start");
        let _ = engine.media_stop(session);
        return;
    };
    let started = Instant::now();
    let mut planes: Vec<u8> = Vec::new();
    let mut frame = 0u64;
    let mut failures = 0;
    while !stop.load(Ordering::Relaxed) {
        let due = started + Duration::from_micros(frame * 1_000_000 / u64::from(fps));
        // The viewer says the link carries less or more: the encoder is made again at that rate, which also starts with a keyframe.
        let wanted = bitrate.load(Ordering::Relaxed);
        if wanted.abs_diff(rate) * 5 > rate {
            if let Some(fresh) = make_encoder(wanted, fps) {
                encoder = fresh;
                rate = wanted;
            }
        }
        match grabber.grab() {
            Some((source_w, source_h, bgra)) => {
                failures = 0;
                bgra_to_i420(&bgra, source_w as usize, source_h as usize, width as usize, height as usize, &mut planes);
                if keyframe.swap(false, Ordering::Relaxed) {
                    encoder.force_intra_frame();
                }
                let yuv = YUVBuffer::from_vec(std::mem::take(&mut planes), width as usize, height as usize);
                if let Ok(stream) = encoder.encode(&yuv) {
                    let kind = stream.frame_type();
                    if !matches!(kind, FrameType::Skip | FrameType::Invalid) {
                        let key = matches!(kind, FrameType::IDR | FrameType::I);
                        let pts = frame * 1_000_000 / u64::from(fps);
                        match engine.media_push_frame(session, stream.to_vec(), pts, key) {
                            TandemMediaPush::NoSession => return,
                            TandemMediaPush::Dropped | TandemMediaPush::WaitingForKeyframe => keyframe.store(true, Ordering::Relaxed),
                            _ => {}
                        }
                    }
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
        frame += 1;
        let now = Instant::now();
        let next = due + Duration::from_micros(1_000_000 / u64::from(fps));
        if next > now {
            std::thread::sleep(next.saturating_duration_since(now));
        } else {
            // Behind: do not try to catch up with a burst, start counting again from now.
            frame = (now - started).as_micros() as u64 * u64::from(fps) / 1_000_000;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

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
    fn a_large_screen_gets_fewer_pictures_a_second() {
        assert_eq!(fps_for(1920, 1080), 15);
        assert_eq!(fps_for(2560, 1440), 10);
        assert_eq!(fps_for(3840, 2160), 6);
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
        let mut encoder = make_encoder(2_000_000, 15).unwrap();
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

    /// A screen of blocks goes in and a stream that starts with a keyframe full of parameter sets comes out, which is what the core wants
    /// from a host.
    #[test]
    fn the_encoder_makes_a_stream_the_core_accepts() {
        let (w, h) = (320usize, 240usize);
        let bgra: Vec<u8> = (0..w * h).flat_map(|i| [(i % 256) as u8, ((i / w) % 256) as u8, 120, 0]).collect();
        let mut planes = Vec::new();
        bgra_to_i420(&bgra, w, h, w, h, &mut planes);
        let mut encoder = make_encoder(1_000_000, 15).unwrap();
        let first = encoder.encode(&YUVBuffer::from_vec(planes.clone(), w, h)).unwrap();
        assert!(matches!(first.frame_type(), FrameType::IDR | FrameType::I));
        let data = first.to_vec();
        assert_eq!(&data[..4], &[0, 0, 0, 1]);
        // SPS (7) and PPS (8) are in the first keyframe.
        let kinds: Vec<u8> = data.windows(5).filter(|w| w[..4] == [0, 0, 0, 1]).map(|w| w[4] & 0x1F).collect();
        assert!(kinds.contains(&7) && kinds.contains(&8), "{kinds:?}");
    }
}
