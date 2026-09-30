//! Everything two devices say to each other, and how it is framed on the wire.
//!
//! A message is a CBOR value inside a length-prefixed frame. Unknown fields are
//! ignored and unknown message kinds are skipped, so a newer app can talk to an
//! older one without either of them breaking.
//!
//! Avoid `SocketAddr`, `IpAddr` and friends inside messages: they serialise to a
//! compact form in CBOR but serde's buffered deserialiser expects text. Addresses
//! travel as strings.

use serde::{Deserialize, Serialize};
use serde_bytes::ByteBuf;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

use crate::circle::Statement;
use crate::error::{Error, Result};
use crate::ids::{DeviceId, Platform, bytes_array};

pub const PROTOCOL_VERSION: u16 = 1;

/// First byte of every stream a peer opens, so the other side knows what follows.
pub const STREAM_CONTROL: u8 = 1;
pub const STREAM_FILE: u8 = 2;

pub const MAX_CONTROL_FRAME: usize = 8 * 1024 * 1024;
pub const MAX_SMALL_FRAME: usize = 64 * 1024;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Hello {
    pub proto: u16,
    pub app_version: String,
    pub name: String,
    pub platform: Platform,
    #[serde(default)]
    pub model: Option<String>,
    /// What this device can do, e.g. "clipboard", "share", "notify", "call".
    #[serde(default)]
    pub caps: Vec<String>,
    /// Addresses (`ip:port`) others can try, including the Tailscale one.
    #[serde(default)]
    pub candidates: Vec<String>,
    #[serde(with = "bytes_array")]
    pub circle_digest: [u8; 32],
    #[serde(default)]
    pub boot_id: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum Msg {
    CircleSync { statements: Vec<Statement> },
    Status(Status),
    Clipboard(ClipboardMsg),
    ShareOffer(ShareOffer),
    ShareReply(ShareReply),
    ShareText(ShareText),
    Notification(NotificationMsg),
    NotificationRemoved { key: String },
    NotificationAction(NotificationAction),
    AppIcon { app_id: String, png: ByteBuf },
    Call(CallMsg),
    CallAction(CallAction),
    Dial { number: String },
    Ring { on: bool },
    Input(InputMsg),
    Hotspot(HotspotMsg),
    /// The key two devices seal Bluetooth frames with. Made by the one with the lower id and
    /// sent over the authenticated connection, never over the air.
    BleKey {
        #[serde(with = "bytes_array")]
        key: [u8; 32],
    },
    /// Addresses this device can now be reached at, sent when the network changes.
    Candidates { addrs: Vec<String> },
    /// Where other circle members were last reachable, so two devices that only ever
    /// paired through a third one can still find each other.
    Introduce { peers: Vec<PeerAddrs> },
    Ping { nonce: u64 },
    Pong { nonce: u64 },
    Bye { reason: String },
    /// Anything this version does not recognise. Never sent.
    #[serde(skip)]
    Unknown,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PeerAddrs {
    pub id: DeviceId,
    pub addrs: Vec<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum NetKind {
    None,
    Wifi,
    Cellular,
    Ethernet,
    #[serde(other)]
    Other,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct Battery {
    pub level: u8,
    pub charging: bool,
    #[serde(default)]
    pub power_save: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct NetworkStatus {
    pub kind: NetKind,
    #[serde(default)]
    pub ssid: Option<String>,
    #[serde(default)]
    pub metered: bool,
    #[serde(default)]
    pub roaming: bool,
    #[serde(default)]
    pub signal: Option<u8>,
}

/// A device's vitals. Every field is optional and a sender only fills in what
/// changed; receivers merge into what they already know.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Status {
    #[serde(default)]
    pub battery: Option<Battery>,
    #[serde(default)]
    pub network: Option<NetworkStatus>,
    #[serde(default)]
    pub hotspot: Option<bool>,
    #[serde(default)]
    pub dnd: Option<bool>,
    #[serde(default)]
    pub locked: Option<bool>,
    #[serde(default)]
    pub free_storage: Option<u64>,
    /// The device is about to sleep (or has just woken). Sent before it goes, so the others can
    /// show "asleep" instead of "offline" and know a wake-up may work.
    #[serde(default)]
    pub asleep: Option<bool>,
    /// The hardware address of a wired network port, for a Wake-on-LAN packet. Only a device
    /// that has one reports it, and it only wakes the device while it is on that cable.
    #[serde(default)]
    pub wake_mac: Option<String>,
}

impl Status {
    pub fn merge(&mut self, other: &Status) {
        macro_rules! take {
            ($($field:ident),*) => {
                $(if other.$field.is_some() { self.$field = other.$field.clone(); })*
            };
        }
        take!(battery, network, hotspot, dnd, locked, free_storage, asleep, wake_mac);
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ClipboardMsg {
    pub id: u64,
    pub ts: u64,
    pub text: String,
    #[serde(default)]
    pub is_url: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ShareOrigin {
    Files,
    Screenshot,
    Photo,
    Clipboard,
    #[serde(other)]
    Other,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ShareItem {
    pub name: String,
    pub size: u64,
    #[serde(default)]
    pub mime: String,
    #[serde(default)]
    pub modified: Option<u64>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ShareOffer {
    pub id: u64,
    pub origin: ShareOrigin,
    pub items: Vec<ShareItem>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ShareReply {
    pub id: u64,
    pub accepted: bool,
    #[serde(default)]
    pub reason: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ShareText {
    pub id: u64,
    pub text: String,
    #[serde(default)]
    pub is_url: bool,
    /// Ask the receiver to open it (a link in the browser) instead of just showing it.
    #[serde(default)]
    pub open: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct NotifButton {
    pub id: String,
    pub title: String,
    #[serde(default)]
    pub is_reply: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct NotificationMsg {
    pub key: String,
    pub app_id: String,
    pub app_name: String,
    pub title: String,
    pub text: String,
    #[serde(default)]
    pub sub_text: Option<String>,
    pub ts: u64,
    #[serde(default)]
    pub ongoing: bool,
    #[serde(default)]
    pub silent: bool,
    #[serde(default)]
    pub buttons: Vec<NotifButton>,
    /// A one-time code found in the text, so the receiver can offer to copy it.
    #[serde(default)]
    pub otp: Option<String>,
    #[serde(default)]
    pub progress: Option<(u32, u32)>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct NotificationAction {
    pub key: String,
    /// Empty means "open" or "dismiss", depending on `dismiss`.
    pub button: String,
    #[serde(default)]
    pub reply: Option<String>,
    #[serde(default)]
    pub dismiss: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum CallState {
    Ringing,
    Dialing,
    Active,
    Ended,
    Missed,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CallMsg {
    pub id: String,
    pub state: CallState,
    pub incoming: bool,
    #[serde(default)]
    pub number: Option<String>,
    #[serde(default)]
    pub name: Option<String>,
    pub ts: u64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum CallActionKind {
    Answer,
    Reject,
    Silence,
    Hangup,
    RejectWithMessage(String),
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CallAction {
    pub id: String,
    pub action: CallActionKind,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MediaKey {
    PlayPause,
    Next,
    Previous,
    VolumeUp,
    VolumeDown,
    Mute,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum InputMsg {
    Pointer { dx: i16, dy: i16 },
    Scroll { dx: i16, dy: i16 },
    Button { button: u8, down: bool },
    Click { button: u8, count: u8 },
    Key { code: u16, down: bool, mods: u8 },
    Text(String),
    Media(MediaKey),
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum HotspotMsg {
    /// The Mac has no connection and asks the phone to share its own.
    Request { reason: String },
    /// The phone reporting what the hotspot is doing.
    State {
        on: bool,
        #[serde(default)]
        ssid: Option<String>,
        #[serde(default)]
        clients: u8,
        #[serde(default)]
        data_used: Option<u64>,
        #[serde(default)]
        error: Option<String>,
    },
    Stop,
}

// Frames for the file stream. The receiver asks, the sender answers.

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct FileRequest {
    pub offer: u64,
    pub index: u32,
    /// Resume from here. Zero for a fresh start.
    pub offset: u64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct FileResponse {
    pub ok: bool,
    #[serde(default)]
    pub error: Option<String>,
    pub size: u64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct FileTrailer {
    /// BLAKE3 of the whole file, not just the part that was sent.
    #[serde(with = "bytes_array")]
    pub blake3: [u8; 32],
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct FileAck {
    pub ok: bool,
}

// Pairing frames. These travel on the pairing ALPN only.

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PairRequest {
    pub name: String,
    pub platform: Platform,
    /// HMAC over the channel binding with the secret from the QR code.
    #[serde(with = "bytes_array")]
    pub proof: [u8; 32],
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PairAccept {
    pub name: String,
    pub platform: Platform,
    pub statements: Vec<Statement>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PairReject {
    pub reason: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum PairReply {
    Accept(PairAccept),
    Reject(PairReject),
}

pub fn encode<T: Serialize>(value: &T) -> Result<Vec<u8>> {
    let mut out = Vec::with_capacity(128);
    ciborium::into_writer(value, &mut out).map_err(|e| Error::protocol(format!("encode: {e}")))?;
    Ok(out)
}

pub fn decode<T: for<'de> Deserialize<'de>>(bytes: &[u8]) -> Result<T> {
    ciborium::from_reader(bytes).map_err(|e| Error::protocol(format!("decode: {e}")))
}

/// Decodes a control message, tolerating kinds this version has never heard of.
/// Well-formed CBOR that is not a known message becomes `Msg::Unknown`.
pub fn decode_msg(bytes: &[u8]) -> Result<Msg> {
    match ciborium::from_reader::<Msg, _>(bytes) {
        Ok(msg) => Ok(msg),
        Err(err) => match ciborium::from_reader::<ciborium::Value, _>(bytes) {
            Ok(_) => {
                tracing::debug!("skipping unknown message: {err}");
                Ok(Msg::Unknown)
            }
            Err(_) => Err(Error::protocol(format!("malformed frame: {err}"))),
        },
    }
}

pub async fn write_frame<W: AsyncWrite + Unpin>(writer: &mut W, payload: &[u8]) -> Result<()> {
    let len = u32::try_from(payload.len()).map_err(|_| Error::protocol("frame too large"))?;
    // One write, so a frame is never split across two packets for no reason.
    let mut buf = Vec::with_capacity(4 + payload.len());
    buf.extend_from_slice(&len.to_be_bytes());
    buf.extend_from_slice(payload);
    writer.write_all(&buf).await?;
    Ok(())
}

/// Reads one frame. `Ok(None)` means the peer closed the stream cleanly between frames.
pub async fn read_frame<R: AsyncRead + Unpin>(reader: &mut R, max: usize) -> Result<Option<Vec<u8>>> {
    let mut len_bytes = [0u8; 4];
    match reader.read_exact(&mut len_bytes).await {
        Ok(_) => {}
        Err(e) if e.kind() == std::io::ErrorKind::UnexpectedEof => return Ok(None),
        Err(e) => return Err(e.into()),
    }
    let len = u32::from_be_bytes(len_bytes) as usize;
    if len > max {
        return Err(Error::protocol(format!("frame of {len} bytes exceeds the limit of {max}")));
    }
    let mut payload = vec![0u8; len];
    reader.read_exact(&mut payload).await?;
    Ok(Some(payload))
}

pub async fn send<W: AsyncWrite + Unpin, T: Serialize>(writer: &mut W, value: &T) -> Result<()> {
    write_frame(writer, &encode(value)?).await
}

pub async fn recv<R: AsyncRead + Unpin, T: for<'de> Deserialize<'de>>(
    reader: &mut R,
    max: usize,
) -> Result<T> {
    let frame = read_frame(reader, max)
        .await?
        .ok_or_else(|| Error::protocol("stream closed before a message arrived"))?;
    decode(&frame)
}

/// Convenience so callers do not need to know about `serde_bytes`.
pub fn bytes(data: Vec<u8>) -> ByteBuf {
    ByteBuf::from(data)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn messages_round_trip() {
        let msg = Msg::Clipboard(ClipboardMsg { id: 7, ts: 99, text: "hi".into(), is_url: false });
        let bytes = encode(&msg).unwrap();
        match decode_msg(&bytes).unwrap() {
            Msg::Clipboard(c) => assert_eq!(c.text, "hi"),
            other => panic!("wrong message: {other:?}"),
        }
    }

    #[test]
    fn unknown_message_kinds_are_skipped_not_fatal() {
        // A future message this version knows nothing about.
        let future = ciborium::Value::Map(vec![(
            ciborium::Value::Text("teleport".into()),
            ciborium::Value::Map(vec![(ciborium::Value::Text("x".into()), ciborium::Value::Integer(1.into()))]),
        )]);
        let mut bytes = Vec::new();
        ciborium::into_writer(&future, &mut bytes).unwrap();
        assert!(matches!(decode_msg(&bytes).unwrap(), Msg::Unknown));
    }

    #[test]
    fn unknown_fields_are_ignored() {
        let value = ciborium::Value::Map(vec![(
            ciborium::Value::Text("Ping".into()),
            ciborium::Value::Map(vec![
                (ciborium::Value::Text("nonce".into()), ciborium::Value::Integer(5.into())),
                (ciborium::Value::Text("added_later".into()), ciborium::Value::Bool(true)),
            ]),
        )]);
        let mut bytes = Vec::new();
        ciborium::into_writer(&value, &mut bytes).unwrap();
        assert!(matches!(decode_msg(&bytes).unwrap(), Msg::Ping { nonce: 5 }));
    }

    #[test]
    fn status_merges_only_what_changed() {
        let mut known = Status {
            battery: Some(Battery { level: 80, charging: false, power_save: false }),
            hotspot: Some(false),
            ..Default::default()
        };
        known.merge(&Status { hotspot: Some(true), ..Default::default() });
        assert_eq!(known.battery.unwrap().level, 80);
        assert_eq!(known.hotspot, Some(true));
    }

    #[tokio::test]
    async fn frames_round_trip_over_a_pipe() {
        let (mut a, mut b) = tokio::io::duplex(1024);
        send(&mut a, &Msg::Ping { nonce: 3 }).await.unwrap();
        drop(a);
        let frame = read_frame(&mut b, MAX_SMALL_FRAME).await.unwrap().unwrap();
        assert!(matches!(decode_msg(&frame).unwrap(), Msg::Ping { nonce: 3 }));
        assert!(read_frame(&mut b, MAX_SMALL_FRAME).await.unwrap().is_none());
    }

    #[tokio::test]
    async fn oversized_frames_are_refused() {
        let (mut a, mut b) = tokio::io::duplex(1024);
        write_frame(&mut a, &vec![0u8; 200]).await.unwrap();
        assert!(read_frame(&mut b, 100).await.is_err());
    }
}
