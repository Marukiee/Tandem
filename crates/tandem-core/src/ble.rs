//! Clipboard and notifications over Bluetooth Low Energy, for when there is no network.
//!
//! The link itself is not trusted: anyone in range can connect to the phone. What makes
//! it safe is the frame, sealed with a key the two devices share.
//!
//! The key is made by whichever of the two has the lower id and travels once, over the
//! authenticated QUIC connection, in [`Msg::BleKey`]. From it each direction gets its own
//! key, so a frame can never be turned round and played back to its sender. Every frame
//! carries a sequence number that only goes up; the receiver remembers the highest one it
//! accepted, so a captured frame cannot be replayed later either.
//!
//! Only a few messages may cross the air (see [`allowed`]). Everything else waits for a
//! real connection. The native side only moves chunks: the frame is cut into pieces that
//! fit one write here, and put back together here.

use std::collections::{HashMap, HashSet, VecDeque};
use std::sync::atomic::Ordering;
use std::sync::Arc;

use ring::aead::{Aad, LessSafeKey, Nonce, UnboundKey, CHACHA20_POLY1305};
use ring::rand::{SecureRandom, SystemRandom};
use serde::{Deserialize, Serialize};

use crate::engine::Inner;
use crate::error::{Error, Result};
use crate::ids::{now_ms, DeviceId, ID_LEN};
use crate::proto::Msg;

const VERSION: u8 = 1;
const NONCE_LEN: usize = 12;
const TAG_LEN: usize = 16;
/// Header of a frame: version, then the sender's id, then the nonce.
const HEADER_LEN: usize = 1 + ID_LEN + NONCE_LEN;
/// A frame above this is not sent over Bluetooth: it would take many seconds.
pub const MAX_FRAME: usize = 48 * 1024;
/// What waits for a link to come up, per device. Old frames are dropped first.
const OUTBOX_LIMIT: usize = 32;
/// A chunk is msg id, index, count, then payload.
const CHUNK_HEADER: usize = 3;

/// Which messages may cross the air. A media cover is not one of them: it is too big for Bluetooth.
pub fn allowed(msg: &Msg) -> bool {
    matches!(
        msg,
        Msg::Clipboard(_)
            | Msg::Notification(_)
            | Msg::NotificationRemoved { .. }
            | Msg::NotificationAction(_)
            | Msg::MediaPlayers { .. }
            | Msg::MediaCommand { .. }
            | Msg::Ping { .. }
    )
}

/// What a frame carries inside: the message and the sequence number that stops replays.
#[derive(Serialize, Deserialize)]
struct Envelope {
    seq: u64,
    msg: Msg,
}

fn direction_key(master: &[u8; 32], from: &DeviceId, to: &DeviceId) -> LessSafeKey {
    let mut hasher = blake3::Hasher::new_derive_key("tandem ble frame v1");
    hasher.update(master);
    hasher.update(from.as_bytes());
    hasher.update(to.as_bytes());
    let derived = hasher.finalize();
    LessSafeKey::new(UnboundKey::new(&CHACHA20_POLY1305, derived.as_bytes()).expect("32 byte key"))
}

fn aad(from: &DeviceId, to: &DeviceId) -> Vec<u8> {
    let mut data = Vec::with_capacity(1 + 2 * ID_LEN);
    data.push(VERSION);
    data.extend_from_slice(from.as_bytes());
    data.extend_from_slice(to.as_bytes());
    data
}

/// Seals a message from `from` to `to` with the shared key.
pub fn seal(master: &[u8; 32], from: &DeviceId, to: &DeviceId, seq: u64, msg: Msg) -> Result<Vec<u8>> {
    if !allowed(&msg) {
        return Err(Error::invalid("that message does not go over Bluetooth"));
    }
    let mut body = crate::proto::encode(&Envelope { seq, msg })?;
    let mut nonce = [0u8; NONCE_LEN];
    SystemRandom::new().fill(&mut nonce).map_err(|_| Error::protocol("no randomness"))?;
    direction_key(master, from, to)
        .seal_in_place_append_tag(Nonce::assume_unique_for_key(nonce), Aad::from(aad(from, to)), &mut body)
        .map_err(|_| Error::protocol("could not seal"))?;
    let mut frame = Vec::with_capacity(HEADER_LEN + body.len());
    frame.push(VERSION);
    frame.extend_from_slice(from.as_bytes());
    frame.extend_from_slice(&nonce);
    frame.extend_from_slice(&body);
    if frame.len() > MAX_FRAME {
        return Err(Error::invalid("too big for Bluetooth"));
    }
    Ok(frame)
}

/// Who sent a frame, so the right key can be picked before opening it.
pub fn sender_of(frame: &[u8]) -> Option<DeviceId> {
    if frame.len() < HEADER_LEN + TAG_LEN || frame[0] != VERSION {
        return None;
    }
    let bytes: [u8; ID_LEN] = frame[1..1 + ID_LEN].try_into().ok()?;
    Some(DeviceId::from_bytes(bytes))
}

/// Opens a frame that was sent to `to`. Returns the sequence number and the message.
pub fn open(master: &[u8; 32], to: &DeviceId, frame: &[u8]) -> Result<(u64, Msg)> {
    let from = sender_of(frame).ok_or_else(|| Error::protocol("not a Bluetooth frame"))?;
    let nonce: [u8; NONCE_LEN] = frame[1 + ID_LEN..HEADER_LEN].try_into().expect("length checked");
    let mut body = frame[HEADER_LEN..].to_vec();
    let plain = direction_key(master, &from, to)
        .open_in_place(Nonce::assume_unique_for_key(nonce), Aad::from(aad(&from, to)), &mut body)
        .map_err(|_| Error::protocol("frame does not open"))?;
    let envelope: Envelope = crate::proto::decode(plain)?;
    if !allowed(&envelope.msg) {
        return Err(Error::protocol("message not allowed over Bluetooth"));
    }
    Ok((envelope.seq, envelope.msg))
}

/// Cuts a frame into writes of at most `chunk` bytes. `None` when it would need more than
/// 255 pieces or the chunk size is too small to be useful.
pub fn fragment(msg_id: u8, frame: &[u8], chunk: usize) -> Option<Vec<Vec<u8>>> {
    if chunk <= CHUNK_HEADER + 8 {
        return None;
    }
    let payload = chunk - CHUNK_HEADER;
    let count = frame.len().div_ceil(payload).max(1);
    if count > 255 {
        return None;
    }
    Some(
        frame
            .chunks(payload)
            .enumerate()
            .map(|(index, part)| {
                let mut out = Vec::with_capacity(CHUNK_HEADER + part.len());
                out.extend_from_slice(&[msg_id, index as u8, count as u8]);
                out.extend_from_slice(part);
                out
            })
            .collect(),
    )
}

/// Puts the pieces of one link back together. A piece with a new message id throws away a
/// half-finished older one, which is what a dropped connection leaves behind.
#[derive(Default)]
pub struct Reassembler {
    id: Option<u8>,
    parts: Vec<Option<Vec<u8>>>,
    got: usize,
}

impl Reassembler {
    pub fn push(&mut self, chunk: &[u8]) -> Option<Vec<u8>> {
        if chunk.len() < CHUNK_HEADER {
            return None;
        }
        let (id, index, count) = (chunk[0], chunk[1] as usize, chunk[2] as usize);
        if count == 0 || index >= count {
            return None;
        }
        if self.id != Some(id) || self.parts.len() != count {
            self.id = Some(id);
            self.parts = vec![None; count];
            self.got = 0;
        }
        if self.parts[index].is_none() {
            self.parts[index] = Some(chunk[CHUNK_HEADER..].to_vec());
            self.got += 1;
        }
        if self.got < count {
            return None;
        }
        let whole: Vec<u8> = self.parts.drain(..).flatten().flatten().collect();
        *self = Reassembler::default();
        Some(whole)
    }
}

/// What is kept about one device, in memory and in the store.
#[derive(Default, Clone)]
pub(crate) struct BleState {
    pub key: Option<[u8; 32]>,
    pub tx_seq: u64,
    pub rx_seq: u64,
}

#[derive(Default)]
pub(crate) struct BleHub {
    pub state: HashMap<DeviceId, BleState>,
    pub loaded: HashSet<DeviceId>,
    pub links: HashSet<DeviceId>,
    pub outbox: HashMap<DeviceId, VecDeque<Vec<u8>>>,
    pub rx: HashMap<String, Reassembler>,
}

fn file(peer: &DeviceId) -> String {
    format!("ble-{peer}.bin")
}

impl Inner {
    fn ble_load(&self, peer: &DeviceId) {
        let mut hub = self.ble.lock().unwrap();
        if !hub.loaded.insert(*peer) {
            return;
        }
        // key (32), tx seq (8), rx seq (8)
        let Ok(Some(bytes)) = self.store.read(&file(peer)) else { return };
        if bytes.len() != 48 {
            return;
        }
        let mut key = [0u8; 32];
        key.copy_from_slice(&bytes[..32]);
        let tx = u64::from_le_bytes(bytes[32..40].try_into().unwrap());
        let rx = u64::from_le_bytes(bytes[40..48].try_into().unwrap());
        hub.state.insert(*peer, BleState { key: Some(key), tx_seq: tx, rx_seq: rx });
    }

    fn ble_save(&self, peer: &DeviceId, state: &BleState) {
        let Some(key) = state.key else { return };
        let mut bytes = Vec::with_capacity(48);
        bytes.extend_from_slice(&key);
        bytes.extend_from_slice(&state.tx_seq.to_le_bytes());
        bytes.extend_from_slice(&state.rx_seq.to_le_bytes());
        if let Err(e) = self.store.write(&file(peer), &bytes) {
            tracing::warn!(error = %e, "could not save the Bluetooth key");
        }
    }

    pub(crate) fn ble_has_key(&self, peer: &DeviceId) -> bool {
        self.ble_load(peer);
        self.ble.lock().unwrap().state.get(peer).is_some_and(|s| s.key.is_some())
    }

    /// The lower id makes the key and sends it. The other side keeps what it is given.
    pub(crate) async fn ble_on_connected(&self, peer: DeviceId) {
        if self.my_id >= peer {
            return;
        }
        self.ble_load(&peer);
        let key = {
            let mut hub = self.ble.lock().unwrap();
            let state = hub.state.entry(peer).or_default();
            if state.key.is_none() {
                let mut fresh = [0u8; 32];
                if SystemRandom::new().fill(&mut fresh).is_err() {
                    return;
                }
                state.key = Some(fresh);
                let copy = state.clone();
                drop(hub);
                self.ble_save(&peer, &copy);
                fresh
            } else {
                state.key.unwrap()
            }
        };
        let _ = self.send_to(&peer, Msg::BleKey { key }).await;
    }

    pub(crate) fn ble_on_key(&self, peer: DeviceId, key: [u8; 32]) {
        // A key from the higher id is not the one to use; both sides agree on who makes it.
        if peer >= self.my_id {
            return;
        }
        self.ble_load(&peer);
        let copy = {
            let mut hub = self.ble.lock().unwrap();
            let state = hub.state.entry(peer).or_default();
            if state.key == Some(key) {
                return;
            }
            state.key = Some(key);
            // A new key starts the counters over: the old ones belonged to the old key.
            state.rx_seq = 0;
            state.clone()
        };
        self.ble_save(&peer, &copy);
    }

    fn ble_next_seq(&self, peer: &DeviceId) -> Option<([u8; 32], u64)> {
        self.ble_load(peer);
        let (key, seq, copy) = {
            let mut hub = self.ble.lock().unwrap();
            let state = hub.state.get_mut(peer)?;
            let key = state.key?;
            // The clock keeps it climbing across restarts; the +1 keeps it climbing within a millisecond.
            state.tx_seq = (state.tx_seq + 1).max(now_ms());
            (key, state.tx_seq, state.clone())
        };
        self.ble_save(peer, &copy);
        Some((key, seq))
    }

    pub(crate) fn ble_seal(&self, peer: &DeviceId, msg: Msg) -> Result<Vec<u8>> {
        let (key, seq) = self.ble_next_seq(peer).ok_or_else(|| Error::invalid("no Bluetooth key for that device yet"))?;
        seal(&key, &self.my_id, peer, seq, msg)
    }

    /// Whether a Bluetooth link to this device is up right now, as the app reported it.
    pub(crate) fn ble_linked(&self, peer: &DeviceId) -> bool {
        self.ble.lock().unwrap().links.contains(peer)
    }

    pub(crate) fn ble_linked_ids(&self) -> Vec<DeviceId> {
        self.ble.lock().unwrap().links.iter().copied().collect()
    }

    /// Queues a message for a device that is only reachable over Bluetooth.
    pub(crate) fn ble_queue(&self, peer: &DeviceId, msg: Msg) -> bool {
        if !allowed(&msg) || !self.ble_linked(peer) {
            return false;
        }
        let Ok(frame) = self.ble_seal(peer, msg) else { return false };
        let mut hub = self.ble.lock().unwrap();
        let queue = hub.outbox.entry(*peer).or_default();
        queue.push_back(frame);
        while queue.len() > OUTBOX_LIMIT {
            queue.pop_front();
        }
        true
    }

    /// Everything waiting for this device, cut into writes of `chunk` bytes, oldest first.
    pub(crate) fn ble_take(&self, peer: &DeviceId, chunk: usize) -> Vec<Vec<u8>> {
        let frames: Vec<Vec<u8>> = {
            let mut hub = self.ble.lock().unwrap();
            hub.outbox.get_mut(peer).map(|q| q.drain(..).collect()).unwrap_or_default()
        };
        frames
            .iter()
            .flat_map(|frame| {
                let id = self.ble_msg_id.fetch_add(1, Ordering::Relaxed);
                fragment(id, frame, chunk).unwrap_or_default()
            })
            .collect()
    }

    /// The first frame on a new link: proves who this side is, and lets the other side
    /// mark the link as this device's.
    pub(crate) fn ble_hello(&self, peer: &DeviceId, chunk: usize) -> Vec<Vec<u8>> {
        let Ok(frame) = self.ble_seal(peer, Msg::Ping { nonce: 0 }) else { return Vec::new() };
        let id = self.ble_msg_id.fetch_add(1, Ordering::Relaxed);
        fragment(id, &frame, chunk).unwrap_or_default()
    }

    /// One write from the link. When it completes a frame that opens, the message is handled
    /// as if it had come over the network, and the sender is returned.
    pub(crate) async fn ble_receive(self: &Arc<Self>, link: &str, chunk: &[u8]) -> Option<DeviceId> {
        let frame = {
            let mut hub = self.ble.lock().unwrap();
            hub.rx.entry(link.to_string()).or_default().push(chunk)?
        };
        let from = sender_of(&frame)?;
        if !self.circle_has(&from) {
            return None;
        }
        self.ble_load(&from);
        let key = self.ble.lock().unwrap().state.get(&from).and_then(|s| s.key)?;
        let (seq, msg) = match open(&key, &self.my_id, &frame) {
            Ok(opened) => opened,
            Err(e) => {
                tracing::debug!(peer = %from, error = %e, "dropped a Bluetooth frame");
                return None;
            }
        };
        let new_link;
        let copy = {
            let mut hub = self.ble.lock().unwrap();
            let state = hub.state.get_mut(&from)?;
            if seq <= state.rx_seq {
                // Seen before, or older than something seen: a replay.
                return None;
            }
            state.rx_seq = seq;
            let copy = state.clone();
            new_link = hub.links.insert(from);
            copy
        };
        self.ble_save(&from, &copy);
        if new_link {
            self.emit(crate::events::Event::DevicesChanged);
        }
        if !matches!(msg, Msg::Ping { .. }) {
            self.handle_msg(from, msg).await;
        }
        Some(from)
    }

    pub(crate) fn ble_link_down(&self, peer: &DeviceId) {
        let was = {
            let mut hub = self.ble.lock().unwrap();
            hub.outbox.remove(peer);
            hub.links.remove(peer)
        };
        if was {
            self.emit(crate::events::Event::DevicesChanged);
        }
    }

    pub(crate) fn ble_drop_link(&self, link: &str) {
        self.ble.lock().unwrap().rx.remove(link);
    }

    fn circle_has(&self, id: &DeviceId) -> bool {
        self.peers.lock().unwrap().contains_key(id)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::proto::ClipboardMsg;

    fn id(n: u8) -> DeviceId {
        DeviceId::from_bytes([n; ID_LEN])
    }

    fn clip(text: &str) -> Msg {
        Msg::Clipboard(ClipboardMsg { id: 1, ts: 2, text: text.into(), is_url: false })
    }

    #[test]
    fn a_frame_opens_only_for_the_right_pair() {
        let key = [7u8; 32];
        let frame = seal(&key, &id(1), &id(2), 10, clip("hello")).unwrap();
        let (seq, msg) = open(&key, &id(2), &frame).unwrap();
        assert_eq!(seq, 10);
        assert!(matches!(msg, Msg::Clipboard(c) if c.text == "hello"));
        // Another key, or another receiver, or a flipped bit, does not open it.
        assert!(open(&[8u8; 32], &id(2), &frame).is_err());
        assert!(open(&key, &id(3), &frame).is_err());
        let mut bent = frame.clone();
        *bent.last_mut().unwrap() ^= 1;
        assert!(open(&key, &id(2), &bent).is_err());
    }

    #[test]
    fn a_frame_cannot_be_turned_round() {
        let key = [7u8; 32];
        let frame = seal(&key, &id(1), &id(2), 10, clip("hello")).unwrap();
        // Claiming the frame came from the other device breaks the key it was sealed with.
        let mut forged = frame.clone();
        forged[1..1 + ID_LEN].copy_from_slice(id(2).as_bytes());
        assert!(open(&key, &id(1), &forged).is_err());
    }

    #[test]
    fn only_a_few_messages_cross_the_air() {
        let key = [7u8; 32];
        assert!(seal(&key, &id(1), &id(2), 1, Msg::Ring { on: true }).is_err());
        assert!(seal(&key, &id(1), &id(2), 1, Msg::Dial { number: "1".into() }).is_err());
        assert!(seal(&key, &id(1), &id(2), 1, Msg::Ping { nonce: 1 }).is_ok());
    }

    #[test]
    fn pieces_go_back_together_in_any_order() {
        let frame: Vec<u8> = (0..1000u32).map(|n| (n % 251) as u8).collect();
        let mut pieces = fragment(4, &frame, 100).unwrap();
        assert!(pieces.len() > 5);
        pieces.reverse();
        let mut rebuilt = Reassembler::default();
        let mut done = None;
        for piece in &pieces {
            done = rebuilt.push(piece).or(done);
        }
        assert_eq!(done.unwrap(), frame);
    }

    #[test]
    fn a_new_message_drops_a_half_finished_one() {
        let first = fragment(1, &vec![1u8; 300], 100).unwrap();
        let second = fragment(2, &vec![2u8; 150], 100).unwrap();
        let mut rebuilt = Reassembler::default();
        assert!(rebuilt.push(&first[0]).is_none());
        let mut done = None;
        for piece in &second {
            done = rebuilt.push(piece).or(done);
        }
        assert_eq!(done.unwrap(), vec![2u8; 150]);
    }

    #[test]
    fn nonsense_pieces_are_ignored() {
        let mut rebuilt = Reassembler::default();
        assert!(rebuilt.push(&[]).is_none());
        assert!(rebuilt.push(&[1, 5, 2, 9]).is_none());
        assert!(rebuilt.push(&[1, 0, 0, 9]).is_none());
        assert!(fragment(1, &[0u8; 10], 5).is_none());
        assert!(fragment(1, &vec![0u8; 300 * 100], 100).is_none());
    }
}
