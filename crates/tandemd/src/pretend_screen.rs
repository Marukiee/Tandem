//! `--pretend-screen <clip.h264>`: this daemon plays a screen. Any paired device that asks for the screen gets the clip,
//! looped, as real H.264 over the real session machinery. The clip is a raw Annex B file, such as the Mac app's encoder
//! harness writes (`TANDEM_DEBUG_SCREEN=encode`).
//!
//! It exists to try a viewer without a second computer: the Android app's decoder, its reconnect, its gestures and the
//! input it sends. Input that arrives is printed, one line each, so a test can read what the phone did.

use std::collections::HashMap;
use std::path::Path;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use anyhow::{Context, Result, bail};
use tandem_core::live::{MediaAnswer, MediaCodec, MediaEnd, MediaHost, MediaInput, MediaKind, MediaRequest};
use tandem_core::{DeviceId, Engine};

const FPS: u32 = 30;

/// One access unit of the clip.
#[derive(Clone)]
struct Unit {
    data: Vec<u8>,
    keyframe: bool,
}

/// The NAL units of an Annex B buffer: where each payload starts and ends, start codes left out.
fn nal_ranges(bytes: &[u8]) -> Vec<(usize, usize)> {
    let mut starts: Vec<(usize, usize)> = Vec::new();
    let mut i = 0;
    while i + 3 <= bytes.len() {
        if bytes[i] == 0 && bytes[i + 1] == 0 {
            if bytes[i + 2] == 1 {
                starts.push((i, i + 3));
                i += 3;
                continue;
            }
            if i + 4 <= bytes.len() && bytes[i + 2] == 0 && bytes[i + 3] == 1 {
                starts.push((i, i + 4));
                i += 4;
                continue;
            }
        }
        i += 1;
    }
    let mut out = Vec::new();
    for (n, (_, payload)) in starts.iter().enumerate() {
        let end = starts.get(n + 1).map(|next| next.0).unwrap_or(bytes.len());
        if *payload < end {
            out.push((*payload, end));
        }
    }
    out
}

/// A stream cut into pictures. A picture starts at a slice that begins at the first macroblock, and parameter sets or
/// SEI behind a picture belong to the next one.
fn access_units(bytes: &[u8]) -> Vec<Unit> {
    let mut units = Vec::new();
    let mut current: Vec<u8> = Vec::new();
    let mut has_picture = false;
    let mut keyframe = false;
    let mut flush = |current: &mut Vec<u8>, has_picture: &mut bool, keyframe: &mut bool| {
        if !current.is_empty() {
            units.push(Unit { data: std::mem::take(current), keyframe: *keyframe });
        }
        *has_picture = false;
        *keyframe = false;
    };
    for (start, end) in nal_ranges(bytes) {
        let nal = &bytes[start..end];
        let kind = nal[0] & 0x1F;
        let is_slice = kind == 1 || kind == 5;
        let starts_picture = is_slice && nal.len() > 1 && nal[1] & 0x80 != 0;
        if kind == 9 || (has_picture && (starts_picture || matches!(kind, 6..=8))) {
            flush(&mut current, &mut has_picture, &mut keyframe);
        }
        current.extend_from_slice(&[0, 0, 0, 1]);
        current.extend_from_slice(nal);
        if is_slice {
            has_picture = true;
        }
        if kind == 5 {
            keyframe = true;
        }
    }
    flush(&mut current, &mut has_picture, &mut keyframe);
    units
}

struct Playing {
    stop: Arc<AtomicBool>,
    /// Set when the viewer asks for a keyframe: the player jumps to the next one in the clip.
    want_keyframe: Arc<AtomicBool>,
    bitrate: Arc<AtomicU32>,
}

struct Pretend {
    engine: Engine,
    units: Arc<Vec<Unit>>,
    width: u32,
    height: u32,
    playing: Mutex<HashMap<u64, Playing>>,
}

impl MediaHost for Pretend {
    fn on_request(&self, from: DeviceId, request: MediaRequest, _pre_approved: bool) {
        println!(
            "screen requested by {} (session {}, control {}, extend {}, box {}x{})",
            from.short(), request.session, request.control, request.extend, request.max_width, request.max_height
        );
        if request.kind != MediaKind::Screen {
            let _ = self.engine.media_deny(request.session, MediaEnd::Unsupported);
            return;
        }
        let answer = MediaAnswer {
            codec: MediaCodec::H264,
            width: self.width,
            height: self.height,
            fps: FPS,
            bitrate: 4_000_000,
            control: request.control,
        };
        if let Err(e) = self.engine.media_accept(request.session, answer) {
            println!("could not accept: {e}");
            return;
        }
        let playing = Playing {
            stop: Arc::new(AtomicBool::new(false)),
            want_keyframe: Arc::new(AtomicBool::new(true)),
            bitrate: Arc::new(AtomicU32::new(4_000_000)),
        };
        let (stop, want, bitrate) = (playing.stop.clone(), playing.want_keyframe.clone(), playing.bitrate.clone());
        self.playing.lock().unwrap().insert(request.session, playing);
        let (engine, units, session) = (self.engine.clone(), self.units.clone(), request.session);
        std::thread::spawn(move || play(engine, units, session, stop, want, bitrate));
    }

    fn on_keyframe(&self, session: u64) {
        if let Some(p) = self.playing.lock().unwrap().get(&session) {
            p.want_keyframe.store(true, Ordering::Relaxed);
        }
    }

    fn on_bitrate(&self, session: u64, bits_per_second: u32) {
        println!("bitrate {session} {bits_per_second}");
        if let Some(p) = self.playing.lock().unwrap().get(&session) {
            p.bitrate.store(bits_per_second, Ordering::Relaxed);
        }
    }

    fn on_input(&self, session: u64, input: MediaInput) {
        println!("input {session} {input:?}");
    }

    fn on_stop(&self, session: u64, reason: MediaEnd) {
        println!("screen stopped {session} {reason:?}");
        if let Some(p) = self.playing.lock().unwrap().remove(&session) {
            p.stop.store(true, Ordering::Relaxed);
        }
    }
}

/// Pushes the clip at its own pace. The clip loops; a keyframe request moves to the next keyframe, which is what a real
/// encoder does when it is told to make one.
fn play(engine: Engine, units: Arc<Vec<Unit>>, session: u64, stop: Arc<AtomicBool>, want: Arc<AtomicBool>, _bitrate: Arc<AtomicU32>) {
    let started = Instant::now();
    let mut index = 0usize;
    let mut frame = 0u64;
    while !stop.load(Ordering::Relaxed) {
        if want.swap(false, Ordering::Relaxed) && !units[index].keyframe {
            let next = (1..=units.len()).map(|n| (index + n) % units.len()).find(|&i| units[i].keyframe);
            if let Some(next) = next {
                index = next;
            }
        }
        let unit = &units[index];
        let pts = frame * 1_000_000 / FPS as u64;
        let outcome = engine.media_push_frame(session, unit.data.clone(), pts, unit.keyframe);
        use tandem_core::live::MediaPush;
        match outcome {
            MediaPush::NoSession => return,
            MediaPush::Invalid => println!("frame {frame} refused as invalid"),
            _ => {}
        }
        index = (index + 1) % units.len();
        frame += 1;
        let due = started + Duration::from_micros(frame * 1_000_000 / FPS as u64);
        let now = Instant::now();
        if due > now {
            std::thread::sleep(due - now);
        }
    }
}

/// Reads the clip and makes this daemon answer screen requests with it.
pub fn install(engine: &Engine, clip: &Path, size: Option<&str>) -> Result<()> {
    let bytes = std::fs::read(clip).with_context(|| format!("cannot read {}", clip.display()))?;
    let units = access_units(&bytes);
    if !units.first().map(|u| u.keyframe).unwrap_or(false) {
        bail!("the clip must start with a keyframe that carries its parameter sets");
    }
    let (width, height) = match size {
        Some(text) => {
            let (w, h) = text.split_once('x').context("the size is written 1280x720")?;
            (w.parse()?, h.parse()?)
        }
        None => (1280, 720),
    };
    println!("pretending to be a screen: {} pictures, {} keyframes, {width}x{height}", units.len(), units.iter().filter(|u| u.keyframe).count());
    let host = Pretend { engine: engine.clone(), units: Arc::new(units), width, height, playing: Mutex::new(HashMap::new()) };
    engine.set_media_host(Arc::new(host));
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    const SPS: [u8; 6] = [0, 0, 0, 1, 0x67, 1];
    const PPS: [u8; 6] = [0, 0, 1, 0x68, 2, 3];

    fn picture(kind: u8, first_mb: bool) -> Vec<u8> {
        vec![0, 0, 0, 1, kind, if first_mb { 0x88 } else { 0x08 }, 5, 6]
    }

    #[test]
    fn a_clip_is_cut_into_pictures_and_keyframes_are_known() {
        let mut clip = Vec::new();
        clip.extend_from_slice(&SPS);
        clip.extend_from_slice(&PPS);
        clip.extend(picture(0x65, true));
        clip.extend(picture(0x41, true));
        clip.extend(picture(0x41, true));
        clip.extend_from_slice(&SPS);
        clip.extend_from_slice(&PPS);
        clip.extend(picture(0x65, true));
        let units = access_units(&clip);
        assert_eq!(units.len(), 4);
        assert_eq!(units.iter().map(|u| u.keyframe).collect::<Vec<_>>(), vec![true, false, false, true]);
        // The parameter sets travel with the keyframe they belong to.
        assert!(units[0].data.starts_with(&[0, 0, 0, 1, 0x67]));
        assert_eq!(units.concat_len(), clip.len() + 2, "the three byte start code is written with four bytes");
    }

    #[test]
    fn a_second_slice_of_the_same_picture_stays_with_it() {
        let mut clip = Vec::new();
        clip.extend_from_slice(&SPS);
        clip.extend_from_slice(&PPS);
        clip.extend(picture(0x65, true));
        clip.extend(picture(0x65, false));
        clip.extend(picture(0x41, true));
        assert_eq!(access_units(&clip).len(), 2);
    }

    trait ConcatLen {
        fn concat_len(&self) -> usize;
    }

    impl ConcatLen for Vec<Unit> {
        fn concat_len(&self) -> usize {
            self.iter().map(|u| u.data.len()).sum()
        }
    }
}
