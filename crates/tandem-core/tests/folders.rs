//! Folders that travel as folders (P4) between real engines over loopback: layers, empty folders, a name that wants out, a device
//! that does not take folders, and a folder that stopped halfway across a restart. See docs/TRANSFERS.md.

use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;

use tandem_core::events::{Event, TransferDone};
use tandem_core::folders::FOLDERS_CAP;
use tandem_core::journal::ItemState;
use tandem_core::platform::{DesktopFiles, FileStore};
use tandem_core::proto::{Msg, ShareItem, ShareOffer, ShareOrigin};
use tandem_core::store::{DeviceSettings, FileSecretStore, Store};
use tandem_core::transfer::OutgoingFile;
use tandem_core::{Engine, EngineConfig};
use tokio::sync::broadcast::Receiver;

struct Node {
    engine: Engine,
    data: PathBuf,
    downloads: PathBuf,
    dir: tempfile::TempDir,
}

fn config(data: &Path, name: &str, folders: bool) -> EngineConfig {
    let mut cfg = EngineConfig::new(data, name);
    cfg.port = 0;
    cfg.enable_mdns = false;
    cfg.loopback = true;
    if folders {
        cfg.caps.push(FOLDERS_CAP.into());
    }
    cfg
}

async fn node(name: &str, folders: bool) -> Node {
    let dir = tempfile::tempdir().unwrap();
    let downloads = dir.path().join("downloads");
    node_with_files(name, folders, dir, Arc::new(DesktopFiles { download_dir: downloads })).await
}

async fn node_with_files(name: &str, folders: bool, dir: tempfile::TempDir, files: Arc<dyn FileStore>) -> Node {
    let data = dir.path().join("data");
    let downloads = dir.path().join("downloads");
    let secrets = Arc::new(FileSecretStore::new(Store::new(&data).unwrap()));
    let engine = Engine::start(config(&data, name, folders), secrets, files).await.unwrap();
    Node { engine, data, downloads, dir }
}

async fn restart(node: &Node, name: &str) -> Engine {
    let secrets = Arc::new(FileSecretStore::new(Store::new(&node.data).unwrap()));
    let files = Arc::new(DesktopFiles { download_dir: node.downloads.clone() });
    Engine::start(config(&node.data, name, true), secrets, files).await.unwrap()
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

async fn finished(events: &mut Receiver<Event>, count: usize) -> Vec<TransferDone> {
    let mut done = Vec::new();
    while done.len() < count {
        done.push(
            expect(events, "a file that came in", |e| match e {
                Event::Finished(d) if d.incoming => Some(d.clone()),
                _ => None,
            })
            .await,
        );
    }
    done.sort_by_key(|d| d.index);
    done
}

async fn paired(phone: &Node, mac: &Node) {
    let offer = phone.engine.create_pairing_offer().unwrap();
    mac.engine.pair_with_uri(&offer.uri).await.unwrap();
    let (p, m) = (phone.engine.id(), mac.engine.id());
    wait_until("connected", || phone.engine.is_connected(&m) && mac.engine.is_connected(&p)).await;
}

fn folder_of(source: &Path) -> OutgoingFile {
    let name = source.file_name().unwrap().to_string_lossy().into_owned();
    OutgoingFile { source: source.to_string_lossy().into_owned(), name, size: 0, mime: String::new() }
}

/// Trip/notes.txt, Trip/day 1/a.jpg, Trip/day 1/raw/b.dng (three layers) and Trip/empty.
fn trip(at: &Path) -> PathBuf {
    let top = at.join("Trip");
    std::fs::create_dir_all(top.join("day 1/raw")).unwrap();
    std::fs::create_dir_all(top.join("empty")).unwrap();
    std::fs::write(top.join("notes.txt"), b"notes").unwrap();
    std::fs::write(top.join("day 1/a.jpg"), vec![7u8; 300_000]).unwrap();
    std::fs::write(top.join("day 1/raw/b.dng"), vec![9u8; 1_000_000]).unwrap();
    top
}

fn everything_in(dir: &Path) -> Vec<String> {
    let mut out = Vec::new();
    let mut stack = vec![dir.to_path_buf()];
    while let Some(at) = stack.pop() {
        for entry in std::fs::read_dir(&at).into_iter().flatten().flatten() {
            let path = entry.path();
            // With `/` on every system, so one list of names holds on Windows too.
            let parts: Vec<String> = path.strip_prefix(dir).unwrap().components().map(|c| c.as_os_str().to_string_lossy().into_owned()).collect();
            out.push(parts.join("/"));
            if path.is_dir() {
                stack.push(path);
            }
        }
    }
    out.sort();
    out
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_folder_arrives_as_a_folder_with_its_layers_and_empty_folders() {
    let phone = node("Phone", true).await;
    let mac = node("Mac", true).await;
    paired(&phone, &mac).await;
    let source = tempfile::tempdir().unwrap();
    let top = trip(source.path());
    std::fs::write(source.path().join("loose.pdf"), b"pdf").unwrap();
    let loose = OutgoingFile { source: source.path().join("loose.pdf").to_string_lossy().into_owned(), name: "loose.pdf".into(), size: 3, mime: "application/pdf".into() };

    let mut events = mac.engine.subscribe();
    let report = phone.engine.send_files(&[mac.engine.id()], vec![folder_of(&top), loose], ShareOrigin::Files).await.unwrap();
    assert_eq!(report.sent_to, vec![mac.engine.id()]);
    assert!(report.no_folders.is_empty());
    let offered = expect(&mut events, "the offer", |e| match e {
        Event::ShareOffered { offer, .. } => Some(offer.clone()),
        _ => None,
    })
    .await;
    assert_eq!(offered.folders, vec!["Trip/empty".to_string()]);
    let done = finished(&mut events, 4).await;
    assert!(done.iter().all(|d| d.error.is_none()), "{done:?}");

    assert_eq!(
        everything_in(&mac.downloads),
        vec!["Trip", "Trip/day 1", "Trip/day 1/a.jpg", "Trip/day 1/raw", "Trip/day 1/raw/b.dng", "Trip/empty", "Trip/notes.txt", "loose.pdf"]
    );
    assert_eq!(std::fs::read(mac.downloads.join("Trip/day 1/raw/b.dng")).unwrap(), vec![9u8; 1_000_000]);
    // Each file says where it is now: inside the folder, not where it waited for the rest.
    let at = |name: &str| PathBuf::from(done.iter().find(|d| d.name == name).unwrap().location.clone().unwrap());
    assert_eq!(at("b.dng"), mac.downloads.join("Trip/day 1/raw/b.dng"));
    assert_eq!(at("loose.pdf"), mac.downloads.join("loose.pdf"));
    let journal = mac.engine.transfers();
    assert_eq!(journal[0].peers[0].items.iter().filter(|i| i.state == ItemState::Done).count(), 4);
    assert!(everything_in(&mac.data.join("folders")).is_empty(), "nothing waits any more");

    // The same folder again takes a name of its own next to the first.
    let report = phone.engine.send_files(&[mac.engine.id()], vec![folder_of(&top)], ShareOrigin::Files).await.unwrap();
    assert_eq!(report.sent_to.len(), 1);
    finished(&mut events, 3).await;
    assert!(mac.downloads.join("Trip (1)/day 1/raw/b.dng").exists());
    assert!(mac.downloads.join("Trip (1)/empty").is_dir());
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_folder_name_that_wants_out_stays_in_the_downloads() {
    let phone = node("Phone", true).await;
    let mac = node("Mac", true).await;
    paired(&phone, &mac).await;
    let source = tempfile::tempdir().unwrap();
    let top = trip(source.path());
    let mut events = mac.engine.subscribe();
    // The name the sender gives the folder is what the receiver builds from, so a sender can try to climb out with it.
    let sneaky = OutgoingFile { name: "../../escape".into(), ..folder_of(&top) };
    phone.engine.send_files(&[mac.engine.id()], vec![sneaky], ShareOrigin::Files).await.unwrap();
    let done = finished(&mut events, 3).await;
    assert!(done.iter().all(|d| d.error.is_none()), "{done:?}");
    for d in &done {
        assert!(Path::new(d.location.as_ref().unwrap()).starts_with(&mac.downloads), "{:?}", d.location);
    }
    assert!(!mac.dir.path().join("escape").exists() && !mac.data.join("escape").exists());
    assert!(everything_in(&mac.downloads).iter().all(|p| !p.split('/').any(|part| part == "..")));
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_device_that_does_not_take_folders_gets_nothing_and_is_named_for_a_zip() {
    let phone = node("Phone", true).await;
    let old = node("Old", false).await;
    paired(&phone, &old).await;
    let source = tempfile::tempdir().unwrap();
    let top = trip(source.path());
    let mut events = old.engine.subscribe();
    let report = phone.engine.send_files(&[old.engine.id()], vec![folder_of(&top)], ShareOrigin::Files).await.unwrap();
    assert_eq!(report.no_folders, vec![old.engine.id()]);
    assert!(report.sent_to.is_empty() && report.offline.is_empty());
    assert!(phone.engine.transfers().is_empty(), "nothing waits for it either");

    // Loose files still go to it as before.
    std::fs::write(source.path().join("a.txt"), b"a").unwrap();
    let file = OutgoingFile { source: source.path().join("a.txt").to_string_lossy().into_owned(), name: "a.txt".into(), size: 1, mime: "text/plain".into() };
    let report = phone.engine.send_files(&[old.engine.id()], vec![file], ShareOrigin::Files).await.unwrap();
    assert_eq!(report.sent_to, vec![old.engine.id()]);
    assert!(report.no_folders.is_empty());
    finished(&mut events, 1).await;

    // A device that never said it takes folders builds none, even when an offer has them: the files would come in loose.
    let id: u64 = 0xF01D;
    let item = ShareItem { name: "x.txt".into(), size: 1, path: "Trip/day 1".into(), ..ShareItem::default() };
    let crafted = ShareOffer { id, origin: ShareOrigin::Files, items: vec![item], folders: vec!["Trip/empty".into()] };
    old.engine.set_device_settings(&phone.engine.id(), DeviceSettings { auto_accept: false, ..Default::default() }).unwrap();
    phone.engine.send_msg(&[old.engine.id()], Msg::ShareOffer(crafted)).await;
    let seen = expect(&mut events, "the crafted offer", |e| match e {
        Event::ShareOffered { offer, .. } if offer.id == id => Some(offer.clone()),
        _ => None,
    })
    .await;
    assert!(seen.folders.is_empty() && seen.items[0].path.is_empty());
    let kept = old.engine.transfers().into_iter().find(|t| t.offer == id).unwrap();
    assert!(kept.folders.is_empty() && kept.items[0].path.is_empty());
}

/// Lets every file be opened except the one named `held`, which waits until the gate opens: a file that is slow to come.
struct Gate {
    inner: DesktopFiles,
    held: &'static str,
    open: Arc<AtomicBool>,
}

impl FileStore for Gate {
    fn open_read(&self, source: &str) -> std::io::Result<std::fs::File> {
        if source.ends_with(self.held) {
            let until = std::time::Instant::now() + Duration::from_secs(30);
            while !self.open.load(Ordering::SeqCst) && std::time::Instant::now() < until {
                std::thread::sleep(Duration::from_millis(20));
            }
        }
        self.inner.open_read(source)
    }

    fn store_download(&self, temp: &Path, name: &str, mime: &str) -> std::io::Result<String> {
        self.inner.store_download(temp, name, mime)
    }
}

/// A folder that stopped halfway: the files that came wait in the data folder across a restart, nothing of the folder shows in
/// the downloads, and a yes brings the rest, after which the whole folder goes to its place.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_folder_halfway_keeps_what_came_across_a_restart_and_a_yes_finishes_it() {
    let open = Arc::new(AtomicBool::new(true));
    let phone_dir = tempfile::tempdir().unwrap();
    let gate = Gate { inner: DesktopFiles { download_dir: phone_dir.path().join("downloads") }, held: "b.dng", open: open.clone() };
    let phone = node_with_files("Phone", true, phone_dir, Arc::new(gate)).await;
    let mac = node("Mac", true).await;
    paired(&phone, &mac).await;
    let source = tempfile::tempdir().unwrap();
    let top = trip(source.path());

    let report = phone.engine.send_files(&[mac.engine.id()], vec![folder_of(&top)], ShareOrigin::Files).await.unwrap();
    // Closed only once the sizes are read: from here b.dng is slow to come.
    open.store(false, Ordering::SeqCst);
    let offer = report.offer;
    let states = |engine: &Engine| -> Vec<(String, ItemState)> {
        engine
            .transfers()
            .into_iter()
            .find(|t| t.offer == offer)
            .map(|t| t.items.iter().zip(&t.peers[0].items).map(|(i, p)| (i.name.clone(), p.state)).collect())
            .unwrap_or_default()
    };
    wait_until("two files came and b.dng is on its way", || {
        let s = states(&mac.engine);
        s.iter().filter(|(_, state)| *state == ItemState::Done).count() == 2 && s.iter().any(|(n, st)| n == "b.dng" && *st == ItemState::Running)
    })
    .await;
    assert!(everything_in(&mac.downloads).is_empty(), "a folder that is not whole is not in the downloads");
    mac.engine.shutdown().await;
    open.store(true, Ordering::SeqCst);
    phone.engine.shutdown().await;

    let mac_again = restart(&mac, "Mac").await;
    let phone_again = restart(&phone, "Phone").await;
    mac_again.add_address(&phone.engine.id(), &format!("127.0.0.1:{}", phone_again.port())).unwrap();
    wait_until("connected again", || mac_again.is_connected(&phone.engine.id()) && phone_again.is_connected(&mac.engine.id())).await;
    assert!(everything_in(&mac.downloads).is_empty(), "nothing goes on by itself");
    assert_eq!(mac_again.resumable_transfers().len(), 1);

    let mut events = mac_again.subscribe();
    mac_again.resume_transfer(phone.engine.id(), offer).unwrap();
    let done = finished(&mut events, 3).await;
    assert!(done.iter().all(|d| d.error.is_none()), "{done:?}");
    assert_eq!(std::fs::read(mac.downloads.join("Trip/day 1/raw/b.dng")).unwrap(), vec![9u8; 1_000_000]);
    assert_eq!(std::fs::read(mac.downloads.join("Trip/notes.txt")).unwrap(), b"notes");
    assert!(mac.downloads.join("Trip/empty").is_dir());
}
