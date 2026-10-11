//! Messages over Bluetooth Low Energy on Linux, through BlueZ on the system bus, for when there is no network between two devices (see
//! docs/BLUETOOTH.md). It works the same way as the Mac does: this program only moves writes. The core seals them, cuts them in pieces and
//! puts them together again; here the pieces go over a link and come back.
//!
//! Two roles, because two computers have to find each other and only one of them can look:
//! - **Looking** (central): for each device in `Target`s it scans for the Tandem service, picks the one that is that device (by the tag
//!   of its advertisement when it has one, or else by whether a frame opens that only that device can make), connects, switches on the
//!   notifications of `msg_out`, says who it is and then moves the queue of the core in both directions.
//! - **Waiting** (peripheral): it serves the Tandem service as a GATT application and advertises it, so a device that looks finds it.
//!   What is written to `msg_in` goes to the core; what the core has for a device goes out as a notification on `msg_out`.
//!
//! Neither role is needed while the network works: the caller only asks for them while a device is out of reach.

use std::collections::{HashMap, HashSet};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use zbus::blocking::connection::Builder;
use zbus::blocking::{Connection, MessageIterator};
use zbus::interface;
use zbus::zvariant::{ObjectPath, OwnedObjectPath, OwnedValue, Value};

pub const SERVICE: &str = "6f2d7a10-8b1c-4e6f-a3d5-1c9e5b7f2a40";
const MSG_IN: &str = "6f2d7a14-8b1c-4e6f-a3d5-1c9e5b7f2a40";
const MSG_OUT: &str = "6f2d7a15-8b1c-4e6f-a3d5-1c9e5b7f2a40";

const BLUEZ: &str = "org.bluez";
const APP_PATH: &str = "/nl/markmaaktmedia/tandem/ble";
const SERVICE_PATH: &str = "/nl/markmaaktmedia/tandem/ble/service0";
const IN_PATH: &str = "/nl/markmaaktmedia/tandem/ble/service0/char0";
const OUT_PATH: &str = "/nl/markmaaktmedia/tandem/ble/service0/char1";
const ADVERT_PATH: &str = "/nl/markmaaktmedia/tandem/ble/advert0";

/// A beacon that shows no tag is tried after this long, when no tagged one has come.
const UNTAGGED_AFTER: Duration = Duration::from_secs(5);
/// A link that has not proven to be the device that was wanted by then is let go.
const VERIFY_WITHIN: Duration = Duration::from_secs(15);
/// A beacon that was tried and was not the one is left alone this long.
const STRANGER_FOR: Duration = Duration::from_secs(600);
/// A beacon not seen for this long is out of range.
const OUT_OF_RANGE: Duration = Duration::from_secs(15);
const TICK: Duration = Duration::from_millis(400);

type Managed = HashMap<OwnedObjectPath, HashMap<String, HashMap<String, OwnedValue>>>;

fn text<E: std::fmt::Display>(error: E) -> String {
    error.to_string()
}

/// What the core is asked, from the threads of this module. The receiving one may wait (the core is asynchronous).
pub struct Callbacks {
    /// One write that arrived on the link named by the first argument; the device whose frame opened with it, if one did.
    pub receive: Box<dyn Fn(&str, Vec<u8>) -> Option<String> + Send + Sync>,
    /// The first writes to send on a new link to this device, in pieces of at most this many bytes.
    pub hello: Box<dyn Fn(&str, usize) -> Vec<Vec<u8>> + Send + Sync>,
    /// What waits for this device, in pieces of at most this many bytes.
    pub outbox: Box<dyn Fn(&str, usize) -> Vec<Vec<u8>> + Send + Sync>,
    /// The link to this device closed.
    pub link_down: Box<dyn Fn(&str) + Send + Sync>,
    /// The link of this name closed: what it had half delivered is forgotten.
    pub drop_link: Box<dyn Fn(&str) + Send + Sync>,
    /// The tag a device puts in its advertisement in this hour.
    pub hint: Box<dyn Fn(&str, u64) -> Vec<u8> + Send + Sync>,
    /// The beacon of this device came into range (true) or went out of it.
    pub nearby: Box<dyn Fn(&str, bool) + Send + Sync>,
}

/// A device that this one looks for.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Target {
    pub id: String,
    /// Whether a link may be opened: the two share a key, and the person wants messages over Bluetooth.
    pub can_link: bool,
}

struct Seen {
    first: Instant,
    last: Instant,
    /// What the device advertised as its tag, if it did.
    tag: Option<Vec<u8>>,
    connected: bool,
}

struct Central {
    path: String,
    in_path: String,
    out_path: String,
    chunk: usize,
    since: Instant,
    verified: bool,
    /// Tried although it showed no tag: it is left alone for a while when it turns out not to be the one.
    believed: bool,
}

/// A device that is connected to this one and writes to it.
struct Peer {
    device: Option<String>,
    mtu: usize,
}

#[derive(Default)]
struct State {
    targets: Vec<Target>,
    waiting: bool,
    registered: bool,
    seen: HashMap<String, Seen>,
    strangers: HashMap<String, Instant>,
    connecting: HashSet<String>,
    links: HashMap<String, Central>,
    peers: HashMap<String, Peer>,
    nearby: HashSet<String>,
}

struct Shared {
    callbacks: Callbacks,
    connection: Connection,
    adapter: String,
    state: Mutex<State>,
    stop: AtomicBool,
}

/// Bluetooth messages for this computer. Dropping it ends both roles.
pub struct Ble {
    shared: Arc<Shared>,
}

impl Ble {
    /// Finds the adapter, serves the service on the bus and starts the threads. An error says why there is no Bluetooth to use (no BlueZ,
    /// no adapter): the app goes on without.
    pub fn start(callbacks: Callbacks) -> Result<Ble, String> {
        let probe = Connection::system().map_err(text)?;
        let adapter = find_adapter(&probe)?;
        let state = Mutex::new(State::default());
        // The objects of the service are served from the start; BlueZ only hears about them while this computer is waiting.
        let shared_for_objects: Arc<Mutex<Option<Arc<Shared>>>> = Arc::new(Mutex::new(None));
        let connection = Builder::system()
            .and_then(|b| b.serve_at(APP_PATH, zbus::fdo::ObjectManager))
            .and_then(|b| b.serve_at(SERVICE_PATH, GattService))
            .and_then(|b| b.serve_at(IN_PATH, InCharacteristic { shared: shared_for_objects.clone() }))
            .and_then(|b| b.serve_at(OUT_PATH, OutCharacteristic { value: Vec::new() }))
            .and_then(|b| b.serve_at(ADVERT_PATH, Advertisement))
            .and_then(|b| b.build())
            .map_err(text)?;
        let shared = Arc::new(Shared { callbacks, connection, adapter, state, stop: AtomicBool::new(false) });
        *shared_for_objects.lock().unwrap() = Some(shared.clone());
        let for_signals = shared.clone();
        std::thread::Builder::new().name("tandem-ble-signals".into()).spawn(move || watch(for_signals)).map_err(text)?;
        let for_tick = shared.clone();
        std::thread::Builder::new().name("tandem-ble".into()).spawn(move || run(for_tick)).map_err(text)?;
        Ok(Ble { shared })
    }

    /// What is wanted now: the devices to look for, and whether to wait for others to look. Safe to call often.
    pub fn want(&self, targets: Vec<Target>, waiting: bool) {
        let mut state = self.shared.state.lock().unwrap();
        state.targets = targets;
        state.waiting = waiting;
    }
}

impl Drop for Ble {
    fn drop(&mut self) {
        self.shared.stop.store(true, Ordering::Relaxed);
        let mut state = self.shared.state.lock().unwrap();
        state.targets.clear();
        state.waiting = false;
    }
}

// ---- BlueZ ---------------------------------------------------------------------------------------------------------------------

fn managed(connection: &Connection) -> Result<Managed, String> {
    let reply = connection
        .call_method(Some(BLUEZ), "/", Some("org.freedesktop.DBus.ObjectManager"), "GetManagedObjects", &())
        .map_err(text)?;
    reply.body().deserialize::<Managed>().map_err(text)
}

/// Whether there is Bluetooth to use: BlueZ on the system bus, with an adapter.
pub fn available() -> bool {
    Connection::system().ok().and_then(|connection| find_adapter(&connection).ok()).is_some()
}

fn find_adapter(connection: &Connection) -> Result<String, String> {
    let objects = managed(connection)?;
    objects
        .iter()
        .filter(|(_, interfaces)| interfaces.contains_key("org.bluez.Adapter1"))
        .map(|(path, _)| path.as_str().to_string())
        .min()
        .ok_or_else(|| "this computer has no Bluetooth adapter".to_string())
}

fn string_of(value: &OwnedValue) -> Option<String> {
    value.try_clone().ok().and_then(|v| String::try_from(v).ok())
}

fn bool_of(value: &OwnedValue) -> Option<bool> {
    value.downcast_ref::<bool>().ok()
}

fn strings_of(value: &OwnedValue) -> Vec<String> {
    value.try_clone().ok().and_then(|v| Vec::<String>::try_from(v).ok()).unwrap_or_default()
}

fn bytes_of(value: &OwnedValue) -> Option<Vec<u8>> {
    value.try_clone().ok().and_then(|v| Vec::<u8>::try_from(v).ok())
}

fn same_uuid(a: &str, b: &str) -> bool {
    a.eq_ignore_ascii_case(b)
}

/// The tag a beacon carries for the Tandem service, from the properties of its device object.
fn tag_of(properties: &HashMap<String, OwnedValue>) -> Option<Vec<u8>> {
    let data = properties.get("ServiceData")?.try_clone().ok().and_then(|v| HashMap::<String, OwnedValue>::try_from(v).ok())?;
    data.iter().find(|(uuid, _)| same_uuid(uuid, SERVICE)).and_then(|(_, value)| bytes_of(value))
}

fn offers_service(properties: &HashMap<String, OwnedValue>) -> bool {
    properties.get("UUIDs").map(|v| strings_of(v).iter().any(|u| same_uuid(u, SERVICE))).unwrap_or(false)
        || tag_of(properties).is_some()
}

fn note_device(state: &mut State, path: &str, properties: &HashMap<String, OwnedValue>) {
    let now = Instant::now();
    let connected = properties.get("Connected").and_then(bool_of);
    let is_ours = offers_service(properties);
    match state.seen.get_mut(path) {
        Some(seen) => {
            if is_ours || properties.contains_key("RSSI") {
                seen.last = now;
            }
            if let Some(tag) = tag_of(properties) {
                seen.tag = Some(tag);
            }
            if let Some(connected) = connected {
                seen.connected = connected;
            }
        }
        None if is_ours => {
            state.seen.insert(path.to_string(), Seen { first: now, last: now, tag: tag_of(properties), connected: connected.unwrap_or(false) });
        }
        None => {}
    }
}

/// Listens to what BlueZ says about devices and characteristics: a device that shows up, a notification that arrives, a link that goes.
fn watch(shared: Arc<Shared>) {
    let rule = match zbus::MatchRule::builder()
        .msg_type(zbus::message::Type::Signal)
        .sender(BLUEZ)
        .and_then(|b| b.path_namespace("/org/bluez"))
        .and_then(|b| Ok(b.build()))
    {
        Ok(rule) => rule,
        Err(error) => return log::warn!("bluetooth: no way to listen to the bus: {error}"),
    };
    let stream = match MessageIterator::for_match_rule(rule, &shared.connection, Some(512)) {
        Ok(stream) => stream,
        Err(error) => return log::warn!("bluetooth: no way to listen to the bus: {error}"),
    };
    for message in stream {
        if shared.stop.load(Ordering::Relaxed) {
            break;
        }
        let Ok(message) = message else { continue };
        let header = message.header();
        let Some(path) = header.path().map(|p| p.as_str().to_string()) else { continue };
        match header.member().map(|m| m.as_str()) {
            Some("InterfacesAdded") => {
                let Ok((added, interfaces)) = message.body().deserialize::<(OwnedObjectPath, HashMap<String, HashMap<String, OwnedValue>>)>() else { continue };
                if let Some(properties) = interfaces.get("org.bluez.Device1") {
                    note_device(&mut shared.state.lock().unwrap(), added.as_str(), properties);
                }
            }
            Some("PropertiesChanged") => {
                let Ok((interface, changed, _)) = message.body().deserialize::<(String, HashMap<String, OwnedValue>, Vec<String>)>() else { continue };
                match interface.as_str() {
                    "org.bluez.Device1" => on_device_changed(&shared, &path, &changed),
                    "org.bluez.GattCharacteristic1" => {
                        if let Some(value) = changed.get("Value").and_then(bytes_of) {
                            on_notification(&shared, &path, value);
                        }
                    }
                    _ => {}
                }
            }
            _ => {}
        }
    }
}

fn on_device_changed(shared: &Arc<Shared>, path: &str, changed: &HashMap<String, OwnedValue>) {
    let mut state = shared.state.lock().unwrap();
    note_device(&mut state, path, changed);
    // A link that dropped: told to the core by the next tick, which sees the device is not connected.
}

/// A notification from a device we are linked to as the one that looks.
fn on_notification(shared: &Arc<Shared>, characteristic: &str, value: Vec<u8>) {
    let link = {
        let state = shared.state.lock().unwrap();
        state.links.iter().find(|(_, l)| l.out_path == characteristic).map(|(id, l)| (id.clone(), l.path.clone()))
    };
    let Some((wanted, path)) = link else { return };
    if let Some(from) = (shared.callbacks.receive)(&path, value) {
        if from == wanted {
            if let Some(link) = shared.state.lock().unwrap().links.get_mut(&wanted) {
                link.verified = true;
            }
        }
    }
}

// ---- The loop ------------------------------------------------------------------------------------------------------------------

fn run(shared: Arc<Shared>) {
    let mut scanning = false;
    let mut discovered_at = Instant::now();
    while !shared.stop.load(Ordering::Relaxed) {
        std::thread::sleep(TICK);
        let (targets, waiting) = {
            let state = shared.state.lock().unwrap();
            (state.targets.clone(), state.waiting)
        };
        // Scanning, only while someone is looked for.
        let want_scan = !targets.is_empty();
        if want_scan && (!scanning || discovered_at.elapsed() > Duration::from_secs(60)) {
            scanning = start_discovery(&shared);
            discovered_at = Instant::now();
        } else if !want_scan && scanning {
            stop_discovery(&shared);
            scanning = false;
        }
        looking(&shared, &targets);
        waiting_for_others(&shared, waiting);
    }
    if scanning {
        stop_discovery(&shared);
    }
    set_registered(&shared, false);
}

fn adapter_call(shared: &Shared, method: &str) -> Result<(), String> {
    shared.connection.call_method(Some(BLUEZ), shared.adapter.as_str(), Some("org.bluez.Adapter1"), method, &()).map(|_| ()).map_err(text)
}

fn start_discovery(shared: &Shared) -> bool {
    let mut filter: HashMap<&str, Value> = HashMap::new();
    filter.insert("UUIDs", Value::from(vec![SERVICE.to_string()]));
    filter.insert("Transport", Value::from("le"));
    // Every sighting and not only the first: the tag of a beacon rides in the scan response, which can come after it.
    filter.insert("DuplicateData", Value::from(true));
    let filtered = shared
        .connection
        .call_method(Some(BLUEZ), shared.adapter.as_str(), Some("org.bluez.Adapter1"), "SetDiscoveryFilter", &(filter,))
        .map(|_| ())
        .map_err(text);
    if let Err(error) = filtered {
        log::debug!("bluetooth: no filter for the scan: {error}");
    }
    match adapter_call(shared, "StartDiscovery") {
        Ok(()) => true,
        // Somebody else (the settings of the desktop) is already scanning: what they find is found for us too.
        Err(error) if error.contains("InProgress") || error.contains("Already") => true,
        Err(error) => {
            log::debug!("bluetooth: the scan did not start: {error}");
            false
        }
    }
}

fn stop_discovery(shared: &Shared) {
    let _ = adapter_call(shared, "StopDiscovery");
}

/// The hints that mean `id`: its tag in the hour before this one, this one and the next, which covers a clock a little off.
fn hints_of(shared: &Shared, id: &str) -> Vec<Vec<u8>> {
    let hour = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs() / 3600).unwrap_or(0);
    [hour.saturating_sub(1), hour, hour + 1].into_iter().map(|h| (shared.callbacks.hint)(id, h)).collect()
}

fn looking(shared: &Arc<Shared>, targets: &[Target]) {
    let now = Instant::now();
    // Links of devices that are not looked for any more, and links that BlueZ says are gone.
    let mut gone: Vec<(String, String)> = Vec::new();
    {
        let mut state = shared.state.lock().unwrap();
        let wanted: HashSet<&str> = targets.iter().map(|t| t.id.as_str()).collect();
        let ids: Vec<String> = state.links.keys().cloned().collect();
        for id in ids {
            let (path, expired, disconnected) = {
                let link = &state.links[&id];
                let connected = state.seen.get(&link.path).map(|s| s.connected).unwrap_or(true);
                // A moment is allowed for BlueZ to say that the connection is up, which it does a little after the call returns.
                let settled = now.duration_since(link.since) > Duration::from_secs(4);
                (link.path.clone(), !link.verified && now.duration_since(link.since) > VERIFY_WITHIN, settled && !connected)
            };
            if !wanted.contains(id.as_str()) || expired || disconnected {
                if let Some(link) = state.links.remove(&id) {
                    if expired && !link.believed {
                        state.strangers.insert(path.clone(), now + STRANGER_FOR);
                    }
                    gone.push((id, path));
                }
            }
        }
        state.strangers.retain(|_, until| *until > now);
        state.seen.retain(|_, s| now.duration_since(s.last) < Duration::from_secs(300));
    }
    for (id, path) in gone {
        disconnect(shared, &path);
        (shared.callbacks.drop_link)(&path);
        (shared.callbacks.link_down)(&id);
    }

    for target in targets {
        let hints = hints_of(shared, &target.id);
        let pick = {
            let state = shared.state.lock().unwrap();
            if state.links.contains_key(&target.id) || state.connecting.contains(&target.id) {
                None
            } else {
                let tagged = state
                    .seen
                    .iter()
                    .filter(|(path, s)| now.duration_since(s.last) < OUT_OF_RANGE && !s.connected && !state.strangers.contains_key(*path))
                    .find(|(_, s)| s.tag.as_ref().is_some_and(|tag| hints.contains(tag)))
                    .map(|(path, _)| (path.clone(), true));
                if tagged.is_none() {
                    let any_tagged = state.seen.values().any(|s| s.tag.is_some() && now.duration_since(s.last) < OUT_OF_RANGE);
                    let untagged = state
                        .seen
                        .iter()
                        .filter(|(path, s)| {
                            s.tag.is_none() && !s.connected && now.duration_since(s.last) < OUT_OF_RANGE && !state.strangers.contains_key(*path)
                        })
                        .filter(|(_, s)| now.duration_since(s.first) >= UNTAGGED_AFTER)
                        .map(|(path, _)| (path.clone(), false))
                        .next();
                    // A beacon with a tag that is somebody else's is not tried; one without a tag is only tried when none had the right one.
                    if any_tagged && untagged.is_none() { None } else { untagged }
                } else {
                    tagged
                }
            }
        };
        // The beacon of a tagged device that is in range says that the device is there, whether or not a link is opened.
        let near = {
            let state = shared.state.lock().unwrap();
            state.links.contains_key(&target.id)
                || state.seen.values().any(|s| now.duration_since(s.last) < OUT_OF_RANGE && s.tag.as_ref().is_some_and(|tag| hints.contains(tag)))
        };
        set_nearby(shared, &target.id, near);
        let Some((path, tagged)) = pick else { continue };
        if !target.can_link {
            continue;
        }
        shared.state.lock().unwrap().connecting.insert(target.id.clone());
        let (shared, id) = (shared.clone(), target.id.clone());
        std::thread::Builder::new().name("tandem-ble-connect".into()).spawn(move || connect(shared, id, path, tagged)).ok();
    }

    // Pump: what the core has for each device that is linked.
    let linked: Vec<(String, String, usize)> = {
        let state = shared.state.lock().unwrap();
        state.links.iter().map(|(id, l)| (id.clone(), l.in_path.clone(), l.chunk)).collect()
    };
    for (id, in_path, chunk) in linked {
        for piece in (shared.callbacks.outbox)(&id, chunk) {
            if write(shared, &in_path, piece).is_err() {
                break;
            }
        }
    }
}

fn set_nearby(shared: &Arc<Shared>, id: &str, near: bool) {
    let changed = {
        let mut state = shared.state.lock().unwrap();
        if near { state.nearby.insert(id.to_string()) } else { state.nearby.remove(id) }
    };
    if changed {
        (shared.callbacks.nearby)(id, near);
    }
}

fn write(shared: &Shared, characteristic: &str, value: Vec<u8>) -> Result<(), String> {
    let mut options: HashMap<&str, Value> = HashMap::new();
    options.insert("type", Value::from("request"));
    shared
        .connection
        .call_method(Some(BLUEZ), characteristic, Some("org.bluez.GattCharacteristic1"), "WriteValue", &(value, options))
        .map(|_| ())
        .map_err(text)
}

fn disconnect(shared: &Shared, device: &str) {
    let _ = shared.connection.call_method(Some(BLUEZ), device, Some("org.bluez.Device1"), "Disconnect", &());
}

/// Opens the link to a beacon: connects, waits for the services, switches on the notifications and says who this is.
fn connect(shared: Arc<Shared>, id: String, path: String, tagged: bool) {
    let result = open_link(&shared, &id, &path, tagged);
    shared.state.lock().unwrap().connecting.remove(&id);
    if let Err(error) = result {
        log::debug!("bluetooth: no link to {id}: {error}");
        disconnect(&shared, &path);
        // A beacon that cannot be talked to is left alone for a while, so it is not tried every few seconds.
        shared.state.lock().unwrap().strangers.insert(path, Instant::now() + Duration::from_secs(60));
    }
}

fn open_link(shared: &Arc<Shared>, id: &str, path: &str, tagged: bool) -> Result<(), String> {
    shared.connection.call_method(Some(BLUEZ), path, Some("org.bluez.Device1"), "Connect", &()).map_err(text)?;
    // The services are known a moment after the connection.
    let deadline = Instant::now() + Duration::from_secs(10);
    let (in_path, out_path, mtu) = loop {
        let objects = managed(&shared.connection)?;
        if let Some(found) = characteristics(&objects, path) {
            break found;
        }
        if Instant::now() > deadline {
            return Err("the services did not show up".into());
        }
        std::thread::sleep(Duration::from_millis(200));
    };
    shared.connection.call_method(Some(BLUEZ), out_path.as_str(), Some("org.bluez.GattCharacteristic1"), "StartNotify", &()).map_err(text)?;
    let chunk = mtu.saturating_sub(3).clamp(20, 180);
    shared.state.lock().unwrap().links.insert(
        id.to_string(),
        Central { path: path.to_string(), in_path: in_path.clone(), out_path, chunk, since: Instant::now(), verified: false, believed: tagged },
    );
    // The first frame says who this is, so the other end can tell which device the link belongs to.
    for piece in (shared.callbacks.hello)(id, chunk) {
        write(shared, &in_path, piece)?;
    }
    Ok(())
}

/// The two characteristics of the service under a device, and the size of a write that BlueZ says one can carry.
fn characteristics(objects: &Managed, device: &str) -> Option<(String, String, usize)> {
    let prefix = format!("{device}/");
    let (mut input, mut output, mut mtu) = (None, None, 23usize);
    for (path, interfaces) in objects {
        if !path.as_str().starts_with(&prefix) {
            continue;
        }
        let Some(properties) = interfaces.get("org.bluez.GattCharacteristic1") else { continue };
        let Some(uuid) = properties.get("UUID").and_then(string_of) else { continue };
        if same_uuid(&uuid, MSG_IN) {
            input = Some(path.as_str().to_string());
            if let Some(m) = properties.get("MTU").and_then(|v| v.downcast_ref::<u16>().ok()) {
                mtu = usize::from(m);
            }
        } else if same_uuid(&uuid, MSG_OUT) {
            output = Some(path.as_str().to_string());
        }
    }
    Some((input?, output?, mtu))
}

// ---- Waiting -------------------------------------------------------------------------------------------------------------------

/// A device that waits is findable: the application and the advertisement are made known to BlueZ, and are taken back when nobody looks.
fn waiting_for_others(shared: &Arc<Shared>, waiting: bool) {
    set_registered(shared, waiting);
    if !waiting {
        return;
    }
    let connected: HashSet<String> = match managed(&shared.connection) {
        Ok(objects) => objects
            .iter()
            .filter(|(_, interfaces)| {
                interfaces.get("org.bluez.Device1").and_then(|p| p.get("Connected")).and_then(bool_of).unwrap_or(false)
            })
            .map(|(path, _)| path.as_str().to_string())
            .collect(),
        Err(_) => return,
    };
    // Links whose device went away, and the pieces for those that stay.
    let mut gone = Vec::new();
    let mut live: Vec<(String, usize)> = Vec::new();
    {
        let mut state = shared.state.lock().unwrap();
        let paths: Vec<String> = state.peers.keys().cloned().collect();
        for path in paths {
            if !connected.contains(&path) {
                if let Some(peer) = state.peers.remove(&path) {
                    gone.push((path, peer.device));
                }
            } else if let Some(device) = state.peers[&path].device.clone() {
                live.push((device, state.peers[&path].mtu));
            }
        }
    }
    for (path, device) in gone {
        (shared.callbacks.drop_link)(&path);
        if let Some(device) = device {
            (shared.callbacks.link_down)(&device);
        }
    }
    for (device, mtu) in live {
        let chunk = mtu.saturating_sub(3).clamp(20, 180);
        for piece in (shared.callbacks.outbox)(&device, chunk) {
            notify(shared, piece);
        }
    }
}

fn set_registered(shared: &Arc<Shared>, on: bool) {
    {
        let mut state = shared.state.lock().unwrap();
        if state.registered == on {
            return;
        }
        state.registered = on;
    }
    let options: HashMap<&str, Value> = HashMap::new();
    let app = ObjectPath::try_from(APP_PATH).unwrap();
    let advert = ObjectPath::try_from(ADVERT_PATH).unwrap();
    let call = |interface: &str, method: &str, path: &ObjectPath, options: &HashMap<&str, Value>| {
        shared
            .connection
            .call_method(Some(BLUEZ), shared.adapter.as_str(), Some(interface), method, &(path, options))
            .map(|_| ())
            .map_err(text)
    };
    if on {
        if let Err(error) = call("org.bluez.GattManager1", "RegisterApplication", &app, &options) {
            log::warn!("bluetooth: the service could not be made known to BlueZ: {error}");
        }
        if let Err(error) = call("org.bluez.LEAdvertisingManager1", "RegisterAdvertisement", &advert, &options) {
            log::warn!("bluetooth: the advertisement could not be made known to BlueZ: {error}");
        }
    } else {
        let _ = call("org.bluez.LEAdvertisingManager1", "UnregisterAdvertisement", &advert, &options);
        let _ = call("org.bluez.GattManager1", "UnregisterApplication", &app, &options);
        let peers: Vec<(String, Option<String>)> = shared.state.lock().unwrap().peers.drain().map(|(p, peer)| (p, peer.device)).collect();
        for (path, device) in peers {
            (shared.callbacks.drop_link)(&path);
            if let Some(device) = device {
                (shared.callbacks.link_down)(&device);
            }
        }
    }
}

/// One piece to everybody who has switched on the notifications. Pieces are sealed for one device, so the others cannot open them.
fn notify(shared: &Arc<Shared>, piece: Vec<u8>) {
    let Ok(interface) = shared.connection.object_server().interface::<_, OutCharacteristic>(OUT_PATH) else { return };
    interface.get_mut().value = piece;
    let emitter = interface.signal_emitter();
    let guard = interface.get();
    zbus::block_on(async {
        let _ = guard.value_changed(emitter).await;
    });
}

struct GattService;

#[interface(name = "org.bluez.GattService1")]
impl GattService {
    #[zbus(property, name = "UUID")]
    fn uuid(&self) -> String {
        SERVICE.to_string()
    }
    #[zbus(property)]
    fn primary(&self) -> bool {
        true
    }
}

struct InCharacteristic {
    shared: Arc<Mutex<Option<Arc<Shared>>>>,
}

#[interface(name = "org.bluez.GattCharacteristic1")]
impl InCharacteristic {
    fn read_value(&self, _options: HashMap<String, OwnedValue>) -> Vec<u8> {
        Vec::new()
    }

    fn write_value(&self, value: Vec<u8>, options: HashMap<String, OwnedValue>) {
        let Some(shared) = self.shared.lock().unwrap().clone() else { return };
        // The device that wrote, by the path of its object: the name of this link.
        let Some(path) = options.get("device").and_then(|v| v.try_clone().ok()).and_then(|v| OwnedObjectPath::try_from(v).ok()) else { return };
        let path = path.as_str().to_string();
        let mtu = options.get("mtu").and_then(|v| v.downcast_ref::<u16>().ok()).map(usize::from).unwrap_or(23);
        shared.state.lock().unwrap().peers.entry(path.clone()).or_insert(Peer { device: None, mtu }).mtu = mtu;
        let Some(from) = (shared.callbacks.receive)(&path, value) else { return };
        let fresh = {
            let mut state = shared.state.lock().unwrap();
            match state.peers.get_mut(&path) {
                Some(peer) if peer.device.as_deref() != Some(from.as_str()) => {
                    peer.device = Some(from.clone());
                    true
                }
                _ => false,
            }
        };
        // The first frame that opens says who this is; this side answers with its own, so the other knows too.
        if fresh {
            let chunk = mtu.saturating_sub(3).clamp(20, 180);
            for piece in (shared.callbacks.hello)(&from, chunk) {
                notify(&shared, piece);
            }
        }
    }

    #[zbus(property, name = "UUID")]
    fn uuid(&self) -> String {
        MSG_IN.to_string()
    }
    #[zbus(property)]
    fn service(&self) -> OwnedObjectPath {
        OwnedObjectPath::try_from(SERVICE_PATH).unwrap()
    }
    #[zbus(property)]
    fn flags(&self) -> Vec<String> {
        vec!["write".to_string(), "write-without-response".to_string()]
    }
}

struct OutCharacteristic {
    value: Vec<u8>,
}

#[interface(name = "org.bluez.GattCharacteristic1")]
impl OutCharacteristic {
    fn read_value(&self, _options: HashMap<String, OwnedValue>) -> Vec<u8> {
        self.value.clone()
    }
    fn start_notify(&self) {}
    fn stop_notify(&self) {}

    #[zbus(property, name = "UUID")]
    fn uuid(&self) -> String {
        MSG_OUT.to_string()
    }
    #[zbus(property)]
    fn service(&self) -> OwnedObjectPath {
        OwnedObjectPath::try_from(SERVICE_PATH).unwrap()
    }
    #[zbus(property)]
    fn flags(&self) -> Vec<String> {
        vec!["notify".to_string()]
    }
    #[zbus(property)]
    fn value(&self) -> Vec<u8> {
        self.value.clone()
    }
}

struct Advertisement;

#[interface(name = "org.bluez.LEAdvertisement1")]
impl Advertisement {
    fn release(&self) {}

    #[zbus(property, name = "Type")]
    fn kind(&self) -> String {
        "peripheral".to_string()
    }
    #[zbus(property, name = "ServiceUUIDs")]
    fn service_uuids(&self) -> Vec<String> {
        vec![SERVICE.to_string()]
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn value<T: Into<Value<'static>>>(v: T) -> OwnedValue {
        OwnedValue::try_from(v.into()).unwrap()
    }

    fn object(properties: Vec<(&str, OwnedValue)>) -> HashMap<String, HashMap<String, OwnedValue>> {
        let mut interfaces = HashMap::new();
        interfaces.insert("org.bluez.GattCharacteristic1".to_string(), properties.into_iter().map(|(k, v)| (k.to_string(), v)).collect());
        interfaces
    }

    #[test]
    fn the_two_characteristics_of_the_service_are_found_under_the_device() {
        let mut objects: Managed = HashMap::new();
        let path = |p: &str| OwnedObjectPath::try_from(p).unwrap();
        objects.insert(path("/org/bluez/hci0/dev_AA/service0001/char0002"), object(vec![("UUID", value(MSG_IN.to_uppercase())), ("MTU", value(185u16))]));
        objects.insert(path("/org/bluez/hci0/dev_AA/service0001/char0004"), object(vec![("UUID", value(MSG_OUT))]));
        // Another device's characteristic, and one of this device that is not ours.
        objects.insert(path("/org/bluez/hci0/dev_BB/service0001/char0002"), object(vec![("UUID", value(MSG_IN))]));
        objects.insert(path("/org/bluez/hci0/dev_AA/service0001/char0006"), object(vec![("UUID", value("00002a00-0000-1000-8000-00805f9b34fb"))]));
        let (input, output, mtu) = characteristics(&objects, "/org/bluez/hci0/dev_AA").unwrap();
        assert_eq!(input, "/org/bluez/hci0/dev_AA/service0001/char0002");
        assert_eq!(output, "/org/bluez/hci0/dev_AA/service0001/char0004");
        assert_eq!(mtu, 185);
        assert!(characteristics(&objects, "/org/bluez/hci0/dev_CC").is_none());
    }

    #[test]
    fn a_beacon_is_ours_by_its_service_and_its_tag_is_read_from_the_service_data() {
        let mut properties: HashMap<String, OwnedValue> = HashMap::new();
        assert!(!offers_service(&properties));
        properties.insert("UUIDs".into(), value(vec![SERVICE.to_uppercase()]));
        assert!(offers_service(&properties));
        assert_eq!(tag_of(&properties), None);
        let mut data: HashMap<String, OwnedValue> = HashMap::new();
        data.insert(SERVICE.to_string(), value(vec![1u8, 2, 3]));
        properties.insert("ServiceData".into(), value(data));
        assert_eq!(tag_of(&properties), Some(vec![1, 2, 3]));
    }

    #[test]
    fn a_new_beacon_is_noted_once_and_seen_again_by_every_sighting() {
        let mut state = State::default();
        let mut properties: HashMap<String, OwnedValue> = HashMap::new();
        properties.insert("UUIDs".into(), value(vec![SERVICE.to_string()]));
        note_device(&mut state, "/org/bluez/hci0/dev_AA", &properties);
        assert!(state.seen.contains_key("/org/bluez/hci0/dev_AA"));
        let first = state.seen["/org/bluez/hci0/dev_AA"].first;
        let mut sighting: HashMap<String, OwnedValue> = HashMap::new();
        sighting.insert("RSSI".into(), value(-60i16));
        sighting.insert("Connected".into(), value(true));
        note_device(&mut state, "/org/bluez/hci0/dev_AA", &sighting);
        let seen = &state.seen["/org/bluez/hci0/dev_AA"];
        assert_eq!(seen.first, first);
        assert!(seen.connected);
        // A device that does not offer the service is not kept.
        note_device(&mut state, "/org/bluez/hci0/dev_ZZ", &sighting);
        assert!(!state.seen.contains_key("/org/bluez/hci0/dev_ZZ"));
    }
}
