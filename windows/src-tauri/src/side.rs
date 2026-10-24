//! On which side of this computer another device stands, found by ear (see `side.rs` in the core): this computer plays a sound from its left
//! and its right speaker and the device says which was louder; or the other way round, another computer plays and this one records. The
//! side found puts the device on that side of this screen for the shared mouse.

use std::sync::{Arc, Mutex};
use std::time::Duration;

use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use cpal::{FromSample, SampleFormat, SizedSample};
use tandem_core::ffi::{TandemSide, TandemSideLevels, TandemSideSound};
use tauri::AppHandle;

use crate::state::AppState;
use crate::{arrange, settings};
use tauri::Manager;

pub fn sound_of(name: &str) -> TandemSideSound {
    match name {
        "chime" => TandemSideSound::Chime,
        "beep" => TandemSideSound::Beep,
        _ => TandemSideSound::Sweep,
    }
}

/// Whether this computer has a microphone to listen with.
pub fn can_listen() -> bool {
    cpal::default_host().default_input_device().is_some()
}

/// Plays the sound once, on the first two channels of the sound card. Returns when it is done.
pub fn play(sound: TandemSideSound) -> Result<(), String> {
    let device = cpal::default_host().default_output_device().ok_or("there is no sound card to play on")?;
    let config = device.default_output_config().map_err(|e| e.to_string())?;
    let rate = config.sample_rate().0;
    let channels = usize::from(config.channels());
    let samples = Arc::new(tandem_core::ffi::tandem_side_pattern(sound, rate));
    let seconds = samples.len() as f32 / 2.0 / rate as f32;
    let at = Arc::new(Mutex::new(0usize));
    let stream = match config.sample_format() {
        SampleFormat::F32 => output::<f32>(&device, &config.into(), samples, at, channels),
        SampleFormat::I16 => output::<i16>(&device, &config.into(), samples, at, channels),
        SampleFormat::U16 => output::<u16>(&device, &config.into(), samples, at, channels),
        other => return Err(format!("the sound card wants {other:?}")),
    }
    .map_err(|e| e.to_string())?;
    stream.play().map_err(|e| e.to_string())?;
    std::thread::sleep(Duration::from_secs_f32(seconds + 0.3));
    Ok(())
}

fn output<T: SizedSample + FromSample<f32>>(
    device: &cpal::Device,
    config: &cpal::StreamConfig,
    samples: Arc<Vec<f32>>,
    at: Arc<Mutex<usize>>,
    channels: usize,
) -> Result<cpal::Stream, cpal::BuildStreamError> {
    device.build_output_stream(
        config,
        move |data: &mut [T], _| {
            let mut at = at.lock().unwrap();
            for frame in data.chunks_mut(channels.max(1)) {
                let (left, right) = if *at * 2 + 1 < samples.len() { (samples[*at * 2], samples[*at * 2 + 1]) } else { (0.0, 0.0) };
                *at += 1;
                for (i, out) in frame.iter_mut().enumerate() {
                    // Left and right on the first two channels; a sound card with one channel gets both, which tells nothing but does no harm.
                    let value = match (channels, i) {
                        (1, _) => left + right,
                        (_, 0) => left,
                        (_, 1) => right,
                        _ => 0.0,
                    };
                    *out = T::from_sample(value);
                }
            }
        },
        |error| log::warn!("the sound card said: {error}"),
        None,
    )
}

/// Records `ms` of the microphone as one channel. The samples and how many there are a second.
fn record(ms: u32) -> Result<(Vec<f32>, u32), String> {
    let device = cpal::default_host().default_input_device().ok_or("there is no microphone")?;
    let config = device.default_input_config().map_err(|e| e.to_string())?;
    let rate = config.sample_rate().0;
    let channels = usize::from(config.channels()).max(1);
    let collected = Arc::new(Mutex::new(Vec::<f32>::with_capacity((rate as usize) * ms as usize / 1000)));
    let stream = match config.sample_format() {
        SampleFormat::F32 => input::<f32>(&device, &config.into(), collected.clone(), channels),
        SampleFormat::I16 => input::<i16>(&device, &config.into(), collected.clone(), channels),
        SampleFormat::U16 => input::<u16>(&device, &config.into(), collected.clone(), channels),
        other => return Err(format!("the microphone gives {other:?}")),
    }
    .map_err(|e| e.to_string())?;
    stream.play().map_err(|e| e.to_string())?;
    std::thread::sleep(Duration::from_millis(u64::from(ms)));
    drop(stream);
    let samples = std::mem::take(&mut *collected.lock().unwrap());
    Ok((samples, rate))
}

fn input<T: SizedSample>(
    device: &cpal::Device,
    config: &cpal::StreamConfig,
    collected: Arc<Mutex<Vec<f32>>>,
    channels: usize,
) -> Result<cpal::Stream, cpal::BuildStreamError>
where
    f32: FromSample<T>,
{
    device.build_input_stream(
        config,
        move |data: &[T], _| {
            let mut out = collected.lock().unwrap();
            for frame in data.chunks(channels) {
                out.push(frame.iter().map(|s| s.to_sample::<f32>()).sum::<f32>() / channels as f32);
            }
        },
        |error| log::warn!("the microphone said: {error}"),
        None,
    )
}

/// Another computer plays its sound: record it and say how loud each side was.
pub fn listen(app: &AppHandle, from: String, id: u64, sound: TandemSideSound) {
    let app = app.clone();
    std::thread::Builder::new()
        .name("tandem-side-listen".into())
        .spawn(move || {
            let levels: Option<TandemSideLevels> = match record(tandem_core::ffi::tandem_side_listen_ms()) {
                Ok((samples, rate)) => tandem_core::ffi::tandem_side_analyze(sound, samples, rate),
                Err(error) => {
                    log::info!("could not listen for the side: {error}");
                    None
                }
            };
            if let Ok(engine) = app.state::<AppState>().engine() {
                let _ = tauri::async_runtime::block_on(engine.side_heard(from, id, levels));
            }
        })
        .ok();
}

/// Plays the sound and asks the device what it heard; puts it on that side for the shared mouse when it is clear. Says "left", "right",
/// "unsure" or "not-heard".
#[tauri::command]
pub async fn side_find(app: AppHandle, id: String) -> Result<String, String> {
    let engine = app.state::<AppState>().engine()?;
    let sound = sound_of(&settings::get(&app).side_sound);
    // The pattern starts with a moment of silence, which is the time the device has to start recording.
    let probe = engine.side_probe(id.clone(), sound);
    let player = tauri::async_runtime::spawn_blocking(move || play(sound));
    let side = probe.await.map_err(|e| e.to_string())?;
    let _ = player.await;
    let text = match side {
        TandemSide::Left => "left",
        TandemSide::Right => "right",
        TandemSide::Unsure => "unsure",
        TandemSide::NotHeard => "not-heard",
    };
    if matches!(side, TandemSide::Left | TandemSide::Right) {
        arrange::put(&app, &id, text);
    }
    Ok(text.to_string())
}

/// Plays the chosen sound, to hear what it is.
#[tauri::command]
pub async fn side_preview(sound: String) -> Result<(), String> {
    tauri::async_runtime::spawn_blocking(move || play(sound_of(&sound))).await.map_err(|e| e.to_string())?
}
