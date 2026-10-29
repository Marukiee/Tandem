//! The camera of a phone that is looked at here, passed on to OBS (or anything with ffmpeg in it) on this computer: each frame as it
//! arrives, wrapped in an MPEG transport stream and sent over UDP to 127.0.0.1. In OBS a Media Source with the input
//! `udp://127.0.0.1:<port>` shows it. Nothing is decoded or encoded again, so it costs next to nothing and adds no delay of its own.
//!
//! The stream is the plainest one players take: one program, one H.264 stream, the tables before every keyframe so a player that
//! starts in the middle finds its way in, and a clock reference on every keyframe.

use std::net::UdpSocket;

use crate::live::MediaFrame;

/// Where the stream goes unless the app says otherwise.
pub const DEFAULT_PORT: u16 = 47993;

const PACKET: usize = 188;
const PMT_PID: u16 = 0x1000;
const VIDEO_PID: u16 = 0x0100;
/// Seven packets fill a datagram that every network stack takes, as ffmpeg sends them.
const PACKETS_PER_DATAGRAM: usize = 7;
/// The decoder gets this much time between the clock and the moment to show a frame: 100 ms at 90 kHz.
const DELAY_90K: u64 = 9_000;

pub struct TsOut {
    socket: UdpSocket,
    port: u16,
    counters: [u8; 3],
    first_us: Option<u64>,
    pending: Vec<u8>,
}

impl TsOut {
    /// None when there is no socket to be had; the viewer goes on without it.
    pub fn new(port: u16) -> Option<TsOut> {
        let socket = UdpSocket::bind("127.0.0.1:0").ok()?;
        socket.set_nonblocking(true).ok()?;
        Some(TsOut { socket, port, counters: [0; 3], first_us: None, pending: Vec::new() })
    }

    pub fn port(&self) -> u16 {
        self.port
    }

    /// Sends one frame. A frame nobody listens for is simply lost, which is what UDP to a closed port does.
    pub fn push(&mut self, frame: &MediaFrame) {
        let packets = self.packets(frame);
        for chunk in packets.chunks(PACKET * PACKETS_PER_DATAGRAM) {
            let _ = self.socket.send_to(chunk, ("127.0.0.1", self.port));
        }
    }

    /// The packets of one frame: the tables first when it is a keyframe, then the frame itself.
    pub fn packets(&mut self, frame: &MediaFrame) -> Vec<u8> {
        let first = *self.first_us.get_or_insert(frame.pts_us);
        // From a second in, so the first frames are not at zero, and kept to the 33 bits the format has.
        let clock = (frame.pts_us.wrapping_sub(first) * 9 / 100 + 90_000) & ((1 << 33) - 1);
        let mut out = std::mem::take(&mut self.pending);
        if frame.keyframe || frame.discontinuity {
            self.section(&mut out, 0, &pat());
            self.section(&mut out, PMT_PID, &pmt());
        }
        let mut pes = Vec::with_capacity(frame.data.len() + 20);
        pes.extend_from_slice(&[0x00, 0x00, 0x01, 0xE0, 0x00, 0x00, 0x80, 0x80, 0x05]);
        pes.extend_from_slice(&timestamp(0x2, (clock + DELAY_90K) & ((1 << 33) - 1)));
        // An access unit delimiter first: some players will not start a picture without one.
        pes.extend_from_slice(&[0x00, 0x00, 0x00, 0x01, 0x09, 0xF0]);
        pes.extend_from_slice(&frame.data);
        self.payload(&mut out, VIDEO_PID, &pes, (frame.keyframe || frame.discontinuity).then_some(clock));
        out
    }

    fn counter(&mut self, pid: u16) -> u8 {
        let slot = match pid {
            0 => 0,
            PMT_PID => 1,
            _ => 2,
        };
        let value = self.counters[slot];
        self.counters[slot] = (value + 1) & 0x0F;
        value
    }

    /// A table in one packet, after the pointer field, the rest filled with 0xFF.
    fn section(&mut self, out: &mut Vec<u8>, pid: u16, section: &[u8]) {
        let cc = self.counter(pid);
        let start = out.len();
        out.extend_from_slice(&[0x47, 0x40 | (pid >> 8) as u8, pid as u8, 0x10 | cc, 0x00]);
        out.extend_from_slice(section);
        out.resize(start + PACKET, 0xFF);
    }

    /// A PES packet over as many TS packets as it takes. The first carries the start flag and, with `pcr`, the clock; the last is
    /// filled up with an adaptation field so every packet is exactly 188 bytes.
    fn payload(&mut self, out: &mut Vec<u8>, pid: u16, data: &[u8], pcr: Option<u64>) {
        let mut rest = data;
        let mut first = true;
        while !rest.is_empty() || first {
            let cc = self.counter(pid);
            let start = out.len();
            out.extend_from_slice(&[0x47, (if first { 0x40 } else { 0 }) | (pid >> 8) as u8, pid as u8, 0]);
            // What the adaptation field must hold: the clock on the first packet, then whatever stuffing makes it fit.
            let mut field: Vec<u8> = Vec::new();
            if let (true, Some(pcr)) = (first, pcr) {
                field.push(0x10);
                field.extend_from_slice(&[(pcr >> 25) as u8, (pcr >> 17) as u8, (pcr >> 9) as u8, (pcr >> 1) as u8, ((pcr & 1) << 7) as u8 | 0x7E, 0x00]);
            }
            let room = PACKET - 4 - if field.is_empty() { 0 } else { 1 + field.len() };
            let take = rest.len().min(room);
            let short = room - take;
            if !field.is_empty() || short > 0 {
                // The field grows to swallow the bytes the payload leaves; a field of one byte is only its length.
                let mut length = field.len() + short;
                if field.is_empty() {
                    length = short - 1;
                    if length > 0 {
                        field.push(0x00);
                        field.resize(length, 0xFF);
                    }
                } else {
                    field.resize(length, 0xFF);
                }
                out[start + 3] = 0x30 | cc;
                out.push(field.len() as u8);
                out.extend_from_slice(&field);
            } else {
                out[start + 3] = 0x10 | cc;
            }
            out.extend_from_slice(&rest[..take]);
            rest = &rest[take..];
            first = false;
            debug_assert_eq!(out.len() - start, PACKET);
        }
    }
}

/// A PTS of 33 bits in the five bytes PES wants, with the marker bits.
fn timestamp(prefix: u8, ts: u64) -> [u8; 5] {
    [
        (prefix << 4) | (((ts >> 30) as u8 & 0x07) << 1) | 1,
        (ts >> 22) as u8,
        (((ts >> 15) as u8) << 1) | 1,
        (ts >> 7) as u8,
        ((ts as u8) << 1) | 1,
    ]
}

/// The program table: program 1, its map in `PMT_PID`.
fn pat() -> Vec<u8> {
    with_crc(vec![0x00, 0xB0, 0x0D, 0x00, 0x01, 0xC1, 0x00, 0x00, 0x00, 0x01, 0xE0 | (PMT_PID >> 8) as u8, PMT_PID as u8])
}

/// The program map: one H.264 stream on `VIDEO_PID`, which also carries the clock.
fn pmt() -> Vec<u8> {
    with_crc(vec![
        0x02,
        0xB0,
        0x12,
        0x00,
        0x01,
        0xC1,
        0x00,
        0x00,
        0xE0 | (VIDEO_PID >> 8) as u8,
        VIDEO_PID as u8,
        0xF0,
        0x00,
        0x1B,
        0xE0 | (VIDEO_PID >> 8) as u8,
        VIDEO_PID as u8,
        0xF0,
        0x00,
    ])
}

fn with_crc(mut section: Vec<u8>) -> Vec<u8> {
    let crc = crc32_mpeg(&section);
    section.extend_from_slice(&crc.to_be_bytes());
    section
}

/// The CRC of MPEG tables: polynomial 0x04C11DB7, start all ones, not reflected, no final xor.
fn crc32_mpeg(data: &[u8]) -> u32 {
    let mut crc = 0xFFFF_FFFFu32;
    for &byte in data {
        crc ^= (byte as u32) << 24;
        for _ in 0..8 {
            crc = if crc & 0x8000_0000 != 0 { (crc << 1) ^ 0x04C1_1DB7 } else { crc << 1 };
        }
    }
    crc
}

#[cfg(test)]
mod tests {
    use super::*;

    fn frame(len: usize, keyframe: bool, pts_us: u64) -> MediaFrame {
        MediaFrame { pts_us, keyframe, discontinuity: false, data: vec![0xAB; len] }
    }

    #[test]
    fn the_program_table_is_the_one_every_player_knows() {
        // The table ffmpeg writes for one program with its map at 0x1000, CRC and all.
        assert_eq!(pat(), vec![0x00, 0xB0, 0x0D, 0x00, 0x01, 0xC1, 0x00, 0x00, 0x00, 0x01, 0xF0, 0x00, 0x2A, 0xB1, 0x04, 0xB2]);
    }

    #[test]
    fn every_packet_is_188_bytes_and_starts_in_sync() {
        let mut out = TsOut::new(DEFAULT_PORT).unwrap();
        for (len, key) in [(10, true), (183, false), (184, false), (185, false), (5000, true), (1, false), (176, false), (177, false)] {
            let bytes = out.packets(&frame(len, key, 1_000_000));
            assert_eq!(bytes.len() % PACKET, 0, "{len}");
            assert!(bytes.chunks(PACKET).all(|p| p[0] == 0x47), "{len}");
        }
    }

    #[test]
    fn a_keyframe_brings_the_tables_and_the_clock() {
        let mut out = TsOut::new(DEFAULT_PORT).unwrap();
        let bytes = out.packets(&frame(100, true, 5_000_000));
        let pids: Vec<u16> = bytes.chunks(PACKET).map(|p| ((p[1] as u16 & 0x1F) << 8) | p[2] as u16).collect();
        assert_eq!(&pids[..3], &[0, PMT_PID, VIDEO_PID]);
        let video = &bytes[2 * PACKET..3 * PACKET];
        assert_eq!(video[1] & 0x40, 0x40, "starts a PES");
        assert_eq!(video[3] & 0x30, 0x30, "has an adaptation field");
        assert_eq!(video[5] & 0x10, 0x10, "with a clock");
        let later = out.packets(&frame(100, false, 5_040_000));
        assert_eq!(later.len(), PACKET, "no tables for an ordinary frame");
    }

    #[test]
    fn all_of_a_frame_arrives_and_the_counters_count() {
        let mut out = TsOut::new(DEFAULT_PORT).unwrap();
        let data: Vec<u8> = (0..3000u32).map(|i| i as u8).collect();
        let bytes = out.packets(&MediaFrame { pts_us: 0, keyframe: false, discontinuity: false, data: data.clone() });
        let mut payload = Vec::new();
        let mut counters = Vec::new();
        for p in bytes.chunks(PACKET) {
            counters.push(p[3] & 0x0F);
            let start = if p[3] & 0x20 != 0 { 5 + p[4] as usize } else { 4 };
            payload.extend_from_slice(&p[start..]);
        }
        // The PES header (9), the timestamp (5) and the delimiter (6) come before the frame itself.
        assert_eq!(&payload[20..], &data[..]);
        assert!(counters.windows(2).all(|w| w[1] == (w[0] + 1) & 0x0F));
    }

    #[test]
    fn a_timestamp_carries_its_markers() {
        let t = timestamp(0x2, 0x1_2345_6789 & ((1 << 33) - 1));
        assert_eq!(t[0] & 0xF1, 0x21);
        assert_eq!(t[2] & 1, 1);
        assert_eq!(t[4] & 1, 1);
    }
}
