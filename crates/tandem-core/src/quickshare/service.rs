//! Quick Share as a service of the app: listening for transfers, being found on the local network, finding others and sending.
//!
//! The app gives a sink for what happens. Receiving waits for the person: the sink is told about the introduction and the app
//! answers with `respond`.

use std::collections::HashMap;
use std::net::{IpAddr, SocketAddr};
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use futures_util::future::BoxFuture;
use mdns_sd::{ServiceDaemon, ServiceEvent, ServiceInfo};
use ring::rand::{SecureRandom, SystemRandom};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::oneshot;
use tokio::task::JoinHandle;

use super::transfer::{self, DeviceKind, Introduction, Offered, Outcome, Outgoing, Receiving, Sending, encode_endpoint_info, parse_endpoint_info};
use crate::{Error, Result};

/// The service type of Quick Share, made from a hash of "NearbySharing".
pub const SERVICE_TYPE: &str = "_FC9F5ED42C8A._tcp.local.";

/// How long the person has to answer before a transfer is turned down.
const ANSWER_WITHIN: Duration = Duration::from_secs(120);

#[derive(Clone, Debug)]
pub struct Peer {
    /// What the other side calls itself on the network: four letters.
    pub id: String,
    pub name: String,
    pub kind: DeviceKind,
    pub addrs: Vec<SocketAddr>,
}

/// What the app hears. Called from tasks of the service, so a call must not block.
pub trait Sink: Send + Sync {
    fn peer_found(&self, peer: Peer);
    fn peer_lost(&self, id: String);
    /// Somebody wants to send files. Answer with `QuickShare::respond` using this id.
    fn incoming(&self, id: u64, introduction: Introduction);
    fn progress(&self, id: u64, done: u64, total: u64);
    /// All files of an incoming transfer are saved.
    fn received(&self, id: u64, paths: Vec<PathBuf>);
    /// The four digits for an outgoing transfer, to compare with the screen of the other side.
    fn pin(&self, id: u64, pin: String);
    fn sent(&self, id: u64, outcome: Outcome);
    fn failed(&self, id: u64, reason: String);
}

struct Inner {
    sink: Arc<dyn Sink>,
    next: AtomicU64,
    waiting: Mutex<HashMap<u64, oneshot::Sender<Option<PathBuf>>>>,
    peers: Mutex<HashMap<String, Peer>>,
    /// The instance names of the peers, to know which one went away.
    names: Mutex<HashMap<String, String>>,
    own_id: String,
}

pub struct QuickShare {
    inner: Arc<Inner>,
    daemon: ServiceDaemon,
    port: u16,
    tasks: Vec<JoinHandle<()>>,
    registered: String,
}

fn endpoint_id() -> String {
    let mut bytes = [0u8; 4];
    SystemRandom::new().fill(&mut bytes).expect("the system has randomness");
    bytes.iter().map(|b| (b'a' + b % 26) as char).collect()
}

/// The 10 bytes of the instance name: a marker, the four letters, the id of the service and two zeros.
fn instance_name(id: &str) -> String {
    let mut raw = vec![0x23u8];
    raw.extend_from_slice(id.as_bytes());
    raw.extend_from_slice(&[0xFC, 0x9F, 0x5E, 0, 0]);
    data_encoding::BASE64URL_NOPAD.encode(&raw)
}

fn id_of_instance(label: &str) -> Option<String> {
    let raw = data_encoding::BASE64URL_NOPAD.decode(label.as_bytes()).ok()?;
    if raw.len() != 10 || raw[0] != 0x23 {
        return None;
    }
    String::from_utf8(raw[1..5].to_vec()).ok()
}

fn useful(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => !(v4.is_loopback() || v4.is_unspecified() || v4.is_link_local()),
        IpAddr::V6(v6) => !(v6.is_loopback() || v6.is_unspecified()),
    }
}

impl QuickShare {
    /// Starts listening and being found, and looking for others.
    pub async fn start(device_name: &str, kind: DeviceKind, sink: Arc<dyn Sink>) -> Result<QuickShare> {
        let listener = TcpListener::bind(("0.0.0.0", 0)).await?;
        let port = listener.local_addr()?.port();
        let own_id = endpoint_id();

        let daemon = ServiceDaemon::new().map_err(|e| Error::Connection(format!("mdns: {e}")))?;
        let instance = instance_name(&own_id);
        let info = encode_endpoint_info(device_name, kind);
        let txt = data_encoding::BASE64URL_NOPAD.encode(&info);
        let host = format!("tandem-{own_id}.local.");
        let service = ServiceInfo::new(SERVICE_TYPE, &instance, &host, "", port, &[("n", txt.as_str())][..])
            .map_err(|e| Error::Connection(format!("mdns service: {e}")))?
            .enable_addr_auto();
        let registered = service.get_fullname().to_string();
        daemon.register(service).map_err(|e| Error::Connection(format!("mdns register: {e}")))?;
        let browse = daemon.browse(SERVICE_TYPE).map_err(|e| Error::Connection(format!("mdns browse: {e}")))?;

        let inner = Arc::new(Inner {
            sink,
            next: AtomicU64::new(1),
            waiting: Mutex::new(HashMap::new()),
            peers: Mutex::new(HashMap::new()),
            names: Mutex::new(HashMap::new()),
            own_id,
        });

        let mut tasks = Vec::new();

        // Others who can be found. The receiver is a blocking channel, so it is read on a thread of its own.
        let seen = inner.clone();
        std::thread::Builder::new()
            .name("tandem-quickshare-mdns".into())
            .spawn(move || {
                while let Ok(event) = browse.recv() {
                    match event {
                        ServiceEvent::ServiceResolved(resolved) => {
                            let fullname = resolved.get_fullname().to_string();
                            let label = fullname.split('.').next().unwrap_or("");
                            let Some(id) = id_of_instance(label) else { continue };
                            if id == seen.own_id {
                                continue;
                            }
                            let Some(text) = resolved.get_property_val_str("n") else { continue };
                            let Ok(raw) = data_encoding::BASE64URL_NOPAD.decode(text.trim_end_matches('=').as_bytes()) else { continue };
                            let Some((name, kind)) = parse_endpoint_info(&raw) else { continue };
                            let port = resolved.get_port();
                            let addrs: Vec<SocketAddr> = resolved
                                .get_addresses()
                                .iter()
                                .map(|a| a.to_ip_addr())
                                .filter(|ip| useful(*ip))
                                .map(|ip| SocketAddr::new(ip, port))
                                .collect();
                            if addrs.is_empty() {
                                continue;
                            }
                            let peer = Peer { id: id.clone(), name, kind, addrs };
                            seen.peers.lock().unwrap().insert(id.clone(), peer.clone());
                            seen.names.lock().unwrap().insert(fullname, id);
                            seen.sink.peer_found(peer);
                        }
                        ServiceEvent::ServiceRemoved(_, fullname) => {
                            let id = seen.names.lock().unwrap().remove(&fullname);
                            if let Some(id) = id {
                                seen.peers.lock().unwrap().remove(&id);
                                seen.sink.peer_lost(id);
                            }
                        }
                        _ => {}
                    }
                }
            })
            .map_err(Error::from)?;

        // Transfers that come in.
        let accepting = inner.clone();
        tasks.push(tokio::spawn(async move {
            loop {
                let Ok((stream, _)) = listener.accept().await else { break };
                let _ = stream.set_nodelay(true);
                let inner = accepting.clone();
                tokio::spawn(async move { inner.take_in(stream).await });
            }
        }));

        Ok(QuickShare { inner, daemon, port, tasks, registered })
    }

    pub fn port(&self) -> u16 {
        self.port
    }

    pub fn peers(&self) -> Vec<Peer> {
        self.inner.peers.lock().unwrap().values().cloned().collect()
    }

    /// The answer of the person to an incoming transfer: `Some(folder)` takes it in, `None` turns it down.
    pub fn respond(&self, id: u64, folder: Option<PathBuf>) {
        if let Some(answer) = self.inner.waiting.lock().unwrap().remove(&id) {
            let _ = answer.send(folder);
        }
    }

    /// Sends files to a peer that was found. Gives the id of the transfer at once; what happens after comes to the sink.
    pub fn send(&self, peer_id: &str, own_name: &str, own_kind: DeviceKind, files: Vec<Outgoing>) -> Result<u64> {
        let peer = self.inner.peers.lock().unwrap().get(peer_id).cloned().ok_or(Error::NotConnected)?;
        let id = self.inner.next.fetch_add(1, Ordering::SeqCst);
        let inner = self.inner.clone();
        let own_name = own_name.to_string();
        tokio::spawn(async move {
            let stream = connect(&peer.addrs).await;
            let outcome = match stream {
                Ok(stream) => {
                    let mut watcher = Watcher { id, inner: inner.clone() };
                    transfer::send(stream, &own_name, own_kind, &files, &mut watcher).await
                }
                Err(e) => Err(e),
            };
            match outcome {
                Ok(outcome) => inner.sink.sent(id, outcome),
                Err(e) => inner.sink.failed(id, e.to_string()),
            }
        });
        Ok(id)
    }

    pub fn stop(&mut self) {
        for task in self.tasks.drain(..) {
            task.abort();
        }
        let _ = self.daemon.unregister(&self.registered);
        let _ = self.daemon.shutdown();
    }
}

impl Drop for QuickShare {
    fn drop(&mut self) {
        self.stop();
    }
}

async fn connect(addrs: &[SocketAddr]) -> Result<TcpStream> {
    let mut last = Error::NotConnected;
    for addr in addrs {
        match tokio::time::timeout(Duration::from_secs(5), TcpStream::connect(addr)).await {
            Ok(Ok(stream)) => {
                let _ = stream.set_nodelay(true);
                return Ok(stream);
            }
            Ok(Err(e)) => last = e.into(),
            Err(_) => last = Error::NotConnected,
        }
    }
    Err(last)
}

impl Inner {
    async fn take_in(self: Arc<Self>, stream: TcpStream) {
        let id = self.next.fetch_add(1, Ordering::SeqCst);
        let mut bridge = Bridge { id, inner: self.clone(), sizes: HashMap::new(), received: HashMap::new(), total: 0 };
        match transfer::receive(stream, &mut bridge).await {
            Ok(paths) if paths.is_empty() => {}
            Ok(paths) => self.sink.received(id, paths),
            Err(e) => self.sink.failed(id, e.to_string()),
        }
    }
}

struct Bridge {
    id: u64,
    inner: Arc<Inner>,
    sizes: HashMap<i64, u64>,
    received: HashMap<i64, u64>,
    total: u64,
}

impl Receiving for Bridge {
    fn introduced<'a>(&'a mut self, introduction: &'a Introduction) -> BoxFuture<'a, Option<PathBuf>> {
        self.sizes = introduction.files.iter().map(|f| (f.payload_id, f.size)).collect();
        self.total = introduction.files.iter().map(|f| f.size).sum();
        let (answer, wait) = oneshot::channel();
        self.inner.waiting.lock().unwrap().insert(self.id, answer);
        self.inner.sink.incoming(self.id, introduction.clone());
        let (inner, id) = (self.inner.clone(), self.id);
        Box::pin(async move {
            let choice = tokio::time::timeout(ANSWER_WITHIN, wait).await;
            inner.waiting.lock().unwrap().remove(&id);
            match choice {
                Ok(Ok(folder)) => folder,
                _ => None,
            }
        })
    }

    fn progress(&mut self, file: &Offered, received: u64) {
        self.received.insert(file.payload_id, received);
        let done: u64 = self.received.values().sum();
        self.inner.sink.progress(self.id, done, self.total);
    }
}

struct Watcher {
    id: u64,
    inner: Arc<Inner>,
}

impl Sending for Watcher {
    fn pin(&mut self, pin: &str) {
        self.inner.sink.pin(self.id, pin.to_string());
    }

    fn progress(&mut self, sent: u64, total: u64) {
        self.inner.sink.progress(self.id, sent, total);
    }
}

impl Outgoing {
    /// A file on disk, with the type it has by its name.
    pub fn from_path(path: PathBuf) -> std::io::Result<Outgoing> {
        let size = std::fs::metadata(&path)?.len();
        let name = path.file_name().and_then(|n| n.to_str()).unwrap_or("file").to_string();
        let mime = mime_of(&name).to_string();
        Ok(Outgoing { path, name, mime, size })
    }
}

fn mime_of(name: &str) -> &'static str {
    match name.rsplit('.').next().map(str::to_ascii_lowercase).as_deref() {
        Some("jpg" | "jpeg") => "image/jpeg",
        Some("png") => "image/png",
        Some("gif") => "image/gif",
        Some("webp") => "image/webp",
        Some("heic") => "image/heic",
        Some("mp4") => "video/mp4",
        Some("mov") => "video/quicktime",
        Some("mkv") => "video/x-matroska",
        Some("mp3") => "audio/mpeg",
        Some("m4a") => "audio/mp4",
        Some("wav") => "audio/wav",
        Some("flac") => "audio/flac",
        Some("pdf") => "application/pdf",
        Some("txt") => "text/plain",
        Some("zip") => "application/zip",
        _ => "application/octet-stream",
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_instance_name_holds_the_four_letters_and_gives_them_back() {
        let name = instance_name("abcd");
        assert_eq!(name.len(), 14);
        assert_eq!(id_of_instance(&name), Some("abcd".to_string()));
        assert_eq!(id_of_instance("not-a-name"), None);
    }

    #[test]
    fn files_get_the_type_of_their_name() {
        assert_eq!(mime_of("Photo.JPG"), "image/jpeg");
        assert_eq!(mime_of("noextension"), "application/octet-stream");
    }
}

#[cfg(test)]
mod network_tests {
    use super::*;
    use tokio::sync::mpsc;

    /// Collects what the sink hears.
    struct Hears {
        peers: mpsc::UnboundedSender<Peer>,
        incoming: mpsc::UnboundedSender<(u64, Introduction)>,
        received: mpsc::UnboundedSender<Vec<PathBuf>>,
        sent: mpsc::UnboundedSender<Outcome>,
    }

    impl Sink for Hears {
        fn peer_found(&self, peer: Peer) {
            let _ = self.peers.send(peer);
        }
        fn peer_lost(&self, _id: String) {}
        fn incoming(&self, id: u64, introduction: Introduction) {
            let _ = self.incoming.send((id, introduction));
        }
        fn progress(&self, _id: u64, _done: u64, _total: u64) {}
        fn received(&self, _id: u64, paths: Vec<PathBuf>) {
            let _ = self.received.send(paths);
        }
        fn pin(&self, _id: u64, _pin: String) {}
        fn sent(&self, _id: u64, outcome: Outcome) {
            let _ = self.sent.send(outcome);
        }
        fn failed(&self, _id: u64, reason: String) {
            panic!("a transfer failed: {reason}");
        }
    }

    /// Two instances on one machine find each other over multicast DNS and send a file over a real connection. Needs a network
    /// that allows multicast, so it is not part of the ordinary run: `cargo test -- --ignored quickshare`.
    #[tokio::test(flavor = "multi_thread")]
    #[ignore]
    async fn two_instances_find_each_other_and_send_a_file() {
        let (peers_a, _seen_a) = mpsc::unbounded_channel();
        let (incoming_a, mut asked_a) = mpsc::unbounded_channel();
        let (received_a, mut got_a) = mpsc::unbounded_channel();
        let (sent_a, _) = mpsc::unbounded_channel();
        let a = QuickShare::start("Receiver", DeviceKind::Laptop, Arc::new(Hears { peers: peers_a, incoming: incoming_a, received: received_a, sent: sent_a }))
            .await
            .unwrap();

        let (peers_b, mut seen_b) = mpsc::unbounded_channel();
        let (incoming_b, _) = mpsc::unbounded_channel();
        let (received_b, _) = mpsc::unbounded_channel();
        let (sent_b, mut done_b) = mpsc::unbounded_channel();
        let b = QuickShare::start("Sender", DeviceKind::Phone, Arc::new(Hears { peers: peers_b, incoming: incoming_b, received: received_b, sent: sent_b }))
            .await
            .unwrap();

        let found = tokio::time::timeout(Duration::from_secs(20), async {
            loop {
                let peer = seen_b.recv().await.unwrap();
                if peer.name == "Receiver" {
                    return peer;
                }
            }
        })
        .await
        .expect("the receiver is found over multicast DNS");

        let from = tempfile::tempdir().unwrap();
        let to = tempfile::tempdir().unwrap();
        std::fs::write(from.path().join("hello.txt"), b"hello from the sender").unwrap();
        b.send(&found.id, "Sender", DeviceKind::Phone, vec![Outgoing::from_path(from.path().join("hello.txt")).unwrap()]).unwrap();
        let (id, intro) = tokio::time::timeout(Duration::from_secs(10), asked_a.recv()).await.unwrap().unwrap();
        assert_eq!(intro.sender, "Sender");
        a.respond(id, Some(to.path().to_path_buf()));
        let paths = tokio::time::timeout(Duration::from_secs(10), got_a.recv()).await.unwrap().unwrap();
        assert_eq!(std::fs::read(&paths[0]).unwrap(), b"hello from the sender");
        assert_eq!(tokio::time::timeout(Duration::from_secs(10), done_b.recv()).await.unwrap().unwrap(), Outcome::Sent);
    }
}
