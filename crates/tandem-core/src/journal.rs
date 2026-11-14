//! The transfer journal: every offer, incoming and outgoing, kept in the data folder with what it holds, for whom, and how far
//! each file got. The engine works from the copy in memory and writes every change of state through to disk, so a restart starts
//! where the last one stopped. One small file per offer, so a change rewrites only that offer and a broken file costs only that
//! offer. The layout and the rules for the packages that build on it are in docs/TRANSFERS.md.

use std::collections::{HashMap, HashSet};
use std::path::Path;
use std::sync::atomic::AtomicBool;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use serde::{Deserialize, Serialize};
use tokio_util::sync::CancellationToken;
use tracing::warn;

use crate::ids::{DeviceId, now_ms};
use crate::proto::{ShareItem, ShareOffer, ShareOrigin};
use crate::store::Store;

/// The layout this build writes. A file of a newer one is left alone, so going back a version and forward again loses nothing.
pub const FORMAT: u32 = 1;
/// The folder in the data folder.
pub const DIR: &str = "transfers";
/// The folder in the data folder where incoming files are written until they are whole.
pub const PARTIAL_DIR: &str = "incoming";

/// An offer nobody said yes to. The sender keeps it as long, so a yes always finds the offer still there.
pub(crate) const WAITING_KEPT: Duration = Duration::from_secs(6 * 3600);
/// A transfer that stopped halfway (the engine stopped, or the sender went away for good) with its partial files, so it can be
/// picked up again (P2). As long as partial files were kept before there was a journal.
pub(crate) const UNFINISHED_KEPT: Duration = Duration::from_secs(3 * 24 * 3600);
/// A transfer that ended, for a list of what came and went, and for a receiver that pulls once more after a failed checksum.
pub(crate) const ENDED_KEPT: Duration = Duration::from_secs(6 * 3600);
/// The most ended transfers kept, whatever their age: a phone that sends a hundred screenshots a day must not grow a hundred files.
const ENDED_MAX: usize = 200;
/// A file that could not be read (broken, or written by a newer version) goes when it is this old.
const UNREADABLE_KEPT: Duration = Duration::from_secs(7 * 24 * 3600);

/// Which offer: outgoing offers are ours, so their number is enough; incoming numbers are chosen by each sender.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Key {
    Out(u64),
    In(DeviceId, u64),
}

impl Key {
    fn file_name(&self) -> String {
        match self {
            Key::Out(offer) => format!("out-{offer:016x}.cbor"),
            Key::In(peer, offer) => format!("in-{peer}-{offer:016x}.cbor"),
        }
    }
}

/// One offer, as it is on disk.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct TransferRecord {
    /// The layout that wrote it (see `FORMAT`).
    #[serde(default)]
    pub format: u32,
    pub offer: u64,
    pub incoming: bool,
    #[serde(default = "other_origin")]
    pub origin: ShareOrigin,
    /// Wall clock in ms, as everything here: an `Instant` does not survive a restart.
    #[serde(default)]
    pub created_ms: u64,
    /// The last change of state.
    #[serde(default)]
    pub updated_ms: u64,
    pub items: Vec<RecordItem>,
    /// Outgoing: one per receiver. Incoming: one, the sender.
    pub peers: Vec<PeerProgress>,
    /// Incoming: not taken by itself, because this device was on mobile data and it is large (see `transfer::METERED_ASK_BYTES`).
    #[serde(default)]
    pub held: bool,
}

fn other_origin() -> ShareOrigin {
    ShareOrigin::Other
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct RecordItem {
    pub name: String,
    pub size: u64,
    #[serde(default)]
    pub mime: String,
    /// As the offer said it (`ShareItem::modified`).
    #[serde(default)]
    pub modified: Option<u64>,
    /// Outgoing: what the platform opens to read it, a path or a content URI. Empty for incoming.
    #[serde(default)]
    pub source: String,
    /// Outgoing: when the source last changed as the sender saw it when it was offered, where it was looked at. With `size` this is
    /// what a queued offer (P3) compares to before it goes out, so a file that changed since is reported and not sent.
    #[serde(default)]
    pub source_modified_ms: Option<u64>,
    /// The folder inside the offer this file belongs in, parts joined by `/`; empty for a loose file. Kept for folders (P4).
    #[serde(default)]
    pub path: String,
}

/// How far one other device is with an offer.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct PeerProgress {
    pub device: DeviceId,
    pub stage: Stage,
    /// When the offer went to this device or came from it; zero while it waits in the queue (P3).
    #[serde(default)]
    pub offered_ms: u64,
    /// One per item, in the order of `TransferRecord::items`.
    pub items: Vec<ItemProgress>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct ItemProgress {
    pub state: ItemState,
    /// The bytes this side has: in the partial file (incoming) or served (outgoing). After a restart, for incoming, the length of the
    /// partial file on disk, which is what a resume starts from.
    #[serde(default)]
    pub done: u64,
    #[serde(default)]
    pub error: Option<String>,
    /// Incoming and done: where the file is now.
    #[serde(default)]
    pub location: Option<String>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Stage {
    /// Outgoing: the receiver was away; the offer goes when it comes (P3). Never written yet.
    Queued,
    /// The offer went out (or came in) and nothing was pulled: an incoming one waits for a yes.
    Offered,
    /// Incoming: the person or auto-accept said yes. Outgoing: the receiver pulled.
    Accepted,
    /// Stopped, declined or expired for this device: nothing is served or pulled any more. Why is on each file.
    Closed,
    #[serde(other)]
    Unknown,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ItemState {
    Waiting,
    /// Being pulled or served now. Never on disk after a restart: it becomes `Interrupted` when the journal is loaded.
    Running,
    /// Stopped halfway, with the partial file kept (incoming). Only a person picks it up again (P2).
    Interrupted,
    Done,
    Failed,
    Cancelled,
    Declined,
    Expired,
    #[serde(other)]
    Unknown,
}

impl ItemState {
    pub fn is_final(self) -> bool {
        !matches!(self, ItemState::Waiting | ItemState::Running | ItemState::Interrupted)
    }

    /// The reason a file that ended this way carries in `TransferDone::error`: short words the apps may translate, like "cancelled".
    pub fn reason(self) -> Option<&'static str> {
        match self {
            ItemState::Cancelled => Some("cancelled"),
            ItemState::Declined => Some("declined"),
            ItemState::Expired => Some("expired"),
            _ => None,
        }
    }
}

impl ItemProgress {
    fn waiting() -> ItemProgress {
        ItemProgress { state: ItemState::Waiting, done: 0, error: None, location: None }
    }
}

impl PeerProgress {
    pub fn new(device: DeviceId, stage: Stage, items: usize, offered_ms: u64) -> PeerProgress {
        PeerProgress { device, stage, offered_ms, items: (0..items).map(|_| ItemProgress::waiting()).collect() }
    }

    /// Whether files may still be served to it or pulled from it.
    pub fn open(&self) -> bool {
        matches!(self.stage, Stage::Offered | Stage::Accepted)
    }

    pub fn unfinished(&self) -> bool {
        self.items.iter().any(|item| !item.state.is_final())
    }

    /// Closes it for good: every file that had not ended ends as `state`. Returns the files that ended now, so each gets one
    /// `Finished` and a second close (the reply and the expiry crossing) says nothing twice.
    pub fn close(&mut self, state: ItemState, error: Option<String>) -> Vec<u32> {
        self.stage = Stage::Closed;
        let mut ended = Vec::new();
        for (index, item) in self.items.iter_mut().enumerate() {
            if !item.state.is_final() {
                item.state = state;
                item.error = error.clone().or_else(|| state.reason().map(str::to_string));
                ended.push(index as u32);
            }
        }
        ended
    }
}

impl TransferRecord {
    pub fn outgoing(offer: u64, origin: ShareOrigin, items: Vec<RecordItem>, peers: &[DeviceId]) -> TransferRecord {
        let now = now_ms();
        let count = items.len();
        TransferRecord {
            format: FORMAT,
            offer,
            incoming: false,
            origin,
            created_ms: now,
            updated_ms: now,
            items,
            peers: peers.iter().map(|id| PeerProgress::new(*id, Stage::Offered, count, now)).collect(),
            held: false,
        }
    }

    pub fn incoming(from: DeviceId, offer: &ShareOffer, held: bool) -> TransferRecord {
        let now = now_ms();
        let items: Vec<RecordItem> = offer.items.iter().map(RecordItem::from_share).collect();
        let count = items.len();
        TransferRecord {
            format: FORMAT,
            offer: offer.id,
            incoming: true,
            origin: offer.origin,
            created_ms: now,
            updated_ms: now,
            items,
            peers: vec![PeerProgress::new(from, Stage::Offered, count, now)],
            held,
        }
    }

    pub fn key(&self) -> Key {
        match (self.incoming, self.peers.first()) {
            (true, Some(sender)) => Key::In(sender.device, self.offer),
            _ => Key::Out(self.offer),
        }
    }

    pub fn peer(&self, id: &DeviceId) -> Option<&PeerProgress> {
        self.peers.iter().find(|p| p.device == *id)
    }

    pub fn peer_mut(&mut self, id: &DeviceId) -> Option<&mut PeerProgress> {
        self.peers.iter_mut().find(|p| p.device == *id)
    }

    /// The progress of one file with one device.
    pub fn item_mut(&mut self, peer: &DeviceId, index: u32) -> Option<&mut ItemProgress> {
        self.peer_mut(peer)?.items.get_mut(index as usize)
    }

    /// Nothing is left to happen: every file with every device ended.
    pub fn ended(&self) -> bool {
        self.peers.iter().all(|p| !p.unfinished())
    }

    /// The offer as it went over the wire.
    pub fn share_offer(&self) -> ShareOffer {
        ShareOffer { id: self.offer, origin: self.origin, items: self.items.iter().map(RecordItem::to_share).collect() }
    }

    /// What a restart finds: nothing runs any more. Whatever was underway is interrupted, and for incoming files the partial file on
    /// disk says how far it got (the last write of state can be older than the last byte). Waiting files of an incoming offer that
    /// was accepted were underway too: they only waited for a free slot. Files of a device that was being stopped when the engine
    /// stopped end as stopped. Whether anything changed.
    fn settle_after_restart(&mut self, partial_len: impl Fn(&DeviceId, u32) -> Option<u64>) -> bool {
        let before = self.clone();
        let incoming = self.incoming;
        for peer in &mut self.peers {
            if peer.stage == Stage::Closed {
                peer.close(ItemState::Cancelled, None);
                continue;
            }
            let accepted = peer.stage == Stage::Accepted;
            for (index, item) in peer.items.iter_mut().enumerate() {
                let underway =
                    item.state == ItemState::Running || (incoming && accepted && item.state == ItemState::Waiting);
                if underway {
                    item.state = ItemState::Interrupted;
                }
                if incoming && item.state == ItemState::Interrupted {
                    item.done = partial_len(&peer.device, index as u32).unwrap_or(0);
                }
            }
        }
        *self != before
    }
}

impl RecordItem {
    pub fn from_share(item: &ShareItem) -> RecordItem {
        RecordItem {
            name: item.name.clone(),
            size: item.size,
            mime: item.mime.clone(),
            modified: item.modified,
            source: String::new(),
            source_modified_ms: None,
            path: String::new(),
        }
    }

    pub fn to_share(&self) -> ShareItem {
        ShareItem { name: self.name.clone(), size: self.size, mime: self.mime.clone(), modified: self.modified }
    }
}

/// Where an incoming file is written until it is whole, in `PARTIAL_DIR`. A resume starts from its length.
pub fn partial_name(peer: &DeviceId, offer: u64, index: u32) -> String {
    format!("{peer}-{offer:016x}-{index}.part")
}

/// What only a running engine has of an offer, and so is never written: whether it was started, and the tokens that stop it.
pub(crate) struct Run {
    /// Incoming: pulling began; a second yes does nothing.
    pub started: AtomicBool,
    /// Stops all of it. Outgoing: the parent of the token of each receiver.
    pub cancel: CancellationToken,
    /// Outgoing: one token per receiver, a child of `cancel`, so stopping one receiver leaves the others running (R-B8).
    peers: Mutex<HashMap<DeviceId, CancellationToken>>,
}

impl Run {
    fn new() -> Run {
        Run { started: AtomicBool::new(false), cancel: CancellationToken::new(), peers: Mutex::new(HashMap::new()) }
    }

    /// The token that stops serving this offer to `peer`.
    pub fn peer_token(&self, peer: &DeviceId) -> CancellationToken {
        self.peers.lock().unwrap().entry(*peer).or_insert_with(|| self.cancel.child_token()).clone()
    }

    pub fn stop_peer(&self, peer: &DeviceId) {
        self.peer_token(peer).cancel();
    }
}

struct Entry {
    record: TransferRecord,
    run: Arc<Run>,
}

/// Files that ended because their offer expired: one `Finished` each, and for an incoming offer that waited for a yes, a word to
/// the sender.
pub(crate) struct Lapse {
    pub key: Key,
    pub peer: DeviceId,
    pub incoming: bool,
    pub items: Vec<(u32, RecordItem)>,
    pub tell_sender: bool,
}

pub(crate) struct Journal {
    store: Store,
    book: Mutex<HashMap<Key, Entry>>,
}

impl Journal {
    /// Reads every offer back from `data_dir`. Never fails: a file that cannot be read is moved aside and the engine starts without
    /// that offer, which is better than an engine that does not start.
    pub fn load(data_dir: &Path) -> std::io::Result<Journal> {
        let store = Store::new(data_dir.join(DIR)).map_err(std::io::Error::other)?;
        let partials = data_dir.join(PARTIAL_DIR);
        let mut book = HashMap::new();
        let mut changed = Vec::new();
        let now = std::time::SystemTime::now();
        for entry in std::fs::read_dir(store.dir())?.flatten() {
            let path = entry.path();
            let name = entry.file_name().to_string_lossy().into_owned();
            let old = |kept: Duration| {
                entry.metadata().and_then(|m| m.modified()).is_ok_and(|modified| now.duration_since(modified).unwrap_or_default() > kept)
            };
            // What a write that was cut off left behind. A fresh one may belong to another process that writes right now.
            if name.ends_with(".tmp") {
                if old(Duration::from_secs(3600)) {
                    let _ = std::fs::remove_file(&path);
                }
                continue;
            }
            if !name.ends_with(".cbor") {
                if old(UNREADABLE_KEPT) {
                    let _ = std::fs::remove_file(&path);
                }
                continue;
            }
            let record = std::fs::read(&path)
                .map_err(crate::Error::from)
                .and_then(|bytes| crate::proto::decode::<TransferRecord>(&bytes));
            let mut record = match record {
                Ok(record) if record.format > FORMAT => {
                    if old(UNREADABLE_KEPT) {
                        let _ = std::fs::remove_file(&path);
                    }
                    continue;
                }
                Ok(record) if record.peers.is_empty() || record.key().file_name() != name => {
                    warn!(file = %name, "a transfer in the journal does not match its file name, set aside");
                    let _ = std::fs::rename(&path, path.with_extension("bad"));
                    continue;
                }
                Ok(record) => record,
                Err(e) => {
                    warn!(file = %name, "a transfer in the journal could not be read, set aside: {e}");
                    let _ = std::fs::rename(&path, path.with_extension("bad"));
                    continue;
                }
            };
            let offer = record.offer;
            let settled = record.settle_after_restart(|peer, index| {
                std::fs::metadata(partials.join(partial_name(peer, offer, index))).ok().map(|m| m.len())
            });
            if settled {
                changed.push(record.key());
            }
            let run = Arc::new(Run::new());
            for peer in &record.peers {
                if peer.stage == Stage::Closed {
                    if record.incoming {
                        run.cancel.cancel();
                    } else {
                        run.stop_peer(&peer.device);
                    }
                }
            }
            book.insert(record.key(), Entry { record, run });
        }
        let journal = Journal { store, book: Mutex::new(HashMap::new()) };
        // What the start changed goes to disk now, so the partial lengths and the stops are there even if nothing changes later.
        for key in changed {
            journal.write(&book[&key].record);
        }
        *journal.book.lock().unwrap() = book;
        Ok(journal)
    }

    fn write(&self, record: &TransferRecord) {
        if let Err(e) = self.store.write_cbor(&record.key().file_name(), record) {
            warn!(offer = record.offer, "the journal could not be written: {e}");
        }
    }

    /// Adds an offer, or replaces the one with the same key, and returns what a running engine keeps of it.
    pub fn insert(&self, record: TransferRecord) -> Arc<Run> {
        let run = Arc::new(Run::new());
        let mut book = self.book.lock().unwrap();
        // Written while the book is locked, here and in `change`: two changes of one offer can never land on disk in the wrong order,
        // and whoever sees a state in memory can count on it being on disk.
        self.write(&record);
        book.insert(record.key(), Entry { record, run: run.clone() });
        run
    }

    pub fn run(&self, key: Key) -> Option<Arc<Run>> {
        self.book.lock().unwrap().get(&key).map(|entry| entry.run.clone())
    }

    pub fn read<R>(&self, key: Key, f: impl FnOnce(&TransferRecord) -> R) -> Option<R> {
        self.book.lock().unwrap().get(&key).map(|entry| f(&entry.record))
    }

    /// Changes an offer and writes it through.
    pub fn change<R>(&self, key: Key, f: impl FnOnce(&mut TransferRecord) -> R) -> Option<R> {
        let mut book = self.book.lock().unwrap();
        let entry = book.get_mut(&key)?;
        let result = f(&mut entry.record);
        entry.record.updated_ms = now_ms();
        self.write(&entry.record);
        Some(result)
    }

    /// Changes an offer in memory only: progress, and `Running`, which a restart turns into `Interrupted` anyway.
    pub fn touch<R>(&self, key: Key, f: impl FnOnce(&mut TransferRecord) -> R) -> Option<R> {
        self.book.lock().unwrap().get_mut(&key).map(|entry| f(&mut entry.record))
    }

    pub fn remove(&self, key: Key) -> Option<TransferRecord> {
        let mut book = self.book.lock().unwrap();
        let entry = book.remove(&key)?;
        let _ = std::fs::remove_file(self.store.dir().join(key.file_name()));
        Some(entry.record)
    }

    /// Every offer, the newest first.
    pub fn records(&self) -> Vec<TransferRecord> {
        let mut all: Vec<TransferRecord> = self.book.lock().unwrap().values().map(|entry| entry.record.clone()).collect();
        all.sort_by(|a, b| b.created_ms.cmp(&a.created_ms).then(a.offer.cmp(&b.offer)));
        all
    }

    /// The partial files that belong to an incoming file that has not ended. Anything else in `PARTIAL_DIR` can never be finished.
    pub fn partials_in_use(&self) -> HashSet<String> {
        let book = self.book.lock().unwrap();
        let mut names = HashSet::new();
        for entry in book.values().filter(|entry| entry.record.incoming) {
            for peer in &entry.record.peers {
                for (index, item) in peer.items.iter().enumerate() {
                    if !item.state.is_final() {
                        names.insert(partial_name(&peer.device, entry.record.offer, index as u32));
                    }
                }
            }
        }
        names
    }

    /// Lets offers go that waited, stopped halfway or ended long enough ago (see the `_KEPT` durations). Returns the files that
    /// ended now; the caller tells the apps and removes partial files.
    pub fn expire(&self, now_ms: u64) -> Vec<Lapse> {
        let age = |since: u64| Duration::from_millis(now_ms.saturating_sub(since));
        let mut book = self.book.lock().unwrap();
        let mut lapses = Vec::new();
        let mut gone = Vec::new();
        for (key, entry) in book.iter_mut() {
            let running = entry.run.started.load(std::sync::atomic::Ordering::SeqCst) && !entry.record.ended();
            let record = &mut entry.record;
            let mut changed = false;
            if !running {
                for peer in &mut record.peers {
                    let lapsed = match peer.stage {
                        Stage::Offered => age(peer.offered_ms.max(record.created_ms)) > WAITING_KEPT,
                        // Its own rules come with the queue (P3).
                        Stage::Queued => false,
                        _ => peer.unfinished() && age(record.updated_ms) > UNFINISHED_KEPT,
                    };
                    if !lapsed {
                        continue;
                    }
                    let tell_sender = record.incoming && peer.stage == Stage::Offered;
                    let ended = peer.close(ItemState::Expired, None);
                    changed = true;
                    let items = ended.iter().map(|&index| (index, record.items[index as usize].clone())).collect();
                    lapses.push(Lapse { key: *key, peer: peer.device, incoming: record.incoming, items, tell_sender });
                    if !record.incoming {
                        entry.run.stop_peer(&peer.device);
                    }
                }
            }
            // An incoming offer that expired is of no use to anyone; an outgoing one stays a while to show what happened.
            let ended = record.ended();
            if (ended && record.incoming && changed) || (ended && age(record.updated_ms) > ENDED_KEPT) {
                gone.push(*key);
            } else if changed {
                record.updated_ms = now_ms;
                if let Err(e) = self.store.write_cbor(&key.file_name(), record) {
                    warn!(offer = record.offer, "the journal could not be written: {e}");
                }
            }
        }
        let mut ended: Vec<(u64, Key)> = book
            .iter()
            .filter(|(key, entry)| entry.record.ended() && !gone.contains(key))
            .map(|(key, entry)| (entry.record.updated_ms, *key))
            .collect();
        if ended.len() > ENDED_MAX {
            ended.sort_by_key(|(updated, _)| *updated);
            gone.extend(ended[..ended.len() - ENDED_MAX].iter().map(|(_, key)| *key));
        }
        for key in gone {
            book.remove(&key);
            let _ = std::fs::remove_file(self.store.dir().join(key.file_name()));
        }
        lapses
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn item(name: &str, size: u64) -> RecordItem {
        RecordItem::from_share(&ShareItem { name: name.into(), size, mime: String::new(), modified: None })
    }

    #[test]
    fn a_restart_turns_what_ran_into_interrupted_with_the_length_of_the_partial_file() {
        let dir = tempfile::tempdir().unwrap();
        let peer = DeviceId::from_bytes([7; 16]);
        let offer = ShareOffer { id: 42, origin: ShareOrigin::Files, items: vec![item("a", 10).to_share(), item("b", 100).to_share(), item("c", 5).to_share()] };
        {
            let journal = Journal::load(dir.path()).unwrap();
            journal.insert(TransferRecord::incoming(peer, &offer, false));
            journal.change(Key::In(peer, 42), |record| {
                let progress = record.peer_mut(&peer).unwrap();
                progress.stage = Stage::Accepted;
                progress.items[0].state = ItemState::Done;
                // Running is only in memory; on disk this file is still waiting, as after a kill in the middle of it.
            });
            journal.touch(Key::In(peer, 42), |record| record.item_mut(&peer, 1).unwrap().state = ItemState::Running);
        }
        std::fs::create_dir_all(dir.path().join(PARTIAL_DIR)).unwrap();
        std::fs::write(dir.path().join(PARTIAL_DIR).join(partial_name(&peer, 42, 1)), [0u8; 37]).unwrap();

        let journal = Journal::load(dir.path()).unwrap();
        let record = journal.read(Key::In(peer, 42), Clone::clone).unwrap();
        let states: Vec<_> = record.peers[0].items.iter().map(|i| (i.state, i.done)).collect();
        assert_eq!(states, [(ItemState::Done, 0), (ItemState::Interrupted, 37), (ItemState::Interrupted, 0)]);
        assert_eq!(journal.partials_in_use().len(), 2);
    }

    #[test]
    fn a_stop_that_the_engine_did_not_see_through_ends_as_stopped_after_the_start() {
        let dir = tempfile::tempdir().unwrap();
        let peer = DeviceId::from_bytes([8; 16]);
        let offer = ShareOffer { id: 5, origin: ShareOrigin::Files, items: vec![item("a", 10).to_share()] };
        {
            let journal = Journal::load(dir.path()).unwrap();
            journal.insert(TransferRecord::incoming(peer, &offer, false));
            journal.change(Key::In(peer, 5), |record| record.peers[0].stage = Stage::Closed);
        }
        let journal = Journal::load(dir.path()).unwrap();
        assert_eq!(journal.read(Key::In(peer, 5), |r| r.peers[0].items[0].state).unwrap(), ItemState::Cancelled);
        assert!(journal.partials_in_use().is_empty());
        assert!(journal.run(Key::In(peer, 5)).unwrap().cancel.is_cancelled());
    }

    #[test]
    fn an_unreadable_or_foreign_file_is_set_aside_and_a_newer_one_left_alone() {
        let dir = tempfile::tempdir().unwrap();
        let transfers = dir.path().join(DIR);
        std::fs::create_dir_all(&transfers).unwrap();
        std::fs::write(transfers.join("out-0000000000000001.cbor"), b"not cbor").unwrap();
        let mut newer = TransferRecord::outgoing(2, ShareOrigin::Files, vec![item("x", 1)], &[DeviceId::from_bytes([1; 16])]);
        newer.format = FORMAT + 1;
        std::fs::write(transfers.join("out-0000000000000002.cbor"), crate::proto::encode(&newer).unwrap()).unwrap();

        let journal = Journal::load(dir.path()).unwrap();
        assert!(journal.records().is_empty());
        assert!(transfers.join("out-0000000000000001.bad").exists());
        assert!(transfers.join("out-0000000000000002.cbor").exists());
    }

    #[test]
    fn an_offer_waiting_for_a_yes_expires_once_and_tells_the_sender() {
        let dir = tempfile::tempdir().unwrap();
        let peer = DeviceId::from_bytes([3; 16]);
        let journal = Journal::load(dir.path()).unwrap();
        let offer = ShareOffer { id: 9, origin: ShareOrigin::Files, items: vec![item("a", 1).to_share(), item("b", 2).to_share()] };
        journal.insert(TransferRecord::incoming(peer, &offer, false));
        let created = journal.read(Key::In(peer, 9), |r| r.created_ms).unwrap();

        assert!(journal.expire(created + 1000).is_empty());
        let lapses = journal.expire(created + WAITING_KEPT.as_millis() as u64 + 1);
        assert_eq!(lapses.len(), 1);
        assert!(lapses[0].tell_sender && lapses[0].incoming);
        assert_eq!(lapses[0].items.len(), 2);
        assert!(journal.records().is_empty());
        assert!(!dir.path().join(DIR).join(Key::In(peer, 9).file_name()).exists());
    }

    #[test]
    fn closing_twice_ends_each_file_once() {
        let mut progress = PeerProgress::new(DeviceId::from_bytes([4; 16]), Stage::Accepted, 3, 0);
        progress.items[0].state = ItemState::Done;
        assert_eq!(progress.close(ItemState::Declined, None), [1, 2]);
        assert_eq!(progress.items[1].error.as_deref(), Some("declined"));
        assert!(progress.close(ItemState::Expired, None).is_empty());
        assert!(!progress.open());
    }

    #[test]
    fn an_older_file_without_the_newer_fields_still_reads() {
        // Only the fields a first version could not do without.
        let peer = DeviceId::from_bytes([5; 16]);
        let value = ciborium::Value::Map(vec![
            (ciborium::Value::Text("offer".into()), ciborium::Value::Integer(1.into())),
            (ciborium::Value::Text("incoming".into()), ciborium::Value::Bool(false)),
            (
                ciborium::Value::Text("items".into()),
                ciborium::Value::Array(vec![ciborium::Value::Map(vec![
                    (ciborium::Value::Text("name".into()), ciborium::Value::Text("a".into())),
                    (ciborium::Value::Text("size".into()), ciborium::Value::Integer(3.into())),
                ])]),
            ),
            (
                ciborium::Value::Text("peers".into()),
                ciborium::Value::Array(vec![ciborium::Value::Map(vec![
                    (ciborium::Value::Text("device".into()), ciborium::Value::serialized(&peer).unwrap()),
                    (ciborium::Value::Text("stage".into()), ciborium::Value::Text("a_stage_from_later".into())),
                    (
                        ciborium::Value::Text("items".into()),
                        ciborium::Value::Array(vec![ciborium::Value::Map(vec![(
                            ciborium::Value::Text("state".into()),
                            ciborium::Value::Text("done".into()),
                        )])]),
                    ),
                ])]),
            ),
        ]);
        let mut bytes = Vec::new();
        ciborium::into_writer(&value, &mut bytes).unwrap();
        let record: TransferRecord = crate::proto::decode(&bytes).unwrap();
        assert_eq!(record.origin, ShareOrigin::Other);
        assert_eq!(record.peers[0].stage, Stage::Unknown);
        assert_eq!(record.peers[0].items[0].state, ItemState::Done);
        assert_eq!(record.items[0].path, "");
    }
}
