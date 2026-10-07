//! The engine: one object per device that owns the identity, the circle, the
//! socket and every connection, and that the app talks to.
//!
//! It needs an ambient tokio runtime. The FFI layer builds one; the daemon and the
//! tests already have one.

use std::collections::{HashMap, HashSet};
use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, RwLock};
use std::time::{Duration, Instant};

use tokio::sync::{Notify, Semaphore, broadcast, mpsc};
use tokio_util::sync::CancellationToken;
use tracing::{debug, info, warn};

use crate::circle::{Circle, Statement};
use crate::discovery::{self, Discovery, Hint, Sighting};
use crate::error::{Error, Result};
use crate::events::Event;
use crate::identity::Identity;
use crate::ids::{DeviceId, Platform, now_ms};
use crate::net::{self, AddrBook, Route};
use crate::pairing::PendingPairing;
use crate::platform::FileStore;
use crate::proto::{Hello, Msg, PROTOCOL_VERSION, ShareOrigin, ShareText, Status};
use crate::session::Session;
use crate::store::{DeviceSettings, SecretStore, Settings, Store, load_or_create_identity};
use crate::tls::{self, Tuning};
use crate::transfer::{InOffer, OutOffer, OutgoingFile};

#[derive(Clone)]
pub struct EngineConfig {
    pub data_dir: PathBuf,
    pub device_name: String,
    pub platform: Platform,
    pub model: Option<String>,
    pub app_version: String,
    /// UDP port to listen on. Zero lets the system choose. The default is tried first
    /// so Tailscale and firewalls can rely on it.
    pub port: u16,
    pub enable_mdns: bool,
    pub caps: Vec<String>,
    pub tuning: Tuning,
    /// Advertise 127.0.0.1 as an address. Only for tests and single-machine setups.
    pub loopback: bool,
}

impl EngineConfig {
    pub fn new(data_dir: impl Into<PathBuf>, device_name: impl Into<String>) -> Self {
        EngineConfig {
            data_dir: data_dir.into(),
            device_name: device_name.into(),
            platform: Platform::current(),
            model: None,
            app_version: env!("CARGO_PKG_VERSION").to_string(),
            port: net::DEFAULT_PORT,
            enable_mdns: true,
            caps: vec!["clipboard".into(), "share".into()],
            tuning: Tuning::default(),
            loopback: false,
        }
    }
}

/// A device as the interface shows it.
#[derive(Clone, Debug)]
pub struct DeviceInfo {
    pub id: DeviceId,
    pub name: String,
    pub platform: Platform,
    pub online: bool,
    pub route: Option<Route>,
    pub rtt_ms: Option<u32>,
    pub status: Status,
    pub app_version: Option<String>,
    pub caps: Vec<String>,
    /// The device that vouched for this one has since been removed. Worth a look.
    pub vouched_by_removed: bool,
    pub settings: DeviceSettings,
    /// A Bluetooth link to this device is up, whether or not there is a network connection.
    pub ble: bool,
}

pub(crate) struct Peer {
    pub key: [u8; 32],
    pub addrs: AddrBook,
    pub session: Option<Arc<Session>>,
    pub dialing: bool,
    pub fail_count: u32,
    pub next_dial: Instant,
    pub status: Status,
    pub hello: Option<Hello>,
}

pub(crate) struct Inner {
    pub cfg: EngineConfig,
    pub identity: Arc<Identity>,
    pub my_id: DeviceId,
    pub my_name: RwLock<String>,
    pub circle: RwLock<Circle>,
    pub endpoint: quinn::Endpoint,
    pub port: u16,
    pub store: Store,
    pub files: Arc<dyn FileStore>,
    pub file_service: crate::files::FileService,
    pub live: crate::live::Live,
    pub events: broadcast::Sender<Event>,
    pub peers: Mutex<HashMap<DeviceId, Peer>>,
    pub out_offers: Mutex<HashMap<u64, OutOffer>>,
    pub in_offers: Mutex<HashMap<(DeviceId, u64), Arc<InOffer>>>,
    pub pairing: Mutex<Option<PendingPairing>>,
    pub open_pairings: Mutex<HashMap<discovery::Hint, crate::pairing::OpenPairing>>,
    pub my_status: Mutex<Status>,
    pub settings: RwLock<Settings>,
    pub last_remote_clip: Mutex<Option<u64>>,
    pub poke: Notify,
    pub sessions_changed: Notify,
    pub cancel: CancellationToken,
    pub boot_id: u64,
    pub addrs_dirty: AtomicBool,
    pub session_serial: AtomicU64,
    pub discovery: Mutex<Option<Discovery>>,
    pub accept_slots: Arc<Semaphore>,
    pub last_announce_hour: AtomicU64,
    pub ble: Mutex<crate::ble::BleHub>,
    pub audio: RwLock<Option<Arc<dyn AudioSink>>>,
    pub ble_msg_id: std::sync::atomic::AtomicU8,
}

/// Where the sound of the other device goes. Called on the connection's own task for every
/// packet, so it must only queue the samples and return.
pub trait AudioSink: Send + Sync {
    fn audio(&self, from: DeviceId, stream: u8, seq: u32, pcm: &[u8]);
}

#[derive(Clone)]
pub struct Engine {
    pub(crate) inner: Arc<Inner>,
}

impl Engine {
    pub async fn start(
        cfg: EngineConfig,
        secrets: Arc<dyn SecretStore>,
        files: Arc<dyn FileStore>,
    ) -> Result<Engine> {
        let store = Store::new(&cfg.data_dir)?;
        let identity = load_or_create_identity(secrets.as_ref())?;
        let my_id = identity.id();

        let mut circle = match store.read_cbor::<Vec<Statement>>("circle.cbor")? {
            Some(statements) => Circle::from_statements(statements),
            None => Circle::default(),
        };
        if !circle.is_member(&identity.public_key()) {
            // First run, or the identity was reset: start a circle of one.
            circle = Circle::genesis(&identity, &cfg.device_name, cfg.platform);
            store.write_cbor("circle.cbor", &circle.statements())?;
        }

        let settings = store.read_cbor::<Settings>("settings.cbor")?.unwrap_or_default();
        let endpoint = net::make_endpoint(&identity, cfg.port, cfg.tuning)?;
        let port = net::local_port(&endpoint);
        let (events, _) = broadcast::channel(2048);
        let file_service = crate::files::FileService::new(store.clone());
        let live = crate::live::Live::new(store.clone());

        let inner = Arc::new(Inner {
            my_name: RwLock::new(cfg.device_name.clone()),
            cfg,
            identity: Arc::new(identity),
            my_id,
            circle: RwLock::new(circle),
            endpoint,
            port,
            store,
            files,
            file_service,
            live,
            events,
            peers: Mutex::new(HashMap::new()),
            out_offers: Mutex::new(HashMap::new()),
            in_offers: Mutex::new(HashMap::new()),
            pairing: Mutex::new(None),
            open_pairings: Mutex::new(HashMap::new()),
            my_status: Mutex::new(Status::default()),
            settings: RwLock::new(settings),
            last_remote_clip: Mutex::new(None),
            poke: Notify::new(),
            sessions_changed: Notify::new(),
            cancel: CancellationToken::new(),
            boot_id: rand_u64(),
            addrs_dirty: AtomicBool::new(false),
            session_serial: AtomicU64::new(1),
            discovery: Mutex::new(None),
            accept_slots: Arc::new(Semaphore::new(16)),
            last_announce_hour: AtomicU64::new(discovery::current_hour()),
            ble: Mutex::new(Default::default()),
            audio: RwLock::new(None),
            ble_msg_id: std::sync::atomic::AtomicU8::new(0),
        });

        // The peers must exist before their saved addresses can be attached to them.
        inner.sync_peers();
        inner.load_addresses();
        inner.clean_partials();
        info!(id = %inner.my_id, port = inner.port, "tandem engine started");

        tokio::spawn(inner.clone().accept_loop());
        tokio::spawn(inner.clone().supervise());
        if inner.cfg.enable_mdns {
            inner.clone().start_discovery();
        }
        Ok(Engine { inner })
    }

    pub fn id(&self) -> DeviceId {
        self.inner.my_id
    }

    pub fn name(&self) -> String {
        self.inner.my_name.read().unwrap().clone()
    }

    pub fn port(&self) -> u16 {
        self.inner.port
    }

    pub fn subscribe(&self) -> broadcast::Receiver<Event> {
        self.inner.events.subscribe()
    }

    pub fn devices(&self) -> Vec<DeviceInfo> {
        self.inner.devices()
    }

    /// Tell the engine the network changed (Wi-Fi to cellular, VPN up or down), so it
    /// reconnects right away instead of waiting for the next retry.
    pub fn network_changed(&self) {
        self.inner.network_changed();
    }

    pub async fn rename_self(&self, name: &str) -> Result<()> {
        *self.inner.my_name.write().unwrap() = name.to_string();
        let statement = {
            let mut circle = self.inner.circle.write().unwrap();
            circle.rename_self(&self.inner.identity, name)?
        };
        self.inner.persist_circle();
        self.inner.gossip(vec![statement], None).await;
        self.inner.emit(Event::DevicesChanged);
        Ok(())
    }

    /// Removes a device from the circle for good. Every other device learns of it.
    pub async fn remove_device(&self, id: DeviceId) -> Result<()> {
        let statement = {
            let mut circle = self.inner.circle.write().unwrap();
            circle.remove(&self.inner.identity, id)?
        };
        self.inner.persist_circle();
        self.inner.gossip(vec![statement], None).await;
        self.inner.sync_peers();
        self.inner.emit(Event::CircleChanged);
        self.inner.emit(Event::DevicesChanged);
        Ok(())
    }

    pub fn device_settings(&self, id: &DeviceId) -> DeviceSettings {
        self.inner.settings.read().unwrap().for_device(id)
    }

    pub fn set_device_settings(&self, id: &DeviceId, settings: DeviceSettings) -> Result<()> {
        {
            self.inner.settings.write().unwrap().set_device(id, settings);
        }
        let snapshot = self.inner.settings.read().unwrap().clone();
        self.inner.store.write_cbor("settings.cbor", &snapshot)?;
        self.inner.emit(Event::DevicesChanged);
        Ok(())
    }

    /// Sends files to every listed device that is connected right now.
    // ---- Files ----------------------------------------------------------------

    /// The files of another device, as far as it lets this one see them.
    pub fn files(&self, peer: DeviceId) -> crate::files::FsClient {
        crate::files::FsClient { inner: self.inner.clone(), peer }
    }

    /// What the device may do with the files of this one.
    pub fn file_policy(&self, id: &DeviceId) -> crate::files::FilePolicy {
        self.inner.file_service.policy(id)
    }

    /// Whether the device has choices of its own, or is on the default.
    pub fn has_own_file_policy(&self, id: &DeviceId) -> bool {
        self.inner.file_service.has_own_policy(id)
    }

    pub fn file_default_policy(&self) -> crate::files::FilePolicy {
        self.inner.file_service.default_policy()
    }

    pub fn set_file_policy(&self, id: &DeviceId, policy: crate::files::FilePolicy) -> Result<()> {
        self.inner.file_service.set_policy(id, policy)
    }

    pub fn clear_file_policy(&self, id: &DeviceId) -> Result<()> {
        self.inner.file_service.clear_policy(id)
    }

    pub fn set_file_default_policy(&self, policy: crate::files::FilePolicy) -> Result<()> {
        self.inner.file_service.set_default_policy(policy)
    }

    /// What other devices did to the files of this one, newest first.
    pub fn file_activity(&self) -> Vec<crate::files::FileActivity> {
        self.inner.file_service.activity()
    }

    pub async fn send_files(
        &self,
        targets: &[DeviceId],
        files: Vec<OutgoingFile>,
        origin: ShareOrigin,
    ) -> Result<crate::transfer::SendReport> {
        self.inner.send_files(targets, files, origin).await
    }

    /// Sends bytes held in memory (a clipboard image, a snippet) as a file.
    pub async fn send_bytes(
        &self,
        targets: &[DeviceId],
        name: &str,
        mime: &str,
        data: Vec<u8>,
        origin: ShareOrigin,
    ) -> Result<crate::transfer::SendReport> {
        let dir = self.inner.store.dir().join("outgoing");
        std::fs::create_dir_all(&dir)?;
        let path = dir.join(format!("{}-{}", now_ms(), crate::platform::sanitize_name(name)));
        std::fs::write(&path, &data)?;
        let file = OutgoingFile {
            source: path.to_string_lossy().into_owned(),
            name: name.to_string(),
            size: data.len() as u64,
            mime: mime.to_string(),
        };
        self.inner.send_files(targets, vec![file], origin).await
    }

    pub async fn send_text(&self, targets: &[DeviceId], text: &str, is_url: bool, open: bool) -> Vec<DeviceId> {
        let msg = Msg::ShareText(ShareText { id: rand_u64(), text: text.to_string(), is_url, open });
        self.inner.send_many(targets, msg).await
    }

    /// The local clipboard changed. Sends it to every device that syncs the clipboard,
    /// unless it is exactly what just arrived from another device.
    pub async fn clipboard_changed(&self, text: &str, is_url: bool) {
        self.inner.clipboard_changed(text, is_url).await;
    }

    /// Sends the clipboard once, to the chosen devices, ignoring the sync settings.
    pub async fn send_clipboard(&self, targets: &[DeviceId], text: &str, is_url: bool) -> Vec<DeviceId> {
        self.inner.send_clipboard(targets, text, is_url).await
    }

    /// Updates what this device reports about itself and sends the change on.
    pub async fn update_status(&self, change: Status) {
        {
            self.inner.my_status.lock().unwrap().merge(&change);
        }
        self.inner.broadcast(Msg::Status(change)).await;
    }

    /// Any control message, for the feature modules (notifications, calls, input).
    pub async fn send_msg(&self, targets: &[DeviceId], msg: Msg) -> Vec<DeviceId> {
        self.inner.send_many(targets, msg).await
    }

    /// Whether this device and `id` share a Bluetooth key yet. It is made the first time they
    /// connect over the network, so a phone that has never met the Mac cannot use it.
    pub fn ble_ready(&self, id: &DeviceId) -> bool {
        self.inner.ble_has_key(id)
    }

    /// The first writes on a new Bluetooth link, which tell the other side who this is.
    pub fn ble_hello(&self, id: &DeviceId, chunk: usize) -> Vec<Vec<u8>> {
        self.inner.ble_hello(id, chunk)
    }

    /// The writes waiting to go to `id` over Bluetooth, each at most `chunk` bytes.
    pub fn ble_take_outbox(&self, id: &DeviceId, chunk: usize) -> Vec<Vec<u8>> {
        self.inner.ble_take(id, chunk)
    }

    /// One write that arrived over Bluetooth on the link named `link`. When it completes a
    /// message from a device in the circle, the message is handled and that device is returned.
    pub async fn ble_receive(&self, link: &str, chunk: &[u8]) -> Option<DeviceId> {
        self.inner.ble_receive(link, chunk).await
    }

    /// Queues a message for `id` over Bluetooth, even if a network connection exists. False when
    /// there is no link, no shared key, or the message is not one that may cross the air.
    pub fn ble_send(&self, id: &DeviceId, msg: Msg) -> bool {
        self.inner.ble_queue(id, msg)
    }

    /// The Bluetooth link to `id` went away.
    pub fn ble_link_down(&self, id: &DeviceId) {
        self.inner.ble_link_down(id);
    }

    /// The link named `link` closed: forget its half-received message.
    pub fn ble_drop_link(&self, link: &str) {
        self.inner.ble_drop_link(link);
    }

    /// Shows a pairing code (as a QR) that another device can scan to join the circle.
    pub fn create_pairing_offer(&self) -> Result<crate::pairing::PairingOffer> {
        self.inner.create_pairing_offer()
    }

    pub fn cancel_pairing_offer(&self) {
        self.inner.cancel_pairing_offer();
    }

    /// Pairs with the device that shows this code: the link from its QR code, or the short code that was typed. A device
    /// that is alone joins the circle of the other. A device that is in a circle takes the other one into it, when that
    /// one is alone.
    pub async fn pair_with_uri(&self, uri: &str) -> Result<DeviceId> {
        self.inner.pair_with_uri(uri).await
    }

    /// Tries a short code on the device at these addresses, without looking for it on the network. For tests.
    #[doc(hidden)]
    pub async fn pair_with_code_at(&self, code: &str, addrs: &[std::net::SocketAddr]) -> Result<DeviceId> {
        use crate::pairing::CodeFailure;
        let code = crate::pairing::normalize_code(code).ok_or_else(|| Error::Pairing("a pairing code has 8 digits".into()))?;
        self.inner.pair_with_code_at(&code, addrs).await.map_err(|failure| match failure {
            CodeFailure::NoMatch => Error::Pairing("the code did not match".into()),
            CodeFailure::Skip(e) | CodeFailure::Fatal(e) => e,
        })
    }

    /// False once another device removed this one from the circle.
    pub fn is_member(&self) -> bool {
        self.inner.circle.read().unwrap().is_member(&self.inner.identity.public_key())
    }

    /// For offers from a device set to ask first: start receiving.
    pub fn accept_offer(&self, from: DeviceId, offer_id: u64) -> Result<()> {
        self.inner.accept_offer(from, offer_id)
    }

    pub async fn decline_offer(&self, from: DeviceId, offer_id: u64) {
        self.inner.decline_offer(from, offer_id).await;
    }

    pub fn is_connected(&self, id: &DeviceId) -> bool {
        self.inner.session_of(id).is_some()
    }

    /// The network addresses this device was seen at, without the port, the likeliest first. For things that talk to the
    /// device by themselves, like the terminal of the app, which needs somewhere to log in to.
    pub fn device_ips(&self, id: &DeviceId) -> Vec<String> {
        let peers = self.inner.peers.lock().unwrap();
        let Some(peer) = peers.get(id) else { return Vec::new() };
        let mut out: Vec<String> = Vec::new();
        for known in peer.addrs.ordered() {
            let Ok(addr) = known.addr.parse::<std::net::SocketAddr>() else { continue };
            let ip = addr.ip().to_canonical().to_string();
            if !out.contains(&ip) {
                out.push(ip);
            }
        }
        out
    }

    pub fn round_trip_ms(&self, id: &DeviceId) -> Option<u32> {
        self.inner.session_of(id).map(|s| s.conn.rtt().as_millis() as u32)
    }

    /// Signs with this device's identity. For proofs that travel outside a QUIC
    /// session, such as the Bluetooth hotspot request.
    pub fn sign_message(&self, message: &[u8]) -> Vec<u8> {
        self.inner.identity.sign(message).to_vec()
    }

    /// True only when `id` is a current member of the circle and `signature` is that
    /// member's Ed25519 signature over `message`. A device that has itself been removed
    /// from the circle vouches for nobody.
    pub fn verify_member(&self, id: &DeviceId, message: &[u8], signature: &[u8]) -> bool {
        let circle = self.inner.circle.read().unwrap();
        if !circle.is_member(&self.inner.identity.public_key()) {
            return false;
        }
        match circle.member(id) {
            Some(member) => crate::identity::verify_signature(&member.public_key, message, signature),
            None => false,
        }
    }

    /// Tells the engine where a circle member can be reached right now (for example a
    /// phone's hotspot gateway) and dials it without waiting for the retry backoff.
    pub fn add_address(&self, id: &DeviceId, addr: &str) -> Result<()> {
        let socket: SocketAddr = addr
            .trim()
            .parse()
            .map_err(|_| Error::invalid("the address must look like ip:port"))?;
        self.inner.add_address(id, socket)
    }

    /// Sends the datagram (a pointer movement) unreliably and as fast as possible.
    pub fn send_datagram(&self, id: &DeviceId, data: bytes::Bytes) -> Result<()> {
        let session = self.inner.session_of(id).ok_or(Error::NotConnected)?;
        session.conn.send_datagram(data).map_err(Error::connection)
    }

    /// Where incoming sound is delivered. Without one it is dropped.
    pub fn set_audio_sink(&self, sink: Arc<dyn AudioSink>) {
        *self.inner.audio.write().unwrap() = Some(sink);
    }

    /// How many bytes of samples fit in one datagram to this device, 0 when unknown. The sender cuts
    /// its sound to this, and keeps it a whole number of sample frames.
    pub fn audio_payload_limit(&self, id: &DeviceId) -> usize {
        self.inner
            .session_of(id)
            .and_then(|s| s.conn.max_datagram_size())
            .map(|max| max.saturating_sub(crate::session::AUDIO_HEADER))
            .unwrap_or(0)
    }

    /// Sends one piece of sound, unreliably: a late packet is worth nothing, so none is retried.
    pub fn send_audio(&self, id: &DeviceId, stream: u8, seq: u32, pcm: &[u8]) -> Result<()> {
        self.send_datagram(id, crate::session::audio_datagram(stream, seq, pcm))
    }

    pub async fn shutdown(&self) {
        let inner = &self.inner;
        inner.cancel.cancel();
        let sessions: Vec<Arc<Session>> = inner
            .peers
            .lock()
            .unwrap()
            .values()
            .filter_map(|p| p.session.clone())
            .collect();
        for session in sessions {
            session.conn.close(0u32.into(), b"bye");
        }
        if let Some(discovery) = inner.discovery.lock().unwrap().take() {
            discovery.shutdown();
        }
        inner.persist_addresses();
        inner.endpoint.close(0u32.into(), b"bye");
        let _ = tokio::time::timeout(Duration::from_secs(2), inner.endpoint.wait_idle()).await;
    }
}

pub(crate) fn rand_u64() -> u64 {
    use ring::rand::{SecureRandom, SystemRandom};
    let mut bytes = [0u8; 8];
    SystemRandom::new().fill(&mut bytes).expect("system randomness");
    u64::from_le_bytes(bytes)
}

impl Inner {
    pub fn emit(&self, event: Event) {
        // No subscribers is fine; the app may not be listening yet.
        let _ = self.events.send(event);
    }

    pub fn name(&self) -> String {
        self.my_name.read().unwrap().clone()
    }

    /// What this device can do for the others: what the app says, and always letting them look at its files.
    pub fn caps(&self) -> Vec<String> {
        let mut caps = self.cfg.caps.clone();
        if !caps.iter().any(|c| c == "files") {
            caps.push("files".into());
        }
        caps
    }

    pub fn my_hello(&self) -> Hello {
        Hello {
            proto: PROTOCOL_VERSION,
            app_version: self.cfg.app_version.clone(),
            name: self.name(),
            platform: self.cfg.platform,
            model: self.cfg.model.clone(),
            caps: self.caps(),
            candidates: net::local_candidates(self.port, self.cfg.loopback),
            circle_digest: self.circle.read().unwrap().digest(),
            boot_id: self.boot_id,
        }
    }

    pub fn session_of(&self, id: &DeviceId) -> Option<Arc<Session>> {
        self.peers.lock().unwrap().get(id).and_then(|p| p.session.clone())
    }

    pub fn connected_ids(&self) -> Vec<DeviceId> {
        self.peers
            .lock()
            .unwrap()
            .iter()
            .filter(|(_, p)| p.session.is_some())
            .map(|(id, _)| *id)
            .collect()
    }

    pub async fn send_to(&self, id: &DeviceId, msg: Msg) -> Result<()> {
        let session = self.session_of(id).ok_or(Error::NotConnected)?;
        session.tx.send(msg).await.map_err(|_| Error::NotConnected)
    }

    /// Sends to each target that is connected and reports who actually got it.
    pub async fn send_many(&self, targets: &[DeviceId], msg: Msg) -> Vec<DeviceId> {
        let mut reached = Vec::new();
        for id in targets {
            if self.send_to(id, msg.clone()).await.is_ok() {
                reached.push(*id);
            } else if self.ble_queue(id, msg.clone()) {
                // No connection, but a Bluetooth link is up: it goes that way.
                reached.push(*id);
            }
        }
        reached
    }

    pub async fn broadcast(&self, msg: Msg) {
        for id in self.connected_ids() {
            let _ = self.send_to(&id, msg.clone()).await;
        }
    }

    /// What this device knows about where the others can be reached, for sharing with
    /// a device that just connected.
    pub fn introductions(&self, except: &DeviceId) -> Vec<crate::proto::PeerAddrs> {
        let peers = self.peers.lock().unwrap();
        peers
            .iter()
            .filter(|(id, peer)| *id != except && !peer.addrs.is_empty())
            .map(|(id, peer)| crate::proto::PeerAddrs {
                id: *id,
                addrs: peer.addrs.ordered().into_iter().take(4).map(|a| a.addr).collect(),
            })
            .collect()
    }

    pub fn devices(&self) -> Vec<DeviceInfo> {
        let circle = self.circle.read().unwrap();
        let settings = self.settings.read().unwrap();
        let peers = self.peers.lock().unwrap();
        let mut out = Vec::new();
        for member in circle.members() {
            if member.id == self.my_id {
                continue;
            }
            let peer = peers.get(&member.id);
            let session = peer.and_then(|p| p.session.as_ref());
            let hello = peer.and_then(|p| p.hello.as_ref());
            out.push(DeviceInfo {
                id: member.id,
                name: hello.map(|h| h.name.clone()).unwrap_or_else(|| member.name.clone()),
                platform: member.platform,
                online: session.is_some(),
                route: session.map(|s| net::route_of(s.remote.ip())),
                rtt_ms: session.map(|s| s.conn.rtt().as_millis() as u32),
                status: peer.map(|p| p.status.clone()).unwrap_or_default(),
                app_version: hello.map(|h| h.app_version.clone()),
                caps: hello.map(|h| h.caps.clone()).unwrap_or_default(),
                vouched_by_removed: member.vouched_by_removed,
                settings: settings.for_device(&member.id),
                ble: self.ble_linked(&member.id),
            });
        }
        out.sort_by(|a, b| b.online.cmp(&a.online).then(a.name.cmp(&b.name)));
        out
    }

    /// Makes the peer table match the circle: new members appear, removed ones are
    /// dropped and their connections closed.
    pub fn sync_peers(&self) {
        let circle = self.circle.read().unwrap();
        let members = circle.members();
        let still_in = circle.is_member(&self.identity.public_key());
        drop(circle);
        // A device that was removed has nobody left to talk to.
        let wanted: HashSet<DeviceId> = if still_in {
            members.iter().map(|m| m.id).filter(|id| *id != self.my_id).collect()
        } else {
            HashSet::new()
        };
        let mut peers = self.peers.lock().unwrap();
        for member in &members {
            if member.id == self.my_id || !wanted.contains(&member.id) {
                continue;
            }
            peers.entry(member.id).or_insert_with(|| Peer {
                key: member.public_key,
                addrs: AddrBook::default(),
                session: None,
                dialing: false,
                fail_count: 0,
                next_dial: Instant::now(),
                status: Status::default(),
                hello: None,
            });
        }
        let gone: Vec<DeviceId> = peers.keys().filter(|id| !wanted.contains(id)).copied().collect();
        for id in gone {
            if let Some(peer) = peers.remove(&id) {
                if let Some(session) = peer.session {
                    // Give the last message (often the removal itself) time to leave
                    // before the connection goes away.
                    tokio::spawn(async move {
                        tokio::time::sleep(Duration::from_millis(600)).await;
                        session.conn.close(3u32.into(), b"removed from circle");
                    });
                }
            }
        }
        drop(peers);
        self.poke.notify_one();
    }

    pub fn persist_circle(&self) {
        let statements = self.circle.read().unwrap().statements();
        if let Err(e) = self.store.write_cbor("circle.cbor", &statements) {
            warn!("could not save the circle: {e}");
        }
    }

    fn load_addresses(&self) {
        let Ok(Some(saved)) = self.store.read_cbor::<HashMap<String, AddrBook>>("addresses.cbor") else {
            return;
        };
        let mut peers = self.peers.lock().unwrap();
        for (key, book) in saved {
            if let Ok(id) = DeviceId::parse(&key) {
                if let Some(peer) = peers.get_mut(&id) {
                    peer.addrs = book;
                }
            }
        }
    }

    pub fn persist_addresses(&self) {
        let snapshot: HashMap<String, AddrBook> = self
            .peers
            .lock()
            .unwrap()
            .iter()
            .map(|(id, peer)| (id.to_string(), peer.addrs.clone()))
            .collect();
        if let Err(e) = self.store.write_cbor("addresses.cbor", &snapshot) {
            warn!("could not save addresses: {e}");
        }
    }

    pub fn learn_addr(&self, id: &DeviceId, addr: SocketAddr, success: bool) {
        let addr = SocketAddr::new(addr.ip().to_canonical(), addr.port());
        let now = now_ms();
        let mut peers = self.peers.lock().unwrap();
        if let Some(peer) = peers.get_mut(id) {
            if success {
                peer.addrs.succeeded(addr, now);
            } else {
                peer.addrs.learn(addr, now);
            }
            self.addrs_dirty.store(true, Ordering::Relaxed);
        }
    }

    pub fn add_address(self: &Arc<Self>, id: &DeviceId, addr: SocketAddr) -> Result<()> {
        {
            let mut peers = self.peers.lock().unwrap();
            let peer = peers.get_mut(id).ok_or(Error::NotTrusted)?;
            peer.addrs.learn(SocketAddr::new(addr.ip().to_canonical(), addr.port()), now_ms());
            peer.next_dial = Instant::now();
            peer.fail_count = 0;
        }
        self.addrs_dirty.store(true, Ordering::Relaxed);
        self.poke.notify_one();
        Ok(())
    }

    pub fn network_changed(self: &Arc<Self>) {
        let mut peers = self.peers.lock().unwrap();
        for peer in peers.values_mut() {
            peer.next_dial = Instant::now();
            peer.fail_count = 0;
        }
        drop(peers);
        self.probe_sessions();
        self.poke.notify_one();
    }

    /// Tells every connected device (except `skip`) about new circle statements.
    pub async fn gossip(&self, statements: Vec<Statement>, skip: Option<DeviceId>) {
        if statements.is_empty() {
            return;
        }
        for id in self.connected_ids() {
            if Some(id) == skip {
                continue;
            }
            let _ = self.send_to(&id, Msg::CircleSync { statements: statements.clone() }).await;
        }
    }

    async fn accept_loop(self: Arc<Self>) {
        loop {
            let incoming = tokio::select! {
                _ = self.cancel.cancelled() => break,
                incoming = self.endpoint.accept() => match incoming {
                    Some(incoming) => incoming,
                    None => break,
                },
            };
            let Ok(permit) = self.accept_slots.clone().try_acquire_owned() else {
                incoming.refuse();
                continue;
            };
            let this = self.clone();
            tokio::spawn(async move {
                let _permit = permit;
                if let Err(e) = this.handle_incoming(incoming).await {
                    debug!("incoming connection ended: {e}");
                }
            });
        }
    }

    async fn handle_incoming(self: Arc<Self>, incoming: quinn::Incoming) -> Result<()> {
        let remote = incoming.remote_address();
        let connecting = incoming.accept().map_err(Error::connection)?;
        let connection = tokio::time::timeout(Duration::from_secs(10), connecting)
            .await
            .map_err(|_| Error::Connection("handshake timed out".into()))?
            .map_err(Error::connection)?;

        let key = tls::peer_key(&connection).ok_or_else(|| Error::Connection("no client key".into()))?;
        match tls::negotiated_alpn(&connection).as_deref() {
            Some(alpn) if alpn == tls::ALPN_PAIR => self.handle_pairing(connection, key).await,
            Some(alpn) if alpn == tls::ALPN_PAIR_CODE => self.handle_code_pairing(connection, key).await,
            Some(alpn) if alpn == tls::ALPN_MAIN => {
                if !self.circle.read().unwrap().is_member(&key) {
                    connection.close(1u32.into(), b"not in the circle");
                    return Err(Error::NotTrusted);
                }
                let id = DeviceId::from_public_key(&key);
                self.learn_addr(&id, remote, true);
                self.establish_incoming(id, connection, remote).await
            }
            _ => {
                connection.close(1u32.into(), b"unknown protocol");
                Err(Error::protocol("unknown ALPN"))
            }
        }
    }

    /// Keeps every circle member connected: dials the ones that are not, backs off on
    /// failure, and hurries up when something changed.
    async fn supervise(self: Arc<Self>) {
        let mut tick = tokio::time::interval(Duration::from_secs(5));
        tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
        loop {
            tokio::select! {
                _ = self.cancel.cancelled() => break,
                _ = self.poke.notified() => {}
                _ = tick.tick() => {}
            }
            self.dial_due_peers();
            self.clean_up_offers();
            self.sync_pairing_announcement();
            if self.addrs_dirty.swap(false, Ordering::Relaxed) {
                self.persist_addresses();
            }
            self.refresh_announcement();
        }
    }

    fn dial_due_peers(self: &Arc<Self>) {
        let mut due = Vec::new();
        {
            let mut peers = self.peers.lock().unwrap();
            let now = Instant::now();
            for (id, peer) in peers.iter_mut() {
                if peer.session.is_none() && !peer.dialing && now >= peer.next_dial && !peer.addrs.is_empty() {
                    peer.dialing = true;
                    due.push((*id, peer.key, peer.addrs.ordered()));
                }
            }
        }
        for (id, key, addrs) in due {
            let this = self.clone();
            tokio::spawn(async move { this.dial_peer(id, key, addrs).await });
        }
    }

    async fn dial_peer(self: Arc<Self>, id: DeviceId, key: [u8; 32], addrs: Vec<net::KnownAddr>) {
        let sockets: Vec<SocketAddr> = addrs.iter().filter_map(|a| a.socket()).collect();
        let outcome = self.dial_any(Some(key), tls::ALPN_MAIN, &sockets).await;
        match outcome {
            Ok((connection, addr)) => {
                self.learn_addr(&id, addr, true);
                {
                    let mut peers = self.peers.lock().unwrap();
                    if let Some(peer) = peers.get_mut(&id) {
                        peer.dialing = false;
                        peer.fail_count = 0;
                    }
                }
                if let Err(e) = self.clone().establish_outgoing(id, connection, addr).await {
                    debug!("could not set up the session with {id}: {e}");
                }
            }
            Err(e) => {
                debug!("could not reach {id}: {e}");
                let mut peers = self.peers.lock().unwrap();
                if let Some(peer) = peers.get_mut(&id) {
                    peer.dialing = false;
                    peer.fail_count = peer.fail_count.saturating_add(1);
                    peer.next_dial = Instant::now() + backoff(peer.fail_count);
                }
            }
        }
    }

    /// Tries every address at once, with a small head start for the local network,
    /// and keeps whichever answers first.
    pub(crate) async fn dial_any(
        &self,
        expected: Option<[u8; 32]>,
        alpn: &'static [u8],
        addrs: &[SocketAddr],
    ) -> Result<(quinn::Connection, SocketAddr)> {
        let mut set = tokio::task::JoinSet::new();
        for (index, addr) in addrs.iter().copied().enumerate() {
            let route = net::route_of(addr.ip());
            let head_start = match route {
                Route::Lan => Duration::ZERO,
                _ => Duration::from_millis(120 * (index as u64).min(6) + 80),
            };
            let timeout = match route {
                Route::Lan => Duration::from_secs(4),
                _ => Duration::from_secs(8),
            };
            let endpoint = self.endpoint.clone();
            let identity = self.identity.clone();
            let tuning = self.cfg.tuning;
            set.spawn(async move {
                tokio::time::sleep(head_start).await;
                let connection = net::dial(&endpoint, &identity, expected, alpn, addr, tuning, timeout).await?;
                Ok::<_, Error>((connection, addr))
            });
        }
        let mut last_error = Error::Connection("no address to try".into());
        while let Some(joined) = set.join_next().await {
            match joined {
                Ok(Ok(win)) => {
                    set.abort_all();
                    return Ok(win);
                }
                Ok(Err(e)) => last_error = e,
                Err(_) => {}
            }
        }
        Err(last_error)
    }

    fn start_discovery(self: Arc<Self>) {
        let (tx, mut rx) = mpsc::unbounded_channel::<Sighting>();
        match Discovery::start(self.my_id, self.port, tx) {
            Ok(discovery) => {
                *self.discovery.lock().unwrap() = Some(discovery);
            }
            Err(e) => {
                warn!("local discovery is not available: {e}");
                return;
            }
        }
        let this = self.clone();
        tokio::spawn(async move {
            loop {
                let sighting = tokio::select! {
                    _ = this.cancel.cancelled() => break,
                    s = rx.recv() => match s { Some(s) => s, None => break },
                };
                this.handle_sighting(sighting);
            }
        });
    }

    fn handle_sighting(&self, sighting: Sighting) {
        self.note_pairing_sighting(sighting.hint, &sighting.addrs, sighting.pairing);
        let hour = discovery::current_hour();
        let matched: Option<DeviceId> = {
            let peers = self.peers.lock().unwrap();
            peers
                .keys()
                .find(|id| discovery::hints_around(id, hour).contains(&sighting.hint as &Hint))
                .copied()
        };
        let Some(id) = matched else { return };
        let mut fresh = false;
        {
            let mut peers = self.peers.lock().unwrap();
            if let Some(peer) = peers.get_mut(&id) {
                let before = peer.addrs.ordered().len();
                let now = now_ms();
                for addr in &sighting.addrs {
                    peer.addrs.learn(SocketAddr::new(addr.ip().to_canonical(), addr.port()), now);
                }
                fresh = peer.session.is_none() && (peer.addrs.ordered().len() != before || peer.fail_count > 0);
                if peer.session.is_none() {
                    peer.next_dial = Instant::now();
                }
            }
        }
        self.addrs_dirty.store(true, Ordering::Relaxed);
        if fresh || self.session_of(&id).is_none() {
            self.poke.notify_one();
        }
    }

    fn refresh_announcement(&self) {
        // The hint changes every hour, so re-announce when the hour turned over.
        let hour = discovery::current_hour();
        if self.last_announce_hour.swap(hour, Ordering::Relaxed) != hour {
            if let Some(discovery) = self.discovery.lock().unwrap().as_mut() {
                let _ = discovery.announce();
            }
        }
    }

    pub fn clean_up_offers(&self) {
        let cutoff = Instant::now() - Duration::from_secs(6 * 3600);
        self.out_offers.lock().unwrap().retain(|_, offer| offer.created > cutoff);
    }

    pub async fn clipboard_changed(&self, text: &str, is_url: bool) {
        let hash = hash_text(text);
        {
            let mut last = self.last_remote_clip.lock().unwrap();
            if *last == Some(hash) {
                // This is the clipboard we just set from another device. Sending it
                // back would bounce it between devices forever.
                *last = None;
                return;
            }
        }
        let targets: Vec<DeviceId> = {
            let settings = self.settings.read().unwrap();
            let mut ids = self.connected_ids();
            for id in self.ble_linked_ids() {
                if !ids.contains(&id) {
                    ids.push(id);
                }
            }
            ids.into_iter().filter(|id| settings.for_device(id).clipboard).collect()
        };
        self.send_clipboard(&targets, text, is_url).await;
    }

    pub async fn send_clipboard(&self, targets: &[DeviceId], text: &str, is_url: bool) -> Vec<DeviceId> {
        let msg = Msg::Clipboard(crate::proto::ClipboardMsg {
            id: rand_u64(),
            ts: now_ms(),
            text: text.to_string(),
            is_url,
        });
        self.send_many(targets, msg).await
    }
}

pub(crate) fn hash_text(text: &str) -> u64 {
    let hash = blake3::hash(text.as_bytes());
    u64::from_le_bytes(hash.as_bytes()[..8].try_into().unwrap())
}

fn backoff(failures: u32) -> Duration {
    let base = 1u64 << failures.min(5);
    let millis = (base * 1000).min(30_000);
    // A little jitter so two devices retrying together do not keep colliding.
    let jitter = rand_u64() % (millis / 5 + 1);
    Duration::from_millis(millis + jitter)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn backoff_grows_and_is_capped() {
        assert!(backoff(0) < Duration::from_millis(1300));
        assert!(backoff(3) >= Duration::from_secs(8));
        assert!(backoff(20) <= Duration::from_millis(36_100));
    }
}
