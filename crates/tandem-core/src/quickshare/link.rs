//! One connection of Quick Share: frames with a length in front, plain until the handshake is done and then sealed, and the
//! frames of Nearby Connections (payloads, keep alives) that carry the messages of the sharing layer.

use std::collections::HashMap;

use prost::Message;
use ring::rand::{SecureRandom, SystemRandom};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

use super::crypto::Channel;
use super::proto::{
    connections::{
        ConnectionResponseFrame as OfflineResponse, KeepAliveFrame, OfflineFrame, PayloadTransferFrame, V1Frame as OfflineV1, offline_frame, payload_transfer_frame::{PacketType, PayloadChunk, PayloadHeader, payload_header::PayloadType}, v1_frame::FrameType as OfflineType, connection_response_frame::ResponseStatus,
    },
    sharing,
};
use crate::{Error, Result};

/// The largest frame that is taken in. A frame carries at most a chunk of a file, so this is generous.
const MAX_FRAME: usize = 8 * 1024 * 1024;

/// The size of a chunk of a file as it is sent.
pub const CHUNK: usize = 64 * 1024;

fn bad(what: &str) -> Error {
    Error::protocol(format!("quick share: {what}"))
}

pub fn random_i64() -> i64 {
    let mut bytes = [0u8; 8];
    SystemRandom::new().fill(&mut bytes).expect("the system has randomness");
    i64::from_be_bytes(bytes) & i64::MAX
}

/// What came in, once the keep alives and the pieces of payloads are dealt with.
pub enum Inbound {
    /// A message of the sharing layer, complete.
    Sharing(sharing::Frame),
    /// A piece of a file.
    File { id: i64, offset: i64, data: Vec<u8>, last: bool },
    /// The other side asks for an answer to a keep alive.
    KeepAlive { wants_answer: bool },
    /// The other side is leaving.
    Disconnected,
    /// Something this side does not use.
    Other,
}

pub struct Link<S> {
    stream: S,
    inbox: Vec<u8>,
    channel: Option<Channel>,
    /// Pieces of messages that are still coming, by payload id.
    partial: HashMap<i64, Vec<u8>>,
    keep_alive: u32,
}

impl<S: AsyncRead + AsyncWrite + Unpin> Link<S> {
    pub fn new(stream: S) -> Link<S> {
        Link { stream, inbox: Vec::new(), channel: None, partial: HashMap::new(), keep_alive: 0 }
    }

    pub fn encrypt_with(&mut self, channel: Channel) {
        self.channel = Some(channel);
    }

    // ---- Frames ------------------------------------------------------------------------------------------------------------

    /// The next frame, as bytes. Nothing is lost when this is dropped in the middle of waiting, so it can be one arm of a select.
    pub async fn read_raw(&mut self) -> Result<Vec<u8>> {
        loop {
            if self.inbox.len() >= 4 {
                let length = u32::from_be_bytes(self.inbox[..4].try_into().expect("four bytes")) as usize;
                if length > MAX_FRAME {
                    return Err(bad("a frame is too large"));
                }
                if self.inbox.len() >= 4 + length {
                    let frame = self.inbox[4..4 + length].to_vec();
                    self.inbox.drain(..4 + length);
                    return Ok(frame);
                }
            }
            if self.stream.read_buf(&mut self.inbox).await? == 0 {
                return Err(Error::NotConnected);
            }
        }
    }

    pub async fn write_raw(&mut self, bytes: &[u8]) -> Result<()> {
        let mut framed = Vec::with_capacity(4 + bytes.len());
        framed.extend_from_slice(&(bytes.len() as u32).to_be_bytes());
        framed.extend_from_slice(bytes);
        self.stream.write_all(&framed).await?;
        self.stream.flush().await?;
        Ok(())
    }

    pub async fn send_plain(&mut self, frame: &OfflineFrame) -> Result<()> {
        self.write_raw(&frame.encode_to_vec()).await
    }

    pub async fn send_offline(&mut self, frame: &OfflineFrame) -> Result<()> {
        let channel = self.channel.as_mut().ok_or_else(|| bad("there is no encryption yet"))?;
        let sealed = channel.seal(&frame.encode_to_vec());
        self.write_raw(&sealed).await
    }

    /// Opens a frame that came in. A plain one is the answer to the connection request: the only thing that is not sealed after
    /// the handshake.
    fn open(&mut self, bytes: &[u8]) -> Result<OfflineFrame> {
        if let Ok(plain) = OfflineFrame::decode(bytes) {
            if plain.v1.as_ref().and_then(|v1| v1.r#type) == Some(OfflineType::ConnectionResponse as i32) {
                return Ok(plain);
            }
        }
        let channel = self.channel.as_mut().ok_or_else(|| bad("a sealed message came before the handshake was done"))?;
        let message = channel.open(bytes)?;
        OfflineFrame::decode(message.as_slice()).map_err(|_| bad("a frame does not parse"))
    }

    /// The first frame of a connection, which is not sealed: the request to connect.
    pub async fn recv_offline_plain(&mut self) -> Result<OfflineV1> {
        let bytes = self.read_raw().await?;
        let frame = OfflineFrame::decode(bytes.as_slice()).map_err(|_| bad("the request to connect does not parse"))?;
        let v1 = frame.v1.ok_or_else(|| bad("the request to connect is empty"))?;
        if v1.r#type != Some(OfflineType::ConnectionRequest as i32) {
            return Err(bad("the first frame is not a request to connect"));
        }
        Ok(v1)
    }

    pub async fn recv_offline(&mut self) -> Result<OfflineFrame> {
        let bytes = self.read_raw().await?;
        self.open(&bytes)
    }

    // ---- Payloads and the messages in them ---------------------------------------------------------------------------------

    fn payload_frame(header: PayloadHeader, chunk: PayloadChunk) -> OfflineFrame {
        OfflineFrame {
            version: Some(offline_frame::Version::V1 as i32),
            v1: Some(OfflineV1 {
                r#type: Some(OfflineType::PayloadTransfer as i32),
                payload_transfer: Some(PayloadTransferFrame {
                    packet_type: Some(PacketType::Data as i32),
                    payload_header: Some(header),
                    payload_chunk: Some(chunk),
                    control_message: None,
                }),
                ..Default::default()
            }),
        }
    }

    /// A message of the sharing layer goes as a payload of bytes: the message in one piece, then an empty piece that says it was
    /// the last.
    pub async fn send_sharing(&mut self, frame: &sharing::Frame) -> Result<()> {
        let body = frame.encode_to_vec();
        let header = PayloadHeader {
            id: Some(random_i64()),
            r#type: Some(PayloadType::Bytes as i32),
            total_size: Some(body.len() as i64),
            ..Default::default()
        };
        let total = body.len() as i64;
        self.send_offline(&Self::payload_frame(header.clone(), PayloadChunk { flags: Some(0), offset: Some(0), body: Some(body), index: None }))
            .await?;
        self.send_offline(&Self::payload_frame(header, PayloadChunk { flags: Some(1), offset: Some(total), body: Some(Vec::new()), index: None }))
            .await
    }

    /// One piece of a file. The last piece is empty and says so.
    pub async fn send_file_piece(&mut self, id: i64, name: &str, total: i64, offset: i64, data: Vec<u8>, last: bool) -> Result<()> {
        let header = PayloadHeader {
            id: Some(id),
            r#type: Some(PayloadType::File as i32),
            total_size: Some(total),
            file_name: Some(name.to_string()),
            ..Default::default()
        };
        self.send_offline(&Self::payload_frame(header, PayloadChunk { flags: Some(i32::from(last)), offset: Some(offset), body: Some(data), index: None }))
            .await
    }

    pub async fn send_keep_alive(&mut self, answer: bool) -> Result<()> {
        self.keep_alive += 1;
        let frame = OfflineFrame {
            version: Some(offline_frame::Version::V1 as i32),
            v1: Some(OfflineV1 {
                r#type: Some(OfflineType::KeepAlive as i32),
                keep_alive: Some(KeepAliveFrame { ack: Some(answer), seq_num: Some(self.keep_alive) }),
                ..Default::default()
            }),
        };
        self.send_offline(&frame).await
    }

    /// The next thing of use. Can be dropped while waiting, like `read_raw`.
    pub async fn next(&mut self) -> Result<Inbound> {
        loop {
            let frame = self.recv_offline().await?;
            let Some(v1) = frame.v1 else { continue };
            match OfflineType::try_from(v1.r#type.unwrap_or(0)) {
                Ok(OfflineType::KeepAlive) => {
                    let ack = v1.keep_alive.and_then(|k| k.ack).unwrap_or(false);
                    return Ok(Inbound::KeepAlive { wants_answer: !ack });
                }
                Ok(OfflineType::Disconnection) => return Ok(Inbound::Disconnected),
                Ok(OfflineType::PayloadTransfer) => {
                    let Some(transfer) = v1.payload_transfer else { continue };
                    if transfer.packet_type != Some(PacketType::Data as i32) {
                        continue;
                    }
                    let (Some(header), Some(chunk)) = (transfer.payload_header, transfer.payload_chunk) else { continue };
                    let id = header.id.unwrap_or(0);
                    let last = chunk.flags.unwrap_or(0) & 1 != 0;
                    if header.r#type == Some(PayloadType::File as i32) {
                        return Ok(Inbound::File { id, offset: chunk.offset.unwrap_or(0), data: chunk.body.unwrap_or_default(), last });
                    }
                    if header.r#type == Some(PayloadType::Bytes as i32) {
                        let buffer = self.partial.entry(id).or_default();
                        buffer.extend_from_slice(&chunk.body.unwrap_or_default());
                        if buffer.len() > MAX_FRAME {
                            return Err(bad("a message is too large"));
                        }
                        if last {
                            let done = self.partial.remove(&id).unwrap_or_default();
                            if done.is_empty() {
                                continue;
                            }
                            let message = sharing::Frame::decode(done.as_slice()).map_err(|_| bad("a message of the sharing layer does not parse"))?;
                            return Ok(Inbound::Sharing(message));
                        }
                        continue;
                    }
                    return Ok(Inbound::Other);
                }
                _ => return Ok(Inbound::Other),
            }
        }
    }

    /// The next message of the sharing layer, whatever else comes before it. Keep alives are answered.
    pub async fn next_sharing(&mut self) -> Result<sharing::Frame> {
        loop {
            match self.next().await? {
                Inbound::Sharing(frame) => return Ok(frame),
                Inbound::KeepAlive { wants_answer } => {
                    if wants_answer {
                        self.send_keep_alive(true).await?;
                    }
                }
                Inbound::Disconnected => return Err(Error::NotConnected),
                _ => {}
            }
        }
    }

    pub async fn disconnect(&mut self) {
        let frame = OfflineFrame {
            version: Some(offline_frame::Version::V1 as i32),
            v1: Some(OfflineV1 { r#type: Some(OfflineType::Disconnection as i32), ..Default::default() }),
        };
        let _ = self.send_offline(&frame).await;
        let _ = self.stream.shutdown().await;
    }
}

// ---- Frames that are made often ---------------------------------------------------------------------------------------------

#[allow(deprecated)]
pub fn connection_response(accept: bool) -> OfflineFrame {
    OfflineFrame {
        version: Some(offline_frame::Version::V1 as i32),
        v1: Some(OfflineV1 {
            r#type: Some(OfflineType::ConnectionResponse as i32),
            connection_response: Some(OfflineResponse {
                response: Some(if accept { ResponseStatus::Accept } else { ResponseStatus::Reject } as i32),
                status: Some(if accept { 0 } else { 1 }),
                ..Default::default()
            }),
            ..Default::default()
        }),
    }
}

pub fn is_accepting(frame: &OfflineFrame) -> bool {
    frame.v1.as_ref().and_then(|v1| v1.connection_response.as_ref()).and_then(|r| r.response) == Some(ResponseStatus::Accept as i32)
}

pub fn sharing_frame(kind: sharing::v1_frame::FrameType, fill: impl FnOnce(&mut sharing::V1Frame)) -> sharing::Frame {
    let mut v1 = sharing::V1Frame { r#type: Some(kind as i32), ..Default::default() };
    fill(&mut v1);
    sharing::Frame { version: Some(sharing::frame::Version::V1 as i32), v1: Some(v1) }
}

/// The two messages that stand in for the check of the certificates, which "Everyone" does not do: random bytes where a real
/// check would have signed data, and an answer that says the check could not be done.
pub fn paired_key_encryption() -> sharing::Frame {
    let mut secret_id_hash = [0u8; 6];
    let mut signed = [0u8; 72];
    let rng = SystemRandom::new();
    rng.fill(&mut secret_id_hash).expect("the system has randomness");
    rng.fill(&mut signed).expect("the system has randomness");
    sharing_frame(sharing::v1_frame::FrameType::PairedKeyEncryption, |v1| {
        v1.paired_key_encryption = Some(sharing::PairedKeyEncryptionFrame {
            signed_data: Some(signed.to_vec()),
            secret_id_hash: Some(secret_id_hash.to_vec()),
            optional_signed_data: None,
            qr_code_handshake_data: None,
        });
    })
}

pub fn paired_key_result() -> sharing::Frame {
    sharing_frame(sharing::v1_frame::FrameType::PairedKeyResult, |v1| {
        v1.paired_key_result = Some(sharing::PairedKeyResultFrame {
            status: Some(sharing::paired_key_result_frame::Status::Unable as i32),
            os_type: None,
        });
    })
}

pub fn is_type(frame: &sharing::Frame, kind: sharing::v1_frame::FrameType) -> bool {
    frame.v1.as_ref().and_then(|v1| v1.r#type) == Some(kind as i32)
}
