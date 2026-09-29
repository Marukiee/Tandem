//! One live connection to one device: the control stream, the datagrams, and the
//! file streams the other side opens.

use std::net::SocketAddr;
use std::sync::Arc;
use std::sync::atomic::Ordering;
use std::time::{Duration, Instant};

use quinn::{RecvStream, SendStream};
use tokio::sync::{Notify, mpsc};
use tracing::{debug, info, warn};

use crate::circle::StatementId;
use crate::engine::Inner;
use crate::error::{Error, Result};
use crate::events::Event;
use crate::ids::DeviceId;
use crate::proto::{
    self, Hello, InputMsg, MAX_CONTROL_FRAME, MAX_SMALL_FRAME, Msg, STREAM_CONTROL, STREAM_FILE, decode_msg,
};

pub(crate) struct Session {
    pub peer: DeviceId,
    pub conn: quinn::Connection,
    pub tx: mpsc::Sender<Msg>,
    /// Whether this device opened the connection. Decides which of two simultaneous
    /// connections survives.
    pub dialer: bool,
    pub remote: SocketAddr,
    pub serial: u64,
    pub pong: Notify,
}

const DATAGRAM_POINTER: u8 = 1;
const DATAGRAM_SCROLL: u8 = 2;

/// Encodes a pointer movement as a datagram: kind, then dx and dy as big-endian i16.
pub fn pointer_datagram(dx: i16, dy: i16) -> bytes::Bytes {
    datagram(DATAGRAM_POINTER, dx, dy)
}

pub fn scroll_datagram(dx: i16, dy: i16) -> bytes::Bytes {
    datagram(DATAGRAM_SCROLL, dx, dy)
}

fn datagram(kind: u8, dx: i16, dy: i16) -> bytes::Bytes {
    let mut out = Vec::with_capacity(5);
    out.push(kind);
    out.extend_from_slice(&dx.to_be_bytes());
    out.extend_from_slice(&dy.to_be_bytes());
    bytes::Bytes::from(out)
}

fn parse_datagram(data: &[u8]) -> Option<InputMsg> {
    if data.len() < 5 {
        return None;
    }
    let dx = i16::from_be_bytes([data[1], data[2]]);
    let dy = i16::from_be_bytes([data[3], data[4]]);
    match data[0] {
        DATAGRAM_POINTER => Some(InputMsg::Pointer { dx, dy }),
        DATAGRAM_SCROLL => Some(InputMsg::Scroll { dx, dy }),
        _ => None,
    }
}

impl Inner {
    pub(crate) async fn establish_outgoing(
        self: Arc<Self>,
        id: DeviceId,
        conn: quinn::Connection,
        remote: SocketAddr,
    ) -> Result<()> {
        let (mut send, mut recv) = conn.open_bi().await.map_err(Error::connection)?;
        send.write_all(&[STREAM_CONTROL]).await.map_err(Error::connection)?;
        proto::send(&mut send, &self.my_hello()).await?;
        let hello: Hello = proto::recv(&mut recv, MAX_SMALL_FRAME).await?;
        self.register(id, conn, send, recv, hello, true, remote).await
    }

    pub(crate) async fn establish_incoming(
        self: Arc<Self>,
        id: DeviceId,
        conn: quinn::Connection,
        remote: SocketAddr,
    ) -> Result<()> {
        let (mut send, mut recv) = conn.accept_bi().await.map_err(Error::connection)?;
        let mut kind = [0u8; 1];
        tokio::io::AsyncReadExt::read_exact(&mut recv, &mut kind).await?;
        if kind[0] != STREAM_CONTROL {
            conn.close(1u32.into(), b"expected the control stream");
            return Err(Error::protocol("first stream was not the control stream"));
        }
        let hello: Hello = proto::recv(&mut recv, MAX_SMALL_FRAME).await?;
        proto::send(&mut send, &self.my_hello()).await?;
        self.register(id, conn, send, recv, hello, false, remote).await
    }

    #[allow(clippy::too_many_arguments)]
    async fn register(
        self: Arc<Self>,
        id: DeviceId,
        conn: quinn::Connection,
        send: SendStream,
        recv: RecvStream,
        hello: Hello,
        dialer: bool,
        remote: SocketAddr,
    ) -> Result<()> {
        if hello.proto == 0 {
            conn.close(1u32.into(), b"bad protocol version");
            return Err(Error::protocol("peer speaks protocol 0"));
        }
        let (tx, rx) = mpsc::channel::<Msg>(256);
        let session = Arc::new(Session {
            peer: id,
            conn: conn.clone(),
            tx,
            dialer,
            remote,
            serial: self.session_serial.fetch_add(1, Ordering::Relaxed),
            pong: Notify::new(),
        });

        {
            let mut peers = self.peers.lock().unwrap();
            let Some(peer) = peers.get_mut(&id) else {
                conn.close(1u32.into(), b"not in the circle");
                return Err(Error::NotTrusted);
            };
            if let Some(existing) = &peer.session {
                if existing.conn.close_reason().is_none() {
                    // Two connections at once: keep the one dialed by the lower id, so
                    // both ends make the same choice without talking about it.
                    let new_dialer = if dialer { self.my_id } else { id };
                    let old_dialer = if existing.dialer { self.my_id } else { id };
                    if new_dialer > old_dialer {
                        conn.close(2u32.into(), b"duplicate connection");
                        return Ok(());
                    }
                    existing.conn.close(2u32.into(), b"duplicate connection");
                }
            }
            peer.session = Some(session.clone());
            peer.hello = Some(hello.clone());
            peer.fail_count = 0;
        }

        for candidate in &hello.candidates {
            if let Ok(addr) = candidate.parse::<SocketAddr>() {
                self.learn_addr(&id, addr, false);
            }
        }

        info!(peer = %id, name = %hello.name, dialer, "connected");
        self.emit(Event::Connected { id });
        self.emit(Event::DevicesChanged);
        self.sessions_changed.notify_waiters();

        // Bring the other side up to date.
        let status = self.my_status.lock().unwrap().clone();
        let _ = session.tx.send(Msg::Status(status)).await;
        let known = self.introductions(&id);
        if !known.is_empty() {
            let _ = session.tx.send(Msg::Introduce { peers: known }).await;
        }
        // And tell everyone else where this device can be found.
        let mut mine: Vec<String> = hello.candidates.clone();
        mine.push(remote.to_string());
        let intro = Msg::Introduce { peers: vec![crate::proto::PeerAddrs { id, addrs: mine }] };
        for other in self.connected_ids() {
            if other != id {
                let _ = self.send_to(&other, intro.clone()).await;
            }
        }
        if hello.circle_digest != self.circle.read().unwrap().digest() {
            let statements = self.circle.read().unwrap().statements();
            let _ = session.tx.send(Msg::CircleSync { statements }).await;
        }

        let this = self.clone();
        tokio::spawn(async move { this.run_session(session, send, recv, rx).await });
        Ok(())
    }

    async fn run_session(
        self: Arc<Self>,
        session: Arc<Session>,
        mut send: SendStream,
        mut recv: RecvStream,
        mut rx: mpsc::Receiver<Msg>,
    ) {
        let id = session.peer;
        let conn = session.conn.clone();

        let reader = {
            let this = self.clone();
            async move {
                loop {
                    match proto::read_frame(&mut recv, MAX_CONTROL_FRAME).await {
                        Ok(Some(frame)) => match decode_msg(&frame) {
                            Ok(Msg::Unknown) => {}
                            Ok(Msg::Bye { .. }) => break,
                            Ok(msg) => this.handle_msg(id, msg).await,
                            Err(e) => {
                                warn!(peer = %id, "bad frame: {e}");
                                break;
                            }
                        },
                        Ok(None) => break,
                        Err(e) => {
                            debug!(peer = %id, "control stream ended: {e}");
                            break;
                        }
                    }
                }
            }
        };

        let writer = async move {
            while let Some(msg) = rx.recv().await {
                if let Err(e) = proto::send(&mut send, &msg).await {
                    debug!("could not write to the control stream: {e}");
                    break;
                }
            }
        };

        let files = {
            let this = self.clone();
            let conn = conn.clone();
            async move {
                while let Ok((send, recv)) = conn.accept_bi().await {
                    let this = this.clone();
                    tokio::spawn(async move {
                        if let Err(e) = this.serve_stream(id, send, recv).await {
                            debug!(peer = %id, "file stream failed: {e}");
                        }
                    });
                }
            }
        };

        let datagrams = {
            let this = self.clone();
            let conn = conn.clone();
            async move {
                while let Ok(data) = conn.read_datagram().await {
                    if let Some(input) = parse_datagram(&data) {
                        this.emit(Event::Input { from: id, input });
                    }
                }
            }
        };

        tokio::select! {
            _ = reader => {}
            _ = writer => {}
            _ = files => {}
            _ = datagrams => {}
            _ = conn.closed() => {}
            _ = self.cancel.cancelled() => {}
        }
        conn.close(0u32.into(), b"bye");
        self.session_ended(&session);
    }

    fn session_ended(&self, session: &Arc<Session>) {
        let id = session.peer;
        let mut was_current = false;
        {
            let mut peers = self.peers.lock().unwrap();
            if let Some(peer) = peers.get_mut(&id) {
                if peer.session.as_ref().map(|s| s.serial) == Some(session.serial) {
                    peer.session = None;
                    peer.next_dial = Instant::now() + Duration::from_millis(400);
                    was_current = true;
                }
            }
        }
        if was_current {
            info!(peer = %id, "disconnected");
            self.emit(Event::Disconnected { id });
            self.emit(Event::DevicesChanged);
            self.poke.notify_one();
        }
    }

    async fn serve_stream(self: Arc<Self>, id: DeviceId, send: SendStream, mut recv: RecvStream) -> Result<()> {
        let mut kind = [0u8; 1];
        tokio::io::AsyncReadExt::read_exact(&mut recv, &mut kind).await?;
        match kind[0] {
            STREAM_FILE => self.serve_file(id, send, recv).await,
            other => Err(Error::protocol(format!("unknown stream kind {other}"))),
        }
    }

    async fn handle_msg(self: &Arc<Self>, id: DeviceId, msg: Msg) {
        match msg {
            Msg::CircleSync { statements } => {
                let before: std::collections::HashSet<StatementId> =
                    self.circle.read().unwrap().statements().iter().map(|s| s.id()).collect();
                let changed = self.circle.write().unwrap().merge(statements.clone());
                if changed {
                    let new: Vec<_> = statements.into_iter().filter(|s| !before.contains(&s.id())).collect();
                    self.persist_circle();
                    // Pass it on first: a device that was just removed should still hear it.
                    self.gossip(new, Some(id)).await;
                    self.sync_peers();
                    self.emit(Event::CircleChanged);
                    self.emit(Event::DevicesChanged);
                    if !self.circle.read().unwrap().is_member(&self.identity.public_key()) {
                        warn!("this device was removed from the circle");
                        self.emit(Event::RemovedFromCircle);
                    }
                }
            }
            Msg::Status(status) => {
                if let Some(peer) = self.peers.lock().unwrap().get_mut(&id) {
                    peer.status.merge(&status);
                }
                self.emit(Event::DevicesChanged);
            }
            Msg::Clipboard(clip) => {
                if !self.settings.read().unwrap().for_device(&id).clipboard {
                    return;
                }
                *self.last_remote_clip.lock().unwrap() = Some(crate::engine::hash_text(&clip.text));
                self.emit(Event::Clipboard { from: id, text: clip.text, is_url: clip.is_url });
            }
            Msg::ShareOffer(offer) => self.clone().on_share_offer(id, offer),
            Msg::ShareReply(reply) => {
                debug!(peer = %id, offer = reply.id, accepted = reply.accepted, "share reply");
                if !reply.accepted {
                    if let Some(offer) = self.out_offers.lock().unwrap().get_mut(&reply.id) {
                        offer.targets.remove(&id);
                    }
                }
            }
            Msg::ShareText(text) => {
                self.emit(Event::ShareText { from: id, text: text.text, is_url: text.is_url, open: text.open });
            }
            Msg::Notification(notification) => self.emit(Event::Notification { from: id, notification }),
            Msg::NotificationRemoved { key } => self.emit(Event::NotificationRemoved { from: id, key }),
            Msg::NotificationAction(action) => self.emit(Event::NotificationAction { from: id, action }),
            Msg::AppIcon { app_id, png } => {
                self.emit(Event::AppIcon { from: id, app_id, png: png.into_vec() });
            }
            Msg::Call(call) => self.emit(Event::Call { from: id, call }),
            Msg::CallAction(action) => self.emit(Event::CallAction { from: id, action }),
            Msg::Dial { number } => self.emit(Event::Dial { from: id, number }),
            Msg::Ring { on } => self.emit(Event::Ring { from: id, on }),
            Msg::Input(input) => self.emit(Event::Input { from: id, input }),
            Msg::Hotspot(hotspot) => self.emit(Event::Hotspot { from: id, hotspot }),
            Msg::Candidates { addrs } => {
                for text in addrs {
                    if let Ok(addr) = text.parse::<SocketAddr>() {
                        self.learn_addr(&id, addr, false);
                    }
                }
            }
            Msg::Introduce { peers } => {
                for entry in peers {
                    if entry.id == self.my_id {
                        continue;
                    }
                    for text in entry.addrs {
                        if let Ok(addr) = text.parse::<SocketAddr>() {
                            self.learn_addr(&entry.id, addr, false);
                        }
                    }
                }
                self.poke.notify_one();
            }
            Msg::Ping { nonce } => {
                let _ = self.send_to(&id, Msg::Pong { nonce }).await;
            }
            Msg::Pong { .. } => {
                if let Some(session) = self.session_of(&id) {
                    session.pong.notify_waiters();
                }
            }
            Msg::Bye { .. } | Msg::Unknown => {}
        }
    }

    /// After a network change, checks that each connection still works and closes the
    /// ones that do not, so they are replaced within seconds instead of after the idle
    /// timeout.
    pub(crate) fn probe_sessions(self: &Arc<Self>) {
        let sessions: Vec<Arc<Session>> = self
            .peers
            .lock()
            .unwrap()
            .values()
            .filter_map(|p| p.session.clone())
            .collect();
        for session in sessions {
            tokio::spawn(async move {
                let waiter = session.pong.notified();
                tokio::pin!(waiter);
                waiter.as_mut().enable();
                if session.tx.send(Msg::Ping { nonce: crate::engine::rand_u64() }).await.is_err() {
                    return;
                }
                if tokio::time::timeout(Duration::from_secs(3), waiter).await.is_err() {
                    debug!(peer = %session.peer, "connection did not answer after a network change");
                    session.conn.close(4u32.into(), b"path lost");
                }
            });
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pointer_datagrams_round_trip() {
        let data = pointer_datagram(-12, 300);
        assert_eq!(parse_datagram(&data), Some(InputMsg::Pointer { dx: -12, dy: 300 }));
        let data = scroll_datagram(0, -4);
        assert_eq!(parse_datagram(&data), Some(InputMsg::Scroll { dx: 0, dy: -4 }));
        assert_eq!(parse_datagram(&[9, 0, 0, 0, 0]), None);
        assert_eq!(parse_datagram(&[1, 0]), None);
    }
}
