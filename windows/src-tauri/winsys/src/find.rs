//! Being found: the sounds of the system to pick from (`C:\Windows\Media` on Windows, the freedesktop sound themes on Linux), playing
//! one over and over until it is stopped, the volume raised for it and put back, and the name of the Wi-Fi this computer is on.

use std::path::{Path, PathBuf};

/// A sound of the system, and the set it belongs to (a sound scheme of Windows, a sound theme of Linux).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Sound {
    pub path: PathBuf,
    pub name: String,
    pub group: String,
}

#[cfg_attr(not(any(windows, target_os = "linux")), allow(dead_code))]
fn listed(folder: &Path, group: &str, extensions: &[&str], depth: u8, out: &mut Vec<Sound>) {
    let Ok(entries) = std::fs::read_dir(folder) else { return };
    let mut paths: Vec<PathBuf> = entries.flatten().map(|e| e.path()).collect();
    paths.sort();
    for path in paths {
        if path.is_dir() {
            if depth > 0 {
                listed(&path, group, extensions, depth - 1, out);
            }
            continue;
        }
        let extension = path.extension().and_then(|e| e.to_str()).map(str::to_ascii_lowercase).unwrap_or_default();
        if !extensions.contains(&extension.as_str()) {
            continue;
        }
        let stem = path.file_stem().and_then(|s| s.to_str()).unwrap_or_default();
        out.push(Sound { name: pretty(stem), path: path.clone(), group: group.to_string() });
    }
}

/// `phone-incoming-call` reads as "Phone incoming call"; the names of Windows ("Windows Notify Calendar") are kept.
#[cfg_attr(not(any(windows, target_os = "linux")), allow(dead_code))]
fn pretty(stem: &str) -> String {
    let words = stem.replace(['-', '_'], " ");
    let mut chars = words.chars();
    chars.next().map(|first| first.to_uppercase().chain(chars).collect()).unwrap_or_default()
}

/// What there is to pick from, the sounds of the current theme first.
pub fn gallery() -> Vec<Sound> {
    #[cfg(windows)]
    {
        win::gallery()
    }
    #[cfg(target_os = "linux")]
    {
        linux::gallery()
    }
    #[cfg(not(any(windows, target_os = "linux")))]
    {
        Vec::new()
    }
}

/// The sound that plays when nothing was picked: one that is meant to be heard from another room.
pub fn default_sound() -> Option<PathBuf> {
    let all = gallery();
    #[cfg(windows)]
    let wanted = ["Alarm01", "Ring01", "Windows Ringin"];
    #[cfg(not(windows))]
    let wanted = ["phone-incoming-call", "alarm-clock-elapsed", "complete", "bell"];
    wanted
        .iter()
        .find_map(|stem| all.iter().find(|s| s.path.file_stem().and_then(|f| f.to_str()) == Some(stem)))
        .or(all.first())
        .map(|s| s.path.clone())
}

/// Whether `path` can be played here: it is there, and on Linux there is a program to play it with.
pub fn can_play(path: &Path) -> bool {
    #[cfg(target_os = "linux")]
    {
        path.is_file() && linux::player(path).is_some()
    }
    #[cfg(windows)]
    {
        path.is_file()
    }
    #[cfg(not(any(windows, target_os = "linux")))]
    {
        let _ = path;
        false
    }
}

/// Starts playing `path` again and again until [`stop`]; `once` plays it a single time (a preview). False when it cannot be played.
pub fn play(path: &Path, once: bool) -> bool {
    #[cfg(windows)]
    {
        win::play(path, once)
    }
    #[cfg(target_os = "linux")]
    {
        linux::play(path, once)
    }
    #[cfg(not(any(windows, target_os = "linux")))]
    {
        let _ = (path, once);
        false
    }
}

pub fn stop() {
    #[cfg(windows)]
    win::stop();
    #[cfg(target_os = "linux")]
    linux::stop();
}

/// How loud the output was before [`raise`], to put back with [`restore`].
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Before {
    pub volume: Option<f32>,
    pub muted: bool,
    /// What was set, so a change the person made in the meantime is not undone.
    pub set: f32,
}

/// Unmutes the output and brings a volume below `level` (0 to 1) up to it. Nothing when it was loud enough or cannot be changed.
pub fn raise(level: f32) -> Option<Before> {
    let (volume, muted) = output()?;
    let low = volume < level;
    if !low && !muted {
        return None;
    }
    if muted {
        set_muted(false);
    }
    if low {
        set_volume(level);
    }
    Some(Before { volume: low.then_some(volume), muted, set: level })
}

pub fn restore(before: Before) {
    let Some((now, muted)) = output() else { return };
    if let Some(volume) = before.volume
        && (now - before.set).abs() < 0.02
    {
        set_volume(volume);
    }
    if before.muted && !muted {
        set_muted(true);
    }
}

/// The volume of the default output (0 to 1, as the slider shows it) and whether it is muted.
pub fn output() -> Option<(f32, bool)> {
    #[cfg(windows)]
    {
        win::output()
    }
    #[cfg(target_os = "linux")]
    {
        linux::output()
    }
    #[cfg(not(any(windows, target_os = "linux")))]
    {
        None
    }
}

fn set_volume(level: f32) {
    #[cfg(windows)]
    win::set_volume(level);
    #[cfg(target_os = "linux")]
    linux::set_volume(level);
    #[cfg(not(any(windows, target_os = "linux")))]
    let _ = level;
}

fn set_muted(muted: bool) {
    #[cfg(windows)]
    win::set_muted(muted);
    #[cfg(target_os = "linux")]
    linux::set_muted(muted);
    #[cfg(not(any(windows, target_os = "linux")))]
    let _ = muted;
}

/// The Wi-Fi this computer is on, where the system says (on newer Windows only with Location allowed).
pub fn wifi_name() -> Option<String> {
    #[cfg(windows)]
    {
        win::wifi_name()
    }
    #[cfg(target_os = "linux")]
    {
        linux::wifi_name()
    }
    #[cfg(not(any(windows, target_os = "linux")))]
    {
        None
    }
}

/// The value of `key` in the output of `netsh wlan show interfaces`: `SSID`, not the `BSSID` and `AP BSSID` next to it.
pub fn netsh_value(text: &str, key: &str) -> Option<String> {
    text.lines().find_map(|line| {
        let (name, value) = line.split_once(':')?;
        (name.trim() == key).then(|| value.trim().to_string()).filter(|v| !v.is_empty())
    })
}

/// The active network in `nmcli -t -f ACTIVE,SSID device wifi list`, where a `:` in a name is written `\:`.
pub fn nmcli_active(text: &str) -> Option<String> {
    text.lines().find_map(|line| line.strip_prefix("yes:").map(|name| name.replace("\\:", ":"))).filter(|n| !n.is_empty())
}

/// The volume and whether it is muted, from `wpctl get-volume` ("Volume: 0.40 [MUTED]").
pub fn wpctl_volume(text: &str) -> Option<(f32, bool)> {
    let rest = text.trim().strip_prefix("Volume:")?.trim();
    let level = rest.split_whitespace().next()?.parse::<f32>().ok()?;
    Some((level, rest.contains("[MUTED]")))
}

/// The first percentage in `pactl get-sink-volume` ("Volume: front-left: 26214 /  40% / -23.88 dB, ...").
pub fn pactl_volume(text: &str) -> Option<f32> {
    let percent = text.split('%').next()?.rsplit(|c: char| !c.is_ascii_digit()).next()?;
    percent.parse::<f32>().ok().map(|p| p / 100.0)
}

#[cfg(windows)]
mod win {
    use std::os::windows::ffi::OsStrExt;
    use std::os::windows::process::CommandExt;
    use std::path::{Path, PathBuf};

    use windows::Win32::Media::Audio::Endpoints::IAudioEndpointVolume;
    use windows::Win32::Media::Audio::{IMMDeviceEnumerator, MMDeviceEnumerator, PlaySoundW, SND_ASYNC, SND_FILENAME, SND_FLAGS, SND_LOOP, SND_NODEFAULT, eConsole, eRender};
    use windows::Win32::System::Com::{CLSCTX_ALL, COINIT_MULTITHREADED, CoCreateInstance, CoInitializeEx};
    use windows::core::PCWSTR;

    use super::Sound;

    /// The sounds in `C:\Windows\Media`, and those of each sound scheme in the folders under it.
    pub fn gallery() -> Vec<Sound> {
        let root = std::env::var_os("SystemRoot").or_else(|| std::env::var_os("windir")).unwrap_or_else(|| "C:\\Windows".into());
        let media = PathBuf::from(root).join("Media");
        let mut out = Vec::new();
        super::listed(&media, "Windows", &["wav"], 0, &mut out);
        let mut schemes: Vec<PathBuf> = std::fs::read_dir(&media).map(|e| e.flatten().map(|e| e.path()).filter(|p| p.is_dir()).collect()).unwrap_or_default();
        schemes.sort();
        for scheme in schemes {
            let group = scheme.file_name().and_then(|n| n.to_str()).unwrap_or_default().to_string();
            super::listed(&scheme, &group, &["wav"], 0, &mut out);
        }
        out
    }

    pub fn play(path: &Path, once: bool) -> bool {
        let wide: Vec<u16> = path.as_os_str().encode_wide().chain(std::iter::once(0)).collect();
        let mut flags = SND_FILENAME | SND_ASYNC | SND_NODEFAULT;
        if !once {
            flags |= SND_LOOP;
        }
        unsafe { PlaySoundW(PCWSTR(wide.as_ptr()), None, flags).as_bool() }
    }

    pub fn stop() {
        unsafe {
            let _ = PlaySoundW(PCWSTR::null(), None, SND_FLAGS(0));
        }
    }

    fn endpoint() -> Option<IAudioEndpointVolume> {
        unsafe {
            // Already set up on this thread in another way is fine too.
            let _ = CoInitializeEx(None, COINIT_MULTITHREADED);
            let devices: IMMDeviceEnumerator = CoCreateInstance(&MMDeviceEnumerator, None, CLSCTX_ALL).ok()?;
            let device = devices.GetDefaultAudioEndpoint(eRender, eConsole).ok()?;
            device.Activate::<IAudioEndpointVolume>(CLSCTX_ALL, None).ok()
        }
    }

    pub fn output() -> Option<(f32, bool)> {
        let volume = endpoint()?;
        unsafe { Some((volume.GetMasterVolumeLevelScalar().ok()?, volume.GetMute().ok()?.as_bool())) }
    }

    pub fn set_volume(level: f32) {
        if let Some(volume) = endpoint() {
            unsafe {
                let _ = volume.SetMasterVolumeLevelScalar(level, std::ptr::null());
            }
        }
    }

    pub fn set_muted(muted: bool) {
        if let Some(volume) = endpoint() {
            unsafe {
                let _ = volume.SetMute(muted, std::ptr::null());
            }
        }
    }

    pub fn wifi_name() -> Option<String> {
        let mut command = std::process::Command::new("netsh");
        command.args(["wlan", "show", "interfaces"]);
        // No console window flashing up every minute.
        command.creation_flags(0x0800_0000);
        let output = command.output().ok()?;
        super::netsh_value(&String::from_utf8_lossy(&output.stdout), "SSID")
    }
}

#[cfg(target_os = "linux")]
mod linux {
    use std::path::{Path, PathBuf};
    use std::process::{Child, Command, Stdio};
    use std::sync::atomic::{AtomicU64, Ordering};
    use std::sync::Mutex;
    use std::time::Duration;

    /// The program that plays now, and which round of playing it belongs to: a new round makes the loop of an old one end.
    static CHILD: Mutex<Option<Child>> = Mutex::new(None);
    static ROUND: AtomicU64 = AtomicU64::new(0);

    fn run(program: &str, args: &[&str]) -> Option<String> {
        let mut command = Command::new(program);
        command.args(args).stdin(Stdio::null()).stderr(Stdio::null());
        crate::system_env(&mut command);
        let output = command.output().ok()?;
        output.status.success().then(|| String::from_utf8_lossy(&output.stdout).into_owned())
    }

    fn on_path(program: &str) -> bool {
        std::env::var_os("PATH").is_some_and(|path| std::env::split_paths(&path).any(|dir| dir.join(program).is_file()))
    }

    fn data_dirs() -> Vec<PathBuf> {
        let mut dirs = Vec::new();
        if let Some(home) = std::env::var_os("XDG_DATA_HOME").map(PathBuf::from).or_else(|| std::env::var_os("HOME").map(|h| PathBuf::from(h).join(".local/share"))) {
            dirs.push(home);
        }
        let system = std::env::var("XDG_DATA_DIRS").unwrap_or_default();
        dirs.extend(system.split(':').filter(|d| !d.is_empty()).map(PathBuf::from));
        for fallback in ["/usr/local/share", "/usr/share"] {
            if !dirs.iter().any(|d| d == Path::new(fallback)) {
                dirs.push(PathBuf::from(fallback));
            }
        }
        dirs
    }

    /// The sounds of every theme there is: the one of the desktop first, then the one every theme falls back to, then the rest.
    pub fn gallery() -> Vec<super::Sound> {
        let current = theme();
        let mut all = themes();
        all.sort_by_key(|(name, _)| (name != &current, name != "freedesktop", name.clone()));
        let mut out = Vec::new();
        for (name, dir) in all {
            super::listed(&dir, &name, &["oga", "ogg", "wav"], 2, &mut out);
        }
        out
    }

    /// The sound themes there are (a folder with an `index.theme`), the first of each name.
    fn themes() -> Vec<(String, PathBuf)> {
        let mut found: Vec<(String, PathBuf)> = Vec::new();
        for dir in data_dirs() {
            let Ok(entries) = std::fs::read_dir(dir.join("sounds")) else { continue };
            for entry in entries.flatten() {
                let path = entry.path();
                let Some(name) = path.file_name().and_then(|n| n.to_str()).map(str::to_string) else { continue };
                if path.join("index.theme").is_file() && !found.iter().any(|(n, _)| n == &name) {
                    found.push((name, path));
                }
            }
        }
        found
    }

    /// The sound theme of the desktop: GNOME's setting, or KDE's, or the one of freedesktop.org that every desktop has.
    fn theme() -> String {
        if let Some(name) = run("gsettings", &["get", "org.gnome.desktop.sound", "theme-name"]) {
            let name = name.trim().trim_matches('\'').to_string();
            if !name.is_empty() {
                return name;
            }
        }
        let kde = std::env::var_os("HOME").map(|h| PathBuf::from(h).join(".config/kdeglobals"));
        if let Some(text) = kde.and_then(|p| std::fs::read_to_string(p).ok()) {
            let mut in_sounds = false;
            for line in text.lines() {
                let line = line.trim();
                if line.starts_with('[') {
                    in_sounds = line == "[Sounds]";
                } else if in_sounds && let Some(name) = line.strip_prefix("Theme=") {
                    return name.trim().to_string();
                }
            }
        }
        "freedesktop".into()
    }

    /// A program of the desktop that plays this file, in the order they are most likely to be there.
    pub fn player(path: &Path) -> Option<Vec<String>> {
        let file = path.to_string_lossy().into_owned();
        let wav = path.extension().is_some_and(|e| e.eq_ignore_ascii_case("wav"));
        let choices: [(&str, Vec<String>, bool); 5] = [
            ("pw-play", vec![file.clone()], true),
            ("paplay", vec![file.clone()], true),
            ("canberra-gtk-play", vec!["-f".into(), file.clone()], true),
            ("ffplay", vec!["-nodisp".into(), "-autoexit".into(), "-loglevel".into(), "quiet".into(), file.clone()], true),
            ("aplay", vec!["-q".into(), file], wav),
        ];
        choices.into_iter().find(|(program, _, fits)| *fits && on_path(program)).map(|(program, args, _)| {
            let mut all = vec![program.to_string()];
            all.extend(args);
            all
        })
    }

    pub fn play(path: &Path, once: bool) -> bool {
        stop();
        let Some(command) = player(path) else { return false };
        let round = ROUND.load(Ordering::SeqCst);
        std::thread::Builder::new()
            .name("tandem-find".into())
            .spawn(move || loop {
                let mut program = Command::new(&command[0]);
                program.args(&command[1..]).stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null());
                crate::system_env(&mut program);
                let Ok(child) = program.spawn() else { return };
                {
                    let mut current = CHILD.lock().unwrap();
                    if ROUND.load(Ordering::SeqCst) != round {
                        let mut child = child;
                        let _ = child.kill();
                        let _ = child.wait();
                        return;
                    }
                    *current = Some(child);
                }
                // Waits for this play to end without holding the lock, so a stop can kill it meanwhile.
                loop {
                    std::thread::sleep(Duration::from_millis(100));
                    let mut current = CHILD.lock().unwrap();
                    match current.as_mut().map(|c| c.try_wait()) {
                        Some(Ok(None)) => continue,
                        _ => {
                            *current = None;
                            break;
                        }
                    }
                }
                if once || ROUND.load(Ordering::SeqCst) != round {
                    return;
                }
                // A short rest, so a short sound reads as a ring and not as one long tone.
                std::thread::sleep(Duration::from_millis(350));
                if ROUND.load(Ordering::SeqCst) != round {
                    return;
                }
            })
            .is_ok()
    }

    pub fn stop() {
        ROUND.fetch_add(1, Ordering::SeqCst);
        if let Some(mut child) = CHILD.lock().unwrap().take() {
            let _ = child.kill();
            let _ = child.wait();
        }
    }

    pub fn output() -> Option<(f32, bool)> {
        if let Some(found) = run("wpctl", &["get-volume", "@DEFAULT_AUDIO_SINK@"]).and_then(|t| super::wpctl_volume(&t)) {
            return Some(found);
        }
        let volume = super::pactl_volume(&run("pactl", &["get-sink-volume", "@DEFAULT_SINK@"])?)?;
        let muted = run("pactl", &["get-sink-mute", "@DEFAULT_SINK@"]).is_some_and(|t| t.contains("yes"));
        Some((volume, muted))
    }

    pub fn set_volume(level: f32) {
        if run("wpctl", &["set-volume", "@DEFAULT_AUDIO_SINK@", &format!("{level:.2}")]).is_none() {
            run("pactl", &["set-sink-volume", "@DEFAULT_SINK@", &format!("{}%", (level * 100.0).round() as u32)]);
        }
    }

    pub fn set_muted(muted: bool) {
        let value = if muted { "1" } else { "0" };
        if run("wpctl", &["set-mute", "@DEFAULT_AUDIO_SINK@", value]).is_none() {
            run("pactl", &["set-sink-mute", "@DEFAULT_SINK@", value]);
        }
    }

    pub fn wifi_name() -> Option<String> {
        run("nmcli", &["-t", "-f", "ACTIVE,SSID", "device", "wifi", "list", "--rescan", "no"])
            .and_then(|t| super::nmcli_active(&t))
            .or_else(|| run("iwgetid", &["-r"]).map(|t| t.trim().to_string()).filter(|n| !n.is_empty()))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_wifi_name_is_read_from_what_the_tools_print() {
        let netsh = "    Name                   : Wi-Fi\n    SSID                   : Thuis: boven\n    AP BSSID               : 12:34:56:78:9a:bc\n    BSSID                  : 12:34\n";
        assert_eq!(netsh_value(netsh, "SSID").as_deref(), Some("Thuis: boven"));
        assert_eq!(netsh_value("    State : disconnected\n", "SSID"), None);
        assert_eq!(nmcli_active("no:Buren\nyes:Thuis\\: boven\n").as_deref(), Some("Thuis: boven"));
        assert_eq!(nmcli_active("no:Buren\n"), None);
    }

    #[test]
    fn the_volume_is_read_from_what_the_tools_print() {
        assert_eq!(wpctl_volume("Volume: 0.40 [MUTED]\n"), Some((0.40, true)));
        assert_eq!(wpctl_volume("Volume: 1.00\n"), Some((1.0, false)));
        assert_eq!(wpctl_volume("nothing"), None);
        let pactl = "Volume: front-left: 26214 /  40% / -23.88 dB,   front-right: 26214 /  40% / -23.88 dB\n";
        assert_eq!(pactl_volume(pactl), Some(0.40));
    }

    #[test]
    fn names_read_like_words() {
        assert_eq!(pretty("phone-incoming-call"), "Phone incoming call");
        assert_eq!(pretty("Windows Notify Calendar"), "Windows Notify Calendar");
        assert_eq!(pretty(""), "");
    }
}
