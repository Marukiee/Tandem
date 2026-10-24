//! A second screen end to end through the core: the viewer says how big its screen is in pixels and in logical pixels, the host
//! hears both, and sixty frames a second of the size a laptop screen gives arrive in order and in time. Two devices can each have
//! one at the same time. Real engines, real QUIC, loopback. The time a frame spends in the core is printed (`--nocapture`), so a
//! change to the path can be measured.

use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use tandem_core::live::{
    MediaAccept, MediaAnswer, MediaCodec, MediaEnd, MediaFacing, MediaFormat, MediaFrame, MediaHost, MediaInput, MediaKind, MediaPush,
    MediaRequest, MediaResize, MediaUpdate, MediaViewer, MediaWant,
};
use tandem_core::platform::DesktopFiles;
use tandem_core::store::{FileSecretStore, Store};
use tandem_core::{DeviceId, Engine, EngineConfig};

struct Node {
    engine: Engine,
    _dir: tempfile::TempDir,
}

async fn node(name: &str) -> Node {
    let dir = tempfile::tempdir().unwrap();
    let data = dir.path().join("data");
    let mut cfg = EngineConfig::new(&data, name);
    cfg.port = 0;
    cfg.enable_mdns = false;
    cfg.loopback = true;
    let secrets = Arc::new(FileSecretStore::new(Store::new(&data).unwrap()));
    let files = Arc::new(DesktopFiles { download_dir: dir.path().join("downloads") });
    let engine = Engine::start(cfg, secrets, files).await.unwrap();
    Node { engine, _dir: dir }
}

async fn wait_until(what: &str, mut condition: impl FnMut() -> bool) {
    let deadline = Instant::now() + Duration::from_secs(45);
    while !condition() {
        assert!(Instant::now() < deadline, "timed out waiting for: {what}");
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
}

fn online(node: &Node, id: DeviceId) -> bool {
    node.engine.devices().iter().any(|d| d.id == id && d.online)
}

#[derive(Default)]
struct Host {
    requests: Mutex<Vec<MediaRequest>>,
    resizes: Mutex<Vec<MediaResize>>,
}

impl MediaHost for Host {
    fn on_request(&self, _from: DeviceId, request: MediaRequest, _pre_approved: bool) {
        self.requests.lock().unwrap().push(request);
    }
    fn on_keyframe(&self, _session: u64) {}
    fn on_bitrate(&self, _session: u64, _bits_per_second: u32) {}
    fn on_input(&self, _session: u64, _input: MediaInput) {}
    fn on_stop(&self, _session: u64, _reason: MediaEnd) {}
    fn on_resize(&self, resize: MediaResize) {
        self.resizes.lock().unwrap().push(resize);
    }
}

struct Viewer {
    base: Instant,
    accepted: Mutex<Option<MediaAccept>>,
    updates: Mutex<Vec<MediaUpdate>>,
    /// Picture time and when it arrived, both in microseconds since `base`.
    frames: Mutex<Vec<(u64, u64, bool)>>,
    ended: Mutex<Option<MediaEnd>>,
}

impl Viewer {
    fn new(base: Instant) -> Arc<Self> {
        Arc::new(Viewer { base, accepted: Mutex::new(None), updates: Mutex::new(Vec::new()), frames: Mutex::new(Vec::new()), ended: Mutex::new(None) })
    }
}

impl MediaViewer for Viewer {
    fn on_accepted(&self, _session: u64, accept: MediaAccept) {
        *self.accepted.lock().unwrap() = Some(accept);
    }
    fn on_update(&self, _session: u64, update: MediaUpdate) {
        self.updates.lock().unwrap().push(update);
    }
    fn on_frame(&self, _session: u64, frame: MediaFrame) {
        let at = self.base.elapsed().as_micros() as u64;
        self.frames.lock().unwrap().push((frame.pts_us, at, frame.keyframe));
    }
    fn on_ended(&self, _session: u64, reason: MediaEnd) {
        *self.ended.lock().unwrap() = Some(reason);
    }
}

/// An access unit in Annex B of about `size` bytes: parameter sets and an IDR for a keyframe, a plain slice otherwise.
fn unit(keyframe: bool, size: usize) -> Vec<u8> {
    let mut out = Vec::with_capacity(size + 32);
    if keyframe {
        out.extend_from_slice(&[0, 0, 0, 1, 0x67, 0x64, 0x00, 0x34, 0xAC]);
        out.extend_from_slice(&[0, 0, 0, 1, 0x68, 0xEE, 0x3C, 0x80]);
        out.extend_from_slice(&[0, 0, 0, 1, 0x65, 0x88]);
    } else {
        out.extend_from_slice(&[0, 0, 0, 1, 0x41, 0x9A]);
    }
    // No zeros, so nothing in the payload reads as a start code.
    out.resize(size, 0x5A);
    out
}

fn percentile(sorted: &[u64], q: f64) -> u64 {
    sorted[((sorted.len() as f64 * q) as usize).min(sorted.len() - 1)]
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_second_screen_carries_its_logical_size_and_sixty_frames_a_second_arrive_in_time() {
    let laptop = node("Laptop").await;
    let mac = node("Mac").await;
    let offer = mac.engine.create_pairing_offer().unwrap();
    laptop.engine.pair_with_uri(&offer.uri).await.unwrap();
    wait_until("connected", || online(&laptop, mac.engine.id()) && online(&mac, laptop.engine.id())).await;

    let host = Arc::new(Host::default());
    mac.engine.set_media_host(host.clone());
    let base = Instant::now();
    let viewer = Viewer::new(base);
    laptop.engine.set_media_viewer(viewer.clone());

    // A laptop of 2880 by 1800 pixels at 150%: its desktop is 1920 by 1200.
    let mut want = MediaWant::new(MediaKind::Screen);
    want.max_width = 2880;
    want.max_height = 1800;
    want.max_fps = 60;
    want.extend = true;
    want.facing = MediaFacing::Any;
    want.logical_width = 1920;
    want.logical_height = 1200;
    let session = laptop.engine.media_request(mac.engine.id(), want).unwrap();

    wait_until("the request", || !host.requests.lock().unwrap().is_empty()).await;
    let request = host.requests.lock().unwrap()[0].clone();
    assert!(request.extend);
    assert_eq!((request.max_width, request.max_height), (2880, 1800));
    assert_eq!((request.logical_width, request.logical_height), (1920, 1200));

    let bitrate = 24_000_000;
    mac.engine
        .media_accept(session, MediaAnswer { codec: MediaCodec::H264, width: 2880, height: 1800, fps: 60, bitrate, control: false })
        .unwrap();
    wait_until("the answer", || viewer.accepted.lock().unwrap().is_some()).await;

    // Three seconds at sixty: a keyframe of 300 KB, then frames of the size 24 Mbit/s gives them.
    let frames = 180u64;
    let p_size = (bitrate / 8 / 60) as usize;
    let start = tokio::time::Instant::now();
    let mut refused = 0;
    for n in 0..frames {
        tokio::time::sleep_until(start + Duration::from_micros(n * 1_000_000 / 60)).await;
        let key = n == 0;
        let pts = base.elapsed().as_micros() as u64;
        let pushed = mac.engine.media_push_frame(session, unit(key, if key { 300 * 1024 } else { p_size }), pts, key);
        if pushed != MediaPush::Sent {
            refused += 1;
        }
    }
    wait_until("the last frame", || viewer.frames.lock().unwrap().len() as u64 >= frames - refused).await;

    let got = viewer.frames.lock().unwrap().clone();
    assert!(got[0].2, "the first frame is the keyframe");
    assert!(got.windows(2).all(|w| w[0].0 < w[1].0), "in order");
    let mut late: Vec<u64> = got.iter().map(|(pts, at, _)| at.saturating_sub(*pts)).collect();
    late.sort_unstable();
    let (p50, p99, max) = (percentile(&late, 0.5), percentile(&late, 0.99), *late.last().unwrap());
    println!(
        "second screen through the core: {} of {frames} frames, {refused} refused by the host, in the core p50 {:.1} ms, p99 {:.1} ms, max {:.1} ms",
        got.len(),
        p50 as f64 / 1000.0,
        p99 as f64 / 1000.0,
        max as f64 / 1000.0
    );
    assert_eq!(refused, 0, "a loopback link takes 24 Mbit/s without a frame turned away");
    // The frames right behind the large keyframe arrive while it is still coming in, and are used: nothing was lost and no second
    // keyframe was needed (before, the first of them was turned away and the chain broke at the start of every session).
    let stats = laptop.engine.media_stats(session).unwrap();
    assert_eq!((stats.lost, stats.discarded, stats.keyframes_requested), (0, 0, 0), "{stats:?}");
    // Generous for a busy test machine; on an idle one this is a few milliseconds.
    assert!(p99 < 100_000, "p99 {p99} us");
}

fn extend_want(width: u32, height: u32) -> MediaWant {
    let mut want = MediaWant::new(MediaKind::Screen);
    want.max_width = width;
    want.max_height = height;
    want.max_fps = 60;
    want.extend = true;
    want
}

/// A laptop on the left and a phone on the right, both an extra screen of the same Mac: the Mac hears two requests, answers both,
/// each device gets the pictures of its own session and none of the other's, and stopping one leaves the other running.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn two_devices_each_have_a_second_screen_at_once_and_stop_on_their_own() {
    let mac = node("Mac").await;
    let laptop = node("Laptop").await;
    let phone = node("Phone").await;
    for device in [&laptop, &phone] {
        let offer = mac.engine.create_pairing_offer().unwrap();
        device.engine.pair_with_uri(&offer.uri).await.unwrap();
    }
    wait_until("connected", || {
        online(&laptop, mac.engine.id()) && online(&phone, mac.engine.id()) && online(&mac, laptop.engine.id()) && online(&mac, phone.engine.id())
    })
    .await;

    let host = Arc::new(Host::default());
    mac.engine.set_media_host(host.clone());
    let base = Instant::now();
    let on_laptop = Viewer::new(base);
    let on_phone = Viewer::new(base);
    laptop.engine.set_media_viewer(on_laptop.clone());
    phone.engine.set_media_viewer(on_phone.clone());

    let left = laptop.engine.media_request(mac.engine.id(), extend_want(2880, 1800)).unwrap();
    let right = phone.engine.media_request(mac.engine.id(), extend_want(2400, 1080)).unwrap();
    wait_until("both requests", || host.requests.lock().unwrap().len() == 2).await;

    for (session, width, height) in [(left, 2880, 1800), (right, 2400, 1080)] {
        mac.engine
            .media_accept(session, MediaAnswer { codec: MediaCodec::H264, width, height, fps: 60, bitrate: 8_000_000, control: true })
            .unwrap();
    }
    wait_until("both answers", || on_laptop.accepted.lock().unwrap().is_some() && on_phone.accepted.lock().unwrap().is_some()).await;
    assert_eq!(on_laptop.accepted.lock().unwrap().as_ref().map(|a| (a.width, a.height)), Some((2880, 1800)));
    assert_eq!(on_phone.accepted.lock().unwrap().as_ref().map(|a| (a.width, a.height)), Some((2400, 1080)));

    // Ten pictures to each, told apart by their size: the laptop's are larger.
    for n in 0..10u64 {
        let pts = base.elapsed().as_micros() as u64 + n;
        assert_eq!(mac.engine.media_push_frame(left, unit(n == 0, 9_000), pts, n == 0), MediaPush::Sent);
        assert_eq!(mac.engine.media_push_frame(right, unit(n == 0, 3_000), pts, n == 0), MediaPush::Sent);
        tokio::time::sleep(Duration::from_millis(16)).await;
    }
    wait_until("ten pictures on each", || on_laptop.frames.lock().unwrap().len() == 10 && on_phone.frames.lock().unwrap().len() == 10).await;

    // The laptop stops showing its screen; the phone goes on.
    mac.engine.media_stop(left).unwrap();
    wait_until("the laptop's screen ended", || on_laptop.ended.lock().unwrap().is_some()).await;
    assert_eq!(mac.engine.media_push_frame(left, unit(false, 9_000), base.elapsed().as_micros() as u64, false), MediaPush::NoSession);
    for n in 0..5u64 {
        let pts = base.elapsed().as_micros() as u64 + n;
        assert_eq!(mac.engine.media_push_frame(right, unit(false, 3_000), pts, false), MediaPush::Sent);
        tokio::time::sleep(Duration::from_millis(16)).await;
    }
    wait_until("the phone still gets pictures", || on_phone.frames.lock().unwrap().len() == 15).await;
    assert!(on_phone.ended.lock().unwrap().is_none(), "the phone's screen did not end with the laptop's");
    assert_eq!(on_laptop.frames.lock().unwrap().len(), 10);
}

/// A phone that is an extra screen is turned upright: the Mac hears the new shape in the session that runs (no new request, so nothing
/// is asked again), says the new picture, and the frames of it arrive. Only a screen made for the phone can be turned, and only once
/// it runs.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_second_screen_that_is_turned_is_resized_within_its_session() {
    let mac = node("Mac").await;
    let phone = node("Phone").await;
    let offer = mac.engine.create_pairing_offer().unwrap();
    phone.engine.pair_with_uri(&offer.uri).await.unwrap();
    wait_until("connected", || online(&phone, mac.engine.id()) && online(&mac, phone.engine.id())).await;

    let host = Arc::new(Host::default());
    mac.engine.set_media_host(host.clone());
    let base = Instant::now();
    let viewer = Viewer::new(base);
    phone.engine.set_media_viewer(viewer.clone());

    let session = phone.engine.media_request(mac.engine.id(), extend_want(2340, 1080)).unwrap();
    let upright = MediaResize { session, max_width: 1080, max_height: 2340, logical_width: 0, logical_height: 0 };
    assert!(phone.engine.media_resize(upright).is_err(), "nothing to turn before the Mac said yes");
    wait_until("the request", || !host.requests.lock().unwrap().is_empty()).await;
    mac.engine
        .media_accept(session, MediaAnswer { codec: MediaCodec::H264, width: 2340, height: 1080, fps: 60, bitrate: 8_000_000, control: true })
        .unwrap();
    wait_until("the answer", || viewer.accepted.lock().unwrap().is_some()).await;
    assert_eq!(mac.engine.media_push_frame(session, unit(true, 4_000), base.elapsed().as_micros() as u64, true), MediaPush::Sent);
    wait_until("the first picture", || viewer.frames.lock().unwrap().len() == 1).await;

    phone.engine.media_resize(upright).unwrap();
    wait_until("the turn reaches the Mac", || !host.resizes.lock().unwrap().is_empty()).await;
    assert_eq!(host.resizes.lock().unwrap()[0], upright);
    assert_eq!(host.requests.lock().unwrap().len(), 1, "no second request");

    // The Mac made its screen upright and says so before the first picture of it, a keyframe.
    let format = MediaFormat { width: 1080, height: 2340, rotation: 0, fps: 60 };
    mac.engine.media_update(session, MediaUpdate { format: Some(format), control: None }).unwrap();
    wait_until("the new shape on the phone", || !viewer.updates.lock().unwrap().is_empty()).await;
    assert_eq!(viewer.updates.lock().unwrap()[0].format, Some(format));
    for n in 0..5u64 {
        let pts = base.elapsed().as_micros() as u64 + n;
        assert_eq!(mac.engine.media_push_frame(session, unit(n == 0, 4_000), pts, n == 0), MediaPush::Sent);
        tokio::time::sleep(Duration::from_millis(16)).await;
    }
    wait_until("the upright pictures", || viewer.frames.lock().unwrap().len() == 6).await;
    assert!(viewer.ended.lock().unwrap().is_none());

    // A camera is not a screen made for the phone, so it has no shape to change.
    let camera = phone.engine.media_request(mac.engine.id(), MediaWant::new(MediaKind::Camera)).unwrap();
    assert!(phone.engine.media_resize(MediaResize { session: camera, ..upright }).is_err());
}
