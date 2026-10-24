//! On which side of this computer another device stands, found by ear (see `side.rs` in the core): this computer plays a sweep from its left
//! and its right speaker and the device says which was louder; or the other way round, another computer plays and this one records. The
//! side found puts the device on that side of this screen for the shared mouse.
//!
//! Besides the search the person starts, this computer checks by itself with sweeps too high to hear, each time it matters: a device
//! next to this screen connects, the pointer goes over to one that was not checked for a while, a device was moved, and a second screen
//! starts (and every few minutes while it runs). When is up to `side_check.rs` in the core. A side that changed moves the spot of that
//! device and its second screen, with one notification; nothing asks or waits.

use std::collections::HashMap;
use std::sync::{Arc, LazyLock, Mutex};
use std::time::Duration;

use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use cpal::{FromSample, SampleFormat, SizedSample};
use tandem_core::ffi::side_check::{TandemSideReason, TandemSideSchedule};
use tandem_core::ffi::{TandemSide, TandemSideLevels};
use tauri::AppHandle;

use crate::state::AppState;
use crate::{arrange, events, i18n, settings};
use tauri::Manager;

/// One sound at a time from these speakers: a check by itself and a search the person started that played over each other would leave
/// both listeners hearing the other's sweeps.
static TURN: LazyLock<tauri::async_runtime::Mutex<()>> = LazyLock::new(|| tauri::async_runtime::Mutex::new(()));
static SCHEDULE: LazyLock<Arc<TandemSideSchedule>> = LazyLock::new(TandemSideSchedule::new);
/// The side a check last moved each device to. A second screen of a device without a spot keeps no side in the settings, and without
/// this every check of it would look like a change and say so again.
static APPLIED: LazyLock<Mutex<HashMap<String, String>>> = LazyLock::new(|| Mutex::new(HashMap::new()));

/// Whether this computer has a microphone to listen with.
pub fn can_listen() -> bool {
    cpal::default_host().default_input_device().is_some()
}

/// Plays the sweeps once at `loudness` (0 to 1), on the first two channels of the sound card. Returns when it is done.
pub fn play(loudness: f32) -> Result<(), String> {
    play_with(|rate| tandem_core::ffi::tandem_side_pattern(rate, loudness))
}

/// The quiet sweeps of a check by itself.
fn play_quiet() -> Result<(), String> {
    play_with(tandem_core::ffi::side_check::tandem_side_quiet_pattern)
}

/// Made at the rate the sound card runs at, so nothing resamples them near the top of what it carries.
fn play_with(make: impl FnOnce(u32) -> Vec<f32>) -> Result<(), String> {
    let device = cpal::default_host().default_output_device().ok_or("there is no sound card to play on")?;
    let config = device.default_output_config().map_err(|e| e.to_string())?;
    let rate = config.sample_rate().0;
    let channels = usize::from(config.channels());
    let samples = make(rate);
    if samples.is_empty() {
        return Err(format!("the sound card plays at {rate} Hz, too low for the quiet sweeps"));
    }
    let samples = Arc::new(samples);
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

/// Records `ms` of the microphone as one channel, after `ready` was called once the microphone is on. The samples and how many there
/// are a second. A microphone that records slower than `min_rate` is not even started.
fn record(ms: u32, min_rate: u32, ready: impl FnOnce()) -> Result<(Vec<f32>, u32), String> {
    let device = cpal::default_host().default_input_device().ok_or("there is no microphone")?;
    let config = device.default_input_config().map_err(|e| e.to_string())?;
    let rate = config.sample_rate().0;
    if rate < min_rate {
        return Err(format!("the microphone records at {rate} Hz, too low for the quiet sweeps"));
    }
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
    ready();
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

/// Another computer plays its sweeps: say when the microphone is on, record, and say how loud each side was. For a check by itself
/// (`quiet`) a microphone too slow for the quiet sweeps says nothing, so nothing is played.
pub fn listen(app: &AppHandle, from: String, id: u64, quiet: bool) {
    let app = app.clone();
    std::thread::Builder::new()
        .name("tandem-side-listen".into())
        .spawn(move || {
            let Ok(engine) = app.state::<AppState>().engine() else { return };
            let ready_engine = engine.clone();
            let ready_to = from.clone();
            let ready = move || {
                let _ = tauri::async_runtime::block_on(ready_engine.side_ready(ready_to, id));
            };
            let min_rate = if quiet { tandem_core::ffi::side_check::tandem_side_quiet_min_rate() } else { 0 };
            let levels: Option<TandemSideLevels> = match record(tandem_core::ffi::tandem_side_listen_ms(), min_rate, ready) {
                Ok((samples, rate)) if quiet => tandem_core::ffi::side_check::tandem_side_analyze_quiet(samples, rate),
                Ok((samples, rate)) => tandem_core::ffi::tandem_side_analyze(samples, rate),
                Err(error) => {
                    log::info!("could not listen for the side: {error}");
                    None
                }
            };
            let _ = tauri::async_runtime::block_on(engine.side_heard(from, id, levels));
        })
        .ok();
}

/// Asks the device to listen, plays the sweeps once it does, and reads its answer: soft first, once more at full loudness when it was not
/// heard well. Puts it on that side for the shared mouse when it is clear. "left", "right", "unsure" or "not-heard".
pub async fn find(app: &AppHandle, id: &str) -> Result<String, String> {
    let engine = app.state::<AppState>().engine()?;
    let _turn = TURN.lock().await;
    let mut side = TandemSide::NotHeard;
    for loudness in [tandem_core::ffi::tandem_side_first_loudness(), 1.0] {
        let Ok(probe) = engine.side_start(id.to_string()).await else { break };
        let _ = tauri::async_runtime::spawn_blocking(move || play(loudness)).await;
        side = engine.side_result(probe).await;
        if matches!(side, TandemSide::Left | TandemSide::Right) {
            break;
        }
    }
    let text = match side {
        TandemSide::Left => "left",
        TandemSide::Right => "right",
        TandemSide::Unsure => "unsure",
        TandemSide::NotHeard => "not-heard",
    };
    if matches!(side, TandemSide::Left | TandemSide::Right) {
        arrange::put(app, id, text);
    }
    Ok(text.to_string())
}

#[tauri::command]
pub async fn side_find(app: AppHandle, id: String) -> Result<String, String> {
    find(&app, &id).await
}

/// A phone was moved: when it sits next to this screen, its side is checked again, quietly.
pub fn moved(app: &AppHandle, from: String) {
    let app = app.clone();
    std::thread::spawn(move || consider(&app, &from, TandemSideReason::Moved));
}

/// A device connected: a moment for it to settle in, then a check when its side counts.
pub fn connected(app: &AppHandle, id: String) {
    let app = app.clone();
    std::thread::spawn(move || {
        std::thread::sleep(Duration::from_secs(3));
        consider(&app, &id, TandemSideReason::Connected);
    });
}

/// A second screen for `peer` is on: a check now, and every minute the schedule is asked again while one runs. Only Linux makes one.
#[cfg(target_os = "linux")]
pub fn screen_started(app: &AppHandle, peer: &str) {
    consider(app, peer, TandemSideReason::ScreenStarted);
    static TICK: std::sync::OnceLock<()> = std::sync::OnceLock::new();
    TICK.get_or_init(|| {
        let app = app.clone();
        std::thread::Builder::new()
            .name("tandem-side-tick".into())
            .spawn(move || {
                loop {
                    std::thread::sleep(Duration::from_secs(60));
                    for peer in crate::host::extending() {
                        consider(&app, &peer, TandemSideReason::InUse);
                    }
                }
            })
            .ok();
    });
}

/// The side matters for a device that has a second screen of this computer, or sits left or right of this screen for the shared mouse.
/// One put above or below was put there by hand and stays.
fn counts(app: &AppHandle, id: &str) -> bool {
    let listens = app.state::<AppState>().data.lock().unwrap().devices.iter().any(|d| {
        d["id"] == id && d["online"].as_bool().unwrap_or(false) && d["caps"].as_array().is_some_and(|caps| caps.iter().any(|c| c == "side.listen"))
    });
    let chosen = settings::get(app);
    listens
        && (crate::host::extending().iter().any(|p| p == id)
            || chosen.pointer_share && chosen.layout.iter().any(|s| s.device == id && (s.edge == "left" || s.edge == "right")))
}

/// Starts a check of `id` for `reason` when the setting is on, its side counts and the schedule says it is due. Blocks for as long as
/// asking the sound system takes, so not on a thread that must not wait.
pub fn consider(app: &AppHandle, id: &str, reason: TandemSideReason) {
    // The sound first: a computer that is muted or plays into headphones is not a device that did not hear, so it does not count as a miss.
    if !settings::get(app).side_check || !counts(app, id) || !speakers_can_play() || !SCHEDULE.start(id.to_string(), reason) {
        return;
    }
    let (app, id) = (app.clone(), id.to_string());
    tauri::async_runtime::spawn(async move { recheck(&app, &id).await });
}

/// The side in use: the spot next to this screen, else where a check last put it, else the right for a second screen (where the desktop
/// puts it).
fn in_use(app: &AppHandle, id: &str) -> Option<String> {
    let spot = settings::get(app).layout.iter().find(|s| s.device == id && (s.edge == "left" || s.edge == "right")).map(|s| s.edge.clone());
    spot.or_else(|| APPLIED.lock().unwrap().get(id).cloned())
        .or_else(|| crate::host::extending().iter().any(|p| p == id).then(|| "right".to_string()))
}

fn name(side: TandemSide) -> Option<&'static str> {
    match side {
        TandemSide::Left => Some("left"),
        TandemSide::Right => Some("right"),
        _ => None,
    }
}

/// One check, and a second when the first says another side than the one in use: a sweep that the room happened to swallow on one side
/// must not move anything the wrong way. Then the move, once the pointer is not on that device.
async fn recheck(app: &AppHandle, id: &str) {
    let known = in_use(app, id);
    let mut found = check(app, id).await;
    if let (Some(known), Some(side)) = (known.as_deref(), name(found)) {
        if side != known {
            tauri::async_runtime::spawn_blocking(|| std::thread::sleep(Duration::from_millis(600))).await.ok();
            if check(app, id).await != found {
                found = TandemSide::Unsure;
            }
        }
    }
    SCHEDULE.done(id.to_string(), found);
    let Some(side) = name(found) else { return };
    if known.as_deref() == Some(side) {
        return;
    }
    // A computer that has the pointer gives it back by the edge it came in by: the spot moves once it is back.
    let (app, id) = (app.clone(), id.to_string());
    tauri::async_runtime::spawn_blocking(move || {
        for _ in 0..240 {
            if crate::capture::pointer_at().as_deref() != Some(id.as_str()) {
                APPLIED.lock().unwrap().insert(id.clone(), side.to_string());
                arrange::flip(&app, &id, side);
                crate::host::place_made(&id, side);
                let key = if side == "left" { "side_now_left" } else { "side_now_right" };
                events::toast(&app, "Tandem", &i18n::t1(&app, key, &events::device_name(&app, &id)));
                return;
            }
            std::thread::sleep(Duration::from_secs(1));
        }
    })
    .await
    .ok();
}

/// Once, with the quiet sweeps; nothing is applied, and nothing is played when the sound of this computer would not come out of its own
/// speakers.
async fn check(app: &AppHandle, id: &str) -> TandemSide {
    let Ok(engine) = app.state::<AppState>().engine() else { return TandemSide::NotHeard };
    let _turn = TURN.lock().await;
    if !tauri::async_runtime::spawn_blocking(speakers_can_play).await.unwrap_or(false) {
        return TandemSide::NotHeard;
    }
    let Ok(probe) = engine.side_check_start(id.to_string()).await else { return TandemSide::NotHeard };
    let played = tauri::async_runtime::spawn_blocking(play_quiet).await.is_ok_and(|r| r.is_ok());
    // Read either way, so the core lets go of the answer it waits for.
    let side = engine.side_check_result(probe).await;
    if played { side } else { TandemSide::NotHeard }
}

/// Whether the quiet sweeps would come out of speakers in this room: not into headphones or Bluetooth, not with the sound off or nearly
/// off, and not while this computer sends its sound to a device that looks at its screen (that device could hear its own speaker).
fn speakers_can_play() -> bool {
    if crate::output::sending() || crate::screen_sound::headphones() {
        return false;
    }
    let Some(device) = cpal::default_host().default_output_device() else { return false };
    // Windows names the output after what is plugged in.
    let name = device.name().unwrap_or_default().to_lowercase();
    if ["headphone", "headset", "earphone", "airpods", "buds", "hands-free"].iter().any(|word| name.contains(word)) {
        return false;
    }
    #[cfg(target_os = "linux")]
    if linux_sound_off() {
        return false;
    }
    true
}

/// The default sink of PulseAudio or PipeWire is muted, or below a twentieth of its volume. Without `pactl` this is not known, and taken
/// as on.
#[cfg(target_os = "linux")]
fn linux_sound_off() -> bool {
    let run = |args: &[&str]| -> Option<String> {
        let mut command = std::process::Command::new("pactl");
        command.args(args).stdin(std::process::Stdio::null()).stderr(std::process::Stdio::null());
        tandem_winsys::system_env(&mut command);
        command.output().ok().filter(|o| o.status.success()).map(|o| String::from_utf8_lossy(&o.stdout).into_owned())
    };
    if run(&["get-sink-mute", "@DEFAULT_SINK@"]).is_some_and(|out| out.contains("yes")) {
        return true;
    }
    // "Volume: front-left: 42000 /  64% / ...": the first percentage.
    run(&["get-sink-volume", "@DEFAULT_SINK@"])
        .and_then(|out| out.split('%').next().and_then(|before| before.rsplit(' ').next()).and_then(|n| n.trim().parse::<u32>().ok()))
        .is_some_and(|percent| percent < 5)
}

/// Plays the sweeps, to hear what they are.
#[tauri::command]
pub async fn side_preview() -> Result<(), String> {
    let _turn = TURN.lock().await;
    tauri::async_runtime::spawn_blocking(|| play(1.0)).await.map_err(|e| e.to_string())?
}
