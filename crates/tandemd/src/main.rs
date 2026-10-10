//! `tandemd`: Tandem for the command line and for machines without a screen.
//!
//! `tandemd run` keeps a device online (a Linux desktop, a server, the Hub). Every
//! other subcommand talks to the running daemon over a local socket, or starts a
//! short-lived engine of its own when no daemon is running.

use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use anyhow::{Context, Result, bail};
use clap::{Parser, Subcommand};
use tandem_core::events::Event;
use tandem_core::files::{FilePolicy, Share, default_shares};
use tandem_core::platform::DesktopFiles;
use tandem_core::proto::ShareOrigin;
use tandem_core::store::{FileSecretStore, Store};
use tandem_core::transfer::OutgoingFile;
use tandem_core::{DeviceId, Engine, EngineConfig};

mod control;
mod pretend_screen;

use control::{ask_daemon, forget, serve_control};

#[derive(Parser)]
#[command(name = "tandemd", version, about = "Tandem for the command line and for machines without a screen")]
struct Cli {
    /// Where the identity, circle and settings live.
    #[arg(long, global = true)]
    data_dir: Option<PathBuf>,
    /// Where received files go.
    #[arg(long, global = true)]
    download_dir: Option<PathBuf>,
    /// The name other devices see.
    #[arg(long, global = true)]
    name: Option<String>,
    /// UDP port to listen on (0 lets the system choose).
    #[arg(long, global = true, default_value_t = tandem_core::net::DEFAULT_PORT)]
    port: u16,
    /// Advertise 127.0.0.1, for trying things out on one machine.
    #[arg(long, global = true)]
    loopback: bool,
    /// Do not announce on the local network.
    #[arg(long, global = true)]
    no_mdns: bool,
    /// Pretend to have a battery, to see how the apps show one: a level, and a plus for charging ("80+").
    #[arg(long, global = true, hide = true)]
    pretend_battery: Option<String>,
    /// Pretend to be playing a track, to see how the apps show a player.
    #[arg(long, global = true, hide = true)]
    pretend_player: bool,
    /// With --pretend-player: the track is paused, to see how the apps show a player that stands still.
    #[arg(long, global = true, hide = true)]
    pretend_paused: bool,
    /// Pretend to be a screen: play this raw Annex B H.264 clip for any device that asks for the screen, to try a viewer.
    #[arg(long, global = true, hide = true)]
    pretend_screen: Option<PathBuf>,
    /// With --pretend-screen: the size of the clip, written 1280x720.
    #[arg(long, global = true, hide = true)]
    pretend_screen_size: Option<String>,
    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
    /// Stay online and print what happens.
    Run,
    /// Show a pairing code as a QR and wait until a device joins.
    PairShow,
    /// Join the circle of the device that showed this code.
    Pair { uri: String },
    /// List the devices in the circle.
    Devices,
    /// Send files to a device (name or id prefix).
    Send { device: String, files: Vec<PathBuf> },
    /// Send text to a device's clipboard.
    Clip { device: String, text: Vec<String> },
    /// Remove a device from the circle for good.
    Remove { device: String },
    /// Print this device's id and name.
    Whoami,
    /// List a folder of another device: `tandemd ls phone /Phone/DCIM`. Without a path, the folders it offers.
    Ls {
        device: String,
        #[arg(default_value = "/")]
        path: String,
    },
    /// Copy a file from another device to this one.
    Get { device: String, remote: String, local: PathBuf },
    /// Copy a file from this device to another one.
    Put {
        device: String,
        local: PathBuf,
        remote: String,
        /// Replace a file that is there already.
        #[arg(long)]
        overwrite: bool,
    },
    /// Make a folder on another device.
    Mkdir { device: String, path: String },
    /// Remove a file or a folder on another device.
    Rm {
        device: String,
        path: String,
        /// A folder with everything in it.
        #[arg(short, long)]
        recursive: bool,
    },
    /// Move or rename something on another device.
    Mv { device: String, from: String, to: String },
    /// What other devices may do with the files of this machine. Without an action, what is allowed now.
    Files {
        /// Change the choices for one device instead of the ones for all.
        #[arg(long)]
        device: Option<String>,
        #[command(subcommand)]
        action: Option<FilesAction>,
    },
}

#[derive(Subcommand, Clone)]
enum FilesAction {
    /// Let other devices see the folders below.
    On,
    /// Show nothing to other devices.
    Off,
    /// Offer a folder, under its own name unless `--name` says otherwise.
    Share {
        path: PathBuf,
        #[arg(long)]
        name: Option<String>,
        /// Others may look at it but not change it.
        #[arg(long)]
        read_only: bool,
    },
    /// Stop offering a folder.
    Unshare { name: String },
    /// Offer Downloads, Documents and Desktop.
    Usual,
    /// Allow or refuse creating, replacing and renaming (`on` or `off`).
    Write { switch: String },
    /// Allow or refuse removing (`on` or `off`).
    Delete { switch: String },
    /// Show or hide the files that start with a dot (`on` or `off`).
    Hidden { switch: String },
    /// The biggest file another device may put here, in bytes. 0 is no limit.
    MaxUpload { bytes: u64 },
    /// Follow the choices for all devices again (with `--device`).
    Follow,
    /// What other devices did lately.
    Log,
}

/// A path as the daemon, which may run somewhere else, should read it.
fn absolute(path: &std::path::Path) -> PathBuf {
    if path.is_absolute() { path.to_path_buf() } else { std::env::current_dir().unwrap_or_default().join(path) }
}

fn default_data_dir() -> PathBuf {
    dirs::data_dir().unwrap_or_else(|| PathBuf::from(".")).join("tandem")
}

fn default_download_dir() -> PathBuf {
    dirs::download_dir().unwrap_or_else(|| PathBuf::from(".")).join("Tandem")
}

async fn start_engine(cli: &Cli) -> Result<(Engine, PathBuf)> {
    let data_dir = cli.data_dir.clone().unwrap_or_else(default_data_dir);
    let name = cli
        .name
        .clone()
        .or_else(|| hostname::get().ok().and_then(|h| h.into_string().ok()))
        .unwrap_or_else(|| "tandemd".to_string());
    let mut cfg = EngineConfig::new(&data_dir, name);
    cfg.port = cli.port;
    cfg.loopback = cli.loopback;
    cfg.enable_mdns = !cli.no_mdns;
    cfg.caps = vec!["clipboard".into(), "share".into()];
    if cli.pretend_screen.is_some() {
        cfg.caps.push("screen.host".into());
        // So the apps show the way to a second screen too, which this host answers with the clip like any other request.
        cfg.caps.push("screen.extend".into());
    }
    let secrets = Arc::new(FileSecretStore::new(Store::new(&data_dir)?));
    let download_dir = cli.download_dir.clone().unwrap_or_else(default_download_dir);
    let files = Arc::new(DesktopFiles { download_dir });
    let engine = Engine::start(cfg, secrets, files).await.context("could not start")?;
    Ok((engine, data_dir))
}

fn resolve(engine: &Engine, wanted: &str) -> Result<DeviceId> {
    let lower = wanted.to_lowercase();
    let devices = engine.devices();
    let matches: Vec<_> = devices
        .iter()
        .filter(|d| d.name.to_lowercase() == lower || d.id.to_string().starts_with(&lower))
        .collect();
    match matches.as_slice() {
        [one] => Ok(one.id),
        [] => {
            let partial: Vec<_> = devices.iter().filter(|d| d.name.to_lowercase().contains(&lower)).collect();
            match partial.as_slice() {
                [one] => Ok(one.id),
                _ => bail!("no device matches {wanted:?}"),
            }
        }
        _ => bail!("{wanted:?} matches more than one device"),
    }
}

fn print_qr(uri: &str) {
    if let Ok(code) = qrcode::QrCode::new(uri.as_bytes()) {
        let art = code
            .render::<qrcode::render::unicode::Dense1x2>()
            .quiet_zone(true)
            .module_dimensions(1, 1)
            .build();
        println!("{art}");
    }
    println!("{uri}");
}

fn describe(engine: &Engine) -> String {
    let mut out = format!("{} ({}) port {}\n", engine.name(), engine.id(), engine.port());
    let devices = engine.devices();
    if devices.is_empty() {
        out.push_str("no other devices yet\n");
    }
    for d in devices {
        let state = if d.online {
            match d.route {
                Some(route) => format!("online via {route:?}, {} ms", d.rtt_ms.unwrap_or(0)),
                None => "online".to_string(),
            }
        } else {
            "offline".to_string()
        };
        let battery = d
            .status
            .battery
            .map(|b| format!(", battery {}%{}", b.level, if b.charging { " charging" } else { "" }))
            .unwrap_or_default();
        out.push_str(&format!("{}  {}  {:?}  {}{}\n", d.id.short(), d.name, d.platform, state, battery));
    }
    out
}

/// Runs one command against an engine and returns what to print.
async fn execute(engine: &Engine, command: &Command) -> Result<String> {
    match command {
        Command::Run => unreachable!("handled by the caller"),
        Command::PairShow => {
            let offer = engine.create_pairing_offer()?;
            Ok(format!("{}\ncode: {}\n", offer.uri, offer.code))
        }
        Command::Whoami => Ok(format!("{} {}\n", engine.id(), engine.name())),
        Command::Devices => Ok(describe(engine)),
        Command::Pair { uri } => {
            let id = engine.pair_with_uri(uri).await?;
            Ok(format!("paired with {id}\n"))
        }
        Command::Send { device, files } => {
            let id = resolve(engine, device)?;
            let mut out = Vec::new();
            for path in files {
                let meta = std::fs::metadata(path).with_context(|| format!("cannot read {}", path.display()))?;
                out.push(OutgoingFile {
                    source: std::fs::canonicalize(path)?.to_string_lossy().into_owned(),
                    name: path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_else(|| "file".into()),
                    size: meta.len(),
                    mime: String::new(),
                });
            }
            let report = engine.send_files(&[id], out, ShareOrigin::Files).await?;
            if report.sent_to.is_empty() {
                bail!("that device is not connected right now");
            }
            Ok(format!("offered {} file(s)\n", files.len()))
        }
        Command::Clip { device, text } => {
            let id = resolve(engine, device)?;
            let reached = engine.send_clipboard(&[id], &text.join(" "), false).await;
            if reached.is_empty() {
                bail!("that device is not connected right now");
            }
            Ok("sent\n".to_string())
        }
        Command::Remove { device } => {
            let id = resolve(engine, device)?;
            engine.remove_device(id).await?;
            Ok("removed\n".to_string())
        }
        Command::Ls { device, path } => {
            let id = resolve(engine, device)?;
            let mut out = String::new();
            for entry in engine.files(id).list(path).await? {
                let size = if entry.dir { "-".to_string() } else { entry.size.to_string() };
                out.push_str(&format!("{size:>12}  {}{}\n", entry.name, if entry.dir { "/" } else { "" }));
            }
            Ok(out)
        }
        Command::Get { device, remote, local } => {
            let id = resolve(engine, device)?;
            let local = absolute(local);
            engine.files(id).download(remote, &local, |_, _| {}).await?;
            Ok(format!("saved {}\n", local.display()))
        }
        Command::Put { device, local, remote, overwrite } => {
            let id = resolve(engine, device)?;
            engine.files(id).upload(&absolute(local), remote, *overwrite, |_, _| {}).await?;
            Ok("sent\n".to_string())
        }
        Command::Mkdir { device, path } => {
            let id = resolve(engine, device)?;
            engine.files(id).mkdir(path).await?;
            Ok("made\n".to_string())
        }
        Command::Rm { device, path, recursive } => {
            let id = resolve(engine, device)?;
            engine.files(id).remove(path, *recursive).await?;
            Ok("removed\n".to_string())
        }
        Command::Mv { device, from, to } => {
            let id = resolve(engine, device)?;
            engine.files(id).rename(from, to, false).await?;
            Ok("moved\n".to_string())
        }
        Command::Files { device, action } => files_policy(engine, device.as_deref(), action.as_ref()),
    }
}

fn switch(value: &str) -> Result<bool> {
    match value {
        "on" | "yes" | "true" => Ok(true),
        "off" | "no" | "false" => Ok(false),
        other => bail!("expected on or off, not {other:?}"),
    }
}

/// Shows or changes what other devices may do with the files here.
fn files_policy(engine: &Engine, device: Option<&str>, action: Option<&FilesAction>) -> Result<String> {
    let target = device.map(|d| resolve(engine, d)).transpose()?;
    let mut policy = match &target {
        Some(id) => engine.file_policy(id),
        None => engine.file_default_policy(),
    };
    let mut changed = true;
    match action {
        None => changed = false,
        Some(FilesAction::Log) => {
            let mut out = String::new();
            for entry in engine.file_activity().into_iter().take(40) {
                let who = engine.devices().iter().find(|d| d.id == entry.peer).map(|d| d.name.clone()).unwrap_or_else(|| entry.peer.short());
                let result = if entry.ok { String::new() } else { format!("  refused: {}", entry.detail) };
                out.push_str(&format!("{who}  {}  {}{result}\n", entry.action, entry.path));
            }
            return Ok(if out.is_empty() { "nothing yet\n".to_string() } else { out });
        }
        Some(FilesAction::Follow) => {
            let id = target.as_ref().context("follow needs --device")?;
            engine.clear_file_policy(id)?;
            changed = false;
            policy = engine.file_policy(id);
        }
        Some(FilesAction::On) => policy.enabled = true,
        Some(FilesAction::Off) => policy.enabled = false,
        Some(FilesAction::Write { switch: value }) => policy.write = switch(value)?,
        Some(FilesAction::Delete { switch: value }) => policy.delete = switch(value)?,
        Some(FilesAction::Hidden { switch: value }) => policy.hidden = switch(value)?,
        Some(FilesAction::MaxUpload { bytes }) => policy.max_upload = *bytes,
        Some(FilesAction::Usual) => {
            for share in default_shares() {
                if !policy.shares.iter().any(|s| s.name == share.name || s.path == share.path) {
                    policy.shares.push(share);
                }
            }
        }
        Some(FilesAction::Share { path, name, read_only }) => {
            let path = std::fs::canonicalize(absolute(path)).with_context(|| format!("cannot find {}", path.display()))?;
            if !path.is_dir() {
                bail!("{} is not a folder", path.display());
            }
            let base = name
                .clone()
                .or_else(|| path.file_name().map(|n| n.to_string_lossy().into_owned()))
                .unwrap_or_else(|| "Files".to_string());
            let name = (1..).map(|n| if n == 1 { base.clone() } else { format!("{base} {n}") }).find(|c| !policy.shares.iter().any(|s| &s.name == c)).unwrap_or(base);
            policy.shares.push(Share { name, path: path.to_string_lossy().into_owned(), write: !read_only });
        }
        Some(FilesAction::Unshare { name }) => {
            let before = policy.shares.len();
            policy.shares.retain(|s| &s.name != name);
            if policy.shares.len() == before {
                bail!("no folder is offered as {name:?}");
            }
        }
    }
    if changed {
        match &target {
            Some(id) => engine.set_file_policy(id, policy.clone())?,
            None => engine.set_file_default_policy(policy.clone())?,
        }
    }
    Ok(describe_policy(engine, target.as_ref(), &policy))
}

fn describe_policy(engine: &Engine, target: Option<&DeviceId>, policy: &FilePolicy) -> String {
    let yes = |on: bool| if on { "on" } else { "off" };
    let mut out = match target {
        None => "files, for all devices\n".to_string(),
        Some(id) => {
            let own = if engine.has_own_file_policy(id) { "its own choices" } else { "follows the choices for all devices" };
            format!("files, for {} ({own})\n", engine.devices().iter().find(|d| &d.id == id).map(|d| d.name.clone()).unwrap_or_default())
        }
    };
    out.push_str(&format!("  sharing {}, changing {}, removing {}, hidden files {}\n", yes(policy.enabled), yes(policy.write), yes(policy.delete), yes(policy.hidden)));
    out.push_str(&format!("  biggest file they may send: {}\n", if policy.max_upload == 0 { "no limit".to_string() } else { format!("{} bytes", policy.max_upload) }));
    if policy.shares.is_empty() {
        out.push_str("  no folders are offered; `tandemd files share <folder>` or `tandemd files usual` offers some\n");
    }
    for share in &policy.shares {
        out.push_str(&format!("  {}  {}  {}\n", share.name, share.path, if share.write { "changes allowed" } else { "read only" }));
    }
    out
}

fn command_line(command: &Command) -> Option<String> {
    let parts: Vec<String> = match command {
        Command::Whoami => vec!["whoami".into()],
        Command::Devices => vec!["devices".into()],
        Command::Pair { uri } => vec!["pair".into(), uri.clone()],
        Command::Send { device, files } => {
            let mut parts = vec!["send".to_string(), device.clone()];
            for file in files {
                parts.push(std::fs::canonicalize(file).ok()?.to_string_lossy().into_owned());
            }
            parts
        }
        Command::Clip { device, text } => vec!["clip".into(), device.clone(), text.join(" ")],
        Command::Remove { device } => vec!["remove".into(), device.clone()],
        Command::PairShow => vec!["pair-show".into()],
        Command::Ls { device, path } => vec!["ls".into(), device.clone(), path.clone()],
        Command::Get { device, remote, local } => {
            vec!["get".into(), device.clone(), remote.clone(), absolute(local).to_string_lossy().into_owned()]
        }
        Command::Put { device, local, remote, overwrite } => vec![
            "put".into(),
            device.clone(),
            absolute(local).to_string_lossy().into_owned(),
            remote.clone(),
            overwrite.to_string(),
        ],
        Command::Mkdir { device, path } => vec!["mkdir".into(), device.clone(), path.clone()],
        Command::Rm { device, path, recursive } => vec!["rm".into(), device.clone(), path.clone(), recursive.to_string()],
        Command::Mv { device, from, to } => vec!["mv".into(), device.clone(), from.clone(), to.clone()],
        Command::Files { device, action } => {
            let mut parts = vec!["files".to_string(), device.clone().unwrap_or_default()];
            match action {
                None => {}
                Some(FilesAction::On) => parts.push("on".into()),
                Some(FilesAction::Off) => parts.push("off".into()),
                Some(FilesAction::Usual) => parts.push("usual".into()),
                Some(FilesAction::Follow) => parts.push("follow".into()),
                Some(FilesAction::Log) => parts.push("log".into()),
                Some(FilesAction::Share { path, name, read_only }) => {
                    parts.extend(["share".into(), absolute(path).to_string_lossy().into_owned(), name.clone().unwrap_or_default(), read_only.to_string()])
                }
                Some(FilesAction::Unshare { name }) => parts.extend(["unshare".into(), name.clone()]),
                Some(FilesAction::Write { switch }) => parts.extend(["write".into(), switch.clone()]),
                Some(FilesAction::Delete { switch }) => parts.extend(["delete".into(), switch.clone()]),
                Some(FilesAction::Hidden { switch }) => parts.extend(["hidden".into(), switch.clone()]),
                Some(FilesAction::MaxUpload { bytes }) => parts.extend(["max-upload".into(), bytes.to_string()]),
            }
            parts
        }
        Command::Run => return None,
    };
    Some(parts.join("\t"))
}

fn parse_line(line: &str) -> Option<Command> {
    let mut parts = line.trim_end_matches(['\n', '\r']).split('\t');
    let name = parts.next()?;
    let rest: Vec<String> = parts.map(str::to_string).collect();
    match name {
        "whoami" => Some(Command::Whoami),
        "devices" => Some(Command::Devices),
        "pair-show" => Some(Command::PairShow),
        "pair" if !rest.is_empty() => Some(Command::Pair { uri: rest.join(" ") }),
        "send" => Some(Command::Send {
            device: rest.first()?.clone(),
            files: rest.iter().skip(1).map(PathBuf::from).collect(),
        }),
        "clip" => Some(Command::Clip { device: rest.first()?.clone(), text: vec![rest.get(1)?.clone()] }),
        "remove" => Some(Command::Remove { device: rest.first()?.clone() }),
        "ls" => Some(Command::Ls { device: rest.first()?.clone(), path: rest.get(1)?.clone() }),
        "get" => Some(Command::Get { device: rest.first()?.clone(), remote: rest.get(1)?.clone(), local: PathBuf::from(rest.get(2)?) }),
        "put" => Some(Command::Put {
            device: rest.first()?.clone(),
            local: PathBuf::from(rest.get(1)?),
            remote: rest.get(2)?.clone(),
            overwrite: rest.get(3).map(|v| v == "true").unwrap_or(false),
        }),
        "mkdir" => Some(Command::Mkdir { device: rest.first()?.clone(), path: rest.get(1)?.clone() }),
        "rm" => Some(Command::Rm {
            device: rest.first()?.clone(),
            path: rest.get(1)?.clone(),
            recursive: rest.get(2).map(|v| v == "true").unwrap_or(false),
        }),
        "mv" => Some(Command::Mv { device: rest.first()?.clone(), from: rest.get(1)?.clone(), to: rest.get(2)?.clone() }),
        "files" => {
            let device = rest.first().filter(|d| !d.is_empty()).cloned();
            let arg = |n: usize| rest.get(n).cloned();
            let action = match rest.get(1).map(String::as_str) {
                None | Some("") => None,
                Some("on") => Some(FilesAction::On),
                Some("off") => Some(FilesAction::Off),
                Some("usual") => Some(FilesAction::Usual),
                Some("follow") => Some(FilesAction::Follow),
                Some("log") => Some(FilesAction::Log),
                Some("share") => Some(FilesAction::Share {
                    path: PathBuf::from(arg(2)?),
                    name: arg(3).filter(|n| !n.is_empty()),
                    read_only: arg(4).map(|v| v == "true").unwrap_or(false),
                }),
                Some("unshare") => Some(FilesAction::Unshare { name: arg(2)? }),
                Some("write") => Some(FilesAction::Write { switch: arg(2)? }),
                Some("delete") => Some(FilesAction::Delete { switch: arg(2)? }),
                Some("hidden") => Some(FilesAction::Hidden { switch: arg(2)? }),
                Some("max-upload") => Some(FilesAction::MaxUpload { bytes: arg(2)?.parse().ok()? }),
                Some(_) => return None,
            };
            Some(Command::Files { device, action })
        }
        _ => None,
    }
}

/// What the daemon says to one line from `tandemd` run as a command: `ok` and the output, or `error` and why.
pub(crate) async fn answer(engine: &Engine, line: &str) -> String {
    match parse_line(line) {
        Some(command) => match execute(engine, &command).await {
            Ok(text) => format!("ok\n{text}"),
            Err(e) => format!("error\n{e:#}\n"),
        },
        None => "error\nunknown command\n".to_string(),
    }
}

fn print_event(engine: &Engine, event: &Event) {
    let name_of = |id: &DeviceId| {
        engine.devices().into_iter().find(|d| d.id == *id).map(|d| d.name).unwrap_or_else(|| id.short())
    };
    match event {
        Event::Connected { id } => println!("connected: {}", name_of(id)),
        Event::Disconnected { id } => println!("disconnected: {}", name_of(id)),
        Event::Paired { id } => println!("paired: {}", name_of(id)),
        Event::RemovedFromCircle => println!("this device was removed from the circle"),
        Event::Clipboard { from, text, .. } => println!("clipboard from {}: {text}", name_of(from)),
        Event::ShareOffered { from, offer } => {
            println!("{} offers {} file(s)", name_of(from), offer.items.len());
        }
        Event::ShareText { from, text, .. } => println!("text from {}: {text}", name_of(from)),
        Event::Finished(done) => match (&done.error, &done.location) {
            (Some(error), _) => println!("failed {}: {error}", done.name),
            (None, Some(location)) if done.incoming => println!("received {} -> {location}", done.name),
            (None, _) => println!("sent {}", done.name),
        },
        _ => {}
    }
}

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(tracing_subscriber::EnvFilter::try_from_default_env().unwrap_or_else(|_| "warn".into()))
        .with_writer(std::io::stderr)
        .init();

    let cli = Cli::parse();
    let data_dir = cli.data_dir.clone().unwrap_or_else(default_data_dir);

    // Most commands go to the daemon when there is one.
    if let Some(line) = command_line(&cli.command) {
        if let Some(reply) = ask_daemon(&data_dir, &line).await? {
            let (status, body) = reply.split_once('\n').unwrap_or((&reply, ""));
            if status == "ok" && matches!(cli.command, Command::PairShow) {
                let mut lines = body.lines();
                let link = lines.next().unwrap_or_default().trim();
                print_qr(link);
                println!("{link}");
                let code = lines.next().and_then(|line| line.strip_prefix("code: ")).unwrap_or_default();
                println!("Scan this with Tandem on another device, or type the code {code} there. It works for five minutes.");
                return Ok(());
            }
            print!("{body}");
            if status != "ok" {
                std::process::exit(1);
            }
            return Ok(());
        }
    }

    let (engine, data_dir) = start_engine(&cli).await?;
    match &cli.command {
        Command::Run => {
            println!("{}", describe(&engine));
            if let Some(clip) = &cli.pretend_screen {
                pretend_screen::install(&engine, clip, cli.pretend_screen_size.as_deref())?;
            }
            if let Some(text) = &cli.pretend_battery {
                if let Ok(level) = text.trim_end_matches('+').parse::<u8>() {
                    let battery = tandem_core::proto::Battery { level, charging: text.ends_with('+'), power_save: false };
                    engine.update_status(tandem_core::proto::Status { battery: Some(battery), ..Default::default() }).await;
                }
            }
            let mut events = engine.subscribe();
            let control_dir = data_dir.clone();
            let control = tokio::spawn({
                let engine = engine.clone();
                async move { serve_control(engine, &control_dir).await }
            });
            loop {
                tokio::select! {
                    _ = tokio::signal::ctrl_c() => break,
                    event = events.recv() => match event {
                        Ok(event) => {
                            if let (true, tandem_core::events::Event::Connected { id }) = (cli.pretend_player, &event) {
                                let player = tandem_core::proto::MediaPlayer {
                                    id: "pretend".into(),
                                    app: "Pretend".into(),
                                    title: "A pretend song".into(),
                                    artist: "Tandem".into(),
                                    album: String::new(),
                                    playing: !cli.pretend_paused,
                                    position_ms: Some(30_000),
                                    duration_ms: Some(240_000),
                                    can_prev: true,
                                    can_next: true,
                                    can_seek: true,
                                    art: 0,
                                };
                                let _ = engine.send_msg(&[*id], tandem_core::proto::Msg::MediaPlayers { players: vec![player] }).await;
                            }
                            print_event(&engine, &event)
                        }
                        Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => {}
                        Err(_) => break,
                    },
                }
            }
            control.abort();
            forget(&data_dir);
        }
        Command::PairShow => {
            let offer = engine.create_pairing_offer()?;
            print_qr(&offer.uri);
            println!("{}", offer.uri);
            println!("Scan this with Tandem on another device, or type the code {} there. It works for five minutes.", offer.code);
            let mut events = engine.subscribe();
            let waited = tokio::time::timeout(Duration::from_secs(300), async {
                loop {
                    if let Ok(Event::Paired { id }) = events.recv().await {
                        return id;
                    }
                }
            })
            .await;
            match waited {
                Ok(id) => println!("paired with {id}"),
                Err(_) => bail!("nobody paired in time"),
            }
        }
        other => {
            // Give a fresh engine a moment to reach the devices it already knows.
            let needs_a_device = matches!(
                other,
                Command::Send { .. }
                    | Command::Clip { .. }
                    | Command::Ls { .. }
                    | Command::Get { .. }
                    | Command::Put { .. }
                    | Command::Mkdir { .. }
                    | Command::Rm { .. }
                    | Command::Mv { .. }
            );
            if needs_a_device {
                for _ in 0..50 {
                    if engine.devices().iter().any(|d| d.online) {
                        break;
                    }
                    tokio::time::sleep(Duration::from_millis(100)).await;
                }
            }
            print!("{}", execute(&engine, other).await?);
            if matches!(other, Command::Send { .. }) {
                // Stay until the transfers are done.
                let mut events = engine.subscribe();
                let mut remaining = match other {
                    Command::Send { files, .. } => files.len(),
                    _ => 0,
                };
                let _ = tokio::time::timeout(Duration::from_secs(3600), async {
                    while remaining > 0 {
                        if let Ok(Event::Finished(done)) = events.recv().await {
                            if !done.incoming {
                                println!("{}", if done.error.is_none() { format!("sent {}", done.name) } else { format!("failed {}", done.name) });
                                remaining -= 1;
                            }
                        }
                    }
                })
                .await;
            }
        }
    }
    engine.shutdown().await;
    Ok(())
}
