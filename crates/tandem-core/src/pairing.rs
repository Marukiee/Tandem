//! Adding a device to the circle.
//!
//! One device shows a QR code. It carries that device's key (so the scanner knows
//! exactly who it is talking to), its addresses, and a secret that lives nowhere
//! else. The scanner connects, proves it knows the secret with an HMAC bound to
//! this very TLS session, and receives the circle. Someone listening on the network
//! or sitting in the middle never sees the secret.

use std::net::SocketAddr;
use std::sync::Arc;
use std::time::{Duration, Instant};

use ring::hmac;
use ring::rand::{SecureRandom, SystemRandom};
use serde::{Deserialize, Serialize};
use tracing::{info, warn};

use crate::circle::Circle;
use crate::engine::Inner;
use crate::error::{Error, Result};
use crate::events::Event;
use crate::ids::{DeviceId, b64_decode, b64_encode, bytes_array, now_ms};
use crate::net;
use crate::proto::{
    self, MAX_CONTROL_FRAME, MAX_SMALL_FRAME, PairAccept, PairReject, PairReply, PairRequest,
};
use crate::tls;

const URI_PREFIX: &str = "tandem://pair/1?d=";
const OFFER_LIFETIME: Duration = Duration::from_secs(5 * 60);
const MAX_ATTEMPTS: u8 = 3;
const EXPORTER_LABEL: &[u8] = b"tandem-pair-v1";

#[derive(Clone, Debug)]
pub struct PairingOffer {
    /// What goes into the QR code.
    pub uri: String,
    pub expires_at: u64,
}

pub(crate) struct PendingPairing {
    secret: [u8; 32],
    expires: Instant,
    attempts: u8,
}

#[derive(Serialize, Deserialize)]
struct Payload {
    v: u8,
    #[serde(with = "bytes_array")]
    key: [u8; 32],
    #[serde(with = "bytes_array")]
    secret: [u8; 32],
    addrs: Vec<String>,
    name: String,
}

fn proof(secret: &[u8; 32], exporter: &[u8; 32], joiner_key: &[u8; 32]) -> hmac::Tag {
    let key = hmac::Key::new(hmac::HMAC_SHA256, secret);
    let mut message = Vec::with_capacity(EXPORTER_LABEL.len() + 64);
    message.extend_from_slice(EXPORTER_LABEL);
    message.extend_from_slice(exporter);
    message.extend_from_slice(joiner_key);
    hmac::sign(&key, &message)
}

fn exporter_of(connection: &quinn::Connection) -> Result<[u8; 32]> {
    let mut out = [0u8; 32];
    connection
        .export_keying_material(&mut out, EXPORTER_LABEL, b"")
        .map_err(|_| Error::crypto("could not bind the pairing to the connection"))?;
    Ok(out)
}

impl Inner {
    /// Starts a pairing window and returns the QR payload. A second call replaces the
    /// first, so only the most recent QR code works.
    pub(crate) fn create_pairing_offer(&self) -> Result<PairingOffer> {
        let mut secret = [0u8; 32];
        SystemRandom::new().fill(&mut secret).map_err(|_| Error::crypto("no randomness"))?;
        let payload = Payload {
            v: 1,
            key: self.identity.public_key(),
            secret,
            addrs: net::local_candidates(self.port, self.cfg.loopback),
            name: self.name(),
        };
        let uri = format!("{URI_PREFIX}{}", b64_encode(&proto::encode(&payload)?));
        *self.pairing.lock().unwrap() =
            Some(PendingPairing { secret, expires: Instant::now() + OFFER_LIFETIME, attempts: 0 });
        Ok(PairingOffer { uri, expires_at: now_ms() + OFFER_LIFETIME.as_millis() as u64 })
    }

    pub(crate) fn cancel_pairing_offer(&self) {
        *self.pairing.lock().unwrap() = None;
    }

    /// The scanning side: connect to the device that showed the QR code and join its
    /// circle.
    pub(crate) async fn pair_with_uri(self: &Arc<Self>, uri: &str) -> Result<DeviceId> {
        let encoded = uri
            .trim()
            .strip_prefix(URI_PREFIX)
            .ok_or_else(|| Error::Pairing("that is not a Tandem pairing code".into()))?;
        let payload: Payload =
            proto::decode(&b64_decode(encoded)?).map_err(|_| Error::Pairing("the pairing code is damaged".into()))?;
        if payload.v != 1 {
            return Err(Error::Pairing("this pairing code is from a newer version".into()));
        }
        let their_id = DeviceId::from_public_key(&payload.key);
        if payload.key == self.identity.public_key() {
            return Err(Error::Pairing("that code is from this device".into()));
        }
        if self.circle.read().unwrap().is_member(&payload.key) {
            return Ok(their_id);
        }
        if !self.circle.read().unwrap().is_alone(&self.my_id) {
            return Err(Error::Pairing(
                "this device is already in a circle. Remove it from there first".into(),
            ));
        }

        let addrs: Vec<SocketAddr> = payload.addrs.iter().filter_map(|a| a.parse().ok()).collect();
        let (connection, addr) = self.dial_any(Some(payload.key), tls::ALPN_PAIR, &addrs).await?;
        let result = self.run_join(&connection, &payload, their_id).await;
        connection.close(0u32.into(), b"done");
        result?;

        for text in &payload.addrs {
            if let Ok(a) = text.parse::<SocketAddr>() {
                self.learn_addr(&their_id, a, false);
            }
        }
        self.learn_addr(&their_id, addr, true);
        self.emit(Event::Paired { id: their_id });
        self.emit(Event::CircleChanged);
        self.emit(Event::DevicesChanged);
        self.network_changed();
        Ok(their_id)
    }

    async fn run_join(&self, connection: &quinn::Connection, payload: &Payload, their_id: DeviceId) -> Result<()> {
        let (mut send, mut recv) = connection.open_bi().await.map_err(Error::connection)?;
        let exporter = exporter_of(connection)?;
        let tag = proof(&payload.secret, &exporter, &self.identity.public_key());
        let mut proof_bytes = [0u8; 32];
        proof_bytes.copy_from_slice(tag.as_ref());
        proto::send(
            &mut send,
            &PairRequest { name: self.name(), platform: self.cfg.platform, proof: proof_bytes },
        )
        .await?;

        let reply: PairReply = tokio::time::timeout(Duration::from_secs(15), proto::recv(&mut recv, MAX_CONTROL_FRAME))
            .await
            .map_err(|_| Error::Pairing("the other device did not answer".into()))??;
        let accept = match reply {
            PairReply::Accept(accept) => accept,
            PairReply::Reject(reject) => return Err(Error::Pairing(reject.reason)),
        };

        let joined = Circle::from_statements(accept.statements);
        if !joined.is_member(&self.identity.public_key()) || !joined.is_member(&payload.key) {
            return Err(Error::Pairing("the circle we received does not include both devices".into()));
        }
        *self.circle.write().unwrap() = joined;
        self.persist_circle();
        self.sync_peers();
        info!(peer = %their_id, "joined the circle of {}", accept.name);
        Ok(())
    }

    /// The showing side: someone connected on the pairing protocol.
    pub(crate) async fn handle_pairing(self: Arc<Self>, connection: quinn::Connection, key: [u8; 32]) -> Result<()> {
        let (mut send, mut recv) = connection.accept_bi().await.map_err(Error::connection)?;
        let request: PairRequest =
            tokio::time::timeout(Duration::from_secs(10), proto::recv(&mut recv, MAX_SMALL_FRAME))
                .await
                .map_err(|_| Error::Pairing("no request arrived".into()))??;
        let exporter = exporter_of(&connection)?;

        let verdict: std::result::Result<(), &'static str> = {
            let mut guard = self.pairing.lock().unwrap();
            match guard.as_mut() {
                None => Err("this device is not showing a pairing code"),
                Some(pending) if Instant::now() > pending.expires => {
                    *guard = None;
                    Err("the pairing code has expired")
                }
                Some(pending) => {
                    pending.attempts += 1;
                    let key_hmac = hmac::Key::new(hmac::HMAC_SHA256, &pending.secret);
                    let mut message = Vec::new();
                    message.extend_from_slice(EXPORTER_LABEL);
                    message.extend_from_slice(&exporter);
                    message.extend_from_slice(&key);
                    if hmac::verify(&key_hmac, &message, &request.proof).is_ok() {
                        *guard = None;
                        Ok(())
                    } else {
                        if pending.attempts >= MAX_ATTEMPTS {
                            *guard = None;
                        }
                        Err("the pairing code did not match")
                    }
                }
            }
        };

        if let Err(reason) = verdict {
            warn!("pairing refused: {reason}");
            let _ = proto::send(&mut send, &PairReply::Reject(PairReject { reason: reason.to_string() })).await;
            let _ = send.finish();
            let _ = tokio::time::timeout(Duration::from_secs(2), send.stopped()).await;
            connection.close(1u32.into(), b"refused");
            return Err(Error::Pairing(reason.to_string()));
        }

        let joiner_id = DeviceId::from_public_key(&key);
        let (statement, statements) = {
            let mut circle = self.circle.write().unwrap();
            let statement = if circle.is_member(&key) {
                None
            } else {
                Some(circle.vouch_for(&self.identity, key, &request.name, request.platform)?)
            };
            (statement, circle.statements())
        };
        self.persist_circle();
        self.sync_peers();
        self.learn_addr(&joiner_id, connection.remote_address(), false);

        proto::send(
            &mut send,
            &PairReply::Accept(PairAccept { name: self.name(), platform: self.cfg.platform, statements }),
        )
        .await?;
        let _ = send.finish();
        let _ = tokio::time::timeout(Duration::from_secs(3), send.stopped()).await;
        connection.close(0u32.into(), b"paired");

        if let Some(statement) = statement {
            self.gossip(vec![statement], None).await;
        }
        info!(peer = %joiner_id, "paired with {}", request.name);
        self.emit(Event::Paired { id: joiner_id });
        self.emit(Event::CircleChanged);
        self.emit(Event::DevicesChanged);
        self.poke.notify_one();
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn proof_depends_on_every_input() {
        let secret = [1u8; 32];
        let exporter = [2u8; 32];
        let key = [3u8; 32];
        let base = proof(&secret, &exporter, &key);
        assert_ne!(base.as_ref(), proof(&[9u8; 32], &exporter, &key).as_ref());
        assert_ne!(base.as_ref(), proof(&secret, &[9u8; 32], &key).as_ref());
        assert_ne!(base.as_ref(), proof(&secret, &exporter, &[9u8; 32]).as_ref());
        assert_eq!(base.as_ref(), proof(&secret, &exporter, &key).as_ref());
    }
}
