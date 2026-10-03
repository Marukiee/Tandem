//! The Windows side: `GetSystemPowerStatus` for the battery and the System Media Transport Controls for the rest.
//!
//! The media controls are driven from a thread of their own. The calls that hand a cover over wait for the system, and
//! the events of the buttons arrive on threads of the system, so neither belongs on the thread of the window.

use std::ffi::c_void;
use std::sync::mpsc::{self, Receiver, Sender};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use windows::Foundation::{TimeSpan, TypedEventHandler};
use windows::Media::{
    MediaPlaybackStatus, MediaPlaybackType, PlaybackPositionChangeRequestedEventArgs, SystemMediaTransportControls,
    SystemMediaTransportControlsButton, SystemMediaTransportControlsButtonPressedEventArgs,
    SystemMediaTransportControlsTimelineProperties,
};
use windows::Storage::Streams::{DataWriter, InMemoryRandomAccessStream, RandomAccessStreamReference};
use windows::Win32::Foundation::HWND;
use windows::Win32::System::Power::{GetSystemPowerStatus, SYSTEM_POWER_STATUS};
use windows::Win32::System::WinRT::{ISystemMediaTransportControlsInterop, RO_INIT_MULTITHREADED, RoInitialize};
use windows::core::{HSTRING, Ref, factory};

use super::{Battery, Button, Now, Request};

pub fn battery() -> Option<Battery> {
    let mut status = SYSTEM_POWER_STATUS::default();
    unsafe { GetSystemPowerStatus(&mut status) }.ok()?;
    // 128 is "no battery in this PC", 255 is "not known".
    if status.BatteryFlag & 128 != 0 || status.BatteryFlag == 255 || status.BatteryLifePercent > 100 {
        return None;
    }
    Some(Battery {
        level: status.BatteryLifePercent,
        charging: status.BatteryFlag & 8 != 0,
        saver: status.SystemStatusFlag & 1 != 0,
    })
}

enum Command {
    Show(Box<Now>),
    Hide,
}

pub struct Media {
    commands: Mutex<Sender<Command>>,
}

impl Media {
    /// Connects to the media controls of the window with this handle. `on_request` is called, on a thread of the system,
    /// when a button is pressed or the bar is dragged.
    pub fn start(window: isize, on_request: impl Fn(Request) + Send + Sync + 'static) -> Media {
        let (commands, inbox) = mpsc::channel();
        let on_request: Arc<dyn Fn(Request) + Send + Sync> = Arc::new(on_request);
        std::thread::Builder::new()
            .name("tandem-media".into())
            .spawn(move || run(window, inbox, on_request))
            .ok();
        Media { commands: Mutex::new(commands) }
    }

    pub fn show(&self, now: Now) {
        let _ = self.commands.lock().unwrap().send(Command::Show(Box::new(now)));
    }

    pub fn hide(&self) {
        let _ = self.commands.lock().unwrap().send(Command::Hide);
    }
}

fn run(window: isize, inbox: Receiver<Command>, on_request: Arc<dyn Fn(Request) + Send + Sync>) {
    unsafe {
        let _ = RoInitialize(RO_INIT_MULTITHREADED);
    }
    let controls = match open(window, on_request) {
        Ok(controls) => controls,
        Err(error) => {
            log::warn!("the media controls of Windows are not available: {error}");
            return;
        }
    };
    let mut cover: Option<u64> = None;
    while let Ok(mut command) = inbox.recv() {
        // What is shown is a state, not a story: of a burst of changes only the last one matters.
        while let Ok(newer) = inbox.try_recv() {
            command = newer;
        }
        let done = match command {
            Command::Show(now) => show(&controls, &now, &mut cover),
            Command::Hide => {
                cover = None;
                hide(&controls)
            }
        };
        if let Err(error) = done {
            log::warn!("the media controls could not be updated: {error}");
        }
    }
}

fn open(window: isize, on_request: Arc<dyn Fn(Request) + Send + Sync>) -> windows::core::Result<SystemMediaTransportControls> {
    let interop = factory::<SystemMediaTransportControls, ISystemMediaTransportControlsInterop>()?;
    let controls: SystemMediaTransportControls = unsafe { interop.GetForWindow(HWND(window as *mut c_void)) }?;
    // Nothing is shown until something plays.
    controls.SetIsEnabled(false)?;

    let buttons = on_request.clone();
    controls.ButtonPressed(&TypedEventHandler::new(
        move |_, args: Ref<SystemMediaTransportControlsButtonPressedEventArgs>| {
            if let Some(args) = args.as_ref() {
                let button = match args.Button()? {
                    SystemMediaTransportControlsButton::Play => Some(Button::Play),
                    SystemMediaTransportControlsButton::Pause => Some(Button::Pause),
                    SystemMediaTransportControlsButton::Stop => Some(Button::Stop),
                    SystemMediaTransportControlsButton::Next => Some(Button::Next),
                    SystemMediaTransportControlsButton::Previous => Some(Button::Previous),
                    _ => None,
                };
                if let Some(button) = button {
                    buttons(Request::Button(button));
                }
            }
            Ok(())
        },
    ))?;

    let seeks = on_request;
    controls.PlaybackPositionChangeRequested(&TypedEventHandler::new(
        move |_, args: Ref<PlaybackPositionChangeRequestedEventArgs>| {
            if let Some(args) = args.as_ref() {
                let ticks = args.RequestedPlaybackPosition()?.Duration.max(0) as u64;
                seeks(Request::Seek(Duration::from_nanos(ticks.saturating_mul(100))));
            }
            Ok(())
        },
    ))?;
    Ok(controls)
}

fn span(time: Duration) -> TimeSpan {
    // A TimeSpan counts in steps of a hundred nanoseconds.
    TimeSpan { Duration: (time.as_nanos() / 100).min(i64::MAX as u128) as i64 }
}

fn show(controls: &SystemMediaTransportControls, now: &Now, cover: &mut Option<u64>) -> windows::core::Result<()> {
    controls.SetIsEnabled(true)?;
    controls.SetIsPlayEnabled(true)?;
    controls.SetIsPauseEnabled(true)?;
    controls.SetIsPreviousEnabled(now.can_prev)?;
    controls.SetIsNextEnabled(now.can_next)?;
    controls.SetPlaybackStatus(if now.playing { MediaPlaybackStatus::Playing } else { MediaPlaybackStatus::Paused })?;

    let updater = controls.DisplayUpdater()?;
    // The cover of the track before this one must not stay next to a new title, so a change of cover starts afresh.
    let wanted = now.cover.as_ref().map(|_| now.art);
    if wanted != *cover && cover.is_some() {
        updater.ClearAll()?;
    }
    updater.SetType(MediaPlaybackType::Music)?;
    let music = updater.MusicProperties()?;
    music.SetTitle(&HSTRING::from(now.title.as_str()))?;
    music.SetArtist(&HSTRING::from(now.artist.as_str()))?;
    music.SetAlbumTitle(&HSTRING::from(now.album.as_str()))?;
    if wanted != *cover {
        if let Some(jpeg) = &now.cover {
            updater.SetThumbnail(&reference(jpeg)?)?;
        }
        *cover = wanted;
    }
    updater.Update()?;

    if let Some(duration) = now.duration.filter(|d| !d.is_zero()) {
        let timeline = SystemMediaTransportControlsTimelineProperties::new()?;
        timeline.SetStartTime(span(Duration::ZERO))?;
        timeline.SetMinSeekTime(span(Duration::ZERO))?;
        timeline.SetEndTime(span(duration))?;
        timeline.SetMaxSeekTime(span(duration))?;
        timeline.SetPosition(span(now.position.unwrap_or_default().min(duration)))?;
        controls.UpdateTimelineProperties(&timeline)?;
    }
    Ok(())
}

fn hide(controls: &SystemMediaTransportControls) -> windows::core::Result<()> {
    controls.SetPlaybackStatus(MediaPlaybackStatus::Closed)?;
    let updater = controls.DisplayUpdater()?;
    updater.ClearAll()?;
    updater.Update()?;
    controls.SetIsEnabled(false)
}

/// A picture in memory, in the form the system takes it.
fn reference(jpeg: &[u8]) -> windows::core::Result<RandomAccessStreamReference> {
    let stream = InMemoryRandomAccessStream::new()?;
    let writer = DataWriter::CreateDataWriter(&stream)?;
    writer.WriteBytes(jpeg)?;
    writer.StoreAsync()?.join()?;
    writer.DetachStream()?;
    stream.Seek(0)?;
    RandomAccessStreamReference::CreateFromStream(&stream)
}
