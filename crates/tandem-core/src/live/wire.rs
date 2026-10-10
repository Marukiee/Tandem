//! What live video says on the wire: the control messages, the header of a frame, the datagram for
//! the pointer, and a little Annex B reading so every keyframe can carry its parameter sets.
//!
//! The format is written down in docs/SCREEN.md. Keep the two in step.

use bytes::Bytes;
use serde::{Deserialize, Serialize};

/// The bytes of a frame header, after the `STREAM_MEDIA` byte.
pub const FRAME_HEADER_LEN: usize = 28;
pub const HEADER_VERSION: u8 = 1;
/// The most one access unit may hold. A 5K keyframe at a high quality stays far below this.
pub const MAX_FRAME_BYTES: usize = 8 * 1024 * 1024;

pub const FLAG_KEYFRAME: u8 = 1;
/// The payload starts with the parameter sets (SPS and PPS, and VPS for HEVC).
pub const FLAG_CONFIG: u8 = 2;
/// Frames were dropped before this one: a decoder should start afresh here.
pub const FLAG_DISCONTINUITY: u8 = 4;

// Error codes on frame streams. The host resets with the first group, the viewer stops with the second.
pub const RESET_LATE: u32 = 1;
pub const RESET_SUPERSEDED: u32 = 2;
pub const RESET_ENDED: u32 = 3;
pub const STOP_UNWANTED: u32 = 11;
pub const STOP_UNKNOWN: u32 = 12;
pub const STOP_BAD: u32 = 13;

/// The kind byte of the datagram that carries an absolute pointer position.
pub const DATAGRAM_MEDIA_POINTER: u8 = 4;
pub const POINTER_DATAGRAM_LEN: usize = 15;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MediaKind {
    Screen,
    Camera,
    #[serde(other)]
    Other,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MediaCodec {
    H264,
    Hevc,
    #[serde(other)]
    Other,
}

/// Why a session did not start or came to an end. One list for a denial and for a stop.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MediaEnd {
    /// A person stopped it.
    Ended,
    Declined,
    /// The policy of the host says never.
    Policy,
    Busy,
    /// The kind or the codec is not something the host can do.
    Unsupported,
    /// The host would, but cannot: no permission to capture, no camera.
    Unavailable,
    Timeout,
    /// The same device started a new session of this kind.
    Replaced,
    /// The other side never heard of this session.
    UnknownSession,
    /// The other device was gone for longer than the grace period, or restarted.
    PeerGone,
    #[serde(other)]
    Error,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MediaFacing {
    Front,
    Back,
    #[default]
    #[serde(other)]
    Any,
}

/// What the viewer asks for. Zeros mean "no preference".
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct MediaRequest {
    /// Chosen by the viewer: random, not zero.
    pub session: u64,
    pub kind: MediaKind,
    /// Preferred first.
    #[serde(default)]
    pub codecs: Vec<MediaCodec>,
    #[serde(default)]
    pub max_width: u32,
    #[serde(default)]
    pub max_height: u32,
    #[serde(default)]
    pub max_fps: u32,
    /// Bits per second.
    #[serde(default)]
    pub max_bitrate: u32,
    /// Only means something for a screen.
    #[serde(default)]
    pub control: bool,
    #[serde(default)]
    pub facing: MediaFacing,
    /// The viewer wants to use the host as the maker of an extra screen: not what is on the host's own display, but a display of its own,
    /// of the size asked for, that the host adds next to its own. Only means something for a screen.
    #[serde(default)]
    pub extend: bool,
}

/// What the host gives. Also what the viewer is told once the host has said yes.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct MediaAccept {
    pub session: u64,
    pub kind: MediaKind,
    pub codec: MediaCodec,
    pub width: u32,
    pub height: u32,
    pub fps: u32,
    /// The starting target, in bits per second. The host never goes above it.
    pub bitrate: u32,
    /// Whether the viewer may send input.
    #[serde(default)]
    pub control: bool,
}

/// The picture changed shape: a phone turned, a window resized.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct MediaFormat {
    pub width: u32,
    pub height: u32,
    /// Degrees clockwise the picture has to be turned to look upright, 0, 90, 180 or 270.
    #[serde(default)]
    pub rotation: u16,
    #[serde(default)]
    pub fps: u32,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct MediaUpdate {
    #[serde(default)]
    pub format: Option<MediaFormat>,
    /// Control granted or taken away while the session runs.
    #[serde(default)]
    pub control: Option<bool>,
}

/// What the viewer saw in the last interval, once a second.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct MediaReport {
    pub session: u64,
    pub interval_ms: u32,
    /// Frames that arrived whole.
    pub frames: u32,
    pub bytes: u64,
    /// Frames that never arrived.
    pub lost: u32,
    /// Frames the viewer's app could not take.
    pub dropped: u32,
    pub jitter_us: u32,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub enum MediaInput {
    /// A fraction of the streamed picture, 0.0 to 1.0, the top left corner is the origin.
    PointerAbs { x: f32, y: f32 },
    PointerRel { dx: i16, dy: i16 },
    /// 0 primary, 1 secondary, 2 middle, 3 back, 4 forward. `clicks` is the count of this press, 0 counts as 1.
    Button {
        button: u8,
        down: bool,
        #[serde(default)]
        clicks: u8,
    },
    /// Pixels of the streamed picture, in the natural direction: the content follows the fingers.
    Scroll { dx: i16, dy: i16 },
    /// `code` is the USB HID usage on the Keyboard page, `mods` is 1 shift, 2 control, 4 alt, 8 meta, 16 caps lock.
    Key {
        code: u32,
        down: bool,
        #[serde(default)]
        mods: u16,
        #[serde(default)]
        text: String,
    },
    /// A piece of text that was committed. Not the clipboard.
    Text { text: String },
}

impl MediaInput {
    /// Numbers that cannot be real are cleaned up, so the app never has to.
    pub fn sanitized(self) -> Option<MediaInput> {
        match self {
            MediaInput::PointerAbs { x, y } if !x.is_finite() || !y.is_finite() => None,
            MediaInput::PointerAbs { x, y } => Some(MediaInput::PointerAbs { x: x.clamp(0.0, 1.0), y: y.clamp(0.0, 1.0) }),
            MediaInput::Key { code, down, mods, mut text } => {
                if text.len() > 64 {
                    text.truncate(text.floor_char_boundary(64));
                }
                Some(MediaInput::Key { code, down, mods, text })
            }
            MediaInput::Text { mut text } => {
                if text.len() > 16 * 1024 {
                    text.truncate(text.floor_char_boundary(16 * 1024));
                }
                Some(MediaInput::Text { text })
            }
            other => Some(other),
        }
    }
}

// ---- The header of a frame -------------------------------------------------------------

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct FrameHeader {
    pub flags: u8,
    pub session: u64,
    pub seq: u32,
    pub pts_us: u64,
    pub len: u32,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum HeaderError {
    /// A version this build does not know. Not a mistake: a newer peer.
    Version(u8),
    TooLarge(u32),
    Empty,
}

impl FrameHeader {
    pub fn keyframe(&self) -> bool {
        self.flags & FLAG_KEYFRAME != 0
    }

    pub fn encode(&self) -> [u8; FRAME_HEADER_LEN] {
        let mut out = [0u8; FRAME_HEADER_LEN];
        out[0] = HEADER_VERSION;
        out[1] = self.flags;
        out[4..12].copy_from_slice(&self.session.to_be_bytes());
        out[12..16].copy_from_slice(&self.seq.to_be_bytes());
        out[16..24].copy_from_slice(&self.pts_us.to_be_bytes());
        out[24..28].copy_from_slice(&self.len.to_be_bytes());
        out
    }

    pub fn decode(bytes: &[u8; FRAME_HEADER_LEN]) -> Result<FrameHeader, HeaderError> {
        if bytes[0] != HEADER_VERSION {
            return Err(HeaderError::Version(bytes[0]));
        }
        let len = u32::from_be_bytes(bytes[24..28].try_into().unwrap());
        if len == 0 {
            return Err(HeaderError::Empty);
        }
        if len as usize > MAX_FRAME_BYTES {
            return Err(HeaderError::TooLarge(len));
        }
        Ok(FrameHeader {
            flags: bytes[1],
            session: u64::from_be_bytes(bytes[4..12].try_into().unwrap()),
            seq: u32::from_be_bytes(bytes[12..16].try_into().unwrap()),
            pts_us: u64::from_be_bytes(bytes[16..24].try_into().unwrap()),
            len,
        })
    }
}

/// True when `a` comes before `b`, counting around the end of the number range.
pub fn seq_before(a: u32, b: u32) -> bool {
    (a.wrapping_sub(b) as i32) < 0
}

// ---- The pointer as a datagram ---------------------------------------------------------

/// Kind, session, a counter that goes up by one per datagram (so an old one that arrives late is ignored), x and y
/// as 0 to 65535 of the picture.
pub fn pointer_datagram(session: u64, counter: u16, x: f32, y: f32) -> Bytes {
    let scale = |v: f32| (v.clamp(0.0, 1.0) * 65535.0).round() as u16;
    let mut out = Vec::with_capacity(POINTER_DATAGRAM_LEN);
    out.push(DATAGRAM_MEDIA_POINTER);
    out.extend_from_slice(&session.to_be_bytes());
    out.extend_from_slice(&counter.to_be_bytes());
    out.extend_from_slice(&scale(x).to_be_bytes());
    out.extend_from_slice(&scale(y).to_be_bytes());
    Bytes::from(out)
}

pub fn parse_pointer_datagram(data: &[u8]) -> Option<(u64, u16, f32, f32)> {
    if data.len() != POINTER_DATAGRAM_LEN || data[0] != DATAGRAM_MEDIA_POINTER {
        return None;
    }
    let session = u64::from_be_bytes(data[1..9].try_into().ok()?);
    let counter = u16::from_be_bytes(data[9..11].try_into().ok()?);
    let x = u16::from_be_bytes(data[11..13].try_into().ok()?) as f32 / 65535.0;
    let y = u16::from_be_bytes(data[13..15].try_into().ok()?) as f32 / 65535.0;
    Some((session, counter, x, y))
}

// ---- Annex B ---------------------------------------------------------------------------

/// Whether the data begins with a start code (`00 00 01` or `00 00 00 01`). Length-prefixed video (AVCC) does not, and
/// a decoder fed that would show garbage, so it is refused at the door.
pub fn starts_with_start_code(data: &[u8]) -> bool {
    data.starts_with(&[0, 0, 1]) || data.starts_with(&[0, 0, 0, 1])
}

/// The NAL units of an Annex B buffer, without their start codes.
pub fn split_nals(data: &[u8]) -> Vec<&[u8]> {
    let mut starts = Vec::new();
    let mut i = 0;
    while i + 3 <= data.len() {
        if data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1 {
            starts.push(i + 3);
            i += 3;
        } else {
            i += 1;
        }
    }
    let mut out = Vec::with_capacity(starts.len());
    for (n, &start) in starts.iter().enumerate() {
        let mut end = if n + 1 < starts.len() { starts[n + 1] - 3 } else { data.len() };
        // Zeros in front of a start code (the four byte form) and trailing zeros are not part of the unit.
        while end > start && data[end - 1] == 0 {
            end -= 1;
        }
        if end > start {
            out.push(&data[start..end]);
        }
    }
    out
}

/// The type in the header byte of a NAL unit.
pub fn nal_type(codec: MediaCodec, nal: &[u8]) -> u8 {
    match (codec, nal.first()) {
        (MediaCodec::Hevc, Some(b)) => (b >> 1) & 0x3F,
        (_, Some(b)) => b & 0x1F,
        (_, None) => 0,
    }
}

fn is_parameter_set(codec: MediaCodec, kind: u8) -> bool {
    match codec {
        MediaCodec::Hevc => matches!(kind, 32..=34),
        _ => matches!(kind, 7 | 8),
    }
}

/// Whether the access unit carries all the parameter sets a decoder needs to start: SPS and PPS, and for HEVC the VPS.
pub fn has_parameter_sets(codec: MediaCodec, data: &[u8]) -> bool {
    let (mut vps, mut sps, mut pps) = (false, false, false);
    for nal in split_nals(data) {
        let kind = nal_type(codec, nal);
        match (codec, kind) {
            (MediaCodec::Hevc, 32) => vps = true,
            (MediaCodec::Hevc, 33) => sps = true,
            (MediaCodec::Hevc, 34) => pps = true,
            (MediaCodec::Hevc, _) => {}
            (_, 7) => sps = true,
            (_, 8) => pps = true,
            _ => {}
        }
    }
    match codec {
        MediaCodec::Hevc => vps && sps && pps,
        _ => sps && pps,
    }
}

/// The parameter sets of an access unit, each behind a four byte start code.
pub fn parameter_sets(codec: MediaCodec, data: &[u8]) -> Vec<u8> {
    let mut out = Vec::new();
    for nal in split_nals(data) {
        if is_parameter_set(codec, nal_type(codec, nal)) {
            out.extend_from_slice(&[0, 0, 0, 1]);
            out.extend_from_slice(nal);
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn h264_key() -> Vec<u8> {
        let mut v = vec![0, 0, 0, 1, 0x67, 1, 2, 3];
        v.extend_from_slice(&[0, 0, 0, 1, 0x68, 4, 5]);
        v.extend_from_slice(&[0, 0, 1, 0x65, 9, 9, 9, 9]);
        v
    }

    #[test]
    fn a_header_round_trips() {
        let header = FrameHeader { flags: FLAG_KEYFRAME | FLAG_CONFIG, session: 0x0102_0304_0506_0708, seq: 4_000_000_000, pts_us: 99_999_999_999, len: 1234 };
        assert_eq!(FrameHeader::decode(&header.encode()), Ok(header));
        assert!(header.keyframe());
    }

    #[test]
    fn a_header_that_is_not_ours_is_refused() {
        let good = FrameHeader { flags: 0, session: 1, seq: 1, pts_us: 1, len: 10 };
        let mut bytes = good.encode();
        bytes[0] = 9;
        assert_eq!(FrameHeader::decode(&bytes), Err(HeaderError::Version(9)));
        let mut bytes = good.encode();
        bytes[24..28].copy_from_slice(&(MAX_FRAME_BYTES as u32 + 1).to_be_bytes());
        assert!(matches!(FrameHeader::decode(&bytes), Err(HeaderError::TooLarge(_))));
        bytes[24..28].copy_from_slice(&0u32.to_be_bytes());
        assert_eq!(FrameHeader::decode(&bytes), Err(HeaderError::Empty));
        // Reserved bytes are ignored, so a later version can use them.
        let mut bytes = good.encode();
        bytes[2] = 7;
        bytes[3] = 7;
        assert!(FrameHeader::decode(&bytes).is_ok());
    }

    #[test]
    fn sequence_numbers_compare_around_the_end() {
        assert!(seq_before(1, 2));
        assert!(!seq_before(2, 1));
        assert!(!seq_before(5, 5));
        assert!(seq_before(u32::MAX - 1, 3));
        assert!(!seq_before(3, u32::MAX - 1));
    }

    #[test]
    fn the_pointer_datagram_round_trips_within_a_pixel_of_an_8k_screen() {
        let data = pointer_datagram(77, 65535, 0.25, 1.0);
        assert_eq!(data.len(), POINTER_DATAGRAM_LEN);
        let (session, counter, x, y) = parse_pointer_datagram(&data).unwrap();
        assert_eq!((session, counter), (77, 65535));
        assert!((x - 0.25).abs() < 1.0 / 8192.0 && (y - 1.0).abs() < 1.0 / 8192.0);
        assert!(parse_pointer_datagram(&data[..14]).is_none());
        assert!(parse_pointer_datagram(&[1; 15]).is_none());
    }

    #[test]
    fn input_numbers_that_cannot_be_real_are_cleaned_up() {
        assert_eq!(MediaInput::PointerAbs { x: f32::NAN, y: 0.5 }.sanitized(), None);
        assert_eq!(
            MediaInput::PointerAbs { x: -3.0, y: 7.0 }.sanitized(),
            Some(MediaInput::PointerAbs { x: 0.0, y: 1.0 })
        );
        let long = "\u{e9}".repeat(40000);
        match (MediaInput::Text { text: long }).sanitized() {
            Some(MediaInput::Text { text }) => assert!(text.len() <= 16 * 1024),
            other => panic!("unexpected: {other:?}"),
        }
    }

    #[test]
    fn nal_units_are_found_with_either_start_code() {
        let data = h264_key();
        assert!(starts_with_start_code(&data));
        let nals = split_nals(&data);
        assert_eq!(nals.len(), 3);
        assert_eq!(nals[0], &[0x67, 1, 2, 3]);
        assert_eq!(nals[1], &[0x68, 4, 5]);
        assert_eq!(nals[2], &[0x65, 9, 9, 9, 9]);
        assert!(!starts_with_start_code(&[0, 0, 0, 5, 0x65]));
    }

    #[test]
    fn parameter_sets_are_found_and_copied() {
        let data = h264_key();
        assert!(has_parameter_sets(MediaCodec::H264, &data));
        assert_eq!(parameter_sets(MediaCodec::H264, &data), [0, 0, 0, 1, 0x67, 1, 2, 3, 0, 0, 0, 1, 0x68, 4, 5]);
        // An IDR without them is not self-contained.
        assert!(!has_parameter_sets(MediaCodec::H264, &[0, 0, 0, 1, 0x65, 1, 2]));
        // SPS alone is not enough.
        assert!(!has_parameter_sets(MediaCodec::H264, &[0, 0, 0, 1, 0x67, 1, 0, 0, 0, 1, 0x65, 2]));
    }

    #[test]
    fn hevc_needs_all_three() {
        // VPS 32, SPS 33, PPS 34: the type sits in bits 1 to 6 of the first byte.
        let nal = |kind: u8| vec![0, 0, 0, 1, kind << 1, 1, 7];
        let mut all = nal(32);
        all.extend(nal(33));
        all.extend(nal(34));
        all.extend(nal(19));
        assert!(has_parameter_sets(MediaCodec::Hevc, &all));
        let mut no_vps = nal(33);
        no_vps.extend(nal(34));
        assert!(!has_parameter_sets(MediaCodec::Hevc, &no_vps));
        assert_eq!(parameter_sets(MediaCodec::Hevc, &all).len(), 3 * 7);
    }

    #[test]
    fn unknown_reasons_and_kinds_read_as_other() {
        let value = ciborium::Value::Text("something_new".into());
        let mut bytes = Vec::new();
        ciborium::into_writer(&value, &mut bytes).unwrap();
        assert_eq!(crate::proto::decode::<MediaEnd>(&bytes).unwrap(), MediaEnd::Error);
        assert_eq!(crate::proto::decode::<MediaKind>(&bytes).unwrap(), MediaKind::Other);
        assert_eq!(crate::proto::decode::<MediaFacing>(&bytes).unwrap(), MediaFacing::Any);
    }

    #[test]
    fn a_request_from_an_older_peer_without_the_newer_fields_reads() {
        let old = ciborium::Value::Map(vec![
            (ciborium::Value::Text("session".into()), ciborium::Value::Integer(5.into())),
            (ciborium::Value::Text("kind".into()), ciborium::Value::Text("screen".into())),
        ]);
        let mut bytes = Vec::new();
        ciborium::into_writer(&old, &mut bytes).unwrap();
        let request: MediaRequest = crate::proto::decode(&bytes).unwrap();
        assert_eq!(request.session, 5);
        assert!(request.codecs.is_empty() && !request.control);
    }
}
