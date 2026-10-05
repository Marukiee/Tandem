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
use windows::Win32::Foundation::{HINSTANCE, HWND, LPARAM, LRESULT, RECT, WPARAM};
use windows::Win32::Graphics::Dwm::{DWMWA_CLOAKED, DwmGetWindowAttribute};
use windows::Win32::System::DataExchange::AddClipboardFormatListener;
use windows::Win32::System::LibraryLoader::GetModuleHandleW;
use windows::Win32::System::Power::{GetSystemPowerStatus, SYSTEM_POWER_STATUS};
use windows::Win32::System::WinRT::{ISystemMediaTransportControlsInterop, RO_INIT_MULTITHREADED, RoInitialize};
use windows::Win32::UI::WindowsAndMessaging::{
    CreateWindowExW, DefWindowProcW, DispatchMessageW, EnumChildWindows, GWL_EXSTYLE, GWL_STYLE, GetClassNameW,
    GetForegroundWindow, GetMessageW, GetWindowLongPtrW, GetWindowRect, HWND_MESSAGE, IsIconic, IsWindowVisible, MSG,
    RegisterClassW, TranslateMessage, WINDOW_EX_STYLE, WINDOW_STYLE, WM_CLIPBOARDUPDATE, WNDCLASSW,
};
use windows::core::{BOOL, HSTRING, Ref, factory, w};

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
    /// Connects to the media controls of the system. They belong to a window, so this makes one of its own that is never
    /// shown: the windows of the app come and go, and the media controls must not go with them. `on_request` is called,
    /// on a thread of the system, when a button is pressed or the bar is dragged.
    pub fn start(on_request: impl Fn(Request) + Send + Sync + 'static) -> Media {
        let (commands, inbox) = mpsc::channel();
        let on_request: Arc<dyn Fn(Request) + Send + Sync> = Arc::new(on_request);
        std::thread::Builder::new()
            .name("tandem-media".into())
            .spawn(move || run(inbox, on_request))
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

fn run(inbox: Receiver<Command>, on_request: Arc<dyn Fn(Request) + Send + Sync>) {
    unsafe {
        let _ = RoInitialize(RO_INIT_MULTITHREADED);
    }
    // A window of the stock class "STATIC", with no size, never shown.
    let window = unsafe {
        let instance = GetModuleHandleW(None).ok().map(|module| HINSTANCE(module.0));
        CreateWindowExW(WINDOW_EX_STYLE(0), w!("STATIC"), w!("Tandem"), WINDOW_STYLE(0), 0, 0, 0, 0, None, None, instance, None)
    };
    let Ok(window) = window else {
        log::warn!("the media controls of Windows have no window to belong to");
        return;
    };
    let controls = match open(window.0 as isize, on_request) {
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

/// What Windows says about a window and the windows inside it, for the log: it is how a window that is "shown" but not
/// to be seen can be understood from far away.
pub fn describe(window: isize) -> String {
    let hwnd = HWND(window as *mut c_void);
    let mut text = String::new();
    unsafe {
        let mut rect = RECT::default();
        let _ = GetWindowRect(hwnd, &mut rect);
        let mut cloaked = 0u32;
        let _ = DwmGetWindowAttribute(hwnd, DWMWA_CLOAKED, (&mut cloaked as *mut u32).cast(), 4);
        text.push_str(&format!(
            "rect {},{} to {},{}; visible {}; iconic {}; cloaked {}; style {:#x}; exstyle {:#x}; in front {}",
            rect.left,
            rect.top,
            rect.right,
            rect.bottom,
            IsWindowVisible(hwnd).as_bool(),
            IsIconic(hwnd).as_bool(),
            cloaked,
            GetWindowLongPtrW(hwnd, GWL_STYLE),
            GetWindowLongPtrW(hwnd, GWL_EXSTYLE),
            GetForegroundWindow() == hwnd,
        ));
        unsafe extern "system" fn child(hwnd: HWND, found: LPARAM) -> BOOL {
            let text = unsafe { &mut *(found.0 as *mut String) };
            let mut name = [0u16; 64];
            let length = unsafe { GetClassNameW(hwnd, &mut name) }.max(0) as usize;
            let mut rect = RECT::default();
            let _ = unsafe { GetWindowRect(hwnd, &mut rect) };
            text.push_str(&format!(
                "; child {} {} at {},{} to {},{}",
                String::from_utf16_lossy(&name[..length]),
                if unsafe { IsWindowVisible(hwnd) }.as_bool() { "visible" } else { "hidden" },
                rect.left,
                rect.top,
                rect.right,
                rect.bottom
            ));
            BOOL(1)
        }
        let _ = EnumChildWindows(Some(hwnd), Some(child), LPARAM(&mut text as *mut String as isize));
    }
    text
}

// ---- The clipboard ---------------------------------------------------------------

static CLIPBOARD_CHANGED: std::sync::OnceLock<Box<dyn Fn() + Send + Sync>> = std::sync::OnceLock::new();

/// Calls `on_change` each time the clipboard changes, whichever program changed it, and costs nothing in between: the
/// system sends the message, nobody has to look. It comes more than once for one copy (once for each format a program
/// puts on it), so the caller waits a moment and looks once. True when the system accepted the listener.
pub fn watch_clipboard(on_change: impl Fn() + Send + Sync + 'static) -> bool {
    if CLIPBOARD_CHANGED.set(Box::new(on_change)).is_err() {
        return false;
    }
    let (ready_tx, ready_rx) = mpsc::channel();
    let started = std::thread::Builder::new().name("tandem-clipboard".into()).spawn(move || unsafe {
        let Ok(module) = GetModuleHandleW(None) else {
            let _ = ready_tx.send(false);
            return;
        };
        let instance = HINSTANCE(module.0);
        let class = w!("TandemClipboardWatcher");
        let window_class = WNDCLASSW { lpfnWndProc: Some(clipboard_proc), hInstance: instance, lpszClassName: class, ..Default::default() };
        RegisterClassW(&window_class);
        // A window that is only there to be sent messages: it has no size and is never shown.
        let window = CreateWindowExW(WINDOW_EX_STYLE(0), class, w!(""), WINDOW_STYLE(0), 0, 0, 0, 0, Some(HWND_MESSAGE), None, Some(instance), None);
        let listening = match window {
            Ok(window) => AddClipboardFormatListener(window).is_ok(),
            Err(_) => false,
        };
        let _ = ready_tx.send(listening);
        if !listening {
            return;
        }
        let mut message = MSG::default();
        while GetMessageW(&mut message, None, 0, 0).as_bool() {
            let _ = TranslateMessage(&message);
            DispatchMessageW(&message);
        }
    });
    started.is_ok() && ready_rx.recv_timeout(Duration::from_secs(3)).unwrap_or(false)
}

unsafe extern "system" fn clipboard_proc(window: HWND, message: u32, wparam: WPARAM, lparam: LPARAM) -> LRESULT {
    if message == WM_CLIPBOARDUPDATE {
        if let Some(on_change) = CLIPBOARD_CHANGED.get() {
            on_change();
        }
        return LRESULT(0);
    }
    unsafe { DefWindowProcW(window, message, wparam, lparam) }
}

// ---- Seeing the mouse and the keyboard ---------------------------------------------------------------------------------

use std::sync::OnceLock;
use std::sync::atomic::{AtomicBool, AtomicI32, Ordering};

use windows::Win32::Foundation::POINT;
use windows::Win32::UI::WindowsAndMessaging::{
    CallNextHookEx, GetSystemMetrics, HHOOK, KBDLLHOOKSTRUCT, MSLLHOOKSTRUCT, SM_CXSCREEN,
    SM_CXVIRTUALSCREEN, SM_CYSCREEN, SM_CYVIRTUALSCREEN, SM_XVIRTUALSCREEN, SM_YVIRTUALSCREEN, SetCursorPos, SetWindowsHookExW, WH_KEYBOARD_LL, WH_MOUSE_LL, WM_KEYDOWN, WM_KEYUP,
    WM_LBUTTONDOWN, WM_LBUTTONUP, WM_MBUTTONDOWN, WM_MBUTTONUP, WM_MOUSEHWHEEL, WM_MOUSEMOVE, WM_MOUSEWHEEL, WM_RBUTTONDOWN,
    WM_RBUTTONUP, WM_SYSKEYDOWN, WM_SYSKEYUP,
};

use super::Seen;

type Handler = Box<dyn Fn(Seen) -> bool + Send + Sync>;

static HANDLER: OnceLock<Handler> = OnceLock::new();
static HOLDING: AtomicBool = AtomicBool::new(false);
static LAST_X: AtomicI32 = AtomicI32::new(i32::MIN);
static LAST_Y: AtomicI32 = AtomicI32::new(i32::MIN);

/// The size of the primary screen in pixels.
pub fn screen() -> (i32, i32) {
    unsafe { (GetSystemMetrics(SM_CXSCREEN), GetSystemMetrics(SM_CYSCREEN)) }
}

/// Everything the screens together cover: where its top left corner is, and how wide and high it is.
pub fn desktop() -> (i32, i32, i32, i32) {
    unsafe {
        (
            GetSystemMetrics(SM_XVIRTUALSCREEN),
            GetSystemMetrics(SM_YVIRTUALSCREEN),
            GetSystemMetrics(SM_CXVIRTUALSCREEN),
            GetSystemMetrics(SM_CYVIRTUALSCREEN),
        )
    }
}

/// Puts the pointer somewhere.
pub fn warp(x: i32, y: i32) {
    unsafe {
        let _ = SetCursorPos(x, y);
    }
}

/// The hooks on the mouse and keyboard, which live on a thread of their own for as long as the program runs.
pub struct Capture;

impl Capture {
    /// `handler` is told of everything the hands do and says whether it is for somebody else: true swallows the event, so
    /// nothing on this PC sees it. It runs inside the hook, so it has to be quick.
    pub fn start(handler: impl Fn(Seen) -> bool + Send + Sync + 'static) -> Option<Capture> {
        HANDLER.set(Box::new(handler)).ok()?;
        std::thread::Builder::new()
            .name("tandem-hooks".into())
            .spawn(|| unsafe {
                let module = GetModuleHandleW(None).ok().map(|m| HINSTANCE(m.0));
                let mouse: Option<HHOOK> = SetWindowsHookExW(WH_MOUSE_LL, Some(mouse_hook), module, 0).ok();
                let keyboard: Option<HHOOK> = SetWindowsHookExW(WH_KEYBOARD_LL, Some(keyboard_hook), module, 0).ok();
                if mouse.is_none() || keyboard.is_none() {
                    log::warn!("the hooks on the mouse and keyboard were not accepted");
                }
                // Hooks of this kind are called by way of the message loop of the thread that set them.
                let mut message = MSG::default();
                while GetMessageW(&mut message, None, 0, 0).as_bool() {
                    let _ = TranslateMessage(&message);
                    DispatchMessageW(&message);
                }
            })
            .ok()?;
        Some(Capture)
    }

    /// While held, the real pointer sits in the middle of the screen and what it would have moved is read from there.
    pub fn hold(&self, on: bool) {
        HOLDING.store(on, Ordering::SeqCst);
        if on {
            let (w, h) = screen();
            warp(w / 2, h / 2);
        }
        LAST_X.store(i32::MIN, Ordering::SeqCst);
    }
}

fn tell(seen: Seen) -> bool {
    HANDLER.get().is_some_and(|handler| handler(seen))
}

unsafe extern "system" fn mouse_hook(code: i32, wparam: WPARAM, lparam: LPARAM) -> LRESULT {
    if code >= 0 {
        // Mouse events that this program made itself (putting the pointer back in the middle) are not the hands.
        let info = unsafe { &*(lparam.0 as *const MSLLHOOKSTRUCT) };
        let injected = info.flags & 0x1 != 0;
        let swallow = if injected {
            false
        } else {
            match wparam.0 as u32 {
                WM_MOUSEMOVE => {
                    let POINT { x, y } = info.pt;
                    if HOLDING.load(Ordering::SeqCst) {
                        let (w, h) = screen();
                        let (cx, cy) = (w / 2, h / 2);
                        let (dx, dy) = (x - cx, y - cy);
                        if dx != 0 || dy != 0 {
                            tell(Seen::Move { dx, dy, x: cx + dx, y: cy + dy });
                            warp(cx, cy);
                        }
                        true
                    } else {
                        let (lx, ly) = (LAST_X.swap(x, Ordering::SeqCst), LAST_Y.swap(y, Ordering::SeqCst));
                        let (dx, dy) = if lx == i32::MIN { (0, 0) } else { (x - lx, y - ly) };
                        tell(Seen::Move { dx, dy, x, y })
                    }
                }
                WM_LBUTTONDOWN => tell(Seen::Button { button: 0, down: true }),
                WM_LBUTTONUP => tell(Seen::Button { button: 0, down: false }),
                WM_RBUTTONDOWN => tell(Seen::Button { button: 1, down: true }),
                WM_RBUTTONUP => tell(Seen::Button { button: 1, down: false }),
                WM_MBUTTONDOWN => tell(Seen::Button { button: 2, down: true }),
                WM_MBUTTONUP => tell(Seen::Button { button: 2, down: false }),
                WM_MOUSEWHEEL => {
                    let delta = (info.mouseData >> 16) as i16 as i32;
                    tell(Seen::Scroll { dx: 0, dy: delta / 10 })
                }
                WM_MOUSEHWHEEL => {
                    let delta = (info.mouseData >> 16) as i16 as i32;
                    tell(Seen::Scroll { dx: delta / 10, dy: 0 })
                }
                _ => false,
            }
        };
        if swallow {
            return LRESULT(1);
        }
    }
    unsafe { CallNextHookEx(None, code, wparam, lparam) }
}

unsafe extern "system" fn keyboard_hook(code: i32, wparam: WPARAM, lparam: LPARAM) -> LRESULT {
    if code >= 0 {
        let info = unsafe { &*(lparam.0 as *const KBDLLHOOKSTRUCT) };
        let injected = info.flags.0 & 0x10 != 0;
        if !injected {
            let down = match wparam.0 as u32 {
                WM_KEYDOWN | WM_SYSKEYDOWN => Some(true),
                WM_KEYUP | WM_SYSKEYUP => Some(false),
                _ => None,
            };
            if let Some(down) = down {
                if tell(Seen::Key { vk: info.vkCode as u16, down }) {
                    return LRESULT(1);
                }
            }
        }
    }
    unsafe { CallNextHookEx(None, code, wparam, lparam) }
}
