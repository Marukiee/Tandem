//! Tandem Speaker and Tandem Microphone, sound devices that other apps can pick, and the phone as the microphone of this computer.
//!
//! On Linux they are made without root through PulseAudio or PipeWire (its pulse server answers the same `pactl`): a null sink "Tandem
//! Speaker" whose monitor is what goes to the phone, and a null sink with a source remapped from its monitor, "Tandem Microphone", into
//! which the phone's microphone is played. They are made the first time they are needed and taken away when Tandem quits.
//!
//! On Windows a device that apps can pick needs a signed kernel driver (see docs/PARITY.md): the speaker goes on as before, through the
//! loopback of the output, and the microphone says why it cannot when it is pressed.

use std::sync::{LazyLock, Mutex};
use std::time::Instant;

use tauri::{AppHandle, Emitter, Manager};

use crate::state::AppState;
use crate::{events, i18n};

/// The stream a phone sends its microphone on (`MIC_STREAM` in proto.rs).
pub const MIC_STREAM: u8 = 10;
#[cfg_attr(not(target_os = "linux"), allow(dead_code))]
const MIC_CAP: &str = "audio.mic";

struct Mic {
    peer: String,
    since: Instant,
    #[cfg(target_os = "linux")]
    player: Option<linux::Player>,
    /// The input that was the default before, to go back to.
    #[cfg(target_os = "linux")]
    previous_source: Option<String>,
}

static MIC: LazyLock<Mutex<Option<Mic>>> = LazyLock::new(|| Mutex::new(None));

fn mic_changed(app: &AppHandle) {
    let peer = MIC.lock().unwrap().as_ref().map(|m| m.peer.clone());
    let _ = app.emit("mic", peer);
}

#[tauri::command]
pub fn mic_state() -> Option<String> {
    MIC.lock().unwrap().as_ref().map(|m| m.peer.clone())
}

#[tauri::command]
pub fn mic_stop(app: AppHandle) {
    let taken = MIC.lock().unwrap().take();
    if let Some(mic) = taken {
        ask(&app, &mic.peer, false);
        end(&app, mic);
    }
}

/// Asks the phone for its microphone. The error is what the person is told.
#[tauri::command]
pub fn mic_start(app: AppHandle, id: String) -> Result<(), String> {
    #[cfg(not(target_os = "linux"))]
    {
        let _ = id;
        Err(i18n::t(&app, "mic_windows"))
    }
    #[cfg(target_os = "linux")]
    {
        let caps = events::device_caps(&app, &id);
        if !caps.iter().any(|c| c == MIC_CAP) {
            return Err(i18n::t1(&app, "mic_update", &events::device_name(&app, &id)));
        }
        if !linux::ensure() {
            return Err(i18n::t(&app, "mic_no_pulse"));
        }
        mic_stop(app.clone());
        // Apps that record from the default input hear the phone at once; the one that was the default comes back after.
        let previous_source = linux::default_source().filter(|s| s != linux::MIC_SOURCE);
        linux::set_default_source(linux::MIC_SOURCE);
        *MIC.lock().unwrap() = Some(Mic { peer: id.clone(), since: Instant::now(), player: None, previous_source });
        mic_changed(&app);
        ask(&app, &id, true);
        Ok(())
    }
}

fn ask(app: &AppHandle, peer: &str, on: bool) {
    let Ok(engine) = app.state::<AppState>().engine() else { return };
    let peer = peer.to_string();
    tauri::async_runtime::spawn(async move {
        let _ = engine.request_mic(peer, on).await;
    });
}

fn end(app: &AppHandle, mic: Mic) {
    #[cfg(target_os = "linux")]
    {
        drop(mic.player);
        if let Some(previous) = mic.previous_source {
            if linux::default_source().as_deref() == Some(linux::MIC_SOURCE) {
                linux::set_default_source(&previous);
            }
        }
    }
    #[cfg(not(target_os = "linux"))]
    drop(mic);
    mic_changed(app);
}

/// An `AudioStart`: whether it is a microphone, which is then played here into Tandem Microphone.
pub fn started(app: &AppHandle, from: &str, stream: u8, rate: u32, channels: u8) -> bool {
    if stream != MIC_STREAM {
        return false;
    }
    let mut guard = MIC.lock().unwrap();
    let Some(mic) = guard.as_mut().filter(|m| m.peer == from) else {
        drop(guard);
        // Not asked for (any more): the phone stops recording.
        ask(app, from, false);
        return true;
    };
    #[cfg(target_os = "linux")]
    {
        mic.player = linux::Player::start(rate, channels);
        if mic.player.is_none() {
            let taken = guard.take();
            drop(guard);
            ask(app, from, false);
            if let Some(mic) = taken {
                end(app, mic);
            }
            events::say(app, &i18n::t(app, "mic_no_pulse"));
        }
    }
    #[cfg(not(target_os = "linux"))]
    let _ = (mic, rate, channels);
    true
}

/// A piece of the microphone, from the network: whether it was one.
pub fn on_audio(from: &str, stream: u8, pcm: &[u8]) -> bool {
    if stream != MIC_STREAM {
        return false;
    }
    #[cfg(target_os = "linux")]
    if let Some(player) = MIC.lock().unwrap().as_ref().filter(|m| m.peer == from).and_then(|m| m.player.as_ref()) {
        player.push(pcm);
    }
    #[cfg(not(target_os = "linux"))]
    let _ = (from, pcm);
    true
}

/// An `AudioStop`: whether it was about the microphone.
pub fn stopped(app: &AppHandle, from: &str, stream: u8) -> bool {
    if stream != MIC_STREAM {
        return false;
    }
    let taken = {
        let mut guard = MIC.lock().unwrap();
        if guard.as_ref().is_some_and(|m| m.peer == from) { guard.take() } else { None }
    };
    if let Some(mic) = taken {
        let name = events::device_name(app, from);
        // A no right after the question is the phone that does not allow it; later it is the person stopping it there.
        let key = if mic.since.elapsed().as_secs() < 3 { "mic_refused" } else { "mic_stopped_there" };
        events::say(app, &i18n::t1(app, key, &name));
        end(app, mic);
    }
    true
}

pub fn disconnected(app: &AppHandle, id: &str) {
    let taken = {
        let mut guard = MIC.lock().unwrap();
        if guard.as_ref().is_some_and(|m| m.peer == id) { guard.take() } else { None }
    };
    if let Some(mic) = taken {
        events::say(app, &i18n::t1(app, "mic_gone", &events::device_name(app, id)));
        end(app, mic);
    }
}

/// Tandem quits: the devices it made go too.
pub fn unload() {
    #[cfg(target_os = "linux")]
    linux::unload();
}

// ---- Tandem Speaker --------------------------------------------------------------------------------------------------------------

/// What `output` records when this computer's sound goes to a phone: the monitor of Tandem Speaker, after making it the output, so the
/// sound plays there and nowhere else. None where that cannot be (no pactl, or Windows): then what the output plays, as before.
pub fn take_output(app: &AppHandle) -> Option<String> {
    #[cfg(target_os = "linux")]
    {
        if !linux::ensure() {
            return None;
        }
        linux::watch(app);
        linux::THROUGH.store(true, std::sync::atomic::Ordering::SeqCst);
        let current = linux::default_sink();
        if current.as_deref() != Some(linux::SPEAKER_SINK) {
            *linux::PREVIOUS_SINK.lock().unwrap() = current;
            linux::set_default_sink(linux::SPEAKER_SINK);
        }
        Some(format!("{}.monitor", linux::SPEAKER_SINK))
    }
    #[cfg(not(target_os = "linux"))]
    {
        let _ = app;
        None
    }
}

/// The sound stopped going to the phone: the output it had before comes back, unless the person picked another one meanwhile.
pub fn give_back_output() {
    #[cfg(target_os = "linux")]
    {
        linux::THROUGH.store(false, std::sync::atomic::Ordering::SeqCst);
        let previous = linux::PREVIOUS_SINK.lock().unwrap().take();
        if let Some(previous) = previous {
            if linux::default_sink().as_deref() == Some(linux::SPEAKER_SINK) {
                linux::set_default_sink(&previous);
            }
        }
    }
}

#[cfg(target_os = "linux")]
mod linux {
    use std::io::{BufRead, BufReader, Write};
    use std::process::{Child, Command, Stdio};
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::sync::mpsc::{SyncSender, sync_channel};
    use std::sync::{LazyLock, Mutex};
    use std::time::{Duration, Instant};

    use tauri::AppHandle;

    pub const SPEAKER_SINK: &str = "tandem_speaker";
    const MIC_FEED: &str = "tandem_microphone_feed";
    pub const MIC_SOURCE: &str = "tandem_microphone";

    /// The modules this run loaded, to unload at the end. One that was there already (from a run that did not get to clean up) is
    /// used as it is.
    static MODULES: LazyLock<Mutex<Vec<String>>> = LazyLock::new(|| Mutex::new(Vec::new()));
    pub static PREVIOUS_SINK: LazyLock<Mutex<Option<String>>> = LazyLock::new(|| Mutex::new(None));
    /// When Tandem itself last changed the output, so the watcher does not take that for the person.
    static SWITCHED: LazyLock<Mutex<Instant>> = LazyLock::new(|| Mutex::new(Instant::now()));
    static WATCHING: AtomicBool = AtomicBool::new(false);
    /// The sound that goes to a phone now is recorded from Tandem Speaker.
    pub static THROUGH: AtomicBool = AtomicBool::new(false);
    /// The phone Tandem Speaker played on last.
    pub static LAST_PHONE: LazyLock<Mutex<Option<String>>> = LazyLock::new(|| Mutex::new(None));

    fn pactl(args: &[&str]) -> Option<String> {
        let mut command = Command::new("pactl");
        command.args(args).stdin(Stdio::null()).stderr(Stdio::null());
        tandem_winsys::system_env(&mut command);
        let out = command.output().ok()?;
        out.status.success().then(|| String::from_utf8_lossy(&out.stdout).trim().to_string())
    }

    fn has(kind: &str, name: &str) -> bool {
        pactl(&["list", "short", kind]).is_some_and(|list| list.lines().any(|line| line.split('\t').nth(1) == Some(name)))
    }

    fn load(args: &[&str]) -> bool {
        match pactl(&[&["load-module"], args].concat()) {
            Some(index) => {
                MODULES.lock().unwrap().push(index);
                true
            }
            None => false,
        }
    }

    /// Makes the devices that are not there yet. False when the sound server cannot be reached.
    pub fn ensure() -> bool {
        // The quotes are the module's own: the value of the properties is quoted, the name in it again, because of the space.
        (has("sinks", SPEAKER_SINK)
            || load(&["module-null-sink", &format!("sink_name={SPEAKER_SINK}"), "sink_properties=\"device.description='Tandem Speaker'\""]))
            && (has("sinks", MIC_FEED)
                || load(&["module-null-sink", &format!("sink_name={MIC_FEED}"), "sink_properties=\"device.description='Tandem Microphone Feed'\""]))
            && (has("sources", MIC_SOURCE)
                || load(&[
                    "module-remap-source",
                    &format!("master={MIC_FEED}.monitor"),
                    &format!("source_name={MIC_SOURCE}"),
                    "source_properties=\"device.description='Tandem Microphone'\"",
                ]))
    }

    pub fn unload() {
        let sink = default_sink();
        if sink.as_deref() == Some(SPEAKER_SINK) {
            if let Some(previous) = PREVIOUS_SINK.lock().unwrap().take() {
                set_default_sink(&previous);
            }
        }
        // The remapped source first: it stands on the sink.
        for index in MODULES.lock().unwrap().drain(..).rev() {
            let _ = pactl(&["unload-module", &index]);
        }
    }

    pub fn default_sink() -> Option<String> {
        pactl(&["get-default-sink"])
    }

    pub fn set_default_sink(name: &str) {
        *SWITCHED.lock().unwrap() = Instant::now();
        let _ = pactl(&["set-default-sink", name]);
    }

    pub fn default_source() -> Option<String> {
        pactl(&["get-default-source"])
    }

    pub fn set_default_source(name: &str) {
        let _ = pactl(&["set-default-source", name]);
    }

    /// Follows the output the person picks: Tandem Speaker picked in the sound menu sends the sound to the phone it played on last
    /// (or the first one that is there), and another output picked stops it.
    pub fn watch(app: &AppHandle) {
        if WATCHING.swap(true, Ordering::SeqCst) {
            return;
        }
        let app = app.clone();
        let _ = std::thread::Builder::new().name("tandem-sound-menu".into()).spawn(move || {
            let mut command = Command::new("pactl");
            command.arg("subscribe").stdin(Stdio::null()).stdout(Stdio::piped()).stderr(Stdio::null());
            tandem_winsys::system_env(&mut command);
            let Ok(mut child) = command.spawn() else {
                WATCHING.store(false, Ordering::SeqCst);
                return;
            };
            let Some(out) = child.stdout.take() else { return };
            for line in BufReader::new(out).lines().map_while(Result::ok) {
                // The default sink is a property of the server.
                if !line.contains("on server") || SWITCHED.lock().unwrap().elapsed() < Duration::from_secs(1) {
                    continue;
                }
                let picked = default_sink().as_deref() == Some(SPEAKER_SINK);
                let sending = crate::output::sending();
                if picked && !sending {
                    if let Some(phone) = phone(&app) {
                        let _ = crate::output::start(&app, phone, crate::output::Why::Chosen);
                    }
                } else if !picked && sending && THROUGH.load(Ordering::SeqCst) {
                    // Another output was picked: the sound goes there, and Tandem Speaker no longer needs giving back.
                    PREVIOUS_SINK.lock().unwrap().take();
                    crate::output::stop(&app);
                }
            }
            let _ = child.wait();
            WATCHING.store(false, Ordering::SeqCst);
        });
    }

    fn phone(app: &AppHandle) -> Option<String> {
        let online = crate::events::online_phones(app);
        let last = LAST_PHONE.lock().unwrap().clone();
        online.iter().find(|id| Some(*id) == last.as_ref()).or(online.first()).cloned()
    }

    /// Plays the phone's microphone into the feed of Tandem Microphone with pacat. The packets go to a thread that writes, so the
    /// network never waits on the sound server; when that falls behind, packets are dropped rather than queued into a delay.
    pub struct Player {
        child: Child,
        tx: SyncSender<Vec<u8>>,
    }

    impl Player {
        pub fn start(rate: u32, channels: u8) -> Option<Player> {
            let mut command = Command::new("pacat");
            command
                .args(["--playback", "--raw", "--format=s16le", "--latency-msec=40"])
                .arg(format!("--rate={rate}"))
                .arg(format!("--channels={}", channels.clamp(1, 2)))
                .arg(format!("--device={MIC_FEED}"))
                .stdin(Stdio::piped())
                .stdout(Stdio::null())
                .stderr(Stdio::null());
            tandem_winsys::system_env(&mut command);
            let mut child = command.spawn().ok()?;
            let mut stdin = child.stdin.take()?;
            let (tx, rx) = sync_channel::<Vec<u8>>(16);
            std::thread::Builder::new()
                .name("tandem-mic".into())
                .spawn(move || {
                    for pcm in rx {
                        if stdin.write_all(&pcm).is_err() {
                            break;
                        }
                    }
                })
                .ok()?;
            Some(Player { child, tx })
        }

        pub fn push(&self, pcm: &[u8]) {
            let _ = self.tx.try_send(pcm.to_vec());
        }
    }

    impl Drop for Player {
        fn drop(&mut self) {
            let _ = self.child.kill();
            let _ = self.child.wait();
        }
    }
}

/// Remembers the phone Tandem Speaker plays on, for when it is picked in the sound menu.
pub fn remember_phone(id: &str) {
    #[cfg(target_os = "linux")]
    {
        *linux::LAST_PHONE.lock().unwrap() = Some(id.to_string());
    }
    #[cfg(not(target_os = "linux"))]
    let _ = id;
}
