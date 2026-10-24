//! The sound of another device, played on this computer: what plays there plays here too. The device sends 16 bit samples in small
//! pieces; they go into a buffer, and the sound card takes them out at its own speed. A buffer that is too empty plays silence
//! until it has filled a little, and one that has grown too full drops the oldest, so the sound stays close to the device. Several
//! devices can play here at once: each has a stream of its own on the sound card, and the system mixes them.

use std::collections::{HashMap, VecDeque};
use std::sync::{Arc, LazyLock, Mutex};
use std::time::Duration;

use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use cpal::{FromSample, SampleFormat, SizedSample};
use tandem_core::ffi::TandemAudioSink;
use tauri::{AppHandle, Manager};

use crate::{settings, state::AppState};

/// The stream this computer sends its own sound on. Computers from before every device could be the speaker of every other only play
/// this one.
pub const STREAM: u8 = 9;
/// How much sound waits before playing starts, how much may wait at most before the oldest is dropped, and what is kept when that happens.
/// Less is closer to live and stutters sooner on a poor connection; more is steadier.
#[derive(Clone, Copy, Debug)]
pub struct Delay {
    start_ms: usize,
    max_ms: usize,
    keep_ms: usize,
}

impl Delay {
    pub const LOW: Delay = Delay { start_ms: 40, max_ms: 160, keep_ms: 80 };
    pub const NORMAL: Delay = Delay { start_ms: 120, max_ms: 400, keep_ms: 200 };
    pub const SMOOTH: Delay = Delay { start_ms: 250, max_ms: 700, keep_ms: 350 };

    pub fn named(name: &str) -> Delay {
        match name {
            "low" => Delay::LOW,
            "smooth" => Delay::SMOOTH,
            _ => Delay::NORMAL,
        }
    }
}

/// What is waiting to be played: stereo frames at the speed of the phone, and how far into them the sound card is.
struct Buffer {
    delay: Delay,
    rate: u32,
    frames: VecDeque<[f32; 2]>,
    /// A place between two frames, so a rate that does not match can be turned into the one of the card.
    pos: f64,
    playing: bool,
}

impl Buffer {
    fn new(rate: u32, delay: Delay) -> Buffer {
        Buffer { delay, rate: rate.max(8000), frames: VecDeque::new(), pos: 0.0, playing: false }
    }

    fn push(&mut self, pcm: &[u8], channels: usize) {
        let channels = channels.max(1);
        for frame in pcm.chunks_exact(2 * channels) {
            let sample = |i: usize| f32::from(i16::from_le_bytes([frame[i * 2], frame[i * 2 + 1]])) / 32768.0;
            let left = sample(0);
            let right = if channels > 1 { sample(1) } else { left };
            self.frames.push_back([left, right]);
        }
        let max = self.rate as usize * self.delay.max_ms / 1000;
        if self.frames.len() > max {
            let drop = self.frames.len() - self.rate as usize * self.delay.keep_ms / 1000;
            self.frames.drain(..drop);
            self.pos = 0.0;
        }
        if !self.playing && self.frames.len() >= self.rate as usize * self.delay.start_ms / 1000 {
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

/// One device playing now: the thread that keeps the sound card stream alive (a stream cannot move between threads on every system)
/// and the buffer it plays from.
struct Playing {
    stream: u8,
    channels: usize,
    buffer: Arc<Mutex<Buffer>>,
    stop: std::sync::mpsc::Sender<()>,
}

/// By the device that sends.
static PLAYING: LazyLock<Mutex<HashMap<String, Playing>>> = LazyLock::new(|| Mutex::new(HashMap::new()));

/// The core hands the pieces of sound to this.
pub struct Sink;

impl TandemAudioSink for Sink {
    fn on_audio(&self, from: String, stream: u8, _seq: u32, pcm: Vec<u8>) {
        if let Some(playing) = PLAYING.lock().unwrap().get(&from) {
            if playing.stream == stream {
                playing.buffer.lock().unwrap().push(&pcm, playing.channels);
            }
        }
    }
}

/// Whether the sound of `from` plays here now.
pub fn playing_from(from: &str) -> bool {
    PLAYING.lock().unwrap().contains_key(from)
}

/// A device says it is going to send its sound. Only when the person allows it; a no is said, so the device stops sending and can
/// tell its person why.
pub fn start(app: &AppHandle, from: String, stream: u8, rate: u32, channels: u8) {
    // The sound of a screen that this computer looks at and asked for plays also when the sound of other devices is switched off.
    let allowed = settings::get(app).phone_sound || crate::screen_sound::asked(&from);
    // What plays here is recorded with the rest of this computer's sound: sent back to the device it came from, it would go round and
    // round.
    if !allowed || crate::output::sending_to(&from) {
        refuse(app, &from, stream);
        return;
    }
    // A new start of the same device replaces its old one.
    stop(&from);
    let buffer = Arc::new(Mutex::new(Buffer::new(rate, Delay::named(&settings::get(app).sound_delay))));
    let (stop_tx, stop_rx) = std::sync::mpsc::channel::<()>();
    let (ready_tx, ready_rx) = std::sync::mpsc::sync_channel::<bool>(1);
    let player = buffer.clone();
    let spawned = std::thread::Builder::new().name("tandem-sound".into()).spawn(move || {
        let Some(device) = cpal::default_host().default_output_device() else {
            log::warn!("there is no sound card to play the sound of another device on");
            let _ = ready_tx.send(false);
            return;
        };
        let Ok(config) = device.default_output_config() else {
            let _ = ready_tx.send(false);
            return;
        };
        let card_rate = config.sample_rate().0;
        let card_channels = usize::from(config.channels());
        let built = match config.sample_format() {
            SampleFormat::F32 => playback::<f32>(&device, &config.into(), player, card_rate, card_channels),
            SampleFormat::I16 => playback::<i16>(&device, &config.into(), player, card_rate, card_channels),
            SampleFormat::U16 => playback::<u16>(&device, &config.into(), player, card_rate, card_channels),
            other => {
                log::warn!("the sound card wants {other:?}, which is not played");
                let _ = ready_tx.send(false);
                return;
            }
        };
        let Ok(running) = built else {
            // Without a sound server in between, a second device finds the card taken.
            log::warn!("the sound card could not be opened");
            let _ = ready_tx.send(false);
            return;
        };
        if running.play().is_err() {
            let _ = ready_tx.send(false);
            return;
        }
        let _ = ready_tx.send(true);
        // Alive until it is told to stop.
        let _ = stop_rx.recv();
    });
    if spawned.is_err() || !ready_rx.recv_timeout(Duration::from_secs(3)).unwrap_or(false) {
        refuse(app, &from, stream);
        return;
    }
    PLAYING.lock().unwrap().insert(from, Playing { stream, channels: usize::from(channels), buffer, stop: stop_tx });
}

/// The device went away: nothing more of it will come.
pub fn stop(from: &str) {
    if let Some(playing) = PLAYING.lock().unwrap().remove(from) {
        let _ = playing.stop.send(());
    }
}

/// The device stopped sending `stream`. A stop of another stream of the same device is about something else.
pub fn stopped(from: &str, stream: u8) {
    let mut playing = PLAYING.lock().unwrap();
    if playing.get(from).is_some_and(|p| p.stream == stream) {
        if let Some(gone) = playing.remove(from) {
            let _ = gone.stop.send(());
        }
    }
}

/// The person switched off the sound of other devices: each one that plays here is told, so it stops sending.
pub fn stop_all(app: &AppHandle) {
    let gone: Vec<(String, Playing)> = PLAYING.lock().unwrap().drain().collect();
    for (from, playing) in gone {
        let _ = playing.stop.send(());
        refuse(app, &from, playing.stream);
    }
}

fn refuse(app: &AppHandle, to: &str, stream: u8) {
    let Ok(engine) = app.state::<AppState>().engine() else { return };
    let to = to.to_string();
    tauri::async_runtime::spawn(async move {
        let _ = engine.send_audio_stop(to, stream).await;
    });
}

fn playback<T: SizedSample + FromSample<f32>>(
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
        let mut buffer = Buffer::new(48_000, Delay::NORMAL);
        buffer.push(&tone(100, 2), 2);
        assert_eq!(buffer.next(48_000), [0.0, 0.0], "too little to start with");
        buffer.push(&tone(48_000 * Delay::NORMAL.start_ms / 1000, 2), 2);
        let second = {
            buffer.next(48_000);
            buffer.next(48_000)
        };
        assert!(second[0] > 0.0, "the sound plays once it has filled: {second:?}");
    }

    #[test]
    fn a_card_that_runs_slower_takes_fewer_frames_and_one_that_runs_faster_more() {
        let mut slow = Buffer::new(48_000, Delay::NORMAL);
        slow.push(&tone(9_000, 2), 2);
        let before = slow.frames.len();
        for _ in 0..1000 {
            slow.next(24_000);
        }
        let used_slow = before - slow.frames.len();
        let mut fast = Buffer::new(48_000, Delay::NORMAL);
        fast.push(&tone(9_000, 2), 2);
        for _ in 0..1000 {
            fast.next(96_000);
        }
        let used_fast = before - fast.frames.len();
        assert!(used_slow > used_fast * 3, "{used_slow} against {used_fast}");
    }

    #[test]
    fn a_buffer_that_grows_too_full_is_cut_back() {
        let mut buffer = Buffer::new(48_000, Delay::NORMAL);
        buffer.push(&tone(48_000, 1), 1);
        assert!(buffer.frames.len() <= 48_000 * Delay::NORMAL.max_ms / 1000 + 1);
        assert!(buffer.frames.len() >= 48_000 * Delay::NORMAL.keep_ms / 1000 - 1);
    }

    #[test]
    fn mono_is_spread_over_both_sides() {
        let mut buffer = Buffer::new(48_000, Delay::NORMAL);
        buffer.push(&tone(10, 1), 1);
        assert_eq!(buffer.frames[3][0], buffer.frames[3][1]);
    }
}
