//! Transfers between real engines over loopback: stopping one receiver, two receivers of one file, a declined offer, and the
//! journal that keeps offers across a restart (see docs/TRANSFERS.md).

use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;

use tandem_core::events::{Event, TransferDone};
use tandem_core::journal::{ItemState, Stage};
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

fn interrupted(engine: &Engine) -> bool {
    engine.transfers().first().is_some_and(|t| t.peers[0].items.iter().all(|i| i.state == ItemState::Interrupted))
}

/// Sends `first.bin` and `second.bin` to a Mac that asks first, plants half of `second.bin` as its partial file (as a pull that
/// was cut off leaves it), and lets the Mac say yes while the phone is gone, so its files wait for the phone. Returns the offer and
/// the length of the partial file.
async fn a_transfer_left_halfway(phone: &Node, mac: &Node, source: &Path) -> (u64, u64) {
    pair(phone, mac).await;
    wait_until("connected", || online(phone, mac.engine.id()) && online(mac, phone.engine.id())).await;
    ask_first(mac, phone);
    let second = random_bytes(3 * 1024 * 1024, 31);
    let mut mac_events = mac.engine.subscribe();
    phone
        .engine
        .send_files(
            &[mac.engine.id()],
            vec![outgoing(source, "first.bin", &random_bytes(100_000, 30)), outgoing(source, "second.bin", &second)],
            ShareOrigin::Files,
        )
        .await
        .unwrap();
    let offer = offered(&mut mac_events).await;
    let partials = mac.data.join("incoming");
    std::fs::create_dir_all(&partials).unwrap();
    std::fs::write(partials.join(format!("{}-{offer:016x}-1.part", phone.engine.id())), &second[..1_200_000]).unwrap();
    phone.engine.shutdown().await;
    (offer, 1_200_000)
}

/// P1: a transfer stopped halfway is in the journal after a restart, with how far each file got, and nothing goes on by itself.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn the_journal_keeps_a_transfer_stopped_halfway_across_a_restart() {
    let phone = node("Phone").await;
    let mac = node("Mac").await;
    let source = tempfile::tempdir().unwrap();
    let (offer, planted) = a_transfer_left_halfway(&phone, &mac, source.path()).await;
    mac.engine.accept_offer(phone.engine.id(), offer).unwrap();
    wait_until("both files wait for the phone", || {
        mac.engine.transfers().first().is_some_and(|t| t.peers[0].items.iter().all(|i| i.state == ItemState::Running))
    })
    .await;

    mac.engine.shutdown().await;
    wait_until("the stop is written down", || interrupted(&mac.engine)).await;
    let mac_again = restart(&mac, "Mac").await;
    let phone_again = restart(&phone, "Phone").await;

    let transfers = mac_again.transfers();
    assert_eq!(transfers.len(), 1);
    let transfer = &transfers[0];
    assert!(transfer.incoming);
    assert_eq!(transfer.offer, offer);
    assert_eq!(transfer.origin, ShareOrigin::Files);
    assert_eq!(transfer.items.iter().map(|i| i.name.as_str()).collect::<Vec<_>>(), ["first.bin", "second.bin"]);
    assert_eq!(transfer.items[1].size, 3 * 1024 * 1024);
    assert_eq!(transfer.peers.len(), 1);
    assert_eq!(transfer.peers[0].device, phone.engine.id());
    assert_eq!(transfer.peers[0].stage, Stage::Accepted);
    let progress: Vec<_> = transfer.peers[0].items.iter().map(|i| (i.state, i.done)).collect();
    assert_eq!(progress, [(ItemState::Interrupted, 0), (ItemState::Interrupted, planted)]);
    let partial = mac.data.join("incoming").join(format!("{}-{offer:016x}-1.part", phone.engine.id()));
    assert_eq!(std::fs::metadata(&partial).unwrap().len(), planted, "the partial file must stay for a resume");

    // The sender kept its side too: the offer, for the Mac, never pulled.
    let sent = phone_again.transfers();
    assert_eq!(sent.len(), 1);
    assert!(!sent[0].incoming && sent[0].offer == offer);
    assert_eq!(sent[0].peers[0].device, mac.engine.id());
    assert_eq!(sent[0].peers[0].stage, Stage::Offered);

    // Both back and connected: still nothing moves until a person says so (P2), and a yes meant for an offer does not do it.
    let mut events = mac_again.subscribe();
    mac_again.add_address(&phone.engine.id(), &format!("127.0.0.1:{}", phone_again.port())).unwrap();
    wait_until("connected again", || mac_again.is_connected(&phone.engine.id())).await;
    assert!(mac_again.accept_offer(phone.engine.id(), offer).is_err());
    tokio::time::sleep(Duration::from_millis(1500)).await;
    while let Ok(event) = events.try_recv() {
        assert!(!matches!(event, Event::Progress(_) | Event::Finished(_)), "it went on by itself: {event:?}");
    }
    assert_eq!(std::fs::metadata(&partial).unwrap().len(), planted);
    assert!(interrupted(&mac_again));
}

/// A thread pool of its own for one device, so it can be killed like a process: nothing runs any more and nothing is written.
struct Lane(Option<tokio::runtime::Runtime>);

impl Lane {
    fn new() -> Lane {
        Lane(Some(tokio::runtime::Builder::new_multi_thread().worker_threads(2).enable_all().build().unwrap()))
    }

    fn spawn<F>(&self, future: F) -> tokio::task::JoinHandle<F::Output>
    where
        F: std::future::Future + Send + 'static,
        F::Output: Send + 'static,
    {
        self.0.as_ref().unwrap().spawn(future)
    }

    fn kill(mut self) {
        self.0.take().unwrap().shutdown_background();
    }
}

impl Drop for Lane {
    fn drop(&mut self) {
        if let Some(runtime) = self.0.take() {
            runtime.shutdown_background();
        }
    }
}

/// P1: the same when the app is killed in the middle and can write nothing more: the start finds the files underway and makes them
/// interrupted, with the length of the partial file.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_killed_receiver_finds_its_transfer_interrupted_after_the_start() {
    let lane = Lane::new();
    let phone = node("Phone").await;
    let mac = lane.spawn(node("Mac")).await.unwrap();
    let source = tempfile::tempdir().unwrap();
    let (offer, planted) = a_transfer_left_halfway(&phone, &mac, source.path()).await;
    let engine = mac.engine.clone();
    let phone_id = phone.engine.id();
    lane.spawn(async move { engine.accept_offer(phone_id, offer) }).await.unwrap().unwrap();
    wait_until("both files wait for the phone", || {
        mac.engine.transfers().first().is_some_and(|t| t.peers[0].items.iter().all(|i| i.state == ItemState::Running))
    })
    .await;

    lane.kill();
    let mac_again = restart(&mac, "Mac").await;
    let transfer = &mac_again.transfers()[0];
    assert_eq!(transfer.peers[0].stage, Stage::Accepted);
    let progress: Vec<_> = transfer.peers[0].items.iter().map(|i| (i.state, i.done)).collect();
    assert_eq!(progress, [(ItemState::Interrupted, 0), (ItemState::Interrupted, planted)]);
}

/// R-B27: a partial file that no transfer in the journal points at can never be finished, so it goes at the start, not days later.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_partial_file_nothing_points_at_goes_at_the_start() {
    let mac = node("Mac").await;
    mac.engine.shutdown().await;
    let orphan = mac.data.join("incoming").join("0000-0000000000000001-0.part");
    std::fs::create_dir_all(orphan.parent().unwrap()).unwrap();
    std::fs::write(&orphan, b"half").unwrap();
    let _again = restart(&mac, "Mac").await;
    assert!(!orphan.exists());
}

/// Leaves the transfer of `a_transfer_left_halfway` interrupted at the Mac after a restart of both, connected again. `before_phone`
/// runs while both are down, to change what the phone has. Returns the two engines started again.
async fn both_back_after_a_transfer_halfway(phone: &Node, mac: &Node, offer: u64, before_phone: impl FnOnce()) -> (Engine, Engine) {
    mac.engine.accept_offer(phone.engine.id(), offer).unwrap();
    wait_until("both files wait for the phone", || {
        mac.engine.transfers().first().is_some_and(|t| t.peers[0].items.iter().all(|i| i.state == ItemState::Running))
    })
    .await;
    mac.engine.shutdown().await;
    wait_until("the stop is written down", || interrupted(&mac.engine)).await;
    before_phone();
    let mac_again = restart(mac, "Mac").await;
    let phone_again = restart(phone, "Phone").await;
    mac_again.add_address(&phone.engine.id(), &format!("127.0.0.1:{}", phone_again.port())).unwrap();
    wait_until("connected again", || mac_again.is_connected(&phone.engine.id()) && phone_again.is_connected(&mac.engine.id())).await;
    (mac_again, phone_again)
}

async fn finished_with(events: &mut Receiver<Event>, incoming: bool, what: &str) -> TransferDone {
    expect(events, what, |e| match e {
        Event::Finished(done) if done.incoming == incoming => Some(done.clone()),
        _ => None,
    })
    .await
}

/// P2, yes: after a restart the transfer waits for the person, and a yes pulls the rest of each file from where its partial file
/// ends, whole and right.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn yes_to_a_transfer_halfway_pulls_the_rest_from_the_partial_file() {
    let phone = node("Phone").await;
    let mac = node("Mac").await;
    let source = tempfile::tempdir().unwrap();
    let (offer, planted) = a_transfer_left_halfway(&phone, &mac, source.path()).await;
    let (mac_again, _phone_again) = both_back_after_a_transfer_halfway(&phone, &mac, offer, || {}).await;

    let waiting = mac_again.resumable_transfers();
    assert_eq!(waiting.len(), 1);
    assert_eq!(waiting[0].offer, offer);
    assert_eq!(waiting[0].peers[0].items[1].done, planted);

    let mut events = mac_again.subscribe();
    mac_again.resume_transfer(phone.engine.id(), offer).unwrap();
    assert!(mac_again.resumable_transfers().is_empty(), "it is going on, so it is no longer a question");
    let mut first_progress = None;
    let mut done = Vec::new();
    while done.len() < 2 {
        match expect(&mut events, "the rest comes", |e| match e {
            Event::Progress(p) if p.incoming && p.index == 1 => Some(Err(p.done)),
            Event::Finished(d) if d.incoming => Some(Ok(d.clone())),
            _ => None,
        })
        .await
        {
            Err(at) => {
                first_progress.get_or_insert(at);
            }
            Ok(finished) => done.push(finished),
        }
    }
    assert_eq!(first_progress, Some(planted), "the second file must go on from its partial file, not from the start");
    done.sort_by_key(|d| d.index);
    assert!(done.iter().all(|d| d.error.is_none()), "{done:?}");
    assert_eq!(std::fs::read(done[0].location.as_ref().unwrap()).unwrap(), random_bytes(100_000, 30));
    assert_eq!(std::fs::read(done[1].location.as_ref().unwrap()).unwrap(), random_bytes(3 * 1024 * 1024, 31));
}

/// P2, no: throwing it away removes the partial files, ends each file as cancelled, and the sender hears it and closes its side.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn no_to_a_transfer_halfway_throws_it_away_and_tells_the_sender() {
    let phone = node("Phone").await;
    let mac = node("Mac").await;
    let source = tempfile::tempdir().unwrap();
    let (offer, _) = a_transfer_left_halfway(&phone, &mac, source.path()).await;
    let (mac_again, phone_again) = both_back_after_a_transfer_halfway(&phone, &mac, offer, || {}).await;
    let partial = mac.data.join("incoming").join(format!("{}-{offer:016x}-1.part", phone.engine.id()));
    assert!(partial.exists());

    let mut events = mac_again.subscribe();
    mac_again.discard_transfer(phone.engine.id(), offer).await.unwrap();
    for _ in 0..2 {
        assert_eq!(finished_with(&mut events, true, "each file ends").await.error.as_deref(), Some("cancelled"));
    }
    assert!(!partial.exists());
    assert!(mac_again.resumable_transfers().is_empty());
    assert!(mac_again.resume_transfer(phone.engine.id(), offer).is_err(), "thrown away is thrown away");
    wait_until("the sender closes its side", || {
        phone_again.transfers().first().is_some_and(|t| t.peers[0].stage == Stage::Closed && t.ended())
    })
    .await;
}

/// P2: a sender that no longer has the offer (its journal lost it, or it expired there) says so, and that comes as its own reason
/// with the partial files gone.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_sender_that_no_longer_has_it_says_so_as_its_own_reason() {
    let phone = node("Phone").await;
    let mac = node("Mac").await;
    let source = tempfile::tempdir().unwrap();
    let (offer, _) = a_transfer_left_halfway(&phone, &mac, source.path()).await;
    let journal = phone.data.join("transfers").join(format!("out-{offer:016x}.cbor"));
    let (mac_again, _phone_again) =
        both_back_after_a_transfer_halfway(&phone, &mac, offer, || std::fs::remove_file(&journal).unwrap()).await;

    let mut events = mac_again.subscribe();
    mac_again.resume_transfer(phone.engine.id(), offer).unwrap();
    for _ in 0..2 {
        assert_eq!(finished_with(&mut events, true, "each file ends").await.error.as_deref(), Some("gone"));
    }
    assert!(!mac.data.join("incoming").join(format!("{}-{offer:016x}-1.part", phone.engine.id())).exists());
    assert!(mac_again.resumable_transfers().is_empty());
}

/// P2: a file that changed at the sender since the offer is reported and not pulled; the rest still comes.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_file_that_changed_at_the_sender_is_reported_and_the_rest_comes() {
    let phone = node("Phone").await;
    let mac = node("Mac").await;
    let source = tempfile::tempdir().unwrap();
    let (offer, _) = a_transfer_left_halfway(&phone, &mac, source.path()).await;
    let second = source.path().join("second.bin");
    let (mac_again, _phone_again) =
        both_back_after_a_transfer_halfway(&phone, &mac, offer, || std::fs::write(&second, b"something else").unwrap()).await;

    let mut events = mac_again.subscribe();
    mac_again.resume_transfer(phone.engine.id(), offer).unwrap();
    let mut done = vec![finished_with(&mut events, true, "a file ends").await, finished_with(&mut events, true, "a file ends").await];
    done.sort_by_key(|d| d.index);
    assert_eq!(done[0].error, None);
    assert_eq!(done[1].error.as_deref(), Some("changed"));
    assert!(!mac.data.join("incoming").join(format!("{}-{offer:016x}-1.part", phone.engine.id())).exists());
}

/// P5: a receiver refuses an offer larger than its free space before anything is pulled or asked, and the sender hears why; the
/// sender can see it coming from the free space the receiver reports.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn an_offer_larger_than_the_free_space_is_refused_up_front() {
    let phone = node("Phone").await;
    let mac = node("Mac").await;
    pair(&phone, &mac).await;
    wait_until("connected", || online(&phone, mac.engine.id()) && online(&mac, phone.engine.id())).await;
    ask_first(&mac, &phone);
    mac.engine.update_status(tandem_core::proto::Status { free_storage: Some(1_000_000), ..Default::default() }).await;

    let source = tempfile::tempdir().unwrap();
    let small = vec![outgoing(source.path(), "small.bin", &random_bytes(1000, 40))];
    let large = vec![outgoing(source.path(), "large.bin", &random_bytes(2_000_000, 41))];
    wait_until("the phone knows the free space of the Mac", || {
        phone.engine.devices().iter().any(|d| d.id == mac.engine.id() && d.status.free_storage == Some(1_000_000))
    })
    .await;
    assert!(phone.engine.short_of_space(&[mac.engine.id()], &small).is_empty());
    assert_eq!(phone.engine.short_of_space(&[mac.engine.id()], &large), [mac.engine.id()]);

    let mut phone_events = phone.engine.subscribe();
    let mut mac_events = mac.engine.subscribe();
    phone.engine.send_files(&[mac.engine.id()], large, ShareOrigin::Files).await.unwrap();
    let refused = expect(&mut mac_events, "the Mac refuses", |e| match e {
        Event::ShareOffered { .. } => panic!("the person must not be asked about what cannot fit"),
        Event::Finished(done) if done.incoming => Some(done.clone()),
        _ => None,
    })
    .await;
    assert_eq!(refused.error.as_deref(), Some("no space"));
    assert_eq!(finished_with(&mut phone_events, false, "the phone hears why").await.error.as_deref(), Some("no space"));
    assert!(!mac.downloads.join("large.bin").exists());
}
