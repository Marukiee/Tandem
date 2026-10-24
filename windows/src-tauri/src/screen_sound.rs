//! The sound that belongs to a screen that is looked at. The viewer asks for it (`SoundRequest`) when the person wants it here, or
//! when headphones are in and the person says yes; the computer whose screen it is records what it plays and sends it as the sound of
//! a phone is sent (`AudioStart` on stream 9 and datagrams), and stops when the screen session ends.

use std::collections::HashSet;
use std::io::Read;
use std::process::Child;
#[cfg(target_os = "linux")]
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, LazyLock, Mutex};

use tauri::{AppHandle, Manager};

use crate::state::AppState;
use crate::{events, i18n, settings};

/// The devices whose sound this computer asked for, because it looks at their screen.
static ASKED: LazyLock<Mutex<HashSet<String>>> = LazyLock::new(|| Mutex::new(HashSet::new()));

/// Whether this computer asked `from` for its sound: then it plays, also when the sound of a phone is switched off.
pub fn asked(from: &str) -> bool {
    ASKED.lock().unwrap().contains(from)
}

fn ask_for(app: &AppHandle, peer: &str, on: bool) {
    {
        let mut asked = ASKED.lock().unwrap();
        if on { asked.insert(peer.to_string()) } else { asked.remove(peer) };
    }
    let Ok(engine) = app.state::<AppState>().engine() else { return };
    let peer = peer.to_string();
    tauri::async_runtime::spawn(async move {
        let _ = engine.request_sound(peer, on).await;
    });
}

/// This computer started to look at the screen of `peer`: the sound comes here when that is the choice, or when headphones are in and
/// the person says so.
pub fn view_started(app: &AppHandle, peer: String) {
    let chosen = settings::get(app);
    if chosen.screen_sound == "here" {
        ask_for(app, &peer, true);
        return;
    }
    if !chosen.screen_sound_ask || !headphones() {
        return;
    }
    let app = app.clone();
    std::thread::Builder::new()
        .name("tandem-sound-question".into())
        .spawn(move || {
            let name = events::device_name(&app, &peer);
            let question = crate::ask::Question {
                id: 0,
                title: i18n::t1(&app, "screen_sound_title", &name),
                body: i18n::t(&app, "screen_sound_body"),
                allow: i18n::t(&app, "screen_sound_here"),
                always: i18n::t(&app, "screen_sound_always"),
                deny: i18n::t(&app, "screen_sound_there"),
                control: false,
            };
            match crate::ask::ask(&app, question) {
                crate::ask::Answer::Allow => ask_for(&app, &peer, true),
                crate::ask::Answer::Always => {
                    crate::settings::update(&app, |s| s.screen_sound = "here".into());
                    ask_for(&app, &peer, true);
                }
                crate::ask::Answer::Deny => {}
            }
        })
        .ok();
}

pub fn view_ended(app: &AppHandle, peer: &str) {
    if asked(peer) {
        ask_for(app, peer, false);
        crate::sound::stop(peer);
    }
}

/// Whether what this computer plays goes to headphones now: a Bluetooth device, or the jack or a headset port of the sound card.
pub fn headphones() -> bool {
    #[cfg(target_os = "linux")]
    {
        let run = |args: &[&str]| -> Option<String> {
            let mut command = Command::new("pactl");
            command.args(args).stdin(Stdio::null()).stderr(Stdio::null());
            tandem_winsys::system_env(&mut command);
            command.output().ok().filter(|o| o.status.success()).map(|o| String::from_utf8_lossy(&o.stdout).into_owned())
        };
        let Some(sink) = run(&["get-default-sink"]).map(|s| s.trim().to_string()) else { return false };
        if sink.contains("bluez") {
            return true;
        }
        // The port in use of that sink: "headphones" or "headset" when something is in the jack.
        let Some(list) = run(&["list", "sinks"]) else { return false };
        let mut in_sink = false;
        for line in list.lines() {
            let line = line.trim();
            if let Some(name) = line.strip_prefix("Name:") {
                in_sink = name.trim() == sink;
            } else if in_sink {
                if let Some(port) = line.strip_prefix("Active Port:") {
                    let port = port.to_lowercase();
                    return port.contains("headphone") || port.contains("headset");
                }
            }
        }
        false
    }
    #[cfg(not(target_os = "linux"))]
    {
        false
    }
}

// ---- Sending the sound of this computer ------------------------------------------------------------------------------------------

/// What runs while this computer sends its sound to a viewer.
struct Sending {
    to: String,
    stop: Arc<AtomicBool>,
    child: Option<Child>,
}

static SENDING: LazyLock<Mutex<Option<Sending>>> = LazyLock::new(|| Mutex::new(None));

const RATE: u32 = 48_000;
const CHANNELS: u8 = 2;

/// Whether this computer sends what it plays to a viewer now.
pub fn sending() -> bool {
    SENDING.lock().unwrap().is_some()
}

/// A viewer asks for the sound of this computer, or no longer.
pub fn requested(app: &AppHandle, from: String, on: bool) {
    if !on {
        stop_sending(Some(&from));
        return;
    }
    stop_sending(None);
    let Ok(engine) = app.state::<AppState>().engine() else { return };
    let stop = Arc::new(AtomicBool::new(false));
    let (reader, child) = match record() {
        Ok(found) => found,
        Err(error) => {
            log::info!("the sound of this computer cannot be recorded: {error}");
            return;
        }
    };
    *SENDING.lock().unwrap() = Some(Sending { to: from.clone(), stop: stop.clone(), child });
    std::thread::Builder::new()
        .name("tandem-screen-sound".into())
        .spawn(move || send(engine, from, reader, stop))
        .ok();
}

/// The screen session with `peer` ended: its sound stops too.
pub fn host_ended(peer: &str) {
    stop_sending(Some(peer));
}

fn stop_sending(only: Option<&str>) {
    let mut guard = SENDING.lock().unwrap();
    if guard.as_ref().is_some_and(|s| only.is_none_or(|peer| s.to == peer)) {
        if let Some(mut sending) = guard.take() {
            sending.stop.store(true, Ordering::Relaxed);
            if let Some(child) = sending.child.as_mut() {
                let _ = child.kill();
                let _ = child.wait();
            }
        }
    }
}

fn send(engine: Arc<tandem_core::ffi::TandemEngine>, to: String, mut reader: Box<dyn Read + Send>, stop: Arc<AtomicBool>) {
    if tauri::async_runtime::block_on(engine.send_audio_start(to.clone(), crate::sound::STREAM, RATE, CHANNELS)).is_err() {
        return;
    }
    let frame = usize::from(CHANNELS) * 2;
    // As much as fits in one datagram, a whole number of frames.
    let chunk = (engine.audio_payload_limit(to.clone()) as usize / frame * frame).max(frame);
    let mut seq = 0u32;
    let mut packet = vec![0u8; chunk];
    while !stop.load(Ordering::Relaxed) {
        if reader.read_exact(&mut packet).is_err() {
            break;
        }
        if engine.send_audio(to.clone(), crate::sound::STREAM, seq, packet.clone()).is_err() {
            break;
        }
        seq = seq.wrapping_add(1);
    }
    let _ = tauri::async_runtime::block_on(engine.send_audio_stop(to, crate::sound::STREAM));
}

/// What this computer plays, as 16 bit stereo at 48 kHz: from the monitor of the default output through PulseAudio or PipeWire
/// (parec, else GStreamer) on Linux, from the loopback of the default output on Windows.
#[cfg(target_os = "linux")]
fn record() -> Result<(Box<dyn Read + Send>, Option<Child>), String> {
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
                return Ok((Box::new(out), Some(child)));
            }
        }
    }
    Err("neither parec nor GStreamer could record the sound".into())
}

#[cfg(not(target_os = "linux"))]
fn record() -> Result<(Box<dyn Read + Send>, Option<Child>), String> {
    loopback::start().map(|reader| (Box::new(reader) as Box<dyn Read + Send>, None))
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
        /// Dropped with the reader, which ends the thread that holds the stream.
        _stop: SyncSender<()>,
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

    pub fn start() -> Result<Reader, String> {
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
                        // Kept until the reader is dropped.
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
        Ok(Reader { rx, pending: Vec::new(), _stop: stop_tx })
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
