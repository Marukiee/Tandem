//! The sound of a phone, played on this computer: what plays on the phone plays here too. The phone sends 16 bit samples in small
//! pieces; they go into a buffer, and the sound card takes them out at its own speed. A buffer that is too empty plays silence
//! until it has filled a little, and one that has grown too full drops the oldest, so the sound stays close to the phone.

use std::collections::VecDeque;
use std::sync::{Arc, LazyLock, Mutex};

use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use cpal::{FromSample, SampleFormat, SizedSample};
use tandem_core::ffi::TandemAudioSink;
use tauri::{AppHandle, Manager};

use crate::{settings, state::AppState};

/// The stream of the sound of a phone, as the phone numbers it.
pub const STREAM: u8 = 9;
/// Starts playing once this much sound is in the buffer, and drops down to the lower mark when more than the upper one is.
const START_MS: usize = 120;
const MAX_MS: usize = 400;
const KEEP_MS: usize = 200;

/// What is waiting to be played: stereo frames at the speed of the phone, and how far into them the sound card is.
struct Buffer {
    rate: u32,
    frames: VecDeque<[f32; 2]>,
    /// A place between two frames, so a rate that does not match can be turned into the one of the card.
    pos: f64,
    playing: bool,
}

impl Buffer {
    fn new(rate: u32) -> Buffer {
        Buffer { rate: rate.max(8000), frames: VecDeque::new(), pos: 0.0, playing: false }
    }

    fn push(&mut self, pcm: &[u8], channels: usize) {
        let channels = channels.max(1);
        for frame in pcm.chunks_exact(2 * channels) {
            let sample = |i: usize| f32::from(i16::from_le_bytes([frame[i * 2], frame[i * 2 + 1]])) / 32768.0;
            let left = sample(0);
            let right = if channels > 1 { sample(1) } else { left };
            self.frames.push_back([left, right]);
        }
        let max = self.rate as usize * MAX_MS / 1000;
        if self.frames.len() > max {
            let drop = self.frames.len() - self.rate as usize * KEEP_MS / 1000;
            self.frames.drain(..drop);
            self.pos = 0.0;
        }
        if !self.playing && self.frames.len() >= self.rate as usize * START_MS / 1000 {
            self.playing = true;
        }
    }

    /// The next frame for a card that runs at `card_rate`, or silence when nothing is ready.
    fn next(&mut self, card_rate: u32) -> [f32; 2] {
        if !self.playing {
            return [0.0, 0.0];
        }
        if self.frames.len() < 2 {
            // Ran dry: wait for it to fill again before going on.
            self.playing = false;
            return [0.0, 0.0];
        }
        let (a, b) = (self.frames[0], self.frames[1]);
        let t = self.pos as f32;
        let out = [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t];
        self.pos += f64::from(self.rate) / f64::from(card_rate.max(1));
        while self.pos >= 1.0 && self.frames.len() > 1 {
            self.pos -= 1.0;
            self.frames.pop_front();
        }
        out
    }
}

/// One phone playing now: the thread that keeps the sound card stream alive (a stream cannot move between threads on every system)
/// and the buffer it plays from.
struct Playing {
    from: String,
    channels: usize,
    buffer: Arc<Mutex<Buffer>>,
    stop: std::sync::mpsc::Sender<()>,
}

static PLAYING: LazyLock<Mutex<Option<Playing>>> = LazyLock::new(|| Mutex::new(None));

/// The core hands the pieces of sound to this.
pub struct Sink;

impl TandemAudioSink for Sink {
    fn on_audio(&self, from: String, stream: u8, _seq: u32, pcm: Vec<u8>) {
        if stream != STREAM {
            return;
        }
        if let Some(playing) = PLAYING.lock().unwrap().as_ref() {
            if playing.from == from {
                playing.buffer.lock().unwrap().push(&pcm, playing.channels);
            }
        }
    }
}

/// A phone says it is going to send its sound. Only when the person allows it, and only one phone at a time.
pub fn start(app: &AppHandle, from: String, rate: u32, channels: u8) {
    if !settings::get(app).phone_sound {
        return;
    }
    let _ = app.state::<AppState>();
    stop_all();
    let buffer = Arc::new(Mutex::new(Buffer::new(rate)));
    let (stop_tx, stop_rx) = std::sync::mpsc::channel::<()>();
    let player = buffer.clone();
    let spawned = std::thread::Builder::new().name("tandem-sound".into()).spawn(move || {
        let Some(device) = cpal::default_host().default_output_device() else {
            log::warn!("there is no sound card to play the sound of the phone on");
            return;
        };
        let Ok(config) = device.default_output_config() else { return };
        let card_rate = config.sample_rate().0;
        let card_channels = usize::from(config.channels());
        let built = match config.sample_format() {
            SampleFormat::F32 => stream::<f32>(&device, &config.into(), player, card_rate, card_channels),
            SampleFormat::I16 => stream::<i16>(&device, &config.into(), player, card_rate, card_channels),
            SampleFormat::U16 => stream::<u16>(&device, &config.into(), player, card_rate, card_channels),
            other => {
                log::warn!("the sound card wants {other:?}, which is not played");
                return;
            }
        };
        let Ok(running) = built else {
            log::warn!("the sound card could not be opened");
            return;
        };
        if running.play().is_err() {
            return;
        }
        // Alive until it is told to stop.
        let _ = stop_rx.recv();
    });
    if spawned.is_ok() {
        *PLAYING.lock().unwrap() = Some(Playing { from, channels: usize::from(channels), buffer, stop: stop_tx });
    }
}

pub fn stop(from: &str) {
    let mut guard = PLAYING.lock().unwrap();
    if guard.as_ref().is_some_and(|p| p.from == from) {
        if let Some(playing) = guard.take() {
            let _ = playing.stop.send(());
        }
    }
}

pub fn stop_all() {
    if let Some(playing) = PLAYING.lock().unwrap().take() {
        let _ = playing.stop.send(());
    }
}

fn stream<T: SizedSample + FromSample<f32>>(
    device: &cpal::Device,
    config: &cpal::StreamConfig,
    buffer: Arc<Mutex<Buffer>>,
    card_rate: u32,
    card_channels: usize,
) -> Result<cpal::Stream, cpal::BuildStreamError> {
    device.build_output_stream(
        config,
        move |data: &mut [T], _| {
            let mut buffer = buffer.lock().unwrap();
            for frame in data.chunks_mut(card_channels.max(1)) {
                let [left, right] = buffer.next(card_rate);
                for (i, out) in frame.iter_mut().enumerate() {
                    *out = T::from_sample(if i % 2 == 0 { left } else { right });
                }
            }
        },
        |error| log::warn!("the sound card said: {error}"),
        None,
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tone(frames: usize, channels: usize) -> Vec<u8> {
        (0..frames).flat_map(|i| (0..channels).flat_map(move |_| (((i % 3000) as i16) * 10 + 5).to_le_bytes())).collect()
    }

    #[test]
    fn it_waits_until_there_is_enough_and_then_plays() {
        let mut buffer = Buffer::new(48_000);
        buffer.push(&tone(100, 2), 2);
        assert_eq!(buffer.next(48_000), [0.0, 0.0], "too little to start with");
        buffer.push(&tone(48_000 * START_MS / 1000, 2), 2);
        let second = {
            buffer.next(48_000);
            buffer.next(48_000)
        };
        assert!(second[0] > 0.0, "the sound plays once it has filled: {second:?}");
    }

    #[test]
    fn a_card_that_runs_slower_takes_fewer_frames_and_one_that_runs_faster_more() {
        let mut slow = Buffer::new(48_000);
        slow.push(&tone(9_000, 2), 2);
        let before = slow.frames.len();
        for _ in 0..1000 {
            slow.next(24_000);
        }
        let used_slow = before - slow.frames.len();
        let mut fast = Buffer::new(48_000);
        fast.push(&tone(9_000, 2), 2);
        for _ in 0..1000 {
            fast.next(96_000);
        }
        let used_fast = before - fast.frames.len();
        assert!(used_slow > used_fast * 3, "{used_slow} against {used_fast}");
    }

    #[test]
    fn a_buffer_that_grows_too_full_is_cut_back() {
        let mut buffer = Buffer::new(48_000);
        buffer.push(&tone(48_000, 1), 1);
        assert!(buffer.frames.len() <= 48_000 * MAX_MS / 1000 + 1);
        assert!(buffer.frames.len() >= 48_000 * KEEP_MS / 1000 - 1);
    }

    #[test]
    fn mono_is_spread_over_both_sides() {
        let mut buffer = Buffer::new(48_000);
        buffer.push(&tone(10, 1), 1);
        assert_eq!(buffer.frames[3][0], buffer.frames[3][1]);
    }
}
