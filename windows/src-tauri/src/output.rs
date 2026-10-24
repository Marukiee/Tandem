//! This computer's sound, played on another device: the person picks the device on its page (Use as speaker), or a device that looks
//! at this computer's screen asks for it (`SoundRequest`, see `screen_sound`). It goes to one device at a time, as `AudioStart` on
//! stream 9 and datagrams of 16 bit stereo at 48 kHz. It keeps playing here too: muting the output may also silence what is recorded
//! of it, and whether it does differs per sound system.

use std::io::Read;
#[cfg(target_os = "linux")]
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::{Arc, LazyLock, Mutex};
use std::time::{Duration, Instant};

use tauri::{AppHandle, Emitter, Manager};

use crate::sound::STREAM;
use crate::state::AppState;
use crate::{events, i18n};

/// Why the sound goes to a device.
#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Why {
    /// The person picked it on the page of the device.
    Chosen,
    /// It looks at this computer's screen and asked for the sound.
    Screen,
}

/// What keeps the recording going. Dropping it ends the recording, and with it the thread that sends.
enum Capture {
    #[cfg(target_os = "linux")]
    Process(Child),
    #[cfg(not(target_os = "linux"))]
    Loopback { _stop: std::sync::mpsc::SyncSender<()> },
}

impl Drop for Capture {
    fn drop(&mut self) {
        match self {
            #[cfg(target_os = "linux")]
            Capture::Process(child) => {
                let _ = child.kill();
                let _ = child.wait();
            }
            #[cfg(not(target_os = "linux"))]
            Capture::Loopback { .. } => {}
        }
    }
}

struct Sending {
    to: String,
    why: Why,
    since: Instant,
    stop: Arc<AtomicBool>,
    _capture: Capture,
}

static SENDING: LazyLock<Mutex<Option<Sending>>> = LazyLock::new(|| Mutex::new(None));

const RATE: u32 = 48_000;
const CHANNELS: u8 = 2;
/// What the person is told when nothing can be recorded: what is missing differs per system.
const NO_CAPTURE: &str = if cfg!(windows) { "output_no_capture_windows" } else { "output_no_capture_linux" };

/// Whether this computer's sound goes to another device now, as its speaker or to a viewer of its screen.
pub fn sending() -> bool {
    SENDING.lock().unwrap().is_some()
}

/// Whether this computer's sound goes to `peer` now.
pub fn sending_to(peer: &str) -> bool {
    SENDING.lock().unwrap().as_ref().is_some_and(|s| s.to == peer)
}

/// The device the person picked as the speaker of this computer, for the button on its page.
fn chosen() -> Option<String> {
    SENDING.lock().unwrap().as_ref().filter(|s| s.why == Why::Chosen).map(|s| s.to.clone())
}

fn changed(app: &AppHandle) {
    let _ = app.emit("output", chosen());
}

/// Sends this computer's sound to `to`, in place of wherever it went before. The error is what the person is told.
pub fn start(app: &AppHandle, to: String, why: Why) -> Result<(), String> {
    let engine = app.state::<AppState>().engine()?;
    // Its sound plays here, so it would be recorded and sent back to it, round and round.
    if crate::sound::playing_from(&to) {
        return Err(i18n::t1(app, "output_loop", &events::device_name(app, &to)));
    }
    stop(app);
    let (reader, capture) = record().map_err(|error| {
        log::info!("the sound of this computer cannot be recorded: {error}");
        i18n::t(app, NO_CAPTURE)
    })?;
    let stop = Arc::new(AtomicBool::new(false));
    *SENDING.lock().unwrap() = Some(Sending { to: to.clone(), why, since: Instant::now(), stop: stop.clone(), _capture: capture });
    changed(app);
    let app = app.clone();
    std::thread::Builder::new()
        .name("tandem-output".into())
        .spawn(move || send(app, engine, to, reader, stop))
        .map(|_| ())
        .map_err(|e| e.to_string())
}

/// Stops the sound wherever it goes, and tells the device.
pub fn stop(app: &AppHandle) {
    let taken = SENDING.lock().unwrap().take();
    if let Some(sending) = taken {
        end(app, sending, true);
    }
}

/// Stops the sound when it goes to `peer`, for `why` when that is given.
pub fn stop_for(app: &AppHandle, peer: &str, why: Option<Why>) {
    let taken = {
        let mut guard = SENDING.lock().unwrap();
        if guard.as_ref().is_some_and(|s| s.to == peer && why.is_none_or(|w| s.why == w)) { guard.take() } else { None }
    };
    if let Some(sending) = taken {
        if sending.why == Why::Chosen {
            events::say(app, &i18n::t1(app, "output_gone", &events::device_name(app, peer)));
        }
        end(app, sending, true);
    }
}

/// A stop of `stream` from `from`: when this computer sends to it, the device will not, or no longer, play it. Whether it was that.
pub fn refused(app: &AppHandle, from: &str, stream: u8) -> bool {
    let taken = {
        let mut guard = SENDING.lock().unwrap();
        if stream == STREAM && guard.as_ref().is_some_and(|s| s.to == from) { guard.take() } else { None }
    };
    let Some(sending) = taken else { return false };
    if sending.why == Why::Chosen {
        let name = events::device_name(app, from);
        // A no right after the start is the setting there; later it is the person stopping it there.
        let key = if sending.since.elapsed() < Duration::from_secs(3) { "output_refused" } else { "output_stopped_there" };
        events::say(app, &i18n::t1(app, key, &name));
    }
    end(app, sending, false);
    true
}

fn end(app: &AppHandle, sending: Sending, tell: bool) {
    sending.stop.store(true, Ordering::Relaxed);
    if tell {
        if let Ok(engine) = app.state::<AppState>().engine() {
            let to = sending.to.clone();
            tauri::async_runtime::spawn(async move {
                let _ = engine.send_audio_stop(to, STREAM).await;
            });
        }
    }
    drop(sending);
    changed(app);
}

/// The volume of this computer's output as a gain for what it sends, read twice a second while it sends: the volume keys and the slider
/// turn the device that is its speaker up and down, and mute silences it. What is recorded comes before the volume of the output, so
/// without this the keys did nothing over there.
fn follow_volume(stop: Arc<AtomicBool>) -> Arc<AtomicU32> {
    let gain = Arc::new(AtomicU32::new(1f32.to_bits()));
    let shared = gain.clone();
    std::thread::Builder::new()
        .name("tandem-output-volume".into())
        .spawn(move || {
            while !stop.load(Ordering::Relaxed) {
                if let Some((volume, muted)) = tandem_winsys::find::output() {
                    // The slider is a scale for the ear: half way is about a quarter of the level.
                    let level = if muted { 0.0 } else { volume.clamp(0.0, 1.0).powi(2) };
                    shared.store(level.to_bits(), Ordering::Relaxed);
                }
                std::thread::sleep(Duration::from_millis(500));
            }
        })
        .ok();
    gain
}

/// 16-bit samples times `gain`, in place. Untouched at full volume.
fn scale(pcm: &mut [u8], gain: f32) {
    if gain >= 0.999 {
        return;
    }
    for sample in pcm.chunks_exact_mut(2) {
        let value = f32::from(i16::from_le_bytes([sample[0], sample[1]])) * gain;
        sample.copy_from_slice(&(value as i16).to_le_bytes());
    }
}

fn send(app: AppHandle, engine: Arc<tandem_core::ffi::TandemEngine>, to: String, mut reader: Box<dyn Read + Send>, stop: Arc<AtomicBool>) {
    let mut heard = false;
    if tauri::async_runtime::block_on(engine.send_audio_start(to.clone(), STREAM, RATE, CHANNELS)).is_ok() {
        let frame = usize::from(CHANNELS) * 2;
        // As much as fits in one datagram, a whole number of frames.
        let chunk = (engine.audio_payload_limit(to.clone()) as usize / frame * frame).max(frame);
        let mut seq = 0u32;
        let mut packet = vec![0u8; chunk];
        let gain = follow_volume(stop.clone());
        while !stop.load(Ordering::Relaxed) {
            if reader.read_exact(&mut packet).is_err() {
                break;
            }
            heard = true;
            scale(&mut packet, f32::from_bits(gain.load(Ordering::Relaxed)));
            if engine.send_audio(to.clone(), STREAM, seq, packet.clone()).is_err() {
                break;
            }
            seq = seq.wrapping_add(1);
        }
    }
    // Stopped from here, it was all said already. Ended by itself, the recording broke off or the device can no longer be reached.
    let taken = {
        let mut guard = SENDING.lock().unwrap();
        if guard.as_ref().is_some_and(|s| Arc::ptr_eq(&s.stop, &stop)) { guard.take() } else { None }
    };
    if let Some(sending) = taken {
        if sending.why == Why::Chosen {
            // A recorder that ends before it delivered anything had no sound system to record from.
            let text = if heard { i18n::t1(&app, "output_gone", &events::device_name(&app, &to)) } else { i18n::t(&app, NO_CAPTURE) };
            events::say(&app, &text);
        }
        end(&app, sending, true);
    }
}

#[tauri::command]
pub fn output_start(app: AppHandle, id: String) -> Result<(), String> {
    start(&app, id, Why::Chosen)
}

#[tauri::command]
pub fn output_stop(app: AppHandle) {
    stop(&app);
}

#[tauri::command]
pub fn output_state() -> Option<String> {
    chosen()
}

/// What this computer plays, as 16 bit stereo at 48 kHz: from the monitor of the default output through PulseAudio or PipeWire
/// (parec, else GStreamer) on Linux, from the loopback of the default output on Windows.
#[cfg(target_os = "linux")]
fn record() -> Result<(Box<dyn Read + Send>, Capture), String> {
    let mut parec = Command::new("parec");
    parec.args(["--raw", "--format=s16le", "--rate=48000", "--channels=2", "--latency-msec=20", "--device=@DEFAULT_MONITOR@"]);
    let mut gst = Command::new("gst-launch-1.0");
    gst.args(["-q", "pulsesrc", "device=@DEFAULT_MONITOR@", "!", "audioconvert", "!", "audioresample", "!"])
        .arg("audio/x-raw,format=S16LE,rate=48000,channels=2")
        .args(["!", "fdsink", "fd=1", "sync=false"]);
    for mut command in [parec, gst] {
        command.stdin(Stdio::null()).stdout(Stdio::piped()).stderr(Stdio::null());
        tandem_winsys::system_env(&mut command);
        if let Ok(mut child) = command.spawn() {
            if let Some(out) = child.stdout.take() {
                return Ok((Box::new(out), Capture::Process(child)));
            }
        }
    }
    Err("neither parec nor GStreamer could record the sound".into())
}

#[cfg(not(target_os = "linux"))]
fn record() -> Result<(Box<dyn Read + Send>, Capture), String> {
    loopback::start().map(|(reader, stop)| (Box::new(reader) as Box<dyn Read + Send>, Capture::Loopback { _stop: stop }))
}

/// Windows: an input stream on the default output device records what it plays (loopback, which WASAPI gives that way), turned into
/// 16 bit stereo at 48 kHz and read like a pipe.
#[cfg(not(target_os = "linux"))]
mod loopback {
    use std::io::Read;
    use std::sync::mpsc::{sync_channel, Receiver, SyncSender};

    use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
    use cpal::{FromSample, SampleFormat, SizedSample};

    pub struct Reader {
        rx: Receiver<Vec<u8>>,
        pending: Vec<u8>,
    }

    impl Read for Reader {
        fn read(&mut self, out: &mut [u8]) -> std::io::Result<usize> {
            while self.pending.is_empty() {
                match self.rx.recv() {
                    Ok(bytes) => self.pending = bytes,
                    Err(_) => return Ok(0),
                }
            }
            let n = out.len().min(self.pending.len());
            out[..n].copy_from_slice(&self.pending[..n]);
            self.pending.drain(..n);
            Ok(n)
        }
    }

    /// The reader, and what ends the recording when it is dropped: then the stream goes, and the reader reads its end.
    pub fn start() -> Result<(Reader, SyncSender<()>), String> {
        let device = cpal::default_host().default_output_device().ok_or("there is no sound card")?;
        let config = device.default_output_config().map_err(|e| e.to_string())?;
        let rate = config.sample_rate().0;
        let channels = usize::from(config.channels()).max(1);
        let (tx, rx) = sync_channel::<Vec<u8>>(64);
        let (ready_tx, ready_rx) = sync_channel::<Result<(), String>>(1);
        let (stop_tx, stop_rx) = sync_channel::<()>(1);
        // The stream lives on a thread of its own: on Windows it may not move between threads.
        std::thread::Builder::new()
            .name("tandem-loopback".into())
            .spawn(move || {
                let built = match config.sample_format() {
                    SampleFormat::F32 => input::<f32>(&device, &config.into(), tx, rate, channels),
                    SampleFormat::I16 => input::<i16>(&device, &config.into(), tx, rate, channels),
                    SampleFormat::U16 => input::<u16>(&device, &config.into(), tx, rate, channels),
                    other => Err(format!("the sound card gives {other:?}")),
                };
                match built {
                    Ok(stream) if stream.play().is_ok() => {
                        let _ = ready_tx.send(Ok(()));
                        // Kept until the sender of the stop is dropped.
                        let _ = stop_rx.recv();
                        drop(stream);
                    }
                    Ok(_) => {
                        let _ = ready_tx.send(Err("the sound card would not start".into()));
                    }
                    Err(error) => {
                        let _ = ready_tx.send(Err(error));
                    }
                }
            })
            .map_err(|e| e.to_string())?;
        ready_rx.recv().map_err(|e| e.to_string())??;
        Ok((Reader { rx, pending: Vec::new() }, stop_tx))
    }

    fn input<T: SizedSample>(
        device: &cpal::Device,
        config: &cpal::StreamConfig,
        tx: std::sync::mpsc::SyncSender<Vec<u8>>,
        rate: u32,
        channels: usize,
    ) -> Result<cpal::Stream, String>
    where
        f32: FromSample<T>,
    {
        let mut at = 0f64;
        let step = f64::from(rate) / 48_000.0;
        device
            .build_input_stream(
                config,
                move |data: &[T], _| {
                    let frames: Vec<[f32; 2]> = data
                        .chunks(channels)
                        .map(|f| {
                            let left = f[0].to_sample::<f32>();
                            let right = if channels > 1 { f[1].to_sample::<f32>() } else { left };
                            [left, right]
                        })
                        .collect();
                    // To 48 kHz by taking the nearest frame: the sound card of Windows mostly runs at 48 kHz already.
                    let mut out = Vec::with_capacity(frames.len() * 4);
                    while (at as usize) < frames.len() {
                        for sample in frames[at as usize] {
                            out.extend_from_slice(&((sample.clamp(-1.0, 1.0) * 32767.0) as i16).to_le_bytes());
                        }
                        at += step;
                    }
                    at -= frames.len() as f64;
                    let _ = tx.try_send(out);
                },
                |error| log::warn!("the loopback said: {error}"),
                None,
            )
            .map_err(|e| e.to_string())
    }
}
