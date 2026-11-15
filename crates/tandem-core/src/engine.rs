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
use crate::transfer::OutgoingFile;

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
    /// When and where it was last connected to this device; now, for one that is connected.
    pub last_seen: Option<crate::find::LastSeen>,
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
    /// When this device was last seen announcing itself on the local network. Such a device is on the same network and needs no Tailscale.
    pub lan_seen: Option<Instant>,
    /// The network changed: the next try goes to every address, whatever the back-off says about how many.
    pub hurry: bool,
    /// Something made this device reachable that was not: the next try is made whatever the screen and the data (see `Inner::hurry_with`).
    pub force: bool,
    /// When a change of network last gave this device its one try after it had been gone for days.
    pub long_hurried: Option<Instant>,
}

/// This device takes a name given by any device of the circle, and takes its own from it (R-I33).
pub const NAME_CAP: &str = "name.circle";

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
    /// Every offer, in and out, kept in the data folder (see `journal`).
    pub journal: crate::journal::Journal,
    /// Receivers that asked a sender whether it still has a transfer (P2), waiting for `ShareResumed`.
    pub resume_waits: Mutex<HashMap<(DeviceId, u64), tokio::sync::oneshot::Sender<crate::proto::ShareResumed>>>,
    pub pairing: Mutex<Option<PendingPairing>>,
    pub open_pairings: Mutex<HashMap<discovery::Hint, crate::pairing::OpenPairing>>,
    pub my_status: Mutex<Status>,
    /// When the whole status was last sent to everybody (see `Engine::update_status`).
    pub status_sent_in_full: Mutex<std::time::Instant>,
    /// Held while a status is worked out and sent, so that statuses leave in the order they were made.
    pub status_in_order: tokio::sync::Mutex<()>,
    /// What was last said about the battery of each other device (see `battery_alert`).
    pub battery_said: Mutex<HashMap<DeviceId, Option<crate::battery_alert::BatteryAlert>>>,
    pub settings: RwLock<Settings>,
    pub last_remote_clip: Mutex<Option<u64>>,
    /// The last clipboard each device with the clipboard on did not get because it was away (a phone asleep, a laptop shut). It goes
    /// when that device comes back, so what was copied in between is there all the same, unless something newer was copied there.
    pub missed_clip: Mutex<HashMap<DeviceId, crate::proto::ClipboardMsg>>,
    /// When the clipboard of this device last changed, in ms: a clipboard that comes in later but was copied before it is left out.
    pub local_clip_ts: std::sync::atomic::AtomicU64,
    pub poke: Notify,
    pub sessions_changed: Notify,
    pub cancel: CancellationToken,
    pub boot_id: u64,
    pub addrs_dirty: AtomicBool,
    /// When the app was last told that Tailscale is needed, and whether it is being waited for.
    pub tailscale_asked: Mutex<Option<Instant>>,
    pub session_serial: AtomicU64,
    pub discovery: Mutex<Option<Discovery>>,
    pub accept_slots: Arc<Semaphore>,
    pub last_announce_hour: AtomicU64,
    my_addresses: Mutex<Vec<std::net::IpAddr>>,
    said_back: Mutex<Option<Instant>>,
    /// This device is on a network that costs, as its app says (`set_metered`): large files are asked about first.
    pub(crate) metered: AtomicBool,
    /// Files sent to a device that is away wait for it (P3), as its app says (`set_queue_offline`). Off until an app shows the queue.
    pub(crate) queue_offline: AtomicBool,
    /// This device is a phone with its screen off, as its app says (`set_quiet`).
    pub(crate) quiet: AtomicBool,
    /// This device or another one became quiet or awake: every heartbeat looks again at its pace (see `Inner::heartbeat`).
    pub(crate) beat_changed: Notify,
    /// When the devices that are away were last (or will next be) tried because the network changed (see `hurry`).
    last_hurry: Mutex<Option<Instant>>,
    /// The last time a change of the addresses of this device hurried the others, with the screen off on metered data.
    last_address_hurry: Mutex<Option<Instant>>,
    /// The last time a change of network gave the devices that are away their one try, with the screen off on metered data.
    last_costly_hurry: Mutex<Option<Instant>>,
    pub ble: Mutex<crate::ble::BleHub>,
    pub audio: RwLock<Option<Arc<dyn AudioSink>>>,
    pub ble_msg_id: std::sync::atomic::AtomicU8,
    pub find: crate::find::Find,
    /// The Wi-Fi network this device is on, as the app last said (see `wifi`).
    pub wifi: Mutex<Option<crate::wifi::WifiNetwork>>,
    pub(crate) focus: Mutex<crate::focus::FocusSync>,
    pub(crate) health: Mutex<crate::health::Health>,
    /// A permission changed: the table in the status goes out again (see `trust`).
    pub(crate) trust_changed: Notify,
    /// When each kind of refusal was last said to a device, so a refused pointer is not answered dozens of times a second.
    pub(crate) refused_said: Mutex<HashMap<(DeviceId, crate::trust::Permission), Instant>>,
    /// Where the pointer of this computer went over to each other one and has not come back yet (see `trust::on_refused`).
    pub(crate) pointer_out: Mutex<HashMap<DeviceId, f32>>,
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

        // The name that the person gave this device lives in the circle. The one the app brings (the name of the machine) is only for the
        // first run: starting again must not undo a rename.
        let my_name = circle
            .member(&my_id)
            .map(|m| m.name.clone())
            .filter(|n| !n.trim().is_empty())
            .unwrap_or_else(|| cfg.device_name.clone());
        let settings = store.read_cbor::<Settings>("settings.cbor")?.unwrap_or_default();
        let endpoint = net::make_endpoint(&identity, cfg.port, cfg.tuning)?;
        let port = net::local_port(&endpoint);
        let (events, _) = broadcast::channel(2048);
        let file_service = crate::files::FileService::new(store.clone());
        let live = crate::live::Live::new(store.clone());
        let find = crate::find::Find::load(&store);
        let focus = Mutex::new(crate::focus::FocusSync::load(&store));
        let health = Mutex::new(crate::health::Health::load(&store));
        let journal = crate::journal::Journal::load(&cfg.data_dir)?;

        let inner = Arc::new(Inner {
            my_name: RwLock::new(my_name),
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
            journal,
            resume_waits: Mutex::new(HashMap::new()),
            pairing: Mutex::new(None),
            open_pairings: Mutex::new(HashMap::new()),
            my_status: Mutex::new(Status::default()),
            status_sent_in_full: Mutex::new(std::time::Instant::now()),
            status_in_order: tokio::sync::Mutex::new(()),
            battery_said: Mutex::new(HashMap::new()),
            settings: RwLock::new(settings),
            last_remote_clip: Mutex::new(None),
            missed_clip: Mutex::new(HashMap::new()),
            local_clip_ts: std::sync::atomic::AtomicU64::new(0),
            poke: Notify::new(),
            sessions_changed: Notify::new(),
            cancel: CancellationToken::new(),
            boot_id: rand_u64(),
            addrs_dirty: AtomicBool::new(false),
            tailscale_asked: Mutex::new(None),
            session_serial: AtomicU64::new(1),
            discovery: Mutex::new(None),
            accept_slots: Arc::new(Semaphore::new(16)),
            last_announce_hour: AtomicU64::new(discovery::current_hour()),
            my_addresses: Mutex::new(Vec::new()),
            said_back: Mutex::new(None),
            metered: AtomicBool::new(false),
            queue_offline: AtomicBool::new(false),
            quiet: AtomicBool::new(false),
            beat_changed: Notify::new(),
            last_hurry: Mutex::new(None),
            last_address_hurry: Mutex::new(None),
            last_costly_hurry: Mutex::new(None),
            ble: Mutex::new(Default::default()),
            audio: RwLock::new(None),
            ble_msg_id: std::sync::atomic::AtomicU8::new(0),
            find,
            wifi: Mutex::new(None),
            focus,
            health,
            trust_changed: Notify::new(),
            refused_said: Mutex::new(HashMap::new()),
            pointer_out: Mutex::new(HashMap::new()),
        });

        // The peers must exist before their saved addresses can be attached to them.
        inner.sync_peers();
        inner.load_addresses();
        inner.begin_long_away_at_the_long_backoff();
        inner.clean_partials();
        // In what the first connection is told, without waiting for a change.
        let trust = Status { trust: Some(inner.trust_table()), ..Status::default() };
        inner.my_status.lock().unwrap().merge(&trust);
        info!(id = %inner.my_id, port = inner.port, "tandem engine started");

        tokio::spawn(inner.clone().accept_loop());
        tokio::spawn(inner.clone().supervise());
        tokio::spawn(inner.clone().keep_trust_published());
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

    /// The IP address the connection with this device runs over right now. A computer on the hotspot of a phone sees that phone's address
    /// as its gateway, which says so without the name of the network, which macOS hides from apps.
    pub fn device_address(&self, id: &DeviceId) -> Option<String> {
        self.inner.session_of(id).map(|session| session.remote.ip().to_canonical().to_string())
    }

    /// Tell the engine the network changed (Wi-Fi to cellular, VPN up or down), so it
    /// reconnects right away instead of waiting for the next retry.
    pub fn network_changed(&self) {
        self.inner.network_changed();
    }

    pub async fn rename_self(&self, name: &str) -> Result<()> {
        *self.inner.my_name.write().unwrap() = crate::circle::clean_device_name(name);
        let statement = {
            let mut circle = self.inner.circle.write().unwrap();
            circle.rename_self(&self.inner.identity, name)?
        };
        self.inner.persist_circle();
        self.inner.gossip(vec![statement], None).await;
        self.inner.emit(Event::DevicesChanged);
        Ok(())
    }

    /// Gives a device of the circle a name for every device in it, itself included (R-I33). Being in the circle is enough, as for its own
    /// name: a name is not something one device lets another do. A device of a version before this would keep its own name, so it is
    /// refused: `NAME_CAP` says which can.
    pub async fn rename_device(&self, id: &DeviceId, name: &str) -> Result<()> {
        if *id == self.inner.my_id {
            return self.rename_self(name).await;
        }
        if crate::circle::clean_device_name(name).trim().is_empty() {
            return Err(Error::invalid("a name cannot be empty"));
        }
        if !self.inner.knows_names(id) {
            return Err(Error::invalid("that device is of an older version: rename it on the device itself"));
        }
        let statement = {
            let mut circle = self.inner.circle.write().unwrap();
            circle.rename(&self.inner.identity, *id, name)?
        };
        self.inner.persist_circle();
        self.inner.gossip(vec![statement], None).await;
        self.inner.emit(Event::DevicesChanged);
        Ok(())
    }

    /// Whether `id` can be named from here: it said `NAME_CAP` the last time it said hello.
    pub fn can_rename(&self, id: &DeviceId) -> bool {
        *id == self.inner.my_id || self.inner.knows_names(id)
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
        self.inner.set_device_settings(id, settings)
    }

    /// Changes some settings of one device and keeps the rest as they are at that moment.
    pub fn update_device_settings(&self, id: &DeviceId, change: impl FnOnce(&mut DeviceSettings)) -> Result<()> {
        self.inner.update_device_settings(id, change)
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

    // The file policy holds two permissions (see `trust`): each change may change the table.
    pub fn set_file_policy(&self, id: &DeviceId, policy: crate::files::FilePolicy) -> Result<()> {
        self.inner.file_service.set_policy(id, policy)?;
        self.inner.trust_changed.notify_one();
        Ok(())
    }

    pub fn clear_file_policy(&self, id: &DeviceId) -> Result<()> {
        self.inner.file_service.clear_policy(id)?;
        self.inner.trust_changed.notify_one();
        Ok(())
    }

    pub fn set_file_default_policy(&self, policy: crate::files::FilePolicy) -> Result<()> {
        self.inner.file_service.set_default_policy(policy)?;
        self.inner.trust_changed.notify_one();
        Ok(())
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

    /// What this device reports about itself now.
    pub fn my_status(&self) -> Status {
        self.inner.my_status.lock().unwrap().clone()
    }

    /// Updates what this device reports about itself and sends the change on: only the fields that are different from what the others
    /// were told, and nothing when none is. Each radio message of a phone on mobile data keeps its connection to the others awake, so a
    /// status said again every minute would cost more than the status is worth. A new connection is told everything, and a call that
    /// changes nothing still sends everything once per `Tuning::status_refresh`, so a device that missed something is put right.
    pub async fn update_status(&self, change: Status) {
        let refresh = self.inner.cfg.tuning.status_refresh;
        // From working out what is news until it is sent: two quick calls (mute on, mute off) must reach the others in the order they were
        // made, or the older value would stay until the next refresh.
        let _in_order = self.inner.status_in_order.lock().await;
        let to_send = {
            let mut mine = self.inner.my_status.lock().unwrap();
            let news = mine.changes(&change);
            mine.merge(&change);
            let mut refreshed = self.inner.status_sent_in_full.lock().unwrap();
            if news.is_empty() && refreshed.elapsed() < refresh {
                None
            } else if news.is_empty() || refreshed.elapsed() >= refresh {
                *refreshed = std::time::Instant::now();
                Some(mine.clone())
            } else {
                Some(news)
            }
        };
        if let Some(status) = to_send {
            self.inner.broadcast(Msg::Status(status)).await;
        }
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

    /// Which Wi-Fi network this device is on now, `None` when it is on none. Only a device whose caps say `wifi.share` tells
    /// the others; they hear of every change, and a device that comes up (also over Bluetooth) hears it at once.
    pub async fn set_wifi_network(&self, network: Option<crate::wifi::WifiNetwork>) {
        self.inner.set_wifi(network).await;
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

    /// The app of a phone says whether its screen is off. The others hear it in the status and check on it every few minutes instead of
    /// every half minute, so it is not woken for nothing.
    pub async fn set_quiet(&self, quiet: bool) {
        if self.inner.quiet.swap(quiet, Ordering::Relaxed) == quiet {
            return;
        }
        let change = Status { quiet: Some(quiet), ..Status::default() };
        let _in_order = self.inner.status_in_order.lock().await;
        self.inner.my_status.lock().unwrap().merge(&change);
        self.inner.beat_changed.notify_waiters();
        self.inner.broadcast(Msg::Status(change)).await;
    }

    /// The packets that went over the connection with `id` so far, sent and received: how chatty it is, for a test or a look.
    pub fn traffic(&self, id: &DeviceId) -> Option<(u64, u64)> {
        let session = self.inner.session_of(id)?;
        let stats = session.conn.stats();
        Some((stats.udp_tx.datagrams, stats.udp_rx.datagrams))
    }

    /// The app says whether this device is on a network that costs (mobile data, a hotspot it pays for).
    pub fn set_metered(&self, on: bool) {
        self.inner.metered.store(on, Ordering::Relaxed);
    }

    /// Lets files sent to a device that is away wait in the queue until it is back, up to 7 days (P3). Off by default: an app turns it
    /// on once it shows the queue (see docs/TRANSFERS.md). Offers already in the queue go out either way.
    pub fn set_queue_offline(&self, on: bool) {
        self.inner.queue_offline.store(on, Ordering::Relaxed);
    }

    /// Whether an offer waits for the person because this device is on mobile data and it is large (see `transfer::METERED_ASK_BYTES`).
    pub fn offer_held(&self, from: DeviceId, offer_id: u64) -> bool {
        self.inner.offer_held(from, offer_id)
    }

    /// Stops a transfer that is running, as the sender (`incoming` false) or as the receiver.
    pub async fn cancel_transfer(&self, peer: DeviceId, offer_id: u64, incoming: bool) {
        self.inner.cancel_transfer(peer, offer_id, incoming).await;
    }

    pub async fn decline_offer(&self, from: DeviceId, offer_id: u64) {
        self.inner.decline_offer(from, offer_id).await;
    }

    /// Incoming transfers that stopped halfway before this start, newest first. Nothing goes on by itself (P2): the app asks the person
    /// "go on or throw away" for each, after the start and when the sender connects, and answers with `resume_transfer` or
    /// `discard_transfer`. Clipboard, capture and drag offers are never here: they belonged to a moment and were thrown away at the start.
    pub fn resumable_transfers(&self) -> Vec<crate::journal::TransferRecord> {
        self.inner.journal.resumable()
    }

    /// Yes to a transfer that stopped halfway: asks the sender whether it still has the files as they were, and pulls the rest of each
    /// from where its partial file ends. Files the sender no longer has or that changed end with the reason `gone` or `changed`. An error
    /// when the transfer does not wait for this, or the sender is not connected (the app shows Go on grey until it is).
    pub fn resume_transfer(&self, from: DeviceId, offer_id: u64) -> Result<()> {
        self.inner.resume_transfer(from, offer_id)
    }

    /// No to a transfer that stopped halfway: the partial files go, each file ends as `cancelled`, and the sender is told.
    pub async fn discard_transfer(&self, from: DeviceId, offer_id: u64) -> Result<()> {
        self.inner.discard_transfer(from, offer_id).await
    }

    /// The devices among `targets` that said they have less free space than `files` take (P5), for a warning before sending. A device
    /// that does not report its free space is never in it; the receiver refuses by itself when it does not fit.
    pub fn short_of_space(&self, targets: &[DeviceId], files: &[crate::transfer::OutgoingFile]) -> Vec<DeviceId> {
        self.inner.short_of_space(targets, files)
    }

    /// Every transfer this device knows of, in and out, the newest first: what is running, waits for a yes, stopped halfway or
    /// ended a short while ago. The base of the panel of P3b; see docs/TRANSFERS.md.
    pub fn transfers(&self) -> Vec<crate::journal::TransferRecord> {
        self.inner.journal.records()
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
        // What waited for its turn goes to disk now, and what the stop changes after this goes at once.
        inner.journal.flush_all();
        inner.note_all_seen();
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
    pub fn set_device_settings(&self, id: &DeviceId, settings: DeviceSettings) -> Result<()> {
        self.update_device_settings(id, |own| *own = settings)
    }

    /// Changes the settings of one device under the lock, so a change made at the same moment elsewhere is not lost.
    pub fn update_device_settings(&self, id: &DeviceId, change: impl FnOnce(&mut DeviceSettings)) -> Result<()> {
        {
            // Written while the lock is held, so two changes at the same moment reach the disk in the order they were made.
            let mut all = self.settings.write().unwrap();
            let mut own = all.for_device(id);
            change(&mut own);
            all.set_device(id, own);
            self.store.write_cbor("settings.cbor", &*all)?;
        }
        self.trust_changed.notify_one();
        self.emit(Event::DevicesChanged);
        Ok(())
    }

    pub fn emit(&self, event: Event) {
        // No subscribers is fine; the app may not be listening yet.
        let _ = self.events.send(event);
    }

    pub fn name(&self) -> String {
        self.my_name.read().unwrap().clone()
    }

    /// What this device can do for the others: what the app says, always letting them look at its files, and keeping health records.
    pub fn caps(&self) -> Vec<String> {
        let mut caps = self.cfg.caps.clone();
        for always in ["files", crate::health::CAP, crate::session::BEAT_CAP, crate::transfer::RESUME_CAP, crate::trust::CAP, NAME_CAP] {
            if !caps.iter().any(|c| c == always) {
                caps.push(always.into());
            }
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
        // What another device may not see does not leave this one, whichever app or feature sends it.
        if let Some(permission) = crate::trust::needed_to_send(&msg)
            && !self.allowed(id, permission)
        {
            return Err(Error::Invalid(crate::trust::REASON_NOT_ALLOWED.into()));
        }
        self.note_pointer_sent(id, &msg);
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
                connected: peer.session.is_some(),
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
                // The circle has the newest name, also one given from another device (R-I33); what the device says about itself
                // in its hello only comes in when the circle has none.
                name: Some(member.name.clone()).filter(|n| !n.trim().is_empty()).or_else(|| hello.map(|h| h.name.clone())).unwrap_or_default(),
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
                last_seen: self.find.last_seen(&member.id).map(|seen| if session.is_some() { crate::find::LastSeen { at_ms: now_ms(), ..seen } } else { seen }),
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
        self.find.forget_others(&wanted);
        self.health_keep_only(&wanted, still_in);
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
                lan_seen: None,
                hurry: false,
                force: false,
                long_hurried: None,
            });
        }
        let gone: Vec<DeviceId> = peers.keys().filter(|id| !wanted.contains(id)).copied().collect();
        for id in gone {
            if let Some(peer) = peers.remove(&id)
                && let Some(session) = peer.session {
                    // Give the last message (often the removal itself) time to leave
                    // before the connection goes away.
                    tokio::spawn(async move {
                        tokio::time::sleep(Duration::from_millis(600)).await;
                        session.conn.close(3u32.into(), b"removed from circle");
                    });
                }
        }
        drop(peers);
        self.poke.notify_one();
    }

    pub(crate) fn knows_names(&self, id: &DeviceId) -> bool {
        self.peers.lock().unwrap().get(id).and_then(|p| p.hello.as_ref()).is_some_and(|h| h.caps.iter().any(|c| c == NAME_CAP))
    }

    /// Another device gave this one a name (R-I33): it becomes its own, and it is said again as its own rename, which is the only kind
    /// an older version takes. Nothing happens when the circle already has the name this device uses.
    pub(crate) async fn follow_own_name(&self) {
        let named = self.circle.read().unwrap().member(&self.my_id).map(|m| m.name.clone()).filter(|n| !n.trim().is_empty());
        let Some(named) = named else { return };
        if *self.my_name.read().unwrap() == named {
            return;
        }
        *self.my_name.write().unwrap() = named.clone();
        let statement = self.circle.write().unwrap().rename_self(&self.identity, &named);
        if let Ok(statement) = statement {
            self.persist_circle();
            self.gossip(vec![statement], None).await;
        }
        info!(name = %named, "this device was renamed from another device");
        self.emit(Event::SelfRenamed { name: named });
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
            if let Ok(id) = DeviceId::parse(&key)
                && let Some(peer) = peers.get_mut(&id) {
                    peer.addrs = book;
                }
        }
    }

    /// A device that has been gone for days is tried once when this starts, on every address (it may just have come back), and after
    /// that at the long back-off, as if it had failed all this time, and not from the quick tries of a device that was here a moment ago.
    fn begin_long_away_at_the_long_backoff(&self) {
        let mut peers = self.peers.lock().unwrap();
        for (id, peer) in peers.iter_mut() {
            if peer.session.is_none() && self.away_for_days(id, peer) {
                start_long_away(peer);
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

    /// An address another device gave for `id`: it only becomes known, it does not count as a sign of life (see `AddrBook::introduced`).
    pub fn learn_introduced_addr(&self, id: &DeviceId, addr: SocketAddr) {
        let addr = SocketAddr::new(addr.ip().to_canonical(), addr.port());
        if let Some(peer) = self.peers.lock().unwrap().get_mut(id) {
            peer.addrs.introduced(addr, now_ms());
            self.addrs_dirty.store(true, Ordering::Relaxed);
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

    /// Another device says that `id` is connected to it right now, and this device is not: it is up, and one try is made at once, on every
    /// address and whatever the screen and the data, but the back-off is left as it was (see `apply_hurry`).
    pub(crate) fn try_now(&self, id: &DeviceId) {
        if let Some(peer) = self.peers.lock().unwrap().get_mut(id)
            && peer.session.is_none()
        {
            peer.next_dial = Instant::now();
            peer.hurry = true;
            peer.force = true;
            self.poke.notify_one();
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
        self.hurry(false);
    }

    /// Has every device that is not connected tried soon, on all its addresses. The first change in a while also gives it a few quick
    /// tries after that, for a network that is not quite up yet. One that keeps changing (a Wi-Fi that keeps trying, a VPN that
    /// reconnects, the screen going on) leaves the back-off as it was, so the radio of a phone does not keep trying a computer that is
    /// off; and a change soon after the last waits for `HURRY_GAP`. `at_once`: now, for a device that just joined.
    pub(crate) fn hurry(self: &Arc<Self>, at_once: bool) {
        self.hurry_with(at_once, false);
    }

    /// `force`: something happened that makes a device reachable that was not (Tailscale was turned on after it was asked for): every
    /// device that is away is tried now, whatever the screen, the data and how long it has been gone.
    pub(crate) fn hurry_with(self: &Arc<Self>, at_once: bool, force: bool) {
        let now = Instant::now();
        let (at, quiet_before) = {
            let mut last = self.last_hurry.lock().unwrap();
            let quiet_before = at_once || last.is_none_or(|previous| now > previous + QUIET_NETWORK);
            let at = if force { now } else { hurry_at(*last, now, at_once) };
            *last = Some(at);
            (at, quiet_before)
        };
        // A device that just joined is wanted now, whatever the screen and the data.
        let costly = !at_once && !force && self.quiet.load(Ordering::Relaxed) && self.metered.load(Ordering::Relaxed);
        // The one try each device gets there is given at most once in a while, however often the network is said to have changed.
        let skip = costly && {
            let mut last = self.last_costly_hurry.lock().unwrap();
            let recent = last.is_some_and(|previous| previous.elapsed() < COSTLY_HURRY_GAP);
            if !recent {
                *last = Some(Instant::now());
            }
            recent
        };
        let mut peers = self.peers.lock().unwrap();
        for (id, peer) in peers.iter_mut().filter(|(_, p)| p.session.is_none() && !skip) {
            let long_away = self.away_for_days(id, peer);
            apply_hurry(peer, at, quiet_before, long_away, costly, force);
        }
        drop(peers);
        self.probe_sessions();
        self.say_back();
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
            self.notice_tailscale_arriving();
            self.dial_due_peers();
            self.expire_transfers();
            self.send_queued_to_connected();
            self.sync_pairing_announcement();
            if self.addrs_dirty.swap(false, Ordering::Relaxed) {
                self.persist_addresses();
            }
            self.refresh_announcement();
            self.notice_new_addresses();
        }
    }

    /// Not every app is told of every change of network (Linux and Windows are not), and it is what decides whether a device has to be
    /// tried again at once.
    fn notice_new_addresses(self: &Arc<Self>) {
        let Ok(interfaces) = if_addrs::get_if_addrs() else { return };
        // A link-local address comes and goes with every attempt of a Wi-Fi to connect, and reaches no other device.
        let mut now: Vec<std::net::IpAddr> = interfaces.iter().map(|i| i.ip()).filter(|ip| reaches_others(*ip)).collect();
        now.sort();
        let first = {
            let mut known = self.my_addresses.lock().unwrap();
            if *known == now {
                return;
            }
            let first = known.is_empty();
            *known = now;
            first
        };
        if let Some(discovery) = self.discovery.lock().unwrap().as_mut() {
            discovery.stay_quiet();
        }
        if first {
            return;
        }
        // An address on an interface that changed while the app reported no change of network: with the screen off on metered data
        // this is a reason to try again at most once in a while (see `apply_hurry`).
        if self.quiet.load(Ordering::Relaxed) && self.metered.load(Ordering::Relaxed) {
            let mut last = self.last_address_hurry.lock().unwrap();
            if last.is_some_and(|at| at.elapsed() < COSTLY_ADDRESS_CHANGE_GAP) {
                return;
            }
            *last = Some(Instant::now());
        }
        self.network_changed();
    }

    /// Says on the local network that this device is here again. A phone calls `network_changed` for each small change, so not every time.
    fn say_back(&self) {
        {
            let mut last = self.said_back.lock().unwrap();
            if last.is_some_and(|at| at.elapsed() < Duration::from_secs(30)) {
                return;
            }
            *last = Some(Instant::now());
        }
        if let Some(discovery) = self.discovery.lock().unwrap().as_mut() {
            let _ = discovery.say_back();
        }
    }

    /// Tailscale was turned on after it was asked for: devices that were waiting for it are dialled at once and not after their back-off.
    fn notice_tailscale_arriving(self: &Arc<Self>) {
        if self.tailscale_asked.lock().unwrap().is_none() {
            return;
        }
        if if_addrs::get_if_addrs().map(|all| all.iter().any(|i| net::is_tailnet(i.ip()))).unwrap_or(false) {
            // Asked again only after it has gone away again.
            *self.tailscale_asked.lock().unwrap() = None;
            self.hurry_with(false, true);
        }
    }

    fn dial_due_peers(self: &Arc<Self>) {
        let mut due = Vec::new();
        {
            let mut peers = self.peers.lock().unwrap();
            let now = Instant::now();
            // A phone with its screen off on mobile data does not go looking for a device that has been gone for days: that
            // device dials this one when it is back, and this one dials it when the screen goes on or the data is not metered.
            let costly = self.quiet.load(Ordering::Relaxed) && self.metered.load(Ordering::Relaxed);
            for (id, peer) in peers.iter_mut() {
                if peer.session.is_none() && !peer.dialing && now >= peer.next_dial && !peer.addrs.is_empty() {
                    let long_away = self.away_for_days(id, peer);
                    if !may_dial(long_away, costly, peer.force) {
                        continue;
                    }
                    peer.force = false;
                    peer.dialing = true;
                    peer.addrs.forget_old(now_ms());
                    // After a change of network every address, as for a device that never failed.
                    let fails = if std::mem::take(&mut peer.hurry) { 0 } else { peer.fail_count };
                    due.push((*id, peer.key, peer.addrs.ordered(), fails, long_away, costly));
                }
            }
        }
        for (id, key, addrs, fails, long_away, costly) in due {
            let this = self.clone();
            tokio::spawn(async move { this.dial_peer(id, key, addrs, fails, long_away, costly).await });
        }
    }

    /// Whether a device has been gone for more than a day: the newest of when it was last seen (`find`, which also counts a device that
    /// dialled in and one heard on the local network) and when a connection to one of its addresses last worked. A device that was never
    /// seen at all is a new one, and not away for days. One heard on the local network just now is here.
    fn away_for_days(&self, id: &DeviceId, peer: &Peer) -> bool {
        let here = peer.lan_seen.is_some_and(|at| at.elapsed() < Duration::from_secs(90));
        let last_seen = self.find.last_seen(id).map(|seen| seen.at_ms);
        is_away_for_days(now_ms(), last_seen, peer.addrs.newest_ok(), peer.addrs.newest_seen(), here)
    }

    async fn dial_peer(self: Arc<Self>, id: DeviceId, key: [u8; 32], addrs: Vec<net::KnownAddr>, fails: u32, long_away: bool, costly: bool) {
        let tries = addresses_to_try(fails, long_away).min(if costly { COSTLY_ADDRESSES } else { usize::MAX });
        let sockets = pick_addresses(&addrs, tries, costly, long_away);
        // Counted on a phone with `adb logcat -s Tandem`: every attempt wakes the radio.
        info!(peer = %id, fails, addrs = sockets.len(), "dialling a device that is away");
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
                let fails = {
                    let mut peers = self.peers.lock().unwrap();
                    peers.get_mut(&id).map(|peer| {
                        peer.dialing = false;
                        peer.fail_count = peer.fail_count.saturating_add(1);
                        peer.next_dial = Instant::now() + backoff(peer.fail_count, long_away);
                        peer.fail_count
                    })
                };
                if let Some(fails) = fails {
                    self.maybe_need_tailscale(id, fails, &addrs);
                }
            }
        }
    }

    /// A device that does not answer on any address of the local network, and that is known by a Tailscale address, is probably on
    /// another network. When this device has no Tailscale address of its own it cannot get there, so the app is asked (at most once in
    /// a while) to turn Tailscale on. Nothing is asked of a device that is on the same network: that one simply answers.
    fn maybe_need_tailscale(&self, id: DeviceId, fails: u32, addrs: &[net::KnownAddr]) {
        // Heard on the local network a moment ago: it is right here, only not answering yet.
        let on_this_network = self.peers.lock().unwrap().get(&id).and_then(|p| p.lan_seen).is_some_and(|t| t.elapsed() < Duration::from_secs(90));
        if on_this_network {
            return;
        }
        let since_last = self.tailscale_asked.lock().unwrap().map(|t| t.elapsed());
        let has_own = if_addrs::get_if_addrs().map(|all| all.iter().any(|i| net::is_tailnet(i.ip()))).unwrap_or(true);
        if net::needs_tailscale(fails, addrs, has_own, since_last) {
            *self.tailscale_asked.lock().unwrap() = Some(Instant::now());
            self.emit(Event::TailscaleNeeded { id });
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
                peer.lan_seen = Some(Instant::now());
                for addr in &sighting.addrs {
                    peer.addrs.learn(SocketAddr::new(addr.ip().to_canonical(), addr.port()), now);
                }
                fresh = peer.session.is_none() && (peer.addrs.ordered().len() != before || peer.fail_count > 0);
                if peer.session.is_none() {
                    // It is here: every address again, the one just heard above all.
                    peer.next_dial = Instant::now();
                    peer.fail_count = 0;
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
        if self.last_announce_hour.swap(hour, Ordering::Relaxed) != hour
            && let Some(discovery) = self.discovery.lock().unwrap().as_mut() {
                let _ = discovery.announce();
            }
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
        let msg = crate::proto::ClipboardMsg { id: rand_u64(), ts: now_ms(), text: text.to_string(), is_url };
        self.local_clip_ts.store(msg.ts, std::sync::atomic::Ordering::Relaxed);
        let reached = self.send_many(&targets, Msg::Clipboard(msg.clone())).await;
        // Who has the clipboard on but did not get this one gets it when it comes back.
        let away: Vec<DeviceId> = {
            let circle = self.circle.read().unwrap();
            let settings = self.settings.read().unwrap();
            circle.members().into_iter().map(|m| m.id).filter(|id| *id != self.my_id && settings.for_device(id).clipboard && !reached.contains(id)).collect()
        };
        let mut missed = self.missed_clip.lock().unwrap();
        for id in &reached {
            missed.remove(id);
        }
        for id in away {
            missed.insert(id, msg.clone());
        }
    }

    /// A device came back: the clipboard it missed while away, when there is one and it is not old.
    pub fn take_missed_clip(&self, id: &DeviceId) -> Option<crate::proto::ClipboardMsg> {
        let clip = self.missed_clip.lock().unwrap().remove(id)?;
        let fresh = now_ms().saturating_sub(clip.ts) < MISSED_CLIP_KEEP_MS;
        (fresh && self.settings.read().unwrap().for_device(id).clipboard).then_some(clip)
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

/// How long a clipboard that a device missed is kept for it: by the next day it is no longer what the person is busy with.
const MISSED_CLIP_KEEP_MS: u64 = 8 * 3600 * 1000;

/// Whether a clipboard that comes in is older than the last copy on this device, with two seconds for clocks that differ a little: a
/// device that comes back must not undo what was copied here while it was away.
pub(crate) fn clip_is_stale(incoming_ts: u64, local_ts: u64) -> bool {
    incoming_ts.saturating_add(2_000) < local_ts
}

pub(crate) fn hash_text(text: &str) -> u64 {
    let hash = blake3::hash(text.as_bytes());
    u64::from_le_bytes(hash.as_bytes()[..8].try_into().unwrap())
}

/// A device that has been gone for a while comes back by itself: it dials everyone when it starts, wakes or changes network, a phone also
/// when its screen goes on, and it is seen on the local network. Trying it often costs each device a radio that never rests, so after the
/// first minutes this is only for the case that it cannot reach this device.
fn backoff(failures: u32, long_away: bool) -> Duration {
    let millis = match failures {
        0..=8 => ((1u64 << failures.min(5)) * 1000).min(30_000),
        9..=12 => 60_000,
        13..=20 => 300_000,
        // Away for more than a day: a laptop in a drawer. It dials this device when it is back, and when this one is seen on the local
        // network it is tried at once, so waiting this long costs a returning device nothing.
        _ if long_away => LONG_AWAY_CAP.as_millis() as u64,
        // Away for more than half an hour: a computer that is off, most of the time. It dials this device when it is back.
        _ => 900_000,
    };
    // A little jitter so two devices retrying together do not keep colliding.
    let jitter = rand_u64() % (millis / 5 + 1);
    Duration::from_millis(millis + jitter)
}

/// With the screen off on metered data each try on a device that was here lately uses at most this many of its addresses.
const COSTLY_ADDRESSES: usize = 3;
/// With the screen off on metered data, changes of network give the devices that are away one try each at most this often.
const COSTLY_HURRY_GAP: Duration = Duration::from_secs(120);
/// With the screen off on metered data a change of the addresses of this device (and not of the network, as its app says) is a reason to
/// try the devices that are away at most this often.
const COSTLY_ADDRESS_CHANGE_GAP: Duration = Duration::from_secs(300);
/// A device that has not been seen for this long is "away for days": see `is_away_for_days`.
const AWAY_AFTER_MS: u64 = 24 * 3600 * 1000;
/// How often a change of network may give such a device its one try.
const LONG_AWAY_HURRY_GAP: Duration = Duration::from_secs(300);
/// How often such a device is tried at most.
const LONG_AWAY_CAP: Duration = Duration::from_secs(6 * 3600);
/// The failures that start the long back-off (see `backoff`): more than the quick tries and the 5 minute waits.
const LONG_AWAY_FAILS: u32 = 21;
/// Each attempt on a device that has been away for days uses at most this many of its addresses, the best ones.
const LONG_AWAY_ADDRESSES: usize = 2;

/// Whether a device has been gone for more than a day, given when it was last seen and when a connection to it last worked (Unix ms).
/// With neither, when an address of it was last seen or told about: a device that was never connected to and only known by what others
/// said ages like any other (a new one has addresses from now). With nothing at all it is not away. `here`: it is on the local network.
fn is_away_for_days(now: u64, last_seen: Option<u64>, last_worked: Option<u64>, address_seen: Option<u64>, here: bool) -> bool {
    if here {
        return false;
    }
    let newest = match (last_seen, last_worked) {
        (Some(a), Some(b)) => a.max(b),
        (Some(a), None) | (None, Some(a)) => a,
        (None, None) => match address_seen {
            Some(a) => a,
            None => return false,
        },
    };
    now.saturating_sub(newest) > AWAY_AFTER_MS
}

/// A change of network has a device that is away tried soon, on all its addresses.
///
/// One that has been gone for days gets that one try as well, since the change may be the reason it can be reached now (a laptop that
/// is opened, a Tailscale that is turned on), but its back-off is left as it was: the tries after it are the long ones, and not more than
/// one such try per `LONG_AWAY_HURRY_GAP` however often the network changes. With the screen off on metered data (`costly`) it is not
/// dialled at all, apart from `force` (see `Inner::hurry_with`).
///
/// With the screen off on metered data a device that was here lately gets one try soon, on its best addresses, so that a notification is
/// not lost because the way to it changed; but its back-off is left as it was, so the quick tries do not start again.
fn apply_hurry(peer: &mut Peer, at: Instant, quiet_before: bool, long_away: bool, costly: bool, force: bool) {
    if force {
        peer.force = true;
    }
    if long_away {
        if costly && !force {
            return;
        }
        if force || peer.long_hurried.is_none_or(|previous| at >= previous + LONG_AWAY_HURRY_GAP) {
            peer.long_hurried = Some(at);
            peer.next_dial = peer.next_dial.min(at);
            peer.hurry = true;
        }
        return;
    }
    if costly {
        peer.next_dial = peer.next_dial.min(at);
        return;
    }
    peer.next_dial = peer.next_dial.min(at);
    peer.hurry = true;
    if quiet_before {
        peer.fail_count = peer.fail_count.min(QUICK_TRIES);
    }
}

/// Whether a device that is due is dialled now: not one that has been gone for days while the screen is off on metered data, unless
/// something asked for it in particular.
fn may_dial(long_away: bool, costly: bool, forced: bool) -> bool {
    !(long_away && costly) || forced
}

/// What a device that has been gone for days starts at when this device starts: one try on every address, and then the long back-off.
fn start_long_away(peer: &mut Peer) {
    peer.fail_count = peer.fail_count.max(LONG_AWAY_FAILS);
    peer.hurry = true;
}

/// See `Inner::hurry`.
const HURRY_GAP: Duration = Duration::from_secs(30);
/// A change of network after this long without one is a move to another network, not one that keeps changing.
const QUIET_NETWORK: Duration = Duration::from_secs(600);
/// The failures a move to another network leaves a device at: tries after half a minute and a minute, then the long waits again.
const QUICK_TRIES: u32 = 6;

/// When to try the devices that are away after a change of network, given when that last happened (or is about to).
fn hurry_at(last: Option<Instant>, now: Instant, at_once: bool) -> Instant {
    match last {
        _ if at_once => now,
        Some(planned) if now < planned => planned,
        Some(previous) if now < previous + HURRY_GAP => previous + HURRY_GAP,
        _ => now,
    }
}

/// An address another device could reach this one on: not loopback, not link-local.
fn reaches_others(ip: std::net::IpAddr) -> bool {
    match ip {
        std::net::IpAddr::V4(v4) => !v4.is_loopback() && !v4.is_link_local(),
        std::net::IpAddr::V6(v6) => !v6.is_loopback() && !v6.is_unicast_link_local(),
    }
}

/// The addresses of `addrs` (in the order of `AddrBook::ordered`) that one try uses, at most `limit`. A try with fewer addresses than the
/// device has takes the best one of each kind of route first, so that a device is not tried only on the three that worked last, all on a
/// local network that is no longer there: the Tailscale address first when the screen is off on metered data (`costly`: a local
/// address cannot be reached on mobile data), the local one first otherwise. Without a limit all of them, as they come.
fn pick_addresses(addrs: &[net::KnownAddr], limit: usize, costly: bool, long_away: bool) -> Vec<SocketAddr> {
    if limit >= addrs.len() || !(costly || long_away) {
        return addrs.iter().take(limit).filter_map(|a| a.socket()).collect();
    }
    let routes = if costly { [net::Route::Tailnet, net::Route::Other, net::Route::Lan] } else { [net::Route::Lan, net::Route::Tailnet, net::Route::Other] };
    let mut picked: Vec<&net::KnownAddr> = Vec::new();
    for route in routes {
        if let Some(best) = addrs.iter().find(|a| a.route == route) {
            picked.push(best);
        }
    }
    picked.truncate(limit);
    for a in addrs {
        if picked.len() >= limit {
            break;
        }
        if !picked.iter().any(|p| std::ptr::eq(*p, a)) {
            picked.push(a);
        }
    }
    picked.into_iter().filter_map(|a| a.socket()).collect()
}

/// Each address that does not answer costs a few packets of more than a kilobyte. A device that stays away gets the best ones, and all of them
/// once in a while, in case it is somewhere else now.
fn addresses_to_try(failures: u32, long_away: bool) -> usize {
    if long_away && failures >= QUICK_TRIES {
        return LONG_AWAY_ADDRESSES;
    }
    if failures < 6 || failures.is_multiple_of(4) { usize::MAX } else { 3 }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn backoff_grows_and_is_capped() {
        assert!(backoff(0, false) < Duration::from_millis(1300));
        assert!(backoff(3, false) >= Duration::from_secs(8));
        assert!(backoff(8, false) <= Duration::from_millis(36_100));
        assert!(backoff(10, false) >= Duration::from_secs(60));
        assert!(backoff(20, false) >= Duration::from_secs(300));
        assert!(backoff(20, false) <= Duration::from_secs(360));
        assert!(backoff(30, false) >= Duration::from_secs(900));
        assert!(backoff(30, false) <= Duration::from_secs(1080));
    }

    #[test]
    fn a_network_that_keeps_changing_tries_the_devices_that_are_away_at_most_twice_a_minute() {
        let start = Instant::now();
        let gap = HURRY_GAP;
        assert_eq!(hurry_at(None, start, false), start);
        // A second change right after: once the gap is over, not now and not once more for each change in between.
        assert_eq!(hurry_at(Some(start), start + Duration::from_secs(2), false), start + gap);
        assert_eq!(hurry_at(Some(start + gap), start + Duration::from_secs(9), false), start + gap);
        // Long after: at once again.
        assert_eq!(hurry_at(Some(start), start + gap * 3, false), start + gap * 3);
        // A device that just joined is tried at once all the same.
        assert_eq!(hurry_at(Some(start), start + Duration::from_secs(2), true), start + Duration::from_secs(2));
    }

    fn a_peer(fail_count: u32, next_dial: Instant) -> Peer {
        Peer {
            key: [0; 32],
            addrs: AddrBook::default(),
            session: None,
            dialing: false,
            fail_count,
            next_dial,
            status: Status::default(),
            hello: None,
            lan_seen: None,
            hurry: false,
            force: false,
            long_hurried: None,
        }
    }

    fn known(addr: &str, last_ok: u64) -> net::KnownAddr {
        let route = net::route_of(addr.parse::<SocketAddr>().unwrap().ip());
        net::KnownAddr { addr: addr.into(), route, last_ok, seen: last_ok }
    }

    #[test]
    fn with_the_screen_off_on_metered_data_the_tailscale_address_is_tried_first_however_many_local_ones_worked_last() {
        // The phone left the Wi-Fi: three local addresses of the Mac worked most recently, the Tailscale one a while before.
        let book = AddrBook::default();
        assert!(book.is_empty());
        let mut addrs = vec![
            known("192.168.1.5:47820", 90),
            known("192.168.1.9:47820", 80),
            known("10.0.0.4:47820", 70),
            known("100.100.1.1:47820", 10),
        ];
        addrs.sort_by_key(|a| std::cmp::Reverse(a.last_ok));
        let picked = pick_addresses(&addrs, COSTLY_ADDRESSES, true, false);
        assert_eq!(picked.len(), 3);
        assert_eq!(picked[0], "100.100.1.1:47820".parse().unwrap(), "the Tailscale address is the first");
        // Without that state the tries are as before: the best ones in order.
        let before = pick_addresses(&addrs, 3, false, false);
        assert_eq!(before[0], "192.168.1.5:47820".parse().unwrap());
        assert!(!before.contains(&"100.100.1.1:47820".parse().unwrap()));
        // A try on every address is every address.
        assert_eq!(pick_addresses(&addrs, usize::MAX, true, false).len(), 4);
        // A device that has been away for days gets its best of each route, local first, 2 of them.
        let two = pick_addresses(&addrs, 2, false, true);
        assert_eq!(two, vec!["192.168.1.5:47820".parse().unwrap(), "100.100.1.1:47820".parse().unwrap()]);
    }

    #[test]
    fn a_device_is_away_for_days_after_a_day_without_a_sign_of_it() {
        let now = 100 * AWAY_AFTER_MS;
        let hour = 3_600_000;
        assert!(!is_away_for_days(now, Some(now - 23 * hour), None, None, false));
        assert!(is_away_for_days(now, Some(now - 25 * hour), None, None, false));
        // The newest of the two counts: a connection that worked an hour ago, whatever was last seen.
        assert!(!is_away_for_days(now, Some(now - 48 * hour), Some(now - hour), None, false));
        assert!(!is_away_for_days(now, Some(now - hour), Some(now - 48 * hour), None, false));
        assert!(is_away_for_days(now, None, Some(now - 48 * hour), None, false));
        // One never connected to is as old as the newest of its addresses, and one without any is new.
        assert!(is_away_for_days(now, None, None, Some(now - 48 * hour), false));
        assert!(!is_away_for_days(now, None, None, Some(now - hour), false));
        assert!(!is_away_for_days(now, None, None, None, false));
        // One on the local network is here.
        assert!(!is_away_for_days(now, Some(now - 48 * hour), None, None, true));
    }

    #[test]
    fn a_device_that_has_been_away_for_days_waits_six_hours_and_one_that_was_here_yesterday_waits_as_before() {
        assert!(backoff(21, true) >= Duration::from_secs(6 * 3600));
        assert!(backoff(21, true) <= Duration::from_secs(6 * 3600 + 6 * 3600 / 5 + 1));
        assert!(backoff(500, true) >= Duration::from_secs(6 * 3600));
        assert!(backoff(21, false) <= Duration::from_secs(1080));
        // The first tries of a device that is not gone for long are the same: it may be just out of reach.
        for failures in 0..=20 {
            assert!(backoff(failures, true) <= backoff(failures, false) + Duration::from_secs(61), "{failures}");
        }
    }

    #[test]
    fn a_device_that_has_been_away_for_days_gets_its_best_two_addresses() {
        assert_eq!(addresses_to_try(LONG_AWAY_FAILS, true), 2);
        assert_eq!(addresses_to_try(24, true), 2);
        assert_eq!(addresses_to_try(24, false), usize::MAX);
        // The quick tries of a start are all of them.
        assert_eq!(addresses_to_try(0, true), usize::MAX);
    }

    #[test]
    fn a_change_of_network_gives_a_device_that_has_been_away_for_days_one_try_on_every_address_and_leaves_its_back_off() {
        let now = Instant::now();
        let later = now + Duration::from_secs(6 * 3600);

        let mut recent = a_peer(30, later);
        apply_hurry(&mut recent, now, true, false, false, false);
        assert_eq!(recent.next_dial, now);
        assert!(recent.hurry);
        assert_eq!(recent.fail_count, QUICK_TRIES);

        // The laptop that is opened after three days and joins its Wi-Fi: tried at once, on every address.
        let mut gone = a_peer(LONG_AWAY_FAILS, later);
        apply_hurry(&mut gone, now, true, true, false, false);
        assert_eq!(gone.next_dial, now);
        assert!(gone.hurry, "on every address");
        assert_eq!(gone.fail_count, LONG_AWAY_FAILS, "but it does not start counting from the quick tries again");

        // A network that keeps changing gives it one such try per 5 minutes.
        gone.next_dial = later;
        apply_hurry(&mut gone, now + Duration::from_secs(60), true, true, false, false);
        assert_eq!(gone.next_dial, later);
        apply_hurry(&mut gone, now + LONG_AWAY_HURRY_GAP, true, true, false, false);
        assert_eq!(gone.next_dial, now + LONG_AWAY_HURRY_GAP);
    }

    #[test]
    fn tailscale_turned_on_dials_a_device_that_has_been_away_for_days_even_with_the_screen_off_on_metered_data() {
        let now = Instant::now();
        let later = now + Duration::from_secs(6 * 3600);

        // Without a reason it is not dialled at all in that state, and a change of network does not make it so.
        let mut gone = a_peer(LONG_AWAY_FAILS, later);
        apply_hurry(&mut gone, now, true, true, true, false);
        assert_eq!(gone.next_dial, later);
        assert!(!gone.force);
        assert!(!may_dial(true, true, gone.force));

        // Tailscale arriving is a reason: now, on every address, whatever the screen and the data.
        apply_hurry(&mut gone, now, true, true, false, true);
        assert_eq!(gone.next_dial, now);
        assert!(gone.hurry && gone.force);
        assert!(may_dial(true, true, gone.force));
        assert_eq!(gone.fail_count, LONG_AWAY_FAILS);

        // Nothing else is held back: not away, or the screen on or Wi-Fi.
        assert!(may_dial(false, true, false));
        assert!(may_dial(true, false, false));
    }

    #[test]
    fn a_device_that_has_been_away_for_days_starts_with_one_try_on_every_address_and_then_the_long_wait() {
        let mut peer = a_peer(0, Instant::now());
        start_long_away(&mut peer);
        assert!(peer.hurry, "every address on the one try at start");
        assert_eq!(peer.fail_count, LONG_AWAY_FAILS, "and the long back-off after it");
        assert_eq!(addresses_to_try(0, true), usize::MAX);
    }

    #[test]
    fn with_the_screen_off_on_metered_data_a_change_of_network_gives_one_try_and_leaves_the_back_off() {
        let now = Instant::now();
        let later = now + Duration::from_secs(3600);
        let mut lately = a_peer(7, later);
        apply_hurry(&mut lately, now, true, false, true, false);
        assert_eq!(lately.next_dial, now, "one try soon, so a notification is not lost");
        assert!(!lately.hurry, "not on every address");
        assert_eq!(lately.fail_count, 7, "and the quick tries do not start again");
        // Already due earlier: not pushed back.
        let mut due = a_peer(7, now - Duration::from_secs(5));
        apply_hurry(&mut due, now, true, false, true, false);
        assert_eq!(due.next_dial, now - Duration::from_secs(5));
    }

    #[test]
    fn a_link_local_address_is_no_change_of_network() {
        assert!(!reaches_others("fe80::1".parse().unwrap()));
        assert!(!reaches_others("169.254.10.2".parse().unwrap()));
        assert!(!reaches_others("127.0.0.1".parse().unwrap()));
        assert!(reaches_others("192.168.1.20".parse().unwrap()));
        assert!(reaches_others("100.101.102.103".parse().unwrap()));
        assert!(reaches_others("2a02:a46a::1".parse().unwrap()));
    }

    #[test]
    fn a_device_that_stays_away_gets_fewer_addresses_with_all_of_them_now_and_then() {
        assert_eq!(addresses_to_try(0, false), usize::MAX);
        assert_eq!(addresses_to_try(5, false), usize::MAX);
        assert_eq!(addresses_to_try(6, false), 3);
        assert_eq!(addresses_to_try(8, false), usize::MAX);
    }
}
