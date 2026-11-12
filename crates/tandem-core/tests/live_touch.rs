//! A finger on the picture of another phone: every press carries the position it belongs to over the stream, so it never lands where
//! the last one did when the datagram with the position is late or lost. Real engines, real QUIC, loopback.

use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use tandem_core::live::{MediaAccept, MediaAnswer, MediaCodec, MediaEnd, MediaFrame, MediaHost, MediaInput, MediaKind, MediaRequest, MediaResize, MediaUpdate, MediaViewer, MediaWant};
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
    inputs: Mutex<Vec<MediaInput>>,
}

impl MediaHost for Host {
    fn on_request(&self, _from: DeviceId, request: MediaRequest, _pre_approved: bool) {
        self.requests.lock().unwrap().push(request);
    }
    fn on_keyframe(&self, _session: u64) {}
    fn on_bitrate(&self, _session: u64, _bits_per_second: u32) {}
    fn on_input(&self, _session: u64, input: MediaInput) {
        self.inputs.lock().unwrap().push(input);
    }
    fn on_stop(&self, _session: u64, _reason: MediaEnd) {}
    fn on_resize(&self, _resize: MediaResize) {}
}

#[derive(Default)]
struct Viewer {
    accepted: Mutex<Option<MediaAccept>>,
}

impl MediaViewer for Viewer {
    fn on_accepted(&self, _session: u64, accept: MediaAccept) {
        *self.accepted.lock().unwrap() = Some(accept);
    }
    fn on_update(&self, _session: u64, _update: MediaUpdate) {}
    fn on_frame(&self, _session: u64, _frame: MediaFrame) {}
    fn on_ended(&self, _session: u64, _reason: MediaEnd) {}
}

/// A datagram carries the position in steps of 1/65535, the stream exactly.
fn is_at(input: &MediaInput, x: f32, y: f32) -> bool {
    matches!(input, MediaInput::PointerAbs { x: ix, y: iy } if (ix - x).abs() < 1e-4 && (iy - y).abs() < 1e-4)
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_press_carries_its_position_over_the_stream() {
    let viewer_phone = node("Pixel").await;
    let host_phone = node("Xperia").await;
    let offer = host_phone.engine.create_pairing_offer().unwrap();
    viewer_phone.engine.pair_with_uri(&offer.uri).await.unwrap();
    wait_until("connected", || online(&viewer_phone, host_phone.engine.id()) && online(&host_phone, viewer_phone.engine.id())).await;

    let host = Arc::new(Host::default());
    host_phone.engine.set_media_host(host.clone());
    let viewer = Arc::new(Viewer::default());
    viewer_phone.engine.set_media_viewer(viewer.clone());

    let mut want = MediaWant::new(MediaKind::Screen);
    want.max_width = 1080;
    want.max_height = 2340;
    want.control = true;
    let session = viewer_phone.engine.media_request(host_phone.engine.id(), want).unwrap();
    wait_until("the request", || !host.requests.lock().unwrap().is_empty()).await;
    host_phone
        .engine
        .media_accept(session, MediaAnswer { codec: MediaCodec::H264, width: 1080, height: 2340, fps: 60, bitrate: 8_000_000, control: true })
        .unwrap();
    wait_until("the answer", || viewer.accepted.lock().unwrap().is_some()).await;

    // Down at one place, a move by datagram only, up at another.
    let send = |input: MediaInput| viewer_phone.engine.media_send_input(session, input).unwrap();
    send(MediaInput::PointerAbs { x: 0.25, y: 0.5 });
    send(MediaInput::Button { button: 0, down: true, clicks: 1 });
    send(MediaInput::PointerAbs { x: 0.5, y: 0.6 });
    send(MediaInput::PointerAbs { x: 0.75, y: 0.7 });
    send(MediaInput::Button { button: 0, down: false, clicks: 1 });
    wait_until("both presses", || host.inputs.lock().unwrap().iter().filter(|i| matches!(i, MediaInput::Button { .. })).count() == 2).await;

    // Each press is right behind its own position, because the stream keeps the order. The datagrams overtake the stream (here all
    // three came before the first press), so without that the press went down where the finger lifted.
    let got = host.inputs.lock().unwrap().clone();
    let presses: Vec<usize> = got.iter().enumerate().filter(|(_, i)| matches!(i, MediaInput::Button { .. })).map(|(n, _)| n).collect();
    assert!(presses[0] > 0 && is_at(&got[presses[0] - 1], 0.25, 0.5), "{got:?}");
    assert!(is_at(&got[presses[1] - 1], 0.75, 0.7), "{got:?}");
    // The position of the press came twice, once as a datagram and once on the stream in front of the press; the move in between only
    // as a datagram.
    wait_until("the datagrams", || host.inputs.lock().unwrap().iter().filter(|i| is_at(i, 0.25, 0.5)).count() == 2).await;
    let got = host.inputs.lock().unwrap().clone();
    assert_eq!(got.iter().filter(|i| is_at(i, 0.75, 0.7)).count(), 2, "{got:?}");
    assert_eq!(got.iter().filter(|i| is_at(i, 0.5, 0.6)).count(), 1, "{got:?}");

    // A relative move after a position makes that position stale: the press does not jump back to it.
    host.inputs.lock().unwrap().clear();
    send(MediaInput::PointerAbs { x: 0.1, y: 0.1 });
    send(MediaInput::PointerRel { dx: 40, dy: 0 });
    send(MediaInput::Button { button: 0, down: true, clicks: 1 });
    wait_until("the press", || host.inputs.lock().unwrap().iter().any(|i| matches!(i, MediaInput::Button { .. }))).await;
    tokio::time::sleep(Duration::from_millis(200)).await;
    let got = host.inputs.lock().unwrap().clone();
    assert_eq!(got.iter().filter(|i| is_at(i, 0.1, 0.1)).count(), 1, "{got:?}");
}
