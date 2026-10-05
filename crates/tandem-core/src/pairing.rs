//! Adding a device to the circle.
//!
//! One device shows a QR code. It carries that device's key (so the scanner knows
//! exactly who it is talking to), its addresses, and a secret that lives nowhere
//! else. The scanner connects, proves it knows the secret with an HMAC bound to
//! this very TLS session, and receives the circle. Someone listening on the network
//! or sitting in the middle never sees the secret.

use std::collections::HashSet;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::{Duration, Instant};

use ring::hmac;
use ring::rand::{SecureRandom, SystemRandom};
use serde::{Deserialize, Serialize};
use spake2::{Ed25519Group, Identity, Password, Spake2};
use tracing::{info, warn};

use crate::circle::Circle;
use crate::discovery;
use crate::engine::Inner;
use crate::error::{Error, Result};
use crate::events::Event;
use crate::ids::{DeviceId, Platform, b64_decode, b64_encode, bytes_array, now_ms};
use crate::net;
use crate::proto::{
    self, CodeFinish, CodeHello, CodeReply, CodeServerHello, MAX_CONTROL_FRAME, MAX_SMALL_FRAME, PairAccept, PairReject,
    PairReply, PairRequest,
};
use crate::tls;

const URI_PREFIX: &str = "tandem://pair/1?d=";
const OFFER_LIFETIME: Duration = Duration::from_secs(5 * 60);
/// Wrong guesses allowed per code. The code is short, so this is what keeps a stranger from trying them all.
const MAX_ATTEMPTS: u8 = 5;
const EXPORTER_LABEL: &[u8] = b"tandem-pair-v1";
const CODE_EXPORTER_LABEL: &[u8] = b"tandem-pair-code-v1";
const CODE_DIGITS: usize = 8;
const SCANNER_ID: &[u8] = b"tandem-pair-scanner";
const SHOWER_ID: &[u8] = b"tandem-pair-shower";
/// How long the device that is given a code looks for the one that shows it.
const CODE_SEARCH: Duration = Duration::from_secs(12);

#[derive(Clone, Debug)]
pub struct PairingOffer {
    /// What goes into the QR code.
    pub uri: String,
    /// The same offer as a short code that can be typed on the other device, like "1234 5678".
    pub code: String,
    pub expires_at: u64,
}

pub(crate) struct PendingPairing {
    secret: [u8; 32],
    code: String,
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
    #[serde(default)]
    platform: Option<Platform>,
    /// This device can take the circle of a device that scans the code. Older versions leave it out.
    #[serde(default)]
    adopts: bool,
}

/// What a person typed as a code, as eight digits. Spaces and dashes do not matter.
pub fn normalize_code(input: &str) -> Option<String> {
    let digits: String = input.chars().filter(|c| !c.is_whitespace() && *c != '-').collect();
    (digits.len() == CODE_DIGITS && digits.chars().all(|c| c.is_ascii_digit())).then_some(digits)
}

/// A code or a link: the code is all digits, the link starts with the scheme.
pub fn is_code(input: &str) -> bool {
    !input.trim().starts_with("tandem:")
}

fn grouped(code: &str) -> String {
    format!("{} {}", &code[..4], &code[4..])
}

fn new_code() -> Result<String> {
    let mut bytes = [0u8; 8];
    SystemRandom::new().fill(&mut bytes).map_err(|_| Error::crypto("no randomness"))?;
    let number = u64::from_be_bytes(bytes) % 10u64.pow(CODE_DIGITS as u32);
    Ok(format!("{number:0width$}", width = CODE_DIGITS))
}

/// What one side of a code pairing shows the other to prove it ended up with the same key. It also covers the channel and
/// both keys, so a device in the middle cannot pass it on to another connection.
struct Confirmation<'a> {
    role: &'a [u8],
    exporter: &'a [u8; 32],
    scanner_key: &'a [u8; 32],
    shower_key: &'a [u8; 32],
    shower_name: &'a str,
    shower_alone: bool,
}

impl Confirmation<'_> {
    fn message(&self) -> Vec<u8> {
        let mut message = Vec::new();
        message.extend_from_slice(CODE_EXPORTER_LABEL);
        message.extend_from_slice(self.role);
        message.extend_from_slice(self.exporter);
        message.extend_from_slice(self.scanner_key);
        message.extend_from_slice(self.shower_key);
        message.extend_from_slice(&(self.shower_name.len() as u32).to_be_bytes());
        message.extend_from_slice(self.shower_name.as_bytes());
        message.push(self.shower_alone as u8);
        message
    }

    fn tag(&self, key: &[u8]) -> [u8; 32] {
        let mut out = [0u8; 32];
        out.copy_from_slice(hmac::sign(&hmac::Key::new(hmac::HMAC_SHA256, key), &self.message()).as_ref());
        out
    }

    fn holds(&self, key: &[u8], shown: &[u8; 32]) -> bool {
        hmac::verify(&hmac::Key::new(hmac::HMAC_SHA256, key), &self.message(), shown).is_ok()
    }
}

/// Why trying a code on one device did not work.
pub(crate) enum CodeFailure {
    /// The device did not show a matching code, so it was another device or a typo.
    NoMatch,
    /// This device was no use (gone, stale, not answering), another one may be the right one.
    Skip(Error),
    /// It is the right device and it did not work out.
    Fatal(Error),
}

impl From<Error> for CodeFailure {
    fn from(e: Error) -> Self {
        CodeFailure::Skip(e)
    }
}

fn proof(secret: &[u8; 32], exporter: &[u8; 32], joiner_key: &[u8; 32]) -> hmac::Tag {
    let key = hmac::Key::new(hmac::HMAC_SHA256, secret);
    let mut message = Vec::with_capacity(EXPORTER_LABEL.len() + 64);
    message.extend_from_slice(EXPORTER_LABEL);
    message.extend_from_slice(exporter);
    message.extend_from_slice(joiner_key);
    hmac::sign(&key, &message)
}

fn exporter_of(connection: &quinn::Connection, label: &[u8]) -> Result<[u8; 32]> {
    let mut out = [0u8; 32];
    connection
        .export_keying_material(&mut out, label, b"")
        .map_err(|_| Error::crypto("could not bind the pairing to the connection"))?;
    Ok(out)
}

/// A device on the network that is showing a pairing code.
pub(crate) struct OpenPairing {
    pub addrs: Vec<SocketAddr>,
    pub seen: Instant,
}

impl Inner {
    /// Notes that a device started or stopped showing a pairing code.
    pub(crate) fn note_pairing_sighting(&self, hint: discovery::Hint, addrs: &[SocketAddr], showing: bool) {
        let hour = discovery::current_hour();
        if discovery::hints_around(&self.my_id, hour).contains(&hint) {
            return;
        }
        let mut open = self.open_pairings.lock().unwrap();
        if showing {
            open.insert(hint, OpenPairing { addrs: addrs.to_vec(), seen: Instant::now() });
        } else {
            open.remove(&hint);
        }
    }

    /// The devices that are showing a code and were not tried yet, the newest first.
    fn open_pairings(&self, tried: &HashSet<discovery::Hint>) -> Vec<(discovery::Hint, Vec<SocketAddr>)> {
        let mut open = self.open_pairings.lock().unwrap();
        open.retain(|_, entry| entry.seen.elapsed() < OFFER_LIFETIME + Duration::from_secs(60));
        let mut found: Vec<_> = open
            .iter()
            .filter(|(hint, _)| !tried.contains(*hint))
            .map(|(hint, entry)| (*hint, entry.addrs.clone(), entry.seen))
            .collect();
        found.sort_by_key(|(_, _, seen)| std::cmp::Reverse(*seen));
        found.into_iter().map(|(hint, addrs, _)| (hint, addrs)).collect()
    }

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
            platform: Some(self.cfg.platform),
            adopts: true,
        };
        let uri = format!("{URI_PREFIX}{}", b64_encode(&proto::encode(&payload)?));
        let code = new_code()?;
        *self.pairing.lock().unwrap() =
            Some(PendingPairing { secret, code: code.clone(), expires: Instant::now() + OFFER_LIFETIME, attempts: 0 });
        self.sync_pairing_announcement();
        Ok(PairingOffer { uri, code: grouped(&code), expires_at: now_ms() + OFFER_LIFETIME.as_millis() as u64 })
    }

    pub(crate) fn cancel_pairing_offer(&self) {
        *self.pairing.lock().unwrap() = None;
        self.sync_pairing_announcement();
    }

    /// Lets the network know whether this device is showing a code right now, so the short code can find it.
    pub(crate) fn sync_pairing_announcement(&self) {
        let open = {
            let mut guard = self.pairing.lock().unwrap();
            if guard.as_ref().is_some_and(|pending| Instant::now() > pending.expires) {
                *guard = None;
            }
            guard.is_some()
        };
        if let Some(discovery) = self.discovery.lock().unwrap().as_mut() {
            let _ = discovery.set_pairing(open);
        }
    }

    /// The scanning side: connect to the device that showed the QR code (or find the one that shows a typed code) and
    /// become one circle with it.
    pub(crate) async fn pair_with_uri(self: &Arc<Self>, uri: &str) -> Result<DeviceId> {
        if is_code(uri) {
            return self.pair_with_code(uri).await;
        }
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

        // A device that is in a circle cannot join another one, but it can take a new device into its own.
        let invite = if self.circle.read().unwrap().is_alone(&self.my_id) {
            None
        } else if !payload.adopts {
            return Err(Error::Pairing(
                "the other device has to be updated first before this device, which is in a circle, can scan its code".into(),
            ));
        } else {
            Some(self.vouch_in_copy(payload.key, &payload.name, payload.platform.unwrap_or(Platform::Other))?)
        };

        let addrs: Vec<SocketAddr> = payload.addrs.iter().filter_map(|a| a.parse().ok()).collect();
        let (connection, addr) = self.dial_any(Some(payload.key), tls::ALPN_PAIR, &addrs).await?;
        let result = self.run_join(&connection, &payload, invite.as_ref()).await;
        connection.close(0u32.into(), b"done");
        result?;

        self.finish_joining(their_id, &addrs, addr);
        Ok(their_id)
    }

    /// A copy of the circle in which this device vouched for a new one, and the statement that did it. Nothing changes in
    /// the real circle until the other device has taken the invite.
    fn vouch_in_copy(&self, key: [u8; 32], name: &str, platform: Platform) -> Result<(Circle, crate::circle::Statement)> {
        let mut copy = self.circle.read().unwrap().clone();
        let statement = copy.vouch_for(&self.identity, key, name, platform)?;
        Ok((copy, statement))
    }

    fn finish_joining(self: &Arc<Self>, their_id: DeviceId, advertised: &[SocketAddr], connected: SocketAddr) {
        for addr in advertised {
            self.learn_addr(&their_id, *addr, false);
        }
        self.learn_addr(&their_id, connected, true);
        self.emit(Event::Paired { id: their_id });
        self.emit(Event::CircleChanged);
        self.emit(Event::DevicesChanged);
        self.network_changed();
    }

    async fn run_join(
        self: &Arc<Self>,
        connection: &quinn::Connection,
        payload: &Payload,
        invite: Option<&(Circle, crate::circle::Statement)>,
    ) -> Result<()> {
        let (mut send, mut recv) = connection.open_bi().await.map_err(Error::connection)?;
        let exporter = exporter_of(connection, EXPORTER_LABEL)?;
        let tag = proof(&payload.secret, &exporter, &self.identity.public_key());
        let mut proof_bytes = [0u8; 32];
        proof_bytes.copy_from_slice(tag.as_ref());
        proto::send(
            &mut send,
            &PairRequest {
                name: self.name(),
                platform: self.cfg.platform,
                proof: proof_bytes,
                invite: invite.map(|(circle, _)| circle.statements()),
            },
        )
        .await?;

        let reply: PairReply = tokio::time::timeout(Duration::from_secs(15), proto::recv(&mut recv, MAX_CONTROL_FRAME))
            .await
            .map_err(|_| Error::Pairing("the other device did not answer".into()))??;
        let accept = match reply {
            PairReply::Accept(accept) => accept,
            PairReply::Reject(reject) => return Err(Error::Pairing(reject.reason)),
        };
        self.take_accept(accept, payload.key, invite.map(|(_, statement)| statement.clone())).await
    }

    /// What follows the answer of the device that showed the code: join its circle, or, when it took our invite, add it
    /// to ours.
    async fn take_accept(
        self: &Arc<Self>,
        accept: PairAccept,
        their_key: [u8; 32],
        invited: Option<crate::circle::Statement>,
    ) -> Result<()> {
        if let Some(statement) = invited {
            if !accept.adopted {
                return Err(Error::Pairing("the other device did not take the invite, it probably needs an update".into()));
            }
            self.circle.write().unwrap().merge([statement.clone()]);
            self.persist_circle();
            self.sync_peers();
            info!("took {} into the circle", accept.name);
            self.gossip(vec![statement], None).await;
            return Ok(());
        }
        let joined = Circle::from_statements(accept.statements);
        if !joined.is_member(&self.identity.public_key()) || !joined.is_member(&their_key) {
            return Err(Error::Pairing("the circle we received does not include both devices".into()));
        }
        *self.circle.write().unwrap() = joined;
        self.persist_circle();
        self.sync_peers();
        info!("joined the circle of {}", accept.name);
        Ok(())
    }

    /// The showing side: someone connected on the pairing protocol.
    pub(crate) async fn handle_pairing(self: Arc<Self>, connection: quinn::Connection, key: [u8; 32]) -> Result<()> {
        let (mut send, mut recv) = connection.accept_bi().await.map_err(Error::connection)?;
        let request: PairRequest =
            tokio::time::timeout(Duration::from_secs(10), proto::recv(&mut recv, MAX_CONTROL_FRAME))
                .await
                .map_err(|_| Error::Pairing("no request arrived".into()))??;
        let exporter = exporter_of(&connection, EXPORTER_LABEL)?;

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
        self.sync_pairing_announcement();

        if let Err(reason) = verdict {
            return refuse(&connection, &mut send, reason).await;
        }
        match request.invite {
            Some(statements) => self.adopt_invite(connection, send, key, statements).await,
            None => self.admit(connection, send, key, request.name, request.platform).await,
        }
    }

    /// The device that scanned the code has a circle and takes this device into it. Only a device that is alone can do
    /// that, or it would drop the circle it has.
    async fn adopt_invite(
        self: Arc<Self>,
        connection: quinn::Connection,
        mut send: quinn::SendStream,
        key: [u8; 32],
        statements: Vec<crate::circle::Statement>,
    ) -> Result<()> {
        let joined = Circle::from_statements(statements);
        if !self.circle.read().unwrap().is_alone(&self.my_id) {
            return refuse(&connection, &mut send, "this device is already in a circle").await;
        }
        if !joined.is_member(&self.identity.public_key()) || !joined.is_member(&key) {
            return refuse(&connection, &mut send, "the circle does not include both devices").await;
        }
        *self.circle.write().unwrap() = joined;
        self.persist_circle();
        self.sync_peers();
        let inviter = DeviceId::from_public_key(&key);
        self.learn_addr(&inviter, connection.remote_address(), false);

        proto::send(
            &mut send,
            &PairReply::Accept(PairAccept {
                name: self.name(),
                platform: self.cfg.platform,
                statements: Vec::new(),
                adopted: true,
            }),
        )
        .await?;
        let _ = send.finish();
        let _ = tokio::time::timeout(Duration::from_secs(3), send.stopped()).await;
        connection.close(0u32.into(), b"paired");

        info!(peer = %inviter, "was taken into a circle");
        self.emit(Event::Paired { id: inviter });
        self.emit(Event::CircleChanged);
        self.emit(Event::DevicesChanged);
        self.poke.notify_one();
        Ok(())
    }

    /// A device proved it knows the code: vouch for it and send it the circle.
    async fn admit(
        self: Arc<Self>,
        connection: quinn::Connection,
        mut send: quinn::SendStream,
        key: [u8; 32],
        name: String,
        platform: Platform,
    ) -> Result<()> {
        let joiner_id = DeviceId::from_public_key(&key);
        let (statement, statements) = {
            let mut circle = self.circle.write().unwrap();
            let statement = if circle.is_member(&key) {
                None
            } else {
                Some(circle.vouch_for(&self.identity, key, &name, platform)?)
            };
            (statement, circle.statements())
        };
        self.persist_circle();
        self.sync_peers();
        self.learn_addr(&joiner_id, connection.remote_address(), false);

        proto::send(
            &mut send,
            &PairReply::Accept(PairAccept { name: self.name(), platform: self.cfg.platform, statements, adopted: false }),
        )
        .await?;
        let _ = send.finish();
        let _ = tokio::time::timeout(Duration::from_secs(3), send.stopped()).await;
        connection.close(0u32.into(), b"paired");

        if let Some(statement) = statement {
            self.gossip(vec![statement], None).await;
        }
        info!(peer = %joiner_id, "paired with {name}");
        self.emit(Event::Paired { id: joiner_id });
        self.emit(Event::CircleChanged);
        self.emit(Event::DevicesChanged);
        self.poke.notify_one();
        Ok(())
    }

    // The short code.

    /// The device that is given a typed code: look for the devices that are showing a code and see which one it belongs to.
    pub(crate) async fn pair_with_code(self: &Arc<Self>, input: &str) -> Result<DeviceId> {
        let code = normalize_code(input).ok_or_else(|| Error::Pairing("a pairing code has 8 digits".into()))?;
        let deadline = Instant::now() + CODE_SEARCH;
        let mut tried: HashSet<discovery::Hint> = HashSet::new();
        let mut refused = false;
        let mut last_problem: Option<Error> = None;
        loop {
            for (hint, addrs) in self.open_pairings(&tried) {
                tried.insert(hint);
                match self.pair_with_code_at(&code, &addrs).await {
                    Ok(id) => return Ok(id),
                    Err(CodeFailure::NoMatch) => refused = true,
                    Err(CodeFailure::Skip(e)) => last_problem = Some(e),
                    Err(CodeFailure::Fatal(e)) => return Err(e),
                }
            }
            if Instant::now() >= deadline {
                break;
            }
            tokio::time::sleep(Duration::from_millis(300)).await;
        }
        Err(if refused {
            Error::Pairing("no device that shows a code accepted these digits. Check the code".into())
        } else if let Some(e) = last_problem {
            e
        } else {
            Error::Pairing(
                "no device is showing a code. Open the pairing screen on the other device and check that both are on the same Wi-Fi"
                    .into(),
            )
        })
    }

    /// Tries the code on the device at these addresses. Public to the tests, which have no network discovery.
    pub(crate) async fn pair_with_code_at(
        self: &Arc<Self>,
        code: &str,
        addrs: &[SocketAddr],
    ) -> std::result::Result<DeviceId, CodeFailure> {
        let (connection, addr) = self
            .dial_any(None, tls::ALPN_PAIR_CODE, addrs)
            .await
            .map_err(CodeFailure::Skip)?;
        let result = self.run_code_join(&connection, code).await;
        connection.close(0u32.into(), b"done");
        let their_id = result?;
        self.finish_joining(their_id, addrs, addr);
        Ok(their_id)
    }

    async fn run_code_join(
        self: &Arc<Self>,
        connection: &quinn::Connection,
        code: &str,
    ) -> std::result::Result<DeviceId, CodeFailure> {
        let shower_key = tls::peer_key(connection).ok_or_else(|| CodeFailure::Skip(Error::Connection("no key".into())))?;
        let own_key = self.identity.public_key();
        if shower_key == own_key {
            return Err(CodeFailure::Skip(Error::Pairing("that code is from this device".into())));
        }
        let (mut send, mut recv) = connection.open_bi().await.map_err(|e| CodeFailure::Skip(Error::connection(e)))?;
        let exporter = exporter_of(connection, CODE_EXPORTER_LABEL)?;
        let (state, spake) = Spake2::<Ed25519Group>::start_a(
            &Password::new(code.as_bytes()),
            &Identity::new(SCANNER_ID),
            &Identity::new(SHOWER_ID),
        );
        proto::send(&mut send, &CodeHello { name: self.name(), platform: self.cfg.platform, spake }).await?;

        let reply: CodeReply = tokio::time::timeout(Duration::from_secs(15), proto::recv(&mut recv, MAX_SMALL_FRAME))
            .await
            .map_err(|_| CodeFailure::Skip(Error::Pairing("the other device did not answer".into())))??;
        let server = match reply {
            CodeReply::Hello(server) => server,
            CodeReply::Reject(reject) => return Err(CodeFailure::Skip(Error::Pairing(reject.reason))),
        };
        let shared = state.finish(&server.spake).map_err(|_| CodeFailure::NoMatch)?;
        let shower_says = Confirmation {
            role: b"shower",
            exporter: &exporter,
            scanner_key: &own_key,
            shower_key: &shower_key,
            shower_name: &server.name,
            shower_alone: server.alone,
        };
        if !shower_says.holds(&shared, &server.confirm) {
            return Err(CodeFailure::NoMatch);
        }

        // From here on it is the device that shows this code.
        let their_id = DeviceId::from_public_key(&shower_key);
        if self.circle.read().unwrap().is_member(&shower_key) {
            return Ok(their_id);
        }
        let invite = if self.circle.read().unwrap().is_alone(&self.my_id) {
            None
        } else if server.alone {
            Some(self.vouch_in_copy(shower_key, &server.name, server.platform)?)
        } else {
            return Err(CodeFailure::Fatal(Error::Pairing(
                "both devices are in a circle already. Remove one of them from its circle first".into(),
            )));
        };
        let scanner_says = Confirmation { role: b"scanner", ..shower_says };
        proto::send(
            &mut send,
            &CodeFinish { confirm: scanner_says.tag(&shared), invite: invite.as_ref().map(|(circle, _)| circle.statements()) },
        )
        .await?;

        let reply: PairReply = tokio::time::timeout(Duration::from_secs(15), proto::recv(&mut recv, MAX_CONTROL_FRAME))
            .await
            .map_err(|_| CodeFailure::Fatal(Error::Pairing("the other device did not answer".into())))??;
        match reply {
            PairReply::Reject(reject) => Err(CodeFailure::Fatal(Error::Pairing(reject.reason))),
            PairReply::Accept(accept) => {
                self.take_accept(accept, shower_key, invite.map(|(_, statement)| statement))
                    .await
                    .map_err(CodeFailure::Fatal)?;
                Ok(their_id)
            }
        }
    }

    /// The showing side of a typed code.
    pub(crate) async fn handle_code_pairing(self: Arc<Self>, connection: quinn::Connection, key: [u8; 32]) -> Result<()> {
        let (mut send, mut recv) = connection.accept_bi().await.map_err(Error::connection)?;
        let hello: CodeHello = tokio::time::timeout(Duration::from_secs(10), proto::recv(&mut recv, MAX_SMALL_FRAME))
            .await
            .map_err(|_| Error::Pairing("no request arrived".into()))??;
        let exporter = exporter_of(&connection, CODE_EXPORTER_LABEL)?;

        // Every try counts, right or wrong, because this is the moment a guess is made.
        let verdict: std::result::Result<String, &'static str> = {
            let mut guard = self.pairing.lock().unwrap();
            match guard.as_mut() {
                None => Err("this device is not showing a pairing code"),
                Some(pending) if Instant::now() > pending.expires => {
                    *guard = None;
                    Err("the pairing code has expired")
                }
                Some(pending) if pending.attempts >= MAX_ATTEMPTS => {
                    *guard = None;
                    Err("too many tries, show a new code")
                }
                Some(pending) => {
                    pending.attempts += 1;
                    Ok(pending.code.clone())
                }
            }
        };
        self.sync_pairing_announcement();
        let code = match verdict {
            Ok(code) => code,
            Err(reason) => return refuse_code(&connection, &mut send, reason).await,
        };

        let (state, spake) = Spake2::<Ed25519Group>::start_b(
            &Password::new(code.as_bytes()),
            &Identity::new(SCANNER_ID),
            &Identity::new(SHOWER_ID),
        );
        let Ok(shared) = state.finish(&hello.spake) else {
            return refuse_code(&connection, &mut send, "the pairing request was damaged").await;
        };
        let name = self.name();
        let alone = self.circle.read().unwrap().is_alone(&self.my_id);
        let own_key = self.identity.public_key();
        let shower_says =
            Confirmation { role: b"shower", exporter: &exporter, scanner_key: &key, shower_key: &own_key, shower_name: &name, shower_alone: alone };
        let confirm = shower_says.tag(&shared);
        proto::send(
            &mut send,
            &CodeReply::Hello(CodeServerHello { name: name.clone(), platform: self.cfg.platform, alone, spake, confirm }),
        )
        .await?;

        let finish: CodeFinish = tokio::time::timeout(Duration::from_secs(15), proto::recv(&mut recv, MAX_CONTROL_FRAME))
            .await
            .map_err(|_| Error::Pairing("the other device did not confirm the code".into()))??;
        let scanner_says = Confirmation { role: b"scanner", ..shower_says };
        if !scanner_says.holds(&shared, &finish.confirm) {
            return refuse_code(&connection, &mut send, "the pairing code did not match").await;
        }

        // The code was right, so it is used up.
        *self.pairing.lock().unwrap() = None;
        self.sync_pairing_announcement();
        match finish.invite {
            Some(statements) => self.adopt_invite(connection, send, key, statements).await,
            None => self.admit(connection, send, key, hello.name, hello.platform).await,
        }
    }
}

async fn refuse(connection: &quinn::Connection, send: &mut quinn::SendStream, reason: &str) -> Result<()> {
    warn!("pairing refused: {reason}");
    let _ = proto::send(send, &PairReply::Reject(PairReject { reason: reason.to_string() })).await;
    let _ = send.finish();
    let _ = tokio::time::timeout(Duration::from_secs(2), send.stopped()).await;
    connection.close(1u32.into(), b"refused");
    Err(Error::Pairing(reason.to_string()))
}

async fn refuse_code(connection: &quinn::Connection, send: &mut quinn::SendStream, reason: &str) -> Result<()> {
    warn!("pairing with a code refused: {reason}");
    let _ = proto::send(send, &CodeReply::Reject(PairReject { reason: reason.to_string() })).await;
    let _ = send.finish();
    let _ = tokio::time::timeout(Duration::from_secs(2), send.stopped()).await;
    connection.close(1u32.into(), b"refused");
    Err(Error::Pairing(reason.to_string()))
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
