//! Transfers between real engines over loopback: stopping one receiver, two receivers of one file, a declined offer, and the
//! journal that keeps offers across a restart (see docs/TRANSFERS.md).

use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;

use tandem_core::events::{Event, TransferDone};
use tandem_core::platform::{DesktopFiles, FileStore};
use tandem_core::proto::ShareOrigin;
use tandem_core::store::{DeviceSettings, FileSecretStore, Store};
use tandem_core::transfer::OutgoingFile;
use tandem_core::{DeviceId, Engine, EngineConfig};
use tokio::sync::broadcast::Receiver;

struct Node {
    engine: Engine,
    data: PathBuf,
    downloads: PathBuf,
    _dir: tempfile::TempDir,
}

fn config(data: &Path, name: &str) -> EngineConfig {
    let mut cfg = EngineConfig::new(data, name);
    cfg.port = 0;
    cfg.enable_mdns = false;
    cfg.loopback = true;
    cfg
}

async fn node(name: &str) -> Node {
    let dir = tempfile::tempdir().unwrap();
    let downloads = dir.path().join("downloads");
    node_with_files(name, dir, Arc::new(DesktopFiles { download_dir: downloads })).await
}

async fn node_with_files(name: &str, dir: tempfile::TempDir, files: Arc<dyn FileStore>) -> Node {
    let data = dir.path().join("data");
    let downloads = dir.path().join("downloads");
    let secrets = Arc::new(FileSecretStore::new(Store::new(&data).unwrap()));
    let engine = Engine::start(config(&data, name), secrets, files).await.unwrap();
    Node { engine, data, downloads, _dir: dir }
}

/// The same device started again from its data folder, as after the app was closed or killed.
async fn restart(node: &Node, name: &str) -> Engine {
    let secrets = Arc::new(FileSecretStore::new(Store::new(&node.data).unwrap()));
    let files = Arc::new(DesktopFiles { download_dir: node.downloads.clone() });
    Engine::start(config(&node.data, name), secrets, files).await.unwrap()
}

async fn wait_until(what: &str, mut condition: impl FnMut() -> bool) {
    let deadline = std::time::Instant::now() + Duration::from_secs(45);
    while !condition() {
        assert!(std::time::Instant::now() < deadline, "timed out waiting for: {what}");
        tokio::time::sleep(Duration::from_millis(40)).await;
    }
}

async fn expect<T>(rx: &mut Receiver<Event>, what: &str, mut pick: impl FnMut(&Event) -> Option<T>) -> T {
    let deadline = tokio::time::Instant::now() + Duration::from_secs(45);
    loop {
        let remaining = deadline.saturating_duration_since(tokio::time::Instant::now());
        match tokio::time::timeout(remaining, rx.recv()).await {
            Ok(Ok(event)) => {
                if let Some(found) = pick(&event) {
                    return found;
                }
            }
            Ok(Err(tokio::sync::broadcast::error::RecvError::Lagged(_))) => {}
            other => panic!("timed out or closed while waiting for: {what} ({other:?})"),
        }
    }
}

fn online(node: &Node, id: DeviceId) -> bool {
    node.engine.devices().iter().any(|d| d.id == id && d.online)
}

async fn pair(showing: &Node, scanning: &Node) {
    let offer = showing.engine.create_pairing_offer().unwrap();
    let joined = scanning.engine.pair_with_uri(&offer.uri).await.unwrap();
    assert_eq!(joined, showing.engine.id());
}

fn ask_first(receiver: &Node, sender: &Node) {
    receiver
        .engine
        .set_device_settings(&sender.engine.id(), DeviceSettings { auto_accept: false, ..Default::default() })
        .unwrap();
}

fn random_bytes(len: usize, seed: u8) -> Vec<u8> {
    let mut state = 0x9E37_79B9_7F4A_7C15u64 ^ seed as u64;
    (0..len)
        .map(|_| {
            state ^= state << 13;
            state ^= state >> 7;
            state ^= state << 17;
            (state >> 24) as u8
        })
        .collect()
}

fn outgoing(dir: &Path, name: &str, data: &[u8]) -> OutgoingFile {
    let path = dir.join(name);
    std::fs::write(&path, data).unwrap();
    OutgoingFile { source: path.to_string_lossy().into_owned(), name: name.to_string(), size: 0, mime: "application/octet-stream".into() }
}

async fn offered(events: &mut Receiver<Event>) -> u64 {
    expect(events, "offer", |e| match e {
        Event::ShareOffered { offer, .. } => Some(offer.id),
        _ => None,
    })
    .await
}

async fn finished_in(events: &mut Receiver<Event>, what: &str) -> TransferDone {
    expect(events, what, |e| match e {
        Event::Finished(done) if done.incoming => Some(done.clone()),
        _ => None,
    })
    .await
}

/// R-B8: the sender stops one receiver, and the other one still gets the whole file.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn stopping_one_receiver_leaves_the_other_running_to_the_end() {
    let phone = node("Phone").await;
    let mac = node("Mac").await;
    let laptop = node("Laptop").await;
    pair(&phone, &mac).await;
    pair(&phone, &laptop).await;
    wait_until("phone sees both", || online(&phone, mac.engine.id()) && online(&phone, laptop.engine.id())).await;
    // Both ask first, so the test decides who pulls when, and nothing depends on how fast loopback is.
    ask_first(&mac, &phone);
    ask_first(&laptop, &phone);

    let source = tempfile::tempdir().unwrap();
    let data = random_bytes(3 * 1024 * 1024 + 11, 21);
    let mut mac_events = mac.engine.subscribe();
    let mut laptop_events = laptop.engine.subscribe();
    let report = phone
        .engine
        .send_files(&[mac.engine.id(), laptop.engine.id()], vec![outgoing(source.path(), "film.bin", &data)], ShareOrigin::Files)
        .await
        .unwrap();
    assert_eq!(report.sent_to.len(), 2);
    let offer = offered(&mut mac_events).await;
    assert_eq!(offered(&mut laptop_events).await, offer);

    phone.engine.cancel_transfer(mac.engine.id(), offer, false).await;

    laptop.engine.accept_offer(phone.engine.id(), offer).unwrap();
    let done = finished_in(&mut laptop_events, "the laptop gets the file").await;
    assert_eq!(done.error, None, "stopping the Mac must not stop the laptop");
    assert_eq!(std::fs::read(done.location.unwrap()).unwrap(), data);

    // The Mac was stopped: whether the stop reached it before its yes or not, it does not get the file.
    let _ = mac.engine.accept_offer(phone.engine.id(), offer);
    let stopped = finished_in(&mut mac_events, "the Mac ends without the file").await;
    assert!(stopped.error.is_some(), "the stopped receiver must not get the file");
    assert!(!mac.downloads.join("film.bin").exists());
}

/// Hands out duplicates of one descriptor per file, as the Android app does (`AndroidFiles.kt`): duplicates share one read position.
#[cfg(unix)]
struct SharedDescriptor {
    held: std::sync::Mutex<std::collections::HashMap<String, std::fs::File>>,
    downloads: DesktopFiles,
}

#[cfg(unix)]
impl FileStore for SharedDescriptor {
    fn open_read(&self, source: &str) -> std::io::Result<std::fs::File> {
        use std::io::Seek;
        let mut held = self.held.lock().unwrap();
        if !held.contains_key(source) {
            held.insert(source.to_string(), std::fs::File::open(source)?);
        }
        let mut copy = held[source].try_clone()?;
        copy.seek(std::io::SeekFrom::Start(0))?;
        Ok(copy)
    }

    fn store_download(&self, temp: &Path, name: &str, mime: &str) -> std::io::Result<String> {
        self.downloads.store_download(temp, name, mime)
    }
}

/// R-V7 (R-B6): two receivers pull the same file at the same time through duplicated descriptors, and both get it whole the first time.
#[cfg(unix)]
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn two_receivers_pulling_one_shared_descriptor_both_get_the_right_file() {
    let dir = tempfile::tempdir().unwrap();
    let downloads = dir.path().join("downloads");
    let files = Arc::new(SharedDescriptor { held: Default::default(), downloads: DesktopFiles { download_dir: downloads } });
    let phone = node_with_files("Phone", dir, files).await;
    let mac = node("Mac").await;
    let laptop = node("Laptop").await;
    pair(&phone, &mac).await;
    pair(&phone, &laptop).await;
    wait_until("phone sees both", || online(&phone, mac.engine.id()) && online(&phone, laptop.engine.id())).await;

    let source = tempfile::tempdir().unwrap();
    let data = random_bytes(24 * 1024 * 1024 + 5, 22);
    let mut phone_events = phone.engine.subscribe();
    let mut mac_events = mac.engine.subscribe();
    let mut laptop_events = laptop.engine.subscribe();
    phone
        .engine
        .send_files(&[mac.engine.id(), laptop.engine.id()], vec![outgoing(source.path(), "video.mp4", &data)], ShareOrigin::Files)
        .await
        .unwrap();

    for events in [&mut mac_events, &mut laptop_events] {
        let done = finished_in(events, "the file arrives").await;
        assert_eq!(done.error, None);
        assert!(std::fs::read(done.location.unwrap()).unwrap() == data, "the file came in damaged");
    }
    // A reader that was thrown off by the other one is caught by the checksum and pulls again, which the sender hears: the first time
    // must have been right for both.
    for _ in 0..2 {
        let served = expect(&mut phone_events, "served", |e| match e {
            Event::Finished(done) if !done.incoming => Some(done.clone()),
            _ => None,
        })
        .await;
        assert_eq!(served.error, None, "a receiver got a damaged copy first");
    }
}

/// R-B14: a declined offer ends at the sender too, one finished file each, with the reason.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_declined_offer_tells_the_sender_per_file() {
    let phone = node("Phone").await;
    let mac = node("Mac").await;
    pair(&phone, &mac).await;
    wait_until("connected", || online(&phone, mac.engine.id()) && online(&mac, phone.engine.id())).await;
    ask_first(&mac, &phone);

    let source = tempfile::tempdir().unwrap();
    let mut phone_events = phone.engine.subscribe();
    let mut mac_events = mac.engine.subscribe();
    phone
        .engine
        .send_files(
            &[mac.engine.id()],
            vec![outgoing(source.path(), "a.txt", b"one"), outgoing(source.path(), "b.txt", b"two")],
            ShareOrigin::Files,
        )
        .await
        .unwrap();
    let offer = offered(&mut mac_events).await;
    mac.engine.decline_offer(phone.engine.id(), offer).await;

    let mut names = Vec::new();
    for _ in 0..2 {
        let done = expect(&mut phone_events, "the sender hears it was declined", |e| match e {
            Event::Finished(done) if !done.incoming => Some(done.clone()),
            _ => None,
        })
        .await;
        assert_eq!(done.offer, offer);
        assert_eq!(done.peer, mac.engine.id());
        assert_eq!(done.error.as_deref(), Some("declined"));
        assert_eq!(done.location, None);
        names.push(done.name);
    }
    names.sort();
    assert_eq!(names, ["a.txt", "b.txt"]);
}
