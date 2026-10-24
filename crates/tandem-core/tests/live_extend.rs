//! A second screen end to end through the core: the viewer says how big its screen is in pixels and in logical pixels, the host
//! hears both, and sixty frames a second of the size a laptop screen gives arrive in order and in time. Real engines, real QUIC,
//! loopback. The time a frame spends in the core is printed (`--nocapture`), so a change to the path can be measured.

use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use tandem_core::live::{
    MediaAccept, MediaAnswer, MediaCodec, MediaEnd, MediaFacing, MediaFrame, MediaHost, MediaInput, MediaKind, MediaPush, MediaRequest,
    MediaUpdate, MediaViewer, MediaWant,
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
}

impl MediaHost for Host {
    fn on_request(&self, _from: DeviceId, request: MediaRequest, _pre_approved: bool) {
        self.requests.lock().unwrap().push(request);
    }
    fn on_keyframe(&self, _session: u64) {}
    fn on_bitrate(&self, _session: u64, _bits_per_second: u32) {}
    fn on_input(&self, _session: u64, _input: MediaInput) {}
    fn on_stop(&self, _session: u64, _reason: MediaEnd) {}
}

struct Viewer {
    base: Instant,
    accepted: Mutex<Option<MediaAccept>>,
    /// Picture time and when it arrived, both in microseconds since `base`.
    frames: Mutex<Vec<(u64, u64, bool)>>,
}

impl MediaViewer for Viewer {
    fn on_accepted(&self, _session: u64, accept: MediaAccept) {
        *self.accepted.lock().unwrap() = Some(accept);
    }
    fn on_update(&self, _session: u64, _update: MediaUpdate) {}
    fn on_frame(&self, _session: u64, frame: MediaFrame) {
        let at = self.base.elapsed().as_micros() as u64;
        self.frames.lock().unwrap().push((frame.pts_us, at, frame.keyframe));
    }
    fn on_ended(&self, _session: u64, _reason: MediaEnd) {}
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
    let viewer = Arc::new(Viewer { base, accepted: Mutex::new(None), frames: Mutex::new(Vec::new()) });
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
