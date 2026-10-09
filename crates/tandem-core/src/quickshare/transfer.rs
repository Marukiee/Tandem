//! Receiving and sending files over one Quick Share connection, from the connection request to the last piece of the last file.
//!
//! Only the mode "Everyone": the checks that tie a transfer to the contacts of someone's account are answered the way a device
//! without that account answers them.

use std::path::{Path, PathBuf};
use std::time::Duration;

use futures_util::future::BoxFuture;
use ring::rand::{SecureRandom, SystemRandom};
use tokio::fs::File;
use tokio::io::{AsyncReadExt, AsyncRead, AsyncSeekExt, AsyncWrite, AsyncWriteExt};

use super::crypto::{Agreed, Channel, Client, Server};
use super::link::{CHUNK, Inbound, Link, connection_response, is_accepting, is_type, paired_key_encryption, paired_key_result, random_i64, sharing_frame};
use super::proto::{
    connections::{ConnectionRequestFrame, OfflineFrame, V1Frame as OfflineV1, connection_request_frame::Medium, offline_frame, v1_frame::FrameType as OfflineType},
    sharing::{self, file_metadata::Type as FileKind, text_metadata::Type as TextType, v1_frame::FrameType as SharingType},
};
use crate::{Error, Result};

fn bad(what: &str) -> Error {
    Error::protocol(format!("quick share: {what}"))
}

// ---- Who is on the other side -----------------------------------------------------------------------------------------------

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DeviceKind {
    Unknown = 0,
    Phone = 1,
    Tablet = 2,
    Laptop = 3,
}

impl DeviceKind {
    fn from_bits(bits: u8) -> DeviceKind {
        match bits {
            1 => DeviceKind::Phone,
            2 => DeviceKind::Tablet,
            3 => DeviceKind::Laptop,
            _ => DeviceKind::Unknown,
        }
    }
}

/// What a device says about itself while it can be found, and in the connection request: one byte (version, whether it is hidden,
/// what kind of device), sixteen bytes that identify it, and its name with the length in front.
pub fn encode_endpoint_info(name: &str, kind: DeviceKind) -> Vec<u8> {
    let name = name.as_bytes();
    let name = &name[..name.len().min(200)];
    let mut out = Vec::with_capacity(18 + name.len());
    out.push((kind as u8) << 1);
    let mut identity = [0u8; 16];
    SystemRandom::new().fill(&mut identity).expect("the system has randomness");
    out.extend_from_slice(&identity);
    out.push(name.len() as u8);
    out.extend_from_slice(name);
    out
}

pub fn parse_endpoint_info(bytes: &[u8]) -> Option<(String, DeviceKind)> {
    let first = *bytes.first()?;
    let kind = DeviceKind::from_bits((first >> 1) & 0b111);
    let hidden = first & 0b1_0000 != 0;
    if hidden {
        return Some((String::new(), kind));
    }
    let length = *bytes.get(17)? as usize;
    let name = bytes.get(18..18 + length)?;
    Some((String::from_utf8_lossy(name).into_owned(), kind))
}

// ---- Receiving --------------------------------------------------------------------------------------------------------------

#[derive(Clone, Debug)]
pub struct Offered {
    pub payload_id: i64,
    pub name: String,
    pub mime: String,
    pub size: u64,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum TextKind {
    Text,
    Url,
    Address,
    Phone,
}

impl TextKind {
    fn from_wire(kind: Option<i32>) -> TextKind {
        match kind.and_then(|k| TextType::try_from(k).ok()) {
            Some(TextType::Url) => TextKind::Url,
            Some(TextType::Address) => TextKind::Address,
            Some(TextType::PhoneNumber) => TextKind::Phone,
            _ => TextKind::Text,
        }
    }

    fn to_wire(self) -> i32 {
        (match self {
            TextKind::Text => TextType::Text,
            TextKind::Url => TextType::Url,
            TextKind::Address => TextType::Address,
            TextKind::Phone => TextType::PhoneNumber,
        }) as i32
    }

    /// What kind of text this is, by looking at it: a link when it is one address and nothing else.
    pub fn guess(text: &str) -> TextKind {
        let trimmed = text.trim();
        if (trimmed.starts_with("http://") || trimmed.starts_with("https://")) && !trimmed.contains(char::is_whitespace) {
            TextKind::Url
        } else {
            TextKind::Text
        }
    }
}

/// Text that was announced and is still to come.
#[derive(Clone, Debug)]
pub struct IncomingText {
    pub payload_id: i64,
    /// What the sender says it is: the start of the text, or the whole link.
    pub title: String,
    pub kind: TextKind,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ReceivedText {
    pub kind: TextKind,
    pub text: String,
}

/// Everything that came in over one connection.
#[derive(Clone, Debug, Default)]
pub struct Received {
    pub files: Vec<PathBuf>,
    pub texts: Vec<ReceivedText>,
}

#[derive(Clone, Debug)]
pub struct Introduction {
    pub sender: String,
    /// Four digits that are on the screen of the sender too.
    pub pin: String,
    pub files: Vec<Offered>,
    /// Text that was sent along, such as a link.
    pub texts: Vec<IncomingText>,
}

pub trait Receiving: Send {
    /// Asks the person. `Some(folder)` accepts and says where the files go, `None` refuses.
    fn introduced<'a>(&'a mut self, introduction: &'a Introduction) -> BoxFuture<'a, Option<PathBuf>>;
    fn progress(&mut self, _file: &Offered, _received: u64) {}
}

/// A name that is safe to write in a folder and not taken yet.
fn free_name(folder: &Path, wanted: &str) -> PathBuf {
    let base = Path::new(wanted).file_name().and_then(|n| n.to_str()).unwrap_or("file");
    let base = base.trim_start_matches('.');
    let base = if base.is_empty() { "file" } else { base };
    let first = folder.join(base);
    if !first.exists() {
        return first;
    }
    let (stem, extension) = match base.rsplit_once('.') {
        Some((stem, extension)) if !stem.is_empty() => (stem.to_string(), format!(".{extension}")),
        _ => (base.to_string(), String::new()),
    };
    (1..10_000).map(|n| folder.join(format!("{stem} ({n}){extension}"))).find(|p| !p.exists()).unwrap_or(first)
}

/// Takes one transfer in over a connection that was just accepted. Gives the files that were saved.
pub async fn receive<S>(stream: S, events: &mut dyn Receiving) -> Result<Received>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let mut link = Link::new(stream);

    // The request, then the key exchange, then both sides say they accept the connection.
    let request = link.recv_offline_plain().await?;
    let sender_info = request.connection_request.and_then(|r| r.endpoint_info).and_then(|info| parse_endpoint_info(&info));
    let first = link.read_raw().await?;
    let (server, answer) = Server::start(&first)?;
    link.write_raw(&answer).await?;
    let last = link.read_raw().await?;
    let agreed: Agreed = server.finish(&last)?;
    link.send_plain(&connection_response(true)).await?;
    link.encrypt_with(Channel::new(&agreed.next_secret, false));
    let theirs = link.recv_offline().await?;
    if !is_accepting(&theirs) {
        return Err(bad("the sender does not go on"));
    }

    // The exchange that stands in for the check of certificates.
    let mut got_encryption = false;
    let mut got_result = false;
    let introduction = loop {
        let frame = link.next_sharing().await?;
        if is_type(&frame, SharingType::PairedKeyEncryption) {
            got_encryption = true;
            link.send_sharing(&paired_key_encryption()).await?;
        } else if is_type(&frame, SharingType::PairedKeyResult) {
            got_result = true;
            link.send_sharing(&paired_key_result()).await?;
        } else if is_type(&frame, SharingType::Introduction) {
            let _ = (got_encryption, got_result);
            break frame.v1.and_then(|v1| v1.introduction).ok_or_else(|| bad("an introduction is empty"))?;
        }
    };

    let (sender, _) = sender_info.unwrap_or_else(|| (String::new(), DeviceKind::Unknown));
    let offered: Vec<Offered> = introduction
        .file_metadata
        .iter()
        .map(|f| Offered {
            payload_id: f.payload_id.unwrap_or(0),
            name: f.name.clone().unwrap_or_default(),
            mime: f.mime_type.clone().unwrap_or_default(),
            size: f.size.unwrap_or(0).max(0) as u64,
        })
        .collect();
    let texts: Vec<IncomingText> = introduction
        .text_metadata
        .iter()
        .map(|t| IncomingText { payload_id: t.payload_id.unwrap_or(0), title: t.text_title.clone().unwrap_or_default(), kind: TextKind::from_wire(t.r#type) })
        .collect();
    let intro = Introduction { sender, pin: agreed.pin(), files: offered.clone(), texts: texts.clone() };

    // The person decides while the connection is kept alive.
    let folder = {
        let mut ticker = tokio::time::interval(Duration::from_secs(8));
        let mut deciding = events.introduced(&intro);
        loop {
            tokio::select! {
                choice = &mut deciding => break choice,
                _ = ticker.tick() => link.send_keep_alive(false).await?,
            }
        }
    };
    let Some(folder) = folder else {
        link.send_sharing(&answer_frame(sharing::connection_response_frame::Status::Reject)).await?;
        link.disconnect().await;
        return Ok(Received::default());
    };
    link.expect_text(texts.iter().map(|t| t.payload_id));
    link.send_sharing(&answer_frame(sharing::connection_response_frame::Status::Accept)).await?;

    // The files.
    struct Open {
        file: File,
        path: PathBuf,
        offered: Offered,
        written: u64,
        done: bool,
    }
    let mut open: Vec<Open> = Vec::new();
    for offered in &offered {
        let path = free_name(&folder, &offered.name);
        let file = File::create(&path).await?;
        open.push(Open { file, path, offered: offered.clone(), written: 0, done: offered.size == 0 });
    }
    let mut got_texts: Vec<ReceivedText> = Vec::new();
    while open.iter().any(|f| !f.done) || got_texts.len() < texts.len() {
        match link.next().await? {
            Inbound::Text { id, data } => {
                let kind = texts.iter().find(|t| t.payload_id == id).map(|t| t.kind).unwrap_or(TextKind::Text);
                got_texts.push(ReceivedText { kind, text: String::from_utf8_lossy(&data).into_owned() });
            }
            Inbound::File { id, offset, data, last } => {
                let Some(target) = open.iter_mut().find(|f| f.offered.payload_id == id) else { continue };
                if offset as u64 != target.written {
                    return Err(bad("a piece of a file came out of order"));
                }
                target.file.write_all(&data).await?;
                target.written += data.len() as u64;
                events.progress(&target.offered, target.written);
                if last {
                    target.file.flush().await?;
                    target.done = true;
                }
            }
            Inbound::KeepAlive { wants_answer } => {
                if wants_answer {
                    link.send_keep_alive(true).await?;
                }
            }
            Inbound::Sharing(frame) if is_type(&frame, SharingType::Cancel) => return Err(bad("the sender cancelled")),
            Inbound::Disconnected => return Err(Error::NotConnected),
            _ => {}
        }
    }
    link.disconnect().await;
    Ok(Received { files: open.into_iter().map(|f| f.path).collect(), texts: got_texts })
}

fn answer_frame(status: sharing::connection_response_frame::Status) -> sharing::Frame {
    sharing_frame(SharingType::Response, |v1| {
        v1.connection_response = Some(sharing::ConnectionResponseFrame { status: Some(status as i32), ..Default::default() });
    })
}

// ---- Sending ----------------------------------------------------------------------------------------------------------------

/// A piece of text to send: a link, a note.
#[derive(Clone, Debug)]
pub struct OutgoingText {
    pub kind: TextKind,
    pub text: String,
}

#[derive(Clone, Debug)]
pub struct Outgoing {
    pub path: PathBuf,
    pub name: String,
    pub mime: String,
    pub size: u64,
}

pub trait Sending: Send {
    /// The four digits to compare with the screen of the receiver.
    fn pin(&mut self, _pin: &str) {}
    fn progress(&mut self, _sent: u64, _total: u64) {}
}

#[derive(Debug, PartialEq, Eq)]
pub enum Outcome {
    Sent,
    /// The person on the other side said no, or there was no room.
    Refused,
}

fn file_kind(mime: &str) -> FileKind {
    match mime.split('/').next().unwrap_or("") {
        "image" => FileKind::Image,
        "video" => FileKind::Video,
        "audio" => FileKind::Audio,
        _ => FileKind::Unknown,
    }
}

/// Sends files over a connection that was just made. `own_name` is what the other side shows.
pub async fn send<S>(stream: S, own_name: &str, own_kind: DeviceKind, files: &[Outgoing], texts: &[OutgoingText], events: &mut dyn Sending) -> Result<Outcome>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let mut link = Link::new(stream);

    let mut endpoint = [0u8; 4];
    SystemRandom::new().fill(&mut endpoint).expect("the system has randomness");
    let endpoint_id: String = endpoint.iter().map(|b| (b'a' + b % 26) as char).collect();
    let info = encode_endpoint_info(own_name, own_kind);
    link.send_plain(&OfflineFrame {
        version: Some(offline_frame::Version::V1 as i32),
        v1: Some(OfflineV1 {
            r#type: Some(OfflineType::ConnectionRequest as i32),
            connection_request: Some(ConnectionRequestFrame {
                endpoint_id: Some(endpoint_id),
                endpoint_name: Some(own_name.to_string()),
                endpoint_info: Some(info),
                mediums: vec![Medium::WifiLan as i32],
                nonce: Some(random_i64() as i32),
                keep_alive_interval_millis: Some(10_000),
                keep_alive_timeout_millis: Some(30_000),
                ..Default::default()
            }),
            ..Default::default()
        }),
    })
    .await?;

    let (client, first) = Client::start()?;
    link.write_raw(&first).await?;
    let answer = link.read_raw().await?;
    let (last, agreed) = client.finish(&answer)?;
    link.write_raw(&last).await?;
    link.send_plain(&connection_response(true)).await?;
    link.encrypt_with(Channel::new(&agreed.next_secret, true));
    let theirs = link.recv_offline().await?;
    if !is_accepting(&theirs) {
        return Err(bad("the receiver does not go on"));
    }
    events.pin(&agreed.pin());

    link.send_sharing(&paired_key_encryption()).await?;
    link.send_sharing(&paired_key_result()).await?;
    // The receiver answers both; what it says does not matter here.
    let mut answers = 0;
    while answers < 2 {
        let frame = link.next_sharing().await?;
        if is_type(&frame, SharingType::PairedKeyEncryption) || is_type(&frame, SharingType::PairedKeyResult) {
            answers += 1;
        }
    }

    let ids: Vec<i64> = files.iter().map(|_| random_i64()).collect();
    let text_ids: Vec<i64> = texts.iter().map(|_| random_i64()).collect();
    link.send_sharing(&sharing_frame(SharingType::Introduction, |v1| {
        v1.introduction = Some(sharing::IntroductionFrame {
            file_metadata: files
                .iter()
                .zip(&ids)
                .map(|(f, id)| sharing::FileMetadata {
                    name: Some(f.name.clone()),
                    r#type: Some(file_kind(&f.mime) as i32),
                    payload_id: Some(*id),
                    size: Some(f.size as i64),
                    mime_type: Some(f.mime.clone()),
                    id: Some(*id),
                    ..Default::default()
                })
                .collect(),
            text_metadata: texts
                .iter()
                .zip(&text_ids)
                .map(|(t, id)| sharing::TextMetadata {
                    text_title: Some(t.text.chars().take(80).collect()),
                    r#type: Some(t.kind.to_wire()),
                    payload_id: Some(*id),
                    size: Some(t.text.len() as i64),
                    id: Some(*id),
                    is_sensitive_text: None,
                })
                .collect(),
            ..Default::default()
        });
    }))
    .await?;

    // The person on the other side decides.
    let response = loop {
        let frame = link.next_sharing().await?;
        if is_type(&frame, SharingType::Response) {
            break frame.v1.and_then(|v1| v1.connection_response).and_then(|r| r.status);
        }
    };
    if response != Some(sharing::connection_response_frame::Status::Accept as i32) {
        link.disconnect().await;
        return Ok(Outcome::Refused);
    }

    for (text, id) in texts.iter().zip(&text_ids) {
        link.send_bytes(*id, text.text.clone().into_bytes()).await?;
    }
    let total: u64 = files.iter().map(|f| f.size).sum();
    let mut sent = 0u64;
    for (file, id) in files.iter().zip(&ids) {
        let mut source = File::open(&file.path).await?;
        source.seek(std::io::SeekFrom::Start(0)).await?;
        let mut offset = 0i64;
        let mut buffer = vec![0u8; CHUNK];
        loop {
            let n = source.read(&mut buffer).await?;
            if n == 0 {
                break;
            }
            link.send_file_piece(*id, &file.name, file.size as i64, offset, buffer[..n].to_vec(), false).await?;
            offset += n as i64;
            sent += n as u64;
            events.progress(sent, total);
        }
        link.send_file_piece(*id, &file.name, file.size as i64, offset, Vec::new(), true).await?;
    }
    link.send_keep_alive(false).await?;
    // Give the receiver the time to take the last piece in before the connection goes.
    let _ = tokio::time::timeout(Duration::from_secs(5), async {
        loop {
            match link.next().await {
                Ok(Inbound::Disconnected) | Err(_) => break,
                Ok(_) => {}
            }
        }
    })
    .await;
    link.disconnect().await;
    Ok(Outcome::Sent)
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Saves {
        folder: Option<PathBuf>,
        seen: Option<Introduction>,
    }

    impl Receiving for Saves {
        fn introduced<'a>(&'a mut self, introduction: &'a Introduction) -> BoxFuture<'a, Option<PathBuf>> {
            self.seen = Some(introduction.clone());
            let folder = self.folder.clone();
            Box::pin(async move { folder })
        }
    }

    #[derive(Default)]
    struct Watches {
        pin: String,
        last: u64,
    }

    impl Sending for Watches {
        fn pin(&mut self, pin: &str) {
            self.pin = pin.to_string();
        }
        fn progress(&mut self, sent: u64, _total: u64) {
            self.last = sent;
        }
    }

    fn pattern(length: usize, seed: u8) -> Vec<u8> {
        (0..length).map(|i| (i as u8).wrapping_mul(31).wrapping_add(seed)).collect()
    }

    #[tokio::test]
    async fn files_go_from_a_sender_to_a_receiver_intact() {
        let from = tempfile::tempdir().unwrap();
        let to = tempfile::tempdir().unwrap();
        let (a, b) = (pattern(300_000, 1), pattern(10, 2));
        std::fs::write(from.path().join("big.bin"), &a).unwrap();
        std::fs::write(from.path().join("small.txt"), &b).unwrap();
        let files = vec![
            Outgoing { path: from.path().join("big.bin"), name: "big.bin".into(), mime: "application/octet-stream".into(), size: a.len() as u64 },
            Outgoing { path: from.path().join("small.txt"), name: "small.txt".into(), mime: "text/plain".into(), size: b.len() as u64 },
        ];
        let (left, right) = tokio::io::duplex(1 << 20);
        let mut saves = Saves { folder: Some(to.path().to_path_buf()), seen: None };
        let mut watches = Watches::default();
        let (sent, received) = tokio::join!(send(left, "Laptop van Mark", DeviceKind::Laptop, &files, &[], &mut watches), receive(right, &mut saves));
        assert_eq!(sent.unwrap(), Outcome::Sent);
        let paths = received.unwrap().files;
        assert_eq!(paths.len(), 2);
        assert_eq!(std::fs::read(&paths[0]).unwrap(), a);
        assert_eq!(std::fs::read(&paths[1]).unwrap(), b);
        let seen = saves.seen.unwrap();
        assert_eq!(seen.sender, "Laptop van Mark");
        assert_eq!(seen.files.len(), 2);
        assert_eq!(seen.files[0].size, a.len() as u64);
        assert_eq!(seen.pin, watches.pin);
        assert_eq!(watches.last, (a.len() + b.len()) as u64);
    }

    #[tokio::test]
    async fn a_refused_transfer_sends_nothing() {
        let from = tempfile::tempdir().unwrap();
        std::fs::write(from.path().join("a.txt"), b"secret").unwrap();
        let files = vec![Outgoing { path: from.path().join("a.txt"), name: "a.txt".into(), mime: "text/plain".into(), size: 6 }];
        let (left, right) = tokio::io::duplex(1 << 16);
        let mut saves = Saves { folder: None, seen: None };
        let mut watches = Watches::default();
        let (sent, received) = tokio::join!(send(left, "x", DeviceKind::Phone, &files, &[], &mut watches), receive(right, &mut saves));
        assert_eq!(sent.unwrap(), Outcome::Refused);
        assert!(received.unwrap().files.is_empty());
    }

    #[tokio::test]
    async fn a_link_and_a_note_arrive_with_their_kind_next_to_a_file() {
        let from = tempfile::tempdir().unwrap();
        let to = tempfile::tempdir().unwrap();
        std::fs::write(from.path().join("a.txt"), b"file").unwrap();
        let files = vec![Outgoing { path: from.path().join("a.txt"), name: "a.txt".into(), mime: "text/plain".into(), size: 4 }];
        let texts = vec![
            OutgoingText { kind: TextKind::guess("https://example.com/a?b=1"), text: "https://example.com/a?b=1".into() },
            OutgoingText { kind: TextKind::Text, text: "een notitie met é en een regeleinde\nen nog een".into() },
        ];
        let (left, right) = tokio::io::duplex(1 << 16);
        let mut saves = Saves { folder: Some(to.path().to_path_buf()), seen: None };
        let mut watches = Watches::default();
        let (sent, received) = tokio::join!(send(left, "Telefoon", DeviceKind::Phone, &files, &texts, &mut watches), receive(right, &mut saves));
        assert_eq!(sent.unwrap(), Outcome::Sent);
        let received = received.unwrap();
        assert_eq!(received.files.len(), 1);
        assert_eq!(received.texts.len(), 2);
        assert_eq!(received.texts[0], ReceivedText { kind: TextKind::Url, text: "https://example.com/a?b=1".into() });
        assert_eq!(received.texts[1].text, "een notitie met é en een regeleinde\nen nog een");
        assert_eq!(saves.seen.unwrap().texts.len(), 2);
    }

    #[test]
    fn a_link_is_one_address_and_nothing_else() {
        assert_eq!(TextKind::guess("https://example.com"), TextKind::Url);
        assert_eq!(TextKind::guess("  http://a.nl/x  "), TextKind::Url);
        assert_eq!(TextKind::guess("see https://example.com"), TextKind::Text);
        assert_eq!(TextKind::guess("hello"), TextKind::Text);
    }

    #[test]
    fn endpoint_info_round_trips_and_names_are_kept_short() {
        let info = encode_endpoint_info("Pixel 9", DeviceKind::Phone);
        assert_eq!(parse_endpoint_info(&info), Some(("Pixel 9".to_string(), DeviceKind::Phone)));
        let long = "x".repeat(500);
        let (name, _) = parse_endpoint_info(&encode_endpoint_info(&long, DeviceKind::Laptop)).unwrap();
        assert_eq!(name.len(), 200);
        assert_eq!(parse_endpoint_info(&[]), None);
    }

    #[test]
    fn names_of_files_cannot_leave_the_folder_and_are_not_reused() {
        let folder = tempfile::tempdir().unwrap();
        assert_eq!(free_name(folder.path(), "../../etc/passwd"), folder.path().join("passwd"));
        assert_eq!(free_name(folder.path(), ".hidden"), folder.path().join("hidden"));
        std::fs::write(folder.path().join("a.txt"), "x").unwrap();
        assert_eq!(free_name(folder.path(), "a.txt"), folder.path().join("a (1).txt"));
    }
}
