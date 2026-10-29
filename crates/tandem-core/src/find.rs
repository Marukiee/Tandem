//! Finding a device. One that is asked to ring (`Msg::Ring`) plays a sound and says when it starts and stops (`Msg::Ringing`), to the
//! devices that asked. And every device remembers when and on which network it last saw each of the others, so its page can say where
//! a device was while it cannot be reached.

use std::collections::{HashMap, HashSet};
use std::sync::Mutex;

use serde::{Deserialize, Serialize};
use tracing::warn;

use crate::engine::Inner;
use crate::ids::{DeviceId, now_ms};
use crate::net::{self, Route};
use crate::proto::Msg;
use crate::store::Store;

/// When a device was last connected to this one, and where it was.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct LastSeen {
    /// Milliseconds since 1970, by the clock of this device.
    pub at_ms: u64,
    /// The Wi-Fi it was on, when it said so or when it was on the same network as this device.
    #[serde(default)]
    pub ssid: Option<String>,
    #[serde(default)]
    pub route: Option<Route>,
}

const FILE: &str = "seen.cbor";
/// A device that stays connected sends its status every minute or so: the time is kept in memory and only written now and then.
const WRITE_EVERY_MS: u64 = 5 * 60 * 1000;

#[derive(Default)]
pub(crate) struct Find {
    /// The devices that asked this one to ring since it last stopped. They hear when it starts and when it stops.
    askers: Mutex<HashSet<DeviceId>>,
    seen: Mutex<Seen>,
}

#[derive(Default)]
struct Seen {
    map: HashMap<DeviceId, LastSeen>,
    written_ms: u64,
}

/// The network a device was on: the Wi-Fi it reports itself, or, when it says nothing and the two are on the same local network,
/// the Wi-Fi this device is on. Over Tailscale or the internet the network of this device says nothing about the other one.
pub fn network_name(theirs: Option<&str>, route: Option<Route>, mine: Option<&str>) -> Option<String> {
    let named = |s: Option<&str>| s.map(str::trim).filter(|s| !s.is_empty()).map(str::to_string);
    named(theirs).or_else(|| if route == Some(Route::Lan) { named(mine) } else { None })
}

impl Find {
    pub(crate) fn load(store: &Store) -> Find {
        let saved = store.read_cbor::<HashMap<String, LastSeen>>(FILE).ok().flatten().unwrap_or_default();
        let map = saved.into_iter().filter_map(|(id, seen)| DeviceId::parse(&id).ok().map(|id| (id, seen))).collect();
        Find { askers: Mutex::default(), seen: Mutex::new(Seen { map, written_ms: 0 }) }
    }

    pub(crate) fn asked(&self, from: DeviceId, on: bool) {
        if on {
            self.askers.lock().unwrap().insert(from);
        }
    }

    /// Who hears that this device rings now, or stopped. After a stop the list starts again empty.
    fn listeners(&self, on: bool) -> Vec<DeviceId> {
        let mut askers = self.askers.lock().unwrap();
        if on { askers.iter().copied().collect() } else { askers.drain().collect() }
    }

    pub(crate) fn last_seen(&self, id: &DeviceId) -> Option<LastSeen> {
        self.seen.lock().unwrap().map.get(id).cloned()
    }

    /// Keeps what was seen of `id` now. `route` is how it is reached, or nothing when that is not known any more (it just went):
    /// then the place it was is kept and only the time moves.
    fn note(&self, store: &Store, id: DeviceId, route: Option<Route>, ssid: Option<String>, force: bool) {
        let now = now_ms();
        let mut seen = self.seen.lock().unwrap();
        let before = seen.map.get(&id).cloned();
        let entry = match (route, &before) {
            (None, Some(old)) => LastSeen { at_ms: now, ..old.clone() },
            _ => LastSeen { at_ms: now, ssid, route },
        };
        let moved = before.as_ref().is_none_or(|old| old.ssid != entry.ssid || old.route != entry.route);
        seen.map.insert(id, entry);
        if force || moved || now.saturating_sub(seen.written_ms) >= WRITE_EVERY_MS {
            seen.written_ms = now;
            let snapshot: HashMap<String, LastSeen> = seen.map.iter().map(|(id, s)| (id.to_string(), s.clone())).collect();
            // Written with the lock held: two notes a moment apart wrote side by side, and the older one could land last, so after
            // a restart a device seemed to have been seen before it went.
            if let Err(e) = store.write_cbor(FILE, &snapshot) {
                warn!("could not save where the devices were last seen: {e}");
            }
        }
    }

    /// A device that left the circle is not looked for any more.
    pub(crate) fn forget_others(&self, keep: &HashSet<DeviceId>) {
        self.seen.lock().unwrap().map.retain(|id, _| keep.contains(id));
        self.askers.lock().unwrap().retain(|id| keep.contains(id));
    }
}

impl Inner {
    /// Remembers that `id` is here, reached the way its connection goes now, on the network it said it is on.
    pub(crate) fn note_seen(&self, id: DeviceId, force: bool) {
        let (route, theirs) = {
            let peers = self.peers.lock().unwrap();
            let Some(peer) = peers.get(&id) else { return };
            let route = peer.session.as_ref().map(|s| net::route_of(s.remote.ip()));
            (route, peer.status.network.as_ref().and_then(|n| n.ssid.clone()))
        };
        let mine = self.my_status.lock().unwrap().network.as_ref().and_then(|n| n.ssid.clone());
        let ssid = network_name(theirs.as_deref(), route, mine.as_deref());
        self.find.note(&self.store, id, route, ssid, force);
    }

    /// Every device that is connected now was seen now: kept before this device stops, so its time is right after a restart.
    pub(crate) fn note_all_seen(&self) {
        let connected: Vec<DeviceId> = self.peers.lock().unwrap().iter().filter(|(_, p)| p.session.is_some()).map(|(id, _)| *id).collect();
        for id in connected {
            self.note_seen(id, true);
        }
    }

    /// Tells the devices that asked this one to ring that it rings now, or that it stopped.
    pub(crate) async fn report_ringing(&self, on: bool) {
        let targets = self.find.listeners(on);
        if !targets.is_empty() {
            self.send_many(&targets, Msg::Ringing { on }).await;
        }
    }
}

impl crate::engine::Engine {
    /// This device plays its sound for the devices that asked (`Event::Ring`), or stopped. They are told, so they show it.
    pub async fn ringing(&self, on: bool) {
        self.inner.report_ringing(on).await;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn id(n: u8) -> DeviceId {
        DeviceId::from_public_key(&[n; 32])
    }

    #[test]
    fn the_network_is_the_one_a_device_names_and_otherwise_the_shared_one() {
        assert_eq!(network_name(Some("Thuis"), Some(Route::Tailnet), Some("Kantoor")).as_deref(), Some("Thuis"));
        assert_eq!(network_name(None, Some(Route::Lan), Some("Kantoor")).as_deref(), Some("Kantoor"));
        assert_eq!(network_name(Some("  "), Some(Route::Lan), Some("Kantoor")).as_deref(), Some("Kantoor"));
        // Over Tailscale the two are not on one network.
        assert_eq!(network_name(None, Some(Route::Tailnet), Some("Kantoor")), None);
        assert_eq!(network_name(None, None, None), None);
    }

    #[test]
    fn a_device_that_goes_keeps_where_it_was_and_it_is_still_known_after_a_restart() {
        let dir = tempfile::tempdir().unwrap();
        let store = Store::new(dir.path()).unwrap();
        let find = Find::load(&store);
        find.note(&store, id(1), Some(Route::Lan), Some("Thuis".into()), true);
        let first = find.last_seen(&id(1)).unwrap();
        // It goes: no route any more, the place stays and the time moves on.
        find.note(&store, id(1), None, None, true);
        let gone = find.last_seen(&id(1)).unwrap();
        assert_eq!((gone.ssid.as_deref(), gone.route), (Some("Thuis"), Some(Route::Lan)));
        assert!(gone.at_ms >= first.at_ms);

        let again = Find::load(&store);
        assert_eq!(again.last_seen(&id(1)), Some(gone));
        assert_eq!(again.last_seen(&id(2)), None);
    }

    #[test]
    fn a_device_on_another_network_is_not_kept_on_the_old_one() {
        let dir = tempfile::tempdir().unwrap();
        let store = Store::new(dir.path()).unwrap();
        let find = Find::load(&store);
        find.note(&store, id(1), Some(Route::Lan), Some("Thuis".into()), false);
        find.note(&store, id(1), Some(Route::Tailnet), None, false);
        let now = find.last_seen(&id(1)).unwrap();
        assert_eq!((now.ssid, now.route), (None, Some(Route::Tailnet)));
        // A new place is written at once, without waiting for the next few minutes.
        assert_eq!(Find::load(&store).last_seen(&id(1)).unwrap().route, Some(Route::Tailnet));
    }

    #[test]
    fn the_ones_that_asked_hear_the_start_and_the_stop_and_then_nobody_does() {
        let find = Find::default();
        find.asked(id(1), true);
        find.asked(id(2), true);
        find.asked(id(3), false);
        let mut on = find.listeners(true);
        on.sort();
        let mut expected = vec![id(1), id(2)];
        expected.sort();
        assert_eq!(on, expected);
        assert_eq!(find.listeners(false).len(), 2);
        assert!(find.listeners(false).is_empty());
    }

    #[test]
    fn removed_devices_are_forgotten() {
        let dir = tempfile::tempdir().unwrap();
        let store = Store::new(dir.path()).unwrap();
        let find = Find::load(&store);
        find.note(&store, id(1), Some(Route::Lan), None, false);
        find.note(&store, id(2), Some(Route::Lan), None, false);
        find.asked(id(2), true);
        find.forget_others(&HashSet::from([id(1)]));
        assert!(find.last_seen(&id(1)).is_some());
        assert!(find.last_seen(&id(2)).is_none());
        assert!(find.listeners(true).is_empty());
    }
}
