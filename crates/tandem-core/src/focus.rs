//! Focus and Do Not Disturb, the same on every device: the person turns it on or off on one, and the others follow.
//!
//! What travels is the newest change the circle knows (`FocusChange`), ordered by a Lamport counter and, for two changes with the
//! same number, by the id of the device where it was made. Every device keeps the newest one and hands it to a device that connects,
//! so they all agree on what is current without a clock, and one that was away hears it when it is back.
//!
//! The app reads the Focus of its own device and says what it is now (`Engine::focus_local`). Only a change of that reading is news:
//! the first reading after a start is where it stands, and a change to what the newest change already says is a device following
//! (or agreeing), not a person, so it does not travel back.

use crate::engine::{Engine, Inner};
use crate::events::Event;
use crate::ids::DeviceId;
use crate::proto::{FocusChange, Msg, Status};
use crate::store::Store;

/// The device reads its own Focus and sends it on when the person changes it.
pub const CAP_SHARE: &str = "focus.share";
/// The device can turn a Focus of its own on and off when another one does.
pub const CAP_FOLLOW: &str = "focus.follow";

const FILE: &str = "focus.cbor";

pub(crate) struct FocusSync {
    latest: Option<FocusChange>,
    local: Option<bool>,
    send: bool,
    follow: bool,
}

impl FocusSync {
    pub(crate) fn load(store: &Store) -> FocusSync {
        let latest = store.read_cbor::<FocusChange>(FILE).ok().flatten();
        FocusSync { latest, local: None, send: false, follow: false }
    }

    fn sharing(&self) -> bool {
        self.send || self.follow
    }

    /// The Focus of this device now. Returns the change for the others when this is something the person did here.
    fn local(&mut self, me: DeviceId, on: bool, name: &str) -> Option<FocusChange> {
        // Kept also while nothing is sent, so turning sending on later does not take the state of then for a change.
        let before = self.local.replace(on)?;
        if !self.send || before == on || self.latest.as_ref().map(|l| l.on) == Some(on) {
            return None;
        }
        let seq = self.latest.as_ref().map_or(0, |l| l.seq) + 1;
        let change = FocusChange { on, name: name.to_string(), seq, origin: Some(me) };
        self.latest = Some(change.clone());
        Some(change)
    }

    /// A change that came from `from`. Returns it when it is newer than what this device knew.
    fn received(&mut self, from: DeviceId, mut change: FocusChange) -> Option<FocusChange> {
        change.origin.get_or_insert(from);
        if !is_newer(&change, self.latest.as_ref()) {
            return None;
        }
        // Even with sharing off the newest change is kept: a change made here later must be numbered after it, or the others
        // would take it for an old one.
        self.latest = Some(change.clone());
        Some(change)
    }

    /// Whether this device turns its own Focus on or off for a newer change. Never for one it made itself, which a device that
    /// was away may hand back.
    fn follows(&self, me: DeviceId, change: &FocusChange) -> bool {
        self.follow && change.origin != Some(me)
    }
}

fn is_newer(change: &FocusChange, latest: Option<&FocusChange>) -> bool {
    latest.is_none_or(|l| (change.seq, change.origin) > (l.seq, l.origin))
}

/// Whether a device knows the Focus messages, from what its hello says it can do.
pub fn speaks(caps: &[String]) -> bool {
    caps.iter().any(|c| c == CAP_SHARE || c == CAP_FOLLOW)
}

impl Engine {
    /// What this device does with Focus now: `send` the changes its person makes, `follow` the changes of the others. The app knows
    /// both from its setting and from what its system lets it do, and says it again when either changes.
    pub fn set_focus_sharing(&self, send: bool, follow: bool) {
        let mut focus = self.inner.focus.lock().unwrap();
        focus.send = send;
        focus.follow = follow;
    }

    /// The Focus of this device as the app reads it now: on or off, and its name when the system tells. Called at start and whenever
    /// it may have changed; the core works out whether it is news for the others.
    pub async fn focus_local(&self, on: bool, name: &str) {
        let change = self.inner.focus.lock().unwrap().local(self.inner.my_id, on, name);
        let status_changed = {
            let mut status = self.inner.my_status.lock().unwrap();
            let changed = status.dnd != Some(on);
            status.dnd = Some(on);
            changed
        };
        if status_changed {
            self.inner.broadcast(Msg::Status(Status { dnd: Some(on), ..Status::default() })).await;
        }
        if let Some(change) = change {
            self.inner.save_focus(&change);
            for id in self.inner.focus_peers() {
                let _ = self.inner.send_to(&id, Msg::Focus(change.clone())).await;
            }
        }
    }
}

impl Inner {
    pub(crate) fn focus_received(&self, from: DeviceId, change: FocusChange) {
        let (newer, follow) = {
            let mut focus = self.focus.lock().unwrap();
            let newer = focus.received(from, change);
            let follow = newer.as_ref().is_some_and(|c| focus.follows(self.my_id, c));
            (newer, follow)
        };
        let Some(change) = newer else { return };
        self.save_focus(&change);
        if follow {
            let origin = change.origin.unwrap_or(from);
            self.emit(Event::Focus { from, origin, on: change.on, name: change.name });
        }
    }

    /// The newest change, for a device that just connected, when this one takes part and that one knows the message.
    pub(crate) fn focus_catch_up(&self, caps: &[String]) -> Option<Msg> {
        let focus = self.focus.lock().unwrap();
        if !focus.sharing() || !speaks(caps) {
            return None;
        }
        focus.latest.clone().map(Msg::Focus)
    }

    fn focus_peers(&self) -> Vec<DeviceId> {
        let peers = self.peers.lock().unwrap();
        peers
            .iter()
            .filter(|(_, p)| p.session.is_some() && p.hello.as_ref().is_some_and(|h| speaks(&h.caps)))
            .map(|(id, _)| *id)
            .collect()
    }

    fn save_focus(&self, change: &FocusChange) {
        if let Err(e) = self.store.write_cbor(FILE, change) {
            tracing::warn!("could not keep the newest focus change: {e}");
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn id(n: u8) -> DeviceId {
        DeviceId::from_bytes([n; 16])
    }

    fn sync(send: bool, follow: bool) -> FocusSync {
        FocusSync { latest: None, local: None, send, follow }
    }

    #[test]
    fn the_first_reading_is_where_it_stands_and_only_a_change_travels() {
        let mut mac = sync(true, true);
        assert_eq!(mac.local(id(1), false, ""), None);
        assert_eq!(mac.local(id(1), false, ""), None);
        let change = mac.local(id(1), true, "Work").unwrap();
        assert_eq!((change.on, change.name.as_str(), change.seq, change.origin), (true, "Work", 1, Some(id(1))));
        let off = mac.local(id(1), false, "").unwrap();
        assert_eq!((off.on, off.seq), (false, 2));
    }

    fn change(on: bool, seq: u64) -> FocusChange {
        FocusChange { on, name: String::new(), seq, origin: None }
    }

    #[test]
    fn a_device_that_follows_does_not_send_it_back() {
        let mut phone = sync(true, true);
        phone.local(id(2), false, "");
        let from_mac = FocusChange { on: true, name: "Work".into(), seq: 1, origin: None };
        let newer = phone.received(id(1), from_mac).unwrap();
        assert_eq!(newer.origin, Some(id(1)));
        assert!(phone.follows(id(2), &newer));
        // Tandem turned the mode of the phone on: the reading changes, and that is no news.
        assert_eq!(phone.local(id(2), true, ""), None);
        // The person turns it off on the phone: that is, and it comes after the change of the Mac.
        let off = phone.local(id(2), false, "").unwrap();
        assert_eq!((off.on, off.seq, off.origin), (false, 2, Some(id(2))));
    }

    #[test]
    fn an_older_change_is_left_and_a_tie_goes_to_the_higher_id() {
        let mut laptop = sync(true, true);
        assert!(laptop.received(id(1), change(true, 5)).is_some());
        assert!(laptop.received(id(2), change(false, 4)).is_none());
        assert!(laptop.received(id(1), change(true, 5)).is_none());
        // The same number from a device with a higher id wins, everywhere, so all devices pick the same one.
        assert_eq!(laptop.received(id(2), change(false, 5)).unwrap().origin, Some(id(2)));
        assert!(laptop.received(id(1), change(true, 5)).is_none());
    }

    #[test]
    fn a_device_that_only_shares_keeps_the_newest_to_number_its_own_after_it() {
        let mut pc = sync(true, false);
        pc.local(id(4), false, "");
        let newer = pc.received(id(1), change(true, 9)).unwrap();
        assert!(!pc.follows(id(4), &newer));
        // It cannot follow, so its own Do Not Disturb stays off. The person turns it on and off: the off is the newest change.
        assert_eq!(pc.local(id(4), true, ""), None);
        assert_eq!(pc.local(id(4), false, "").map(|c| c.seq), Some(10));
    }

    #[test]
    fn with_sharing_off_nothing_is_sent_or_followed_but_the_order_is_kept() {
        let mut off = sync(false, false);
        off.local(id(5), false, "");
        assert_eq!(off.local(id(5), true, ""), None);
        let newer = off.received(id(1), change(true, 7)).unwrap();
        assert!(!off.follows(id(5), &newer));
        off.send = true;
        off.follow = true;
        assert_eq!(off.local(id(5), false, "").map(|c| c.seq), Some(8));
    }

    #[test]
    fn its_own_change_coming_back_is_not_followed() {
        let mut mac = sync(true, true);
        let mine = FocusChange { on: true, name: String::new(), seq: 3, origin: Some(id(1)) };
        let newer = mac.received(id(2), mine).unwrap();
        assert!(!mac.follows(id(1), &newer));
        assert_eq!(mac.latest.as_ref().map(|l| l.seq), Some(3));
    }
}
